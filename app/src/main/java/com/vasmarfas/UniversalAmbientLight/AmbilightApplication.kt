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
     * Filters out a known Android framework NPE delivered from the system
     * MediaCodec's DisplayListener when a display is removed (e.g. HDMI unplug
     * on NVIDIA SHIELD, certain Sony TVs). The crash signature is:
     *
     *   java.lang.NullPointerException at
     *     android.media.MediaCodec$1.onDisplayChanged(MediaCodec.java)
     *
     * No app code is on the stack — MediaCodec installs the listener internally
     * and Android forgets to null-check DisplayInfo. We cannot prevent the
     * crash from the encoder side because the listener outlives our release()
     * calls on affected firmware. Catching it at the global handler is the
     * pragmatic workaround used by several large apps (see crbug.com/1163307
     * for the Chromium equivalent).
     */
    private fun installFrameworkBugFilter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isMediaCodecDisplayListenerNpe(throwable)) {
                Log.w(
                    "AmbilightApplication",
                    "Swallowed framework MediaCodec.onDisplayChanged NPE on ${thread.name}",
                    throwable
                )
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isMediaCodecDisplayListenerNpe(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        // Top frame must be MediaCodec's anonymous DisplayListener; we further
        // require DisplayManagerGlobal to be on the stack so we don't swallow
        // unrelated NPEs that happen to mention MediaCodec.
        val top = frames[0]
        val matchesTop = top.className.startsWith("android.media.MediaCodec") &&
                top.methodName == "onDisplayChanged"
        if (!matchesTop) return false
        return frames.any {
            it.className.startsWith("android.hardware.display.DisplayManagerGlobal")
        }
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
