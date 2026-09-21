package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MeetingRecordService : Service() {

    enum class ServiceState {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        PROCESSING
    }

    companion object { // Removed 'private' so MainActivity can access it
        const val TAG = "MeetingService"
        const val CHANNEL_ID = "whisper_meeting"
        const val NOTIFICATION_ID = 101

        // Chunk rule: 30-59.9s + 2s silence = close; 60s = force-close.
        const val MIN_CHUNK_MS = 30_000L
        const val MAX_CHUNK_MS = 60_000L
        const val SILENCE_MIN_MS = 2_000L
        const val MIN_CHUNK_BYTES = 8_000

        const val SILENCE_RMS_THRESHOLD = 0.015

        @Volatile
        var serviceState: ServiceState = ServiceState.IDLE
            private set

        @Volatile
        var isServiceRecording = false
            private set
    }

    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var model = "small"
    private var lang = "auto"
    private var mode = "txt"  // "txt" = save clean text file, "type" = commit to focused input
    private var transcriptFile: File? = null
    private var segmentCounter = 0
    private var noteSession: NoteSession? = null
    private var saveAudio = false
    private var wavWriter: PcmWavWriter? = null
    @Volatile
    private var activeRecorder: android.media.AudioRecord? = null
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
                saveAudio =
                    intent.getBooleanExtra(
                        "save_audio",
                        false
                    )
                startMeeting()
            }
            "STOP" -> {
                stopMeeting()
            }
        }
        return START_NOT_STICKY
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
        if (serviceState != ServiceState.IDLE) {
            Log.w(TAG, "Start ignored because state=$serviceState")
            return
        }

        serviceState = ServiceState.STARTING
        if (!MicSessionManager.tryAcquire(MicOwner.MEETING)) {
            serviceState = ServiceState.IDLE
            Log.w(TAG, "Microphone currently owned by another recorder")
            try {
                android.widget.Toast.makeText(this, "Microphone is busy", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        // Storage guard: 8h WAV ~= 900MB when saving audio, else queue scratch only
        val estimatedBytes = if (saveAudio) 1_000_000_000L else 200_000_000L
        if (!hasEnoughStorage(applicationContext, estimatedBytes)) {
            MicSessionManager.release(MicOwner.MEETING)
            serviceState = ServiceState.IDLE
            Log.w(TAG, "Not enough free storage for recording")
            try {
                android.widget.Toast.makeText(this, "Not enough free storage for recording", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            return
        }
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        segmentCounter = 0

        try {
            noteSession = NoteSession(
                applicationContext,
                "meeting"
            )

            transcriptFile = noteSession?.file

            transcriptFile?.absolutePath?.let { path ->
                getSharedPreferences("whisper", MODE_PRIVATE)
                    .edit()
                    .putString("last_transcript_path", path)
                    .apply()
            }

            wavWriter = if (saveAudio) {
                noteSession?.file?.let { txt ->
                    try {
                        PcmWavWriter(File(txt.parent, txt.nameWithoutExtension + ".wav"))
                    } catch (e: Exception) {
                        Log.e(TAG, "wav writer failed: ${e.message}")
                        null
                    }
                }
            } else null

            try {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                }
            } catch (_: Exception) {}
            startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
            Log.i(TAG, "Meeting started: mode=$mode model=$model lang=$lang - WakeLock held, will survive lock screen")
        } catch (e: Exception) {
            // Setup failed after mic acquire (e.g. storage I/O, FGS start):
            // release everything so state and mic never get stuck.
            Log.e(TAG, "Meeting setup failed: ${e.message}", e)
            try { wavWriter?.close() } catch (_: Exception) {}
            wavWriter = null
            noteSession = null
            transcriptFile = null
            MicSessionManager.release(MicOwner.MEETING)
            serviceState = ServiceState.IDLE
            isRecording = false
            isServiceRecording = false
            try {
                android.widget.Toast.makeText(this, "Meeting failed to start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            try { stopSelf() } catch (_: Exception) {}
            return
        }

        recordThread = Thread {
            var pcmChunk = ByteArrayOutputStream()
            var chunkStartTime = System.currentTimeMillis()
            var silenceStartMs: Long? = null
            var recorder: android.media.AudioRecord? = null

            try {
                recorder = AudioUtils.createRecorder(applicationContext)
                activeRecorder = recorder
                recorder.startRecording()

                if (serviceState == ServiceState.STARTING) {
                    isRecording = true
                    isServiceRecording = true
                    serviceState = ServiceState.RECORDING
                } else {
                    // Stop arrived while starting: release the recorder without recording.
                    try { recorder.stop() } catch (_: Exception) {}
                    isRecording = false
                }

                val buffer = ByteArray(4096)
                var lastNotifUpdate = 0L

                while (isRecording) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        if (isRecording) {
                            Log.e(TAG, "Recorder read failed: ${e.message}")
                        }
                        break
                    }

                    if (read <= 0) {
                        if (!isRecording) break
                        continue
                    }

                    pcmChunk.write(buffer, 0, read)

                    try {
                        wavWriter?.write(buffer, 0, read)
                    } catch (e: Exception) {
                        Log.w(TAG, "Full WAV write failed: ${e.message}")
                    }

                    val now = System.currentTimeMillis()
                    val rms = AudioUtils.rms16(buffer, read)

                    if (rms < SILENCE_RMS_THRESHOLD) {
                        if (silenceStartMs == null) {
                            silenceStartMs = now
                        }
                    } else {
                        silenceStartMs = null
                    }

                    if (now - lastNotifUpdate >= 1000L) {
                        lastNotifUpdate = now

                        val elapsed = (now - startTimeMs) / 1000L
                        val min = elapsed / 60L
                        val sec = elapsed % 60L

                        val status = String.format(
                            "%02d:%02d | %s",
                            min,
                            sec,
                            TranscriptionQueue.status()
                        )

                        try {
                            getSystemService(NotificationManager::class.java)
                                .notify(
                                    NOTIFICATION_ID,
                                    buildNotification(status)
                                )
                        } catch (_: Exception) {
                        }

                        val queueBytes = TranscriptionQueue.pendingAudioBytes()
                        if (queueBytes > 250L * 1024 * 1024) {
                            AppLog.w(
                                TAG,
                                "Large pending audio queue: $queueBytes bytes"
                            )
                        }
                    }

                    val chunkElapsed = now - chunkStartTime
                    val silenceDuration =
                        silenceStartMs?.let { now - it } ?: 0L

                    val shouldFlush =
                        pcmChunk.size() >= MIN_CHUNK_BYTES &&
                        (
                            (
                                chunkElapsed >= MIN_CHUNK_MS &&
                                silenceDuration >= SILENCE_MIN_MS
                            ) ||
                            chunkElapsed >= MAX_CHUNK_MS
                        )

                    if (shouldFlush) {
                        val audio = pcmChunk
                        val audioStart = chunkStartTime

                        pcmChunk = ByteArrayOutputStream()
                        chunkStartTime = now
                        silenceStartMs = null

                        flushExecutor.submit {
                            flushChunk(audio, audioStart)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }
                // shared recorder stays alive for bubble/keyboard (never release)
                activeRecorder = null

                // Manual stop must preserve a short final section.
                if (pcmChunk.size() >= 4_000) {
                    val finalAudio = pcmChunk
                    val finalStart = chunkStartTime

                    flushExecutor.submit {
                        flushChunk(finalAudio, finalStart)
                    }
                }

                Log.i(TAG, "Meeting recording thread finished")
            }
        }.apply {
            isDaemon = true
            name = "meeting-recorder"
            start()
        }
    }

    private fun flushChunk(pcmData: ByteArrayOutputStream, chunkStartMs: Long) {
        try {
            val timestamp = System.currentTimeMillis()

            val pcmFile =
                File(cacheDir, "meeting_chunk_$timestamp.pcm")
            FileOutputStream(pcmFile).use { it.write(pcmData.toByteArray()) }

            val wavFile =
                File(cacheDir, "meeting_chunk_$timestamp.wav")
            AudioUtils.pcmToWav(pcmFile, wavFile)
            pcmFile.delete()

            segmentCounter++
            // 16 kHz mono 16-bit PCM = 32,000 bytes/sec (matches AudioUtils.createRecorder).
            val audioSeconds = pcmData.size().toDouble() / 32_000.0
            Log.i(
                TAG,
                "Flushing chunk #$segmentCounter, " +
                    "duration=${"%.1f".format(audioSeconds)}s, " +
                    "wav=${wavFile.length()} bytes"
            )

            val job = TranscriptionQueue.Job(
                context = applicationContext,
                wavFile = wavFile,
                model = model,
                lang = lang,
                    onResult = { text ->
                        val cleaned = text.trim()

                        if (
                            cleaned.isNotEmpty() &&
                            !AudioUtils.isNoSpeechText(cleaned)
                        ) {
                            // Always preserve and preview the transcript.
                            noteSession?.append(cleaned)

                            noteSession?.path()?.let { path ->
                                getSharedPreferences("whisper", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_transcript_path", path)
                                    .apply()
                            }

                            // Type mode additionally sends the text to the focused input.
                            if (mode == "type") {
                                TextRouter.route(cleaned)
                            }
                        }
                    },
                onError = { error ->
                    Log.e(TAG, "Chunk failed: $error")
                }
            )
            if (!TranscriptionQueue.enqueue(job)) {
                AppLog.e(TAG, "Unable to enqueue transcription chunk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushChunk error: ${e.message}")
        }
    }

    private fun stopMeeting() {
        if (
            serviceState == ServiceState.IDLE ||
            serviceState == ServiceState.STOPPING ||
            serviceState == ServiceState.PROCESSING
        ) {
            // Nothing to stop: ensure no empty service lingers.
            try { stopSelf() } catch (_: Exception) {}
            return
        }

        serviceState = ServiceState.STOPPING
        isRecording = false
        isServiceRecording = false

        Log.i(TAG, "Stopping meeting and flushing final audio")

        try {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        "Stopping microphone and saving final audio..."
                    )
                )
        } catch (_: Exception) {
        }

        // Unblock a recorder.read() immediately.
        try {
            activeRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop/unblock failed: ${e.message}")
        }

        Thread {
            finishStoppingMeeting()
        }.apply {
            isDaemon = true
            name = "meeting-stop"
            start()
        }
    }

    private fun finishStoppingMeeting() {
        try {
            /*
             * Wait for the recording thread to reach finally and submit the
             * final partial audio to flushExecutor.
             */
            try {
                recordThread?.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            recordThread = null

            try {
                wavWriter?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Full WAV close failed: ${e.message}")
            }

            wavWriter = null

            /*
             * Because flushExecutor has one worker, this barrier completes only
             * after every earlier flushChunk task has finished enqueueing.
             */
            try {
                flushExecutor.submit {
                    Log.i(TAG, "Meeting flush barrier reached")
                }.get()
            } catch (e: Exception) {
                Log.e(TAG, "Meeting flush barrier failed: ${e.message}")
            }

            serviceState = ServiceState.PROCESSING

            try {
                getSystemService(NotificationManager::class.java)
                    .notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            "Transcribing and saving remaining audio..."
                        )
                    )
            } catch (_: Exception) {
            }

            /*
             * Stop and Save means complete processing. If the queue was paused,
             * resume it so saving cannot wait forever.
             */
            if (TranscriptionQueue.isPaused()) {
                Log.i(TAG, "Resuming paused queue to complete Stop and Save")
                TranscriptionQueue.resume()
            }

            while (TranscriptionQueue.isActive()) {
                try {
                    Thread.sleep(250L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            /*
             * Type mode may still be waiting to deliver the final result.
             * The saved note itself is already complete at this point, so do not
             * block meeting completion indefinitely on input delivery.
             */
            val path = transcriptFile?.absolutePath.orEmpty()

            if (path.isNotEmpty()) {
                getSharedPreferences("whisper", MODE_PRIVATE)
                    .edit()
                    .putString("last_transcript_path", path)
                    .apply()
            }

            Log.i(TAG, "Meeting fully saved: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Meeting completion failed: ${e.message}", e)
        } finally {
            MicSessionManager.release(MicOwner.MEETING)

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {
            }

            try {
                getSystemService(NotificationManager::class.java)
                    .cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }

            serviceState = ServiceState.IDLE

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        try { wavWriter?.close() } catch (_: Exception) {}
        wavWriter = null
        MicSessionManager.release(MicOwner.MEETING)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        serviceState = ServiceState.IDLE
        isServiceRecording = false
        super.onDestroy()
    }

    fun hasEnoughStorage(
        context: android.content.Context,
        requiredBytes: Long
    ): Boolean {

        val stat =
            android.os.StatFs(
                context.filesDir.absolutePath
            )

        val available =
            stat.availableBytes

        return available >= requiredBytes
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
