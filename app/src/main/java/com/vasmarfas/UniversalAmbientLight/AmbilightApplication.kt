package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()
        installFrameworkBugFilter()
        migratePreferences()
        // Инициализируем user properties при запуске приложения
        AnalyticsHelper.initializeUserProperties(this)
    }

    /**
     * Swallows the Android-framework NPE emitted from MediaCodec's internal
     * DisplayListener on display removal (NVIDIA SHIELD HDMI unplug, etc.).
     * Listener outlives release() on affected firmware; no app frames in stack.
     */
    private fun installFrameworkBugFilter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isMediaCodecDisplayListenerNpe(throwable)) {
                Log.w("AmbilightApplication", "Swallowed MediaCodec.onDisplayChanged NPE on ${thread.name}", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isMediaCodecDisplayListenerNpe(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        val top = frames[0]
        if (!top.className.startsWith("android.media.MediaCodec") || top.methodName != "onDisplayChanged") return false
        return frames.any { it.className.startsWith("android.hardware.display.DisplayManagerGlobal") }
    }

    private fun migratePreferences() {
        val prefs = Preferences(this)
        // Migration: pref_key_lighting_was_active was added later.
        // For users who had auto-boot enabled before this preference existed,
        // assume lighting was active so boot-start keeps working after update.
        if (!prefs.contains(R.string.pref_key_lighting_was_active)) {
            if (prefs.getBoolean(R.string.pref_key_boot)) {
                prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            }
        }
    }
}
