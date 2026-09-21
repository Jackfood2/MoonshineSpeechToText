package com.whisperkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var tvMeetingStatus: TextView
    private lateinit var tvMeetingPath: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvLiveTranscript: TextView
    private lateinit var progressTranscribe: ProgressBar
    private lateinit var radioMode: RadioGroup
    private lateinit var transcriptScrollView: NestedScrollView
    private lateinit var btnStartMeeting: Button
    private lateinit var btnStopMeeting: Button

    private var previewPath: String? = null
    private var shownTranscriptBytes = 0L
    private var pendingPartialBytes = ByteArray(0)
    private var wasMeetingBusy = false
    private val previewText =
        android.text.SpannableStringBuilder()

    fun addLiveLine(text: String) {
        appendPreviewText(if (text.endsWith("\n")) text else "$text\n")
    }

    private fun resetPreview() {
        previewText.clear()
        pendingPartialBytes = ByteArray(0)
        shownTranscriptBytes = 0L
        tvLiveTranscript.text = ""
    }

    private fun isTranscriptNearBottom(): Boolean {
        val child = transcriptScrollView.getChildAt(0) ?: return true

        val remaining =
            child.height -
            (
                transcriptScrollView.height +
                transcriptScrollView.scrollY
            )

        return remaining <= 120
    }

    private fun scrollTranscriptToBottom() {
        transcriptScrollView.post {
            transcriptScrollView.fullScroll(
                android.view.View.FOCUS_DOWN
            )
        }
    }

    private fun appendPreviewText(text: String) {
        if (text.isEmpty()) return

        val followBottom = isTranscriptNearBottom()

        previewText.append(text)
        tvLiveTranscript.append(text)

        if (followBottom) {
            scrollTranscriptToBottom()
        }
    }

    private fun updateLivePreview(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) return

            if (previewPath != path) {
                previewPath = path
                resetPreview()
            }

            val currentLength = file.length()

            // Same file was externally truncated or recreated.
            if (currentLength < shownTranscriptBytes) {
                resetPreview()
            }

            if (currentLength == shownTranscriptBytes) {
                return
            }

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(shownTranscriptBytes)

                while (shownTranscriptBytes < currentLength) {
                    val remaining =
                        currentLength - shownTranscriptBytes

                    val wanted =
                        minOf(remaining, 64L * 1024L).toInt()

                    val buffer = ByteArray(wanted)
                    val read = raf.read(buffer)

                    if (read <= 0) break

                    shownTranscriptBytes += read

                    val actual =
                        if (read == buffer.size) {
                            buffer
                        } else {
                            buffer.copyOf(read)
                        }

                    appendPreviewBytes(actual)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "MainActivity",
                "Preview update failed: ${e.message}"
            )
        }
    }

    private fun appendPreviewBytes(newBytes: ByteArray) {
        val combined =
            ByteArray(pendingPartialBytes.size + newBytes.size)

        System.arraycopy(
            pendingPartialBytes,
            0,
            combined,
            0,
            pendingPartialBytes.size
        )

        System.arraycopy(
            newBytes,
            0,
            combined,
            pendingPartialBytes.size,
            newBytes.size
        )

        var lastNewline = -1

        for (index in combined.indices.reversed()) {
            if (combined[index] == '\n'.code.toByte()) {
                lastNewline = index
                break
            }
        }

        if (lastNewline < 0) {
            pendingPartialBytes = combined
            return
        }

        val completed =
            combined.copyOfRange(
                0,
                lastNewline + 1
            )

        pendingPartialBytes =
            combined.copyOfRange(
                lastNewline + 1,
                combined.size
            )

        val text = completed.toString(Charsets.UTF_8)

        appendPreviewText(text)
    }

    private val permRequestCode = 100

    private val pqListener = object : TranscriptionQueue.ProgressListener {
        override fun onProgress(pct: Int) {
            runOnUiThread {
                progressTranscribe.progress = pct
                tvProgressPctText(pct)
            }
        }
    }

    private fun tvProgressPctText(pct: Int) {
        findViewById<TextView>(R.id.tvProgressPct).text =
            if (pct == 0) "0% - idle" else if (TranscriptionQueue.isActive()) {
                val (cur, total) = TranscriptionQueue.batchPosition(); "$pct% ($cur/$total)"
            } else "$pct%"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMeetingStatus = findViewById(R.id.tvMeetingStatus)
        tvMeetingPath = findViewById(R.id.tvMeetingPath)
        tvLiveTranscript = findViewById(R.id.tvLiveTranscript)
        transcriptScrollView = findViewById(R.id.transcriptScrollView)
        btnStartMeeting = findViewById(R.id.btnStartMeeting)
        btnStopMeeting = findViewById(R.id.btnStopMeeting)

        tvLiveTranscript.setTextIsSelectable(true)
        tvLiveTranscript.isLongClickable = true
        btnStopMeeting.isEnabled = false
        tvQueue = findViewById(R.id.tvQueue)
        progressTranscribe = findViewById(R.id.progressTranscribe)
        radioMode = findViewById(R.id.radioMode)

        // gear icon -> comprehensive settings
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnEnableIME).setOnClickListener {
            // Try auto-enable Moonshine, then open settings
            try {
                // Requires no permission via shell - try direct enable
                val imeId = "com.moonshinekeyboard/com.whisperkeyboard.WhisperKeyboardService"
                val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
                if (!enabled.contains(imeId)) {
                    // Ask system to enable - will auto add if user granted WRITE_SECURE_SETTINGS
                    try { Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS, if (enabled.isEmpty()) imeId else "$enabled:$imeId") } catch (_: SecurityException) {}
                }
            } catch (_: Exception) {}
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "Enable 'Moonshine Speech to Text' then return", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnPickIME).setOnClickListener {
            val moonshineIme = "com.moonshinekeyboard/com.whisperkeyboard.WhisperKeyboardService"
            var switched = false
            try {
                // Try direct switch if we have WRITE_SECURE_SETTINGS (granted via adb)
                Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, moonshineIme)
                val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                if (cur == moonshineIme) {
                    Toast.makeText(this, "Switched to Moonshine", Toast.LENGTH_SHORT).show()
                    switched = true
                }
            } catch (e: SecurityException) {
                // No permission - fall back to picker
            } catch (_: Exception) {}
            if (!switched) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                Toast.makeText(this, "Pick 'Moonshine Speech to Text' from list", Toast.LENGTH_LONG).show()
            }
        }

        val swSaveAudio = findViewById<SwitchMaterial>(R.id.switchSaveAudio)
        swSaveAudio.isChecked = getSharedPreferences("whisper", MODE_PRIVATE).getBoolean("save_audio", false)
        swSaveAudio.setOnCheckedChangeListener { _, b ->
            getSharedPreferences("whisper", MODE_PRIVATE).edit().putBoolean("save_audio", b).apply()
            Toast.makeText(this, if (b) "Meeting audio will be saved (large files)" else "Meeting audio deleted after transcription", Toast.LENGTH_SHORT).show()
        }

        btnStartMeeting.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            btnStartMeeting.isEnabled = false
            btnStopMeeting.isEnabled = false
            tvMeetingStatus.text = "Starting microphone..."

            val prefs =
                getSharedPreferences("whisper", MODE_PRIVATE)

            val lang =
                prefs.getString("lang", "auto") ?: "auto"

            val model =
                prefs.getString("model", "small") ?: "small"

            val mode =
                if (
                    radioMode.checkedRadioButtonId ==
                    R.id.radioType
                ) {
                    "type"
                } else {
                    "txt"
                }

            val saveAudio =
                prefs.getBoolean("save_audio", false)

            val intent =
                Intent(
                    this,
                    MeetingRecordService::class.java
                ).apply {
                    action = "START"
                    putExtra("model", model)
                    putExtra("lang", lang)
                    putExtra("mode", mode)
                    putExtra("save_audio", saveAudio)
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        btnStopMeeting.setOnClickListener {
            btnStartMeeting.isEnabled = false
            btnStopMeeting.isEnabled = false

            tvMeetingStatus.text =
                "Stopping and saving final transcription..."

            val intent =
                Intent(
                    this,
                    MeetingRecordService::class.java
                ).apply {
                    action = "STOP"
                }

            startService(intent)
        }

        findViewById<Button>(R.id.btnDonate).setOnClickListener {
            try {
                val uri =
                    android.net.Uri.parse(
                        "https://www.paypal.com/paypalme/jackfood2004"
                    )

                startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "PayPal: jackfood2004@gmail.com",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener { startActivity(Intent(this, PrivacyDashboardActivity::class.java)) }
        findViewById<Button>(R.id.btnPauseQueue).setOnClickListener {
            val paused = TranscriptionQueue.togglePause()
            (it as Button).text = if (paused) "Resume" else "Pause"
        }
        findViewById<Button>(R.id.btnClearQueue).setOnClickListener {
            val n = TranscriptionQueue.clearQueue()
            Toast.makeText(this, "Cleared $n", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRetryQueue).setOnClickListener {
            val c = TranscriptionQueue.failedCount()
            if (c == 0) Toast.makeText(this, "No failed", Toast.LENGTH_SHORT).show() else { TranscriptionQueue.retryFailed(); Toast.makeText(this, "Retrying $c", Toast.LENGTH_SHORT).show() }
        }

        TranscriptionQueue.addListener(pqListener)
        progressTranscribe.progress = TranscriptionQueue.progress()
        tvProgressPctText(TranscriptionQueue.progress())

        requestPermissions()

        // On-demand model policy: do NOT auto-load on app start (battery/RAM).
        // Model loads when the keyboard is entered, on first keyboard switch,
        // or when a meeting starts.
        val prefs = getSharedPreferences("whisper", MODE_PRIVATE)

        lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(800)
                withContext(Dispatchers.Main) {
                    tvQueue.text = TranscriptionQueue.status()

                    val state =
                        MeetingRecordService.serviceState

                    val queueBusy =
                        TranscriptionQueue.isActive()

                    when (state) {
                        MeetingRecordService.ServiceState.STARTING -> {
                            btnStartMeeting.isEnabled = false
                            btnStopMeeting.isEnabled = false
                            tvMeetingStatus.text = "Starting microphone..."
                            wasMeetingBusy = true
                        }

                        MeetingRecordService.ServiceState.RECORDING -> {
                            btnStartMeeting.isEnabled = false
                            btnStopMeeting.isEnabled = true
                            tvMeetingStatus.text =
                                "Recording... tap Stop and Save when finished"
                            wasMeetingBusy = true
                        }

                        MeetingRecordService.ServiceState.STOPPING -> {
                            btnStartMeeting.isEnabled = false
                            btnStopMeeting.isEnabled = false
                            tvMeetingStatus.text =
                                "Stopping microphone and flushing final audio..."
                            wasMeetingBusy = true
                        }

                        MeetingRecordService.ServiceState.PROCESSING -> {
                            btnStartMeeting.isEnabled = false
                            btnStopMeeting.isEnabled = false
                            tvMeetingStatus.text =
                                "Transcribing remaining audio... please wait"
                            wasMeetingBusy = true
                        }

                        MeetingRecordService.ServiceState.IDLE -> {
                            btnStartMeeting.isEnabled = !queueBusy
                            btnStopMeeting.isEnabled = false

                            if (queueBusy) {
                                tvMeetingStatus.text =
                                    "Processing transcription queue..."
                            } else if (wasMeetingBusy) {
                                tvMeetingStatus.text =
                                    "Saved. Ready to record again."
                                wasMeetingBusy = false
                            } else {
                                tvMeetingStatus.text =
                                    "Ready to record"
                            }
                        }
                    }

                    val lastPath = prefs.getString("last_transcript_path", "")
                    if (!lastPath.isNullOrEmpty()) {
                        tvMeetingPath.text = "Last: $lastPath"
                        updateLivePreview(lastPath)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        TranscriptionQueue.removeListener(pqListener)
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return mic && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), permRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permRequestCode && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Mic permission required", Toast.LENGTH_LONG).show()
        }
    }
}
