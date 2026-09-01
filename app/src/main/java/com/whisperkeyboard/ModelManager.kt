package com.whisperkeyboard

import android.content.Context
import java.io.File

/**
 * Moonshine v2 ModelManager - replaces Whisper ggml-*.bin manager.
 * Models are .ort bundles auto-downloaded by ai.moonshine:moonshine-voice into
 * ModelCache.defaultRoot(context) (internal cache). We keep a thin marker file
 * in external files/models so existing UI (modelFile/clear) still works.
 */
object ModelManager {

    // Human-readable sizes for UI (approx on-disk after download)
    private fun sizeFor(model: String): String = when (model) {
        "tiny" -> "~34 MB (tiny-streaming, Moonshine v2)"
        "base" -> "~60 MB (base-streaming)"
        "small" -> "~123 MB (small-streaming, v2)"
        "medium" -> "~245 MB (medium-streaming, v2, best accuracy)"
        else -> "unknown"
    }

    fun modelsDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Marker file so old code's `modelFile(...).exists()` can be migrated.
    // Real models live in ModelCache; marker just indicates "download attempted".
    fun modelFile(ctx: Context, model: String): File {
        return File(modelsDir(ctx), "moonshine-$model.marker")
    }

    // Check if moonshine model is actually cached and loadable
    fun isModelReady(ctx: Context, model: String): Boolean {
        // Check marker + try to see if ModelCache has files
        // We treat marker existence as ready after first successful load,
        // but also allow probing via MoonshineEngine.isLoaded
        val marker = modelFile(ctx, model)
        if (marker.exists() && marker.length() >= 0) return true
        // Also check internal cache directory
        return try {
            val cacheRoot = ai.moonshine.voice.ModelCache.defaultRoot(ctx)
            // look for any dir containing model name
            cacheRoot.listFiles()?.any { it.name.contains(model, ignoreCase = true) } == true
        } catch (_: Throwable) { false }
    }

    fun localStatus(ctx: Context, model: String): String {
        val marker = modelFile(ctx, model)
        return if (isModelReady(ctx, model) || MoonshineEngine.isLoaded(model)) {
            val arch = when (model) {
                "tiny" -> "Tiny Streaming v2"
                "base" -> "Base Streaming"
                "small" -> "Small Streaming v2"
                "medium" -> "Medium Streaming v2"
                else -> model
            }
            "Ready: $arch ${sizeFor(model)} - cached on-device"
        } else {
            if (marker.exists()) "Cached but not verified - tap Download to verify"
            else "Not downloaded - tap Download (WiFi recommended, ${sizeFor(model)})"
        }
    }

    /**
     * Download moonshine model via MoonshineEngine (which uses micTranscriber.load()).
     * Calls onProgress with 0..100.
     */
    fun download(ctx: Context, model: String, onProgress: (Int, String) -> Unit) {
        val marker = modelFile(ctx, model)
        if (isModelReady(ctx, model) && MoonshineEngine.isLoaded(model)) {
            onProgress(100, "Already downloaded: moonshine-$model")
            return
        }
        onProgress(5, "Preparing $model (${sizeFor(model)})...")
        try {
            // MoonshineEngine.ensureModel will download .ort bundles to internal cache
            // It reports via AppLog; we simulate progress
            onProgress(15, "Downloading $model - this may take a minute on first run...")
            val ok = MoonshineEngine.ensureModel(ctx, model, "en")
            if (!ok) {
                throw RuntimeException(MoonshineEngine.lastError.ifEmpty { "Model load failed" })
            }
            // create marker for UI persistence
            try {
                marker.parentFile?.mkdirs()
                marker.writeText("moonshine-$model cached at ${System.currentTimeMillis()}")
            } catch (_: Throwable) {}
            onProgress(100, "Ready: moonshine-$model (${sizeFor(model)})")
        } catch (e: Throwable) {
            AppLog.e("ModelManager", "download failed: ${e.message}")
            throw RuntimeException(e.message ?: "Download failed", e)
        }
    }

    // Helper for UI to list all models
    fun allModels() = arrayOf("tiny", "base", "small", "medium")
}
