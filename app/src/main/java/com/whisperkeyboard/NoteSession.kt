package com.whisperkeyboard

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteSession(
    private val context: Context,
    private val title: String
) {

    val file: File

    private val lock = Any()

    init {
        val docsDir = File(
            context.getExternalFilesDir(null),
            "WhisperNotes"
        )

        if (!docsDir.exists()) {
            docsDir.mkdirs()
        }

        val stamp =
            SimpleDateFormat(
                "yyyy-MM-dd_HHmmss_SSS",
                Locale.US
            ).format(Date())

        file = File(
            docsDir,
            "${title}_$stamp.txt"
        )

        if (!file.exists()) {
            file.createNewFile()
        }
    }

    fun append(text: String) {
        if (text.isBlank()) return

        synchronized(lock) {
            file.appendText(
                text.trim() + "\n"
            )
        }
    }

    fun path(): String = file.absolutePath
}
