package com.bluehyperx.moodgleam.common.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bluehyperx.moodgleam.R

/**
 * Utility to help users report bugs on GitHub with pre-formatted device info.
 * This is the FOSS-friendly alternative to automated crash reporting.
 */
object BugReportHelper {
    private const val TAG = "BugReportHelper"

    fun launchBugReport(context: Context) {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "Unknown"
        }

        val deviceInfo = """
            |
            |---
            |### Device Info
            |- **App Version**: $appVersion
            |- **Android Version**: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            |- **Device**: ${Build.MANUFACTURER} ${Build.MODEL}
            |- **Capture Method**: ${getCaptureMethod(context)}
            |- **Connection Type**: ${getConnectionType(context)}
            |
            |### Description
            |Please describe what happened and how to reproduce it. 
            |If possible, attach a logcat output below.
        """.trimMargin()

        val baseUrl = context.getString(R.string.github_issues_url)
        val title = "Bug Report: [Short description]"
        val uri = Uri.parse(baseUrl)
            .buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", deviceInfo)
            .build()

        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GitHub issue URL", e)
        }
    }

    private fun getCaptureMethod(context: Context): String {
        return try {
            val prefs = Preferences(context)
            prefs.getString(R.string.pref_key_capture_method, "media_projection") ?: "media_projection"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getConnectionType(context: Context): String {
        return try {
            val prefs = Preferences(context)
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
