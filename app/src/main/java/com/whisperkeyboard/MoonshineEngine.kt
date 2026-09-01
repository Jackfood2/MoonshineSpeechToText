package com.whisperkeyboard

import android.content.Context
import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.Transcript
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Moonshine v2 engine - replaces WhisperEngine.
 * Uses ai.moonshine:moonshine-voice (Transcriber + MicTranscriber) for on-device streaming STT.
 * Same public API as WhisperEngine so TranscriptionQueue / IME need minimal changes.
 * File transcription via Transcriber.transcribeWithoutStreaming (offline), not mic.
 */
object MoonshineEngine {

    private const val TAG = "MoonshineEngine"
    private val lock = ReentrantLock()
    private val busyCount = AtomicInteger(0)

    @Volatile private var loadedModel: String? = null
    @Volatile private var loadedArch: Int = -1
    @Volatile var lastError: String = ""
        private set

    // The transcriber used for file transcription (offline)
    @Volatile private var transcriber: Transcriber? = null
    // Mic transcriber cache for download (also usable for transcription)
    @Volatile private var micTranscriber: MicTranscriber? = null

    private fun archFor(model: String): Int = when (model) {
        "tiny" -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
        "base" -> JNI.MOONSHINE_MODEL_ARCH_BASE_STREAMING
        "small" -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
        "medium" -> JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
        "tiny-legacy" -> JNI.MOONSHINE_MODEL_ARCH_TINY
        "base-legacy" -> JNI.MOONSHINE_MODEL_ARCH_BASE
        else -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
    }

    private fun langFor(lang: String): String = when (lang) {
        "auto", "" -> "en"
        "en", "zh", "ja", "ko", "fr", "de", "es" -> lang
        else -> "en"
    }

    fun setThreads(threads: Int) {
        // Moonshine uses ONNX Runtime thread pool internally; no manual set needed.
        AppLog.i(TAG, "setThreads($threads) - no-op (ONNX Runtime auto)")
    }

    fun applyThreadPref(ctx: Context?): Int {
        if (ctx == null) return 4
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pref = ctx.getSharedPreferences("whisper", android.content.Context.MODE_PRIVATE).getString("threads_mode", "auto") ?: "auto"
        val n = if (pref == "auto") {
            if (cores >= 8) 6 else if (cores >= 4) 4 else cores
        } else pref.toIntOrNull()?.coerceIn(1, cores) ?: 4
        setThreads(n)
        return n
    }

    fun cancelCurrent() {
        // Transcriber transcribeWithoutStreaming is synchronous; no cancel hook.
        // MicTranscriber stop would cancel streaming - no-op here.
        AppLog.i(TAG, "cancelCurrent - no-op for offline transcription")
    }

    fun ensureModel(context: Context, model: String, lang: String = "en"): Boolean {
        if (model.isBlank()) return false
        lock.withLock {
            try {
                val arch = archFor(model)
                val langCode = langFor(lang)
                if (loadedModel == model && loadedArch == arch && transcriber?.isLoaded == true) return true

                AppLog.i(TAG, "ensureModel $model arch=$arch lang=$langCode")
                // Use MicTranscriber to trigger download + caching, then create a Transcriber for file use.
                // MicTranscriber.load() handles downloading .ort bundles to cache.
                try {
                    micTranscriber?.close()
                } catch (_: Throwable) {}
                micTranscriber = null

                val mic = MicTranscriber(context).language(langCode).modelArch(arch)
                // Optional progress logging
                mic.onProgress { fraction, file ->
                    AppLog.i(TAG, "downloading $file ${(fraction*100).toInt()}%")
                }
                val t0 = System.currentTimeMillis()
                try {
                    mic.load()
                } catch (e: Throwable) {
                    lastError = e.message ?: "load failed"
                    AppLog.e(TAG, "MicTranscriber load failed: ${e.message}")
                    return false
                }
                val ms = System.currentTimeMillis() - t0
                AppLog.i(TAG, "MicTranscriber loaded $model in $ms ms")

                // Reuse its underlying handle for file transcription:
                // Create a separate Transcriber that loads from the same cache directory.
                // The easiest is to reuse mic itself for file transcription via transcribeWithoutStreaming
                // (MicTranscriber inherits Transcriber).
                transcriber = mic
                micTranscriber = mic
                loadedModel = model
                loadedArch = arch
                lastError = ""
                ModelNotifier.loaded("moonshine-$model", 0)
                return true
            } catch (e: Throwable) {
                lastError = e.message ?: "load error"
                AppLog.e(TAG, "ensureModel error: ${e.message}")
                return false
            }
        }
    }

