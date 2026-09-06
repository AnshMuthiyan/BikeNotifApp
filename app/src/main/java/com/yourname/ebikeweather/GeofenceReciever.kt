package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    private val tag = "GeofenceReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent?.hasError() == true) {
            Log.e(tag, "Geofencing event contained an error.")
            return
        }

        val transition = geofencingEvent?.geofenceTransition
        val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
        when (transition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                prefs.edit().putBoolean("AWAY_FROM_HOME", true).apply()
                Log.i(tag, "Left home geofence.")
            }
            Geofence.GEOFENCE_TRANSITION_ENTER,
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                if (!prefs.getBoolean("AWAY_FROM_HOME", false)) {
                    return
                }

                if (prefs.getBoolean("IS_CURRENTLY_BIKING", false)) {
                    prefs.edit().putBoolean("PENDING_HOME_ARRIVAL", true).apply()
                    Log.i(tag, "Returned home while biking. Waiting for bike activity to end.")
                } else {
                    HomeArrivalWork.enqueue(context)
                    prefs.edit()
                        .putBoolean("AWAY_FROM_HOME", false)
                        .putBoolean("PENDING_HOME_ARRIVAL", false)
                        .apply()
                }
            }
        }
    }
}