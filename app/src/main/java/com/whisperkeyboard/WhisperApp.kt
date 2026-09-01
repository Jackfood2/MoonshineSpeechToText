package com.whisperkeyboard

import android.app.Application
import android.content.Context

class WhisperApp : Application() {
    companion object {
        @Volatile var holder: Context? = null
        val appContext: Context? get() = holder
    }
    override fun onCreate() {
        super.onCreate()
        holder = this
    }
}