    // Compatibility overload used by old callers: modelPath is ggml path, we map to model name
    fun ensureModel(modelPath: String): Boolean {
        // modelPath like /.../ggml-small.bin -> extract "small"
        val name = when {
            modelPath.contains("tiny") -> "tiny"
            modelPath.contains("base") -> "base"
            modelPath.contains("small") -> "small"
            modelPath.contains("medium") -> "medium"
            else -> "small"
        }
        // Need a context - try to get app context via WhisperApp if available
        val ctx = WhisperApp.holder
        return if (ctx != null) ensureModel(ctx, name) else false
    }

    fun isLoaded(model: String): Boolean = loadedModel == model && transcriber?.isLoaded == true
    fun isLoaded(modelPath: String, dummy: Boolean): Boolean = isLoaded(modelPath) // keep compat
    fun loadedModel(): String? = loadedModel
    fun isBusy(): Boolean = busyCount.get() > 0

    fun unloadIfIdle(): Boolean {
        lock.withLock {
            if (busyCount.get() > 0) {
                AppLog.i(TAG, "skip unload - busy")
                return false
            }
            return try {
                val was = loadedModel
                try { transcriber?.close() } catch (_: Throwable) {}
                // mic and transcriber are same object
                transcriber = null
                micTranscriber = null
                loadedModel = null
                loadedArch = -1
                AppLog.i(TAG, "unloaded $was")
                ModelNotifier.unloaded(was)
                true
            } catch (e: Throwable) {
                AppLog.w(TAG, "unload failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Transcribe WAV file using Moonshine.
     * Reads WAV, converts to float PCM [-1,1], calls transcribeWithoutStreaming.
     */
    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        busyCount.incrementAndGet()
        val t0 = System.currentTimeMillis()
        try {
            lock.withLock {
                try {
                    // Resolve model name from path or from loadedModel
                    val modelName = when {
                        modelPath.contains("ggml-tiny") || modelPath.contains("tiny") -> "tiny"
                        modelPath.contains("ggml-base") || modelPath.contains("base") -> "base"
                        modelPath.contains("ggml-medium") || modelPath.contains("medium") -> "medium"
                        else -> loadedModel ?: "small"
                    }
                    val ctx = WhisperApp.holder
                    if (transcriber == null || loadedModel != modelName) {
                        if (ctx != null) {
                            val ok = ensureModel(ctx, modelName, lang)
                            if (!ok) return "ERROR: Failed to load moonshine model $modelName: $lastError"
                        } else {
                            return "ERROR: No context to load model"
                        }
                    }
                    val tr = transcriber ?: return "ERROR: Transcriber not loaded"

                    // Read WAV -> float[]
                    val wavFile = File(wavPath)
                    if (!wavFile.exists()) return "ERROR: WAV not found $wavPath"
                    val pcmFloats = readWavAsFloats(wavFile) ?: return "ERROR: Invalid WAV"
                    if (pcmFloats.isEmpty()) return ""

                    val transcript: Transcript = try {
                        tr.transcribeWithoutStreaming(pcmFloats, 16000)
                    } catch (e: Throwable) {
                        AppLog.e(TAG, "transcribeWithoutStreaming threw: ${e.message}")
                        return "ERROR: ${e.message}"
                    }
                    val text = transcript.text()?.trim() ?: ""
                    val ms = System.currentTimeMillis() - t0
                    AppLog.i(TAG, "moonshine transcribed in $ms ms -> ${text.take(60)}")
                    lastError = ""
                    if (text.isEmpty() || AudioUtils.isNoSpeechText(text)) return ""
                    return text
                } catch (e: OutOfMemoryError) {
                    lastError = "Out of memory"
                    AppLog.e(TAG, "OOM during transcribe")
                    try { transcriber?.close(); transcriber=null; micTranscriber=null; loadedModel=null } catch (_: Throwable) {}
                    return "ERROR: Out of memory - try smaller model"
                } catch (e: Throwable) {
                    lastError = e.message ?: "transcribe error"
                    AppLog.e(TAG, "Transcribe error: ${e.message}")
                    return "ERROR: ${e.message}"
                }
            }
        } finally {
            busyCount.decrementAndGet()
        }
    }

    // overload used by callers that pass model name directly
    fun transcribeWithModel(model: String, wavPath: String, lang: String, ctx: Context): String {
        return transcribe("ggml-$model.bin", wavPath, lang)
    }

    private fun readWavAsFloats(wav: File): FloatArray? {
        try {
            val bytes = wav.readBytes()
            if (bytes.size < 44) return null
            // Parse header
            fun le16(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off+1].toInt() and 0xFF) shl 8)
            fun le32(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off+1].toInt() and 0xFF) shl 8) or ((bytes[off+2].toInt() and 0xFF) shl 16) or ((bytes[off+3].toInt() and 0xFF) shl 24)
            val channels = le16(22)
            val sampleRate = le32(24)
            val bits = le16(34)
            val dataSize = le32(40)
            // Find data chunk if header not 44 (some wavs have extra chunks)
            var dataOffset = 44
            // if "data" not at 36, search
            val dataTag = String(bytes.sliceArray(36..39))
            if (dataTag != "data") {
                // search for "data"
                for (i in 0 until bytes.size - 4) {
                    if (bytes[i]== 'd'.code.toByte() && bytes[i+1]== 'a'.code.toByte() && bytes[i+2]== 't'.code.toByte() && bytes[i+3]== 'a'.code.toByte()) {
                        dataOffset = i+8
                        break
                    }
                }
            }
            val pcmBytes = bytes.size - dataOffset
            val numSamples = when (bits) {
                16 -> pcmBytes / 2 / channels
                32 -> pcmBytes / 4 / channels
                else -> pcmBytes / 2 / channels
            }
            val floats = FloatArray(numSamples)
            if (bits == 16) {
                var idx = dataOffset
                for (i in 0 until numSamples) {
                    val s = if (channels == 2) {
                        // average stereo
                        val l = ((bytes[idx+1].toInt() shl 8) or (bytes[idx].toInt() and 0xFF)).toShort().toInt()
                        val r = ((bytes[idx+2].toInt() shl 8) or (bytes[idx+3].toInt() and 0xFF)).toShort().toInt()
                        idx += 4
                        (l + r) / 2
                    } else {
                        val v = ((bytes[idx+1].toInt() shl 8) or (bytes[idx].toInt() and 0xFF)).toShort().toInt()
                        idx += 2
                        v
                    }
                    floats[i] = s / 32768f
                }
            } else {
                // fallback 16
                var idx = dataOffset
                for (i in 0 until numSamples) {
                    val v = ((bytes[idx+1].toInt() shl 8) or (bytes[idx].toInt() and 0xFF)).toShort().toInt()
                    idx += 2
                    floats[i] = v / 32768f
                }
            }
            // Resample if needed (nearest neighbor simple)
            if (sampleRate != 16000 && floats.isNotEmpty()) {
                val ratio = sampleRate / 16000f
                val newSize = (numSamples / ratio).toInt()
                val res = FloatArray(newSize)
                for (i in 0 until newSize) {
                    val src = (i * ratio).toInt().coerceIn(0, numSamples-1)
                    res[i] = floats[src]
                }
                return res
            }
            return floats
        } catch (e: Throwable) {
            AppLog.e(TAG, "readWav failed: ${e.message}")
            return null
        }
    }
}
