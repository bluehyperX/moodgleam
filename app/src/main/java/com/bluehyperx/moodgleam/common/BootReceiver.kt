package com.bluehyperx.moodgleam.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.util.Preferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val preferences = Preferences(context)
            if (preferences.getBoolean(R.string.pref_key_boot)
                && preferences.getBoolean(R.string.pref_key_lighting_was_active)
            ) {
                val i = Intent(context, BootActivity::class.java)
                i.addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            or Intent.FLAG_ACTIVITY_NO_HISTORY
                            or Intent.FLAG_ACTIVITY_NEW_TASK
                )

                context.startActivity(i)
            }
        }
    }
}
