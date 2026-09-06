package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult
import android.util.Log

class GlobalBikeReceiver : BroadcastReceiver() {
    private val tag = "GlobalBikeReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)

        if (intent.action == GlobalBikeTracker.ACTION_BIKE_LOCATION_UPDATE) {
            val isBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
            if (!isBiking) {
                return
            }

            val location = LocationResult.extractResult(intent)?.lastLocation ?: return
            prefs.edit()
                .putFloat("LAST_BIKE_LAT", location.latitude.toFloat())
                .putFloat("LAST_BIKE_LNG", location.longitude.toFloat())
                .putLong("LAST_BIKE_LOCATION_TS", System.currentTimeMillis())
                .apply()
            Log.d(tag, "Biking location update received.")
            return
        }

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            val editor = prefs.edit()
            val tracker = GlobalBikeTracker(context)

            for (event in result.transitionEvents) {
                if (event.activityType == DetectedActivity.ON_BICYCLE) {
                    when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (!wasBiking) {
                                editor.putBoolean("IS_CURRENTLY_BIKING", true)
                                tracker.startLocationUpdates()
                                Log.i(tag, "Bike ride started. Enabled 20-second location tracking.")
                            }
                        }
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (wasBiking) {
                                editor.putBoolean("IS_CURRENTLY_BIKING", false)
                                tracker.stopLocationUpdates()
                                Log.i(tag, "Bike ride ended. Disabled location tracking.")

                                if (prefs.getBoolean("PENDING_HOME_ARRIVAL", false)) {
                                    HomeArrivalWork.enqueue(context)
                                    editor
                                        .putBoolean("AWAY_FROM_HOME", false)
                                        .putBoolean("PENDING_HOME_ARRIVAL", false)
                                    Log.i(tag, "Completed home arrival after bike activity ended.")
                                }
                            }
                        }
                    }
                }
            }
            editor.apply()
        }
    }

}