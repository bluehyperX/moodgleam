package com.vasmarfas.UniversalAmbientLight.common

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for capturing screen content via Accessibility API (Android 11+).
 * This is a workaround for devices where MediaProjection is blocked.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AccessibilityCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service connected")
        instance.set(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service interrupted")
        instance.set(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance.set(null)
        return super.onUnbind(intent)
    }

    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var copy: Bitmap? = null
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        // Copy to a software-readable bitmap. wrapHardwareBuffer-backed
                        // bitmaps must not be recycled — closing the buffer is enough.
                        copy = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot conversion failed", e)
                    } finally {
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                    }
                    callback(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            })
        } else {
            callback(null)
        }
    }

    companion object {
        private const val TAG = "AccessibilityCapture"
        private val instance = AtomicReference<AccessibilityCaptureService?>(null)

        fun getInstance(): AccessibilityCaptureService? = instance.get()
    }
}
