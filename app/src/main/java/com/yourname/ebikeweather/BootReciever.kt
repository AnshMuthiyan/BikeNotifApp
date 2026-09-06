package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i(tag, "Boot event received. Re-registering bike activity tracker.")
            val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
            val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
            val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()

            if (lat != 0.0 && lng != 0.0) {
                val globalTracker = GlobalBikeTracker(context)
                globalTracker.start()
            }
        }
    }
}