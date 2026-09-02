package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRecordService : Service() {

    private companion object {
        const val TAG = "MeetingService"
        const val CHANNEL_ID = "whisper_meeting"
        const val NOTIFICATION_ID = 101
        // Gold standard VAD chunk - matches desktop main.py _build_vad_chunks target 20s max 28s min 10s
        // No word cut, no overlap/dup, boundaries at silence
        const val TARGET_CHUNK_MS = 20_000L
        const val MIN_CHUNK_MS = 10_000L
        const val MAX_CHUNK_MS = 28_000L
        const val CHUNK_DURATION_MS = 30_000L // legacy fallback
        const val SILENCE_RMS_THRESHOLD = 0.015  // ~ -36dB, matches librosa top_db 28
        const val SILENCE_MIN_MS = 300L
    }

    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var model = "small"
    private var lang = "auto"
    private var mode = "txt"  // "txt" = save clean text file, "type" = commit to focused input
    private var transcriptFile: File? = null
    private var segmentCounter = 0
    private val allText = StringBuilder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "meeting-flush").apply { isDaemon = true } }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:Meeting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                model = intent.getStringExtra("model") ?: "small"
                lang = intent.getStringExtra("lang") ?: "auto"
                mode = intent.getStringExtra("mode") ?: "txt"
                startMeeting()
            }
            "STOP" -> {
                stopMeeting()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Meeting Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Whisper meeting recording in progress"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MeetingRecordService::class.java).apply {
            action = "STOP"
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this, 3, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Meeting - Recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun startMeeting() {
        if (isRecording) return
        // H5: enforce mic mutual exclusion - don't start if other recorder active
        if (TranscriptionQueue.isActive()) {
            Log.w(TAG, "Mic busy - TranscriptionQueue active, not starting meeting")
            try {
                android.widget.Toast.makeText(this, "Mic busy - queue processing", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        segmentCounter = 0
        allText.clear()

        val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val baseName = "meeting_${fmt.format(Date())}"

        if (mode == "txt") {
            val docsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!docsDir.exists()) docsDir.mkdirs()

            transcriptFile = File(docsDir, "$baseName.txt")
            transcriptFile?.writeText("")
        }

        try { if (wakeLock?.isHeld == false) wakeLock?.acquire(4*60*60*1000L) } catch (_: Exception) {}
        startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
        Log.i(TAG, "Meeting started: mode=$mode model=$model lang=$lang - WakeLock held, will survive lock screen")

        recordThread = Thread {
            try {
                val recorder = AudioUtils.createRecorder(applicationContext)
                recorder.startRecording()

                var pcmChunk = ByteArrayOutputStream()
                var chunkStartTime = System.currentTimeMillis()
                var lastNotifUpdate = 0L
                var silenceStartMs: Long? = null
                var lastRms = 0.0

                while (isRecording) {
                    val buffer = ByteArray(4096)
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcmChunk.write(buffer, 0, read)
                        // track silence for VAD boundary
                        lastRms = AudioUtils.rms16(buffer, read)
                        if (lastRms < SILENCE_RMS_THRESHOLD) {
                            if (silenceStartMs == null) silenceStartMs = System.currentTimeMillis()
                        } else {
                            silenceStartMs = null
                        }
                    }

                    val now = System.currentTimeMillis()

                    if (now - lastNotifUpdate > 1000) {
                        lastNotifUpdate = now
                        val elapsed = (now - startTimeMs) / 1000
                        val min = elapsed / 60
                        val sec = elapsed % 60
                        val status = String.format("%02d:%02d | %s", min, sec, TranscriptionQueue.status())
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(status))
                    }

                    val chunkElapsed = now - chunkStartTime
                    val isSilence = silenceStartMs != null && (now - silenceStartMs!!) >= SILENCE_MIN_MS
                    val shouldFlush = when {
                        chunkElapsed >= MAX_CHUNK_MS && pcmChunk.size() > 8000 -> true // force at max 28s
                        chunkElapsed >= TARGET_CHUNK_MS && isSilence && pcmChunk.size() > 8000 -> true // gold: at silence near 20s
                        chunkElapsed >= CHUNK_DURATION_MS && pcmChunk.size() > 8000 -> true // legacy fallback 30s
                        else -> false
                    }
                    if (shouldFlush) {
                        val toFlush = pcmChunk
                        flushExecutor.submit { flushChunk(toFlush, chunkStartTime) }
                        pcmChunk = ByteArrayOutputStream()
                        chunkStartTime = now
                        silenceStartMs = null
                    }
                }

                if (pcmChunk.size() > 4000) {
                    val toFlush = pcmChunk
                    flushExecutor.submit { flushChunk(toFlush, chunkStartTime) }
                }

                try {
                    recorder.stop()
                    // shared recorder stays alive for bubble/keyboard (never release)
                } catch (e: Exception) {
                    Log.w(TAG, "Recorder stop error: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun flushChunk(pcmData: ByteArrayOutputStream, chunkStartMs: Long) {
        try {
            val pcmFile = File(cacheDir, "meeting_chunk_${System.currentTimeMillis()}.pcm")
            FileOutputStream(pcmFile).use { it.write(pcmData.toByteArray()) }

            val wavFile = File(cacheDir, "meeting_chunk_${System.currentTimeMillis()}.wav")
            AudioUtils.pcmToWav(pcmFile, wavFile)
            pcmFile.delete()

            segmentCounter++
            Log.i(TAG, "Flushing chunk #$segmentCounter, wav=${wavFile.length()} bytes")

            TranscriptionQueue.enqueue(
                TranscriptionQueue.Job(
                    context = applicationContext,
                    wavFile = wavFile,
                    model = model,
                    lang = lang,
                    onResult = { text ->
                        if (text.isNotBlank()) {
                            synchronized(this) {
                                allText.append(text).append(" ")

                                if (mode == "txt" && transcriptFile != null) {
                                    // Save clean text only (no timestamps)
                                    transcriptFile!!.appendText("$text\n")
                                    getSharedPreferences("whisper", MODE_PRIVATE)
                                        .edit()
                                        .putString("last_transcript_path", transcriptFile!!.absolutePath)
                                        .apply()
                                }
                            }
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "Chunk failed: $error")
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "flushChunk error: ${e.message}")
        }
    }

    private fun stopMeeting() {
        if (!isRecording) {
            stopSelf()
            return
        }
        isRecording = false
        Log.i(TAG, "Stopping meeting... (non-blocking)")
        // show stopping state immediately - don't block main thread (ANR fix)
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("Stopping - finishing queue..."))
        } catch (_: Exception) {}
        Thread {
            try { recordThread?.join(10000) } catch (_: Exception) {}
            var waitCount = 0
            while (TranscriptionQueue.pendingCount() > 0 && waitCount < 30) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                waitCount++
                Log.i(TAG, "Waiting for queue... ($waitCount) pending=${TranscriptionQueue.pendingCount()}")
            }
            val path = transcriptFile?.absolutePath ?: "unknown"
            Log.i(TAG, "Meeting saved: $path")
            try {
                val nm2 = getSystemService(NotificationManager::class.java)
                nm2.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            try { stopSelf() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
