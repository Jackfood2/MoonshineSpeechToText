package com.whisperkeyboard

import android.content.Context

/**
 * Shim: keeps old API name but delegates to MoonshineEngine (v2).
 * All Whisper .bin / native code removed; Moonshine uses .ort bundles via moonshine-voice.
 * This allows existing app code (TranscriptionQueue, IME, etc) to keep calling WhisperEngine.
 */
object WhisperEngine {

    // Re-expose Moonshine's state so UI shows correct model name
    var lastError: String
        get() = MoonshineEngine.lastError
        set(_) {}

    fun setThreads(threads: Int) = MoonshineEngine.setThreads(threads)
    fun applyThreadPref(ctx: Context?): Int = MoonshineEngine.applyThreadPref(ctx)
    fun cancelCurrent() = MoonshineEngine.cancelCurrent()

    fun ensureModel(modelPath: String): Boolean {
        // modelPath may be ggml-*.bin legacy; MoonshineEngine handles mapping
        return MoonshineEngine.ensureModel(modelPath)
    }

    // New overload with context
    fun ensureModel(ctx: Context, model: String, lang: String = "en"): Boolean {
        return MoonshineEngine.ensureModel(ctx, model, lang)
    }

    fun isLoaded(modelPath: String): Boolean {
        // handle both full path and simple name
        if (modelPath.contains("/") || modelPath.contains(".bin")) {
            val name = when {
                modelPath.contains("tiny") -> "tiny"
                modelPath.contains("base") -> "base"
                modelPath.contains("small") -> "small"
                modelPath.contains("medium") -> "medium"
                else -> modelPath
            }
            return MoonshineEngine.isLoaded(name)
        }
        return MoonshineEngine.isLoaded(modelPath)
    }

    fun loadedModel(): String? = MoonshineEngine.loadedModel()
    fun isBusy(): Boolean = MoonshineEngine.isBusy()
    fun unloadIfIdle(): Boolean = MoonshineEngine.unloadIfIdle()

    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        return MoonshineEngine.transcribe(modelPath, wavPath, lang)
    }
}
