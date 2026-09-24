package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult
import android.util.Log
import kotlinx.coroutines.launch

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

            val homeLat = prefs.getFloat("HOME_LAT", 0f)
            val homeLng = prefs.getFloat("HOME_LNG", 0f)
            var isNearHome = prefs.getBoolean("PENDING_HOME_ARRIVAL", false)
            var isHighFreq = prefs.getBoolean("HIGH_FREQ_GPS_ENABLED", false)

            var distanceMeters = 0f
            val lastLat = prefs.getFloat("LAST_BIKE_LAT", 0f)
            val lastLng = prefs.getFloat("LAST_BIKE_LNG", 0f)
            val lastTs = prefs.getLong("LAST_BIKE_LOCATION_TS", 0L)
            
            if (lastLat != 0f && lastLng != 0f) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(lastLat.toDouble(), lastLng.toDouble(), location.latitude, location.longitude, results)
                distanceMeters = results[0]
            }

            if (homeLat != 0f && homeLng != 0f) {
                val distHome = FloatArray(1)
                android.location.Location.distanceBetween(homeLat.toDouble(), homeLng.toDouble(), location.latitude, location.longitude, distHome)
                
                // 1/3 mile = 536 meters
                if (distHome[0] <= 536f && !isHighFreq) {
                    GlobalBikeTracker(context).startHighFrequencyLocationUpdates()
                    prefs.edit().putBoolean("HIGH_FREQ_GPS_ENABLED", true).apply()
                    Log.i(tag, "Within 1/3 mile of home. Upgraded to 5-second GPS checks.")
                }

                if (!isNearHome && distHome[0] <= 150f) { // Within 150 meters of home
                    isNearHome = true
                    prefs.edit().putBoolean("PENDING_HOME_ARRIVAL", true).apply()
                    Log.i(tag, "Manual distance check triggered home arrival! Geofence may have been asleep.")
                }
            }

            var currentSpeed = if (location.hasSpeed()) location.speed else null
            if (currentSpeed == null && lastTs > 0L) {
                val timeDiffSeconds = (System.currentTimeMillis() - lastTs) / 1000f
                if (timeDiffSeconds > 0) {
                    currentSpeed = distanceMeters / timeDiffSeconds
                }
            }
            
            // Default to 99f so it doesn't trigger if it's the very first ping with no speed
            val speedToEvaluate = currentSpeed ?: 99f

            if (isNearHome && speedToEvaluate < 1.5f) {
                Log.i(tag, "Speed dropped near zero inside geofence! Bypassing slow activity recognition.")
                val editor = prefs.edit()
                editor.putBoolean("IS_CURRENTLY_BIKING", false)
                GlobalBikeTracker(context).stopLocationUpdates()
                
                // Instantly fetch weather and trigger notification!
                val pendingResult = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val engine = WeatherLogicEngine()
                        val alert = engine.checkEbikeParkingConditions(context, location.latitude, location.longitude)
                        if (alert.level != AlertLevel.ALL_CLEAR) {
                            EBikeNotificationManager(context).showParkingNotification(alert)
                        } else {
                            EBikeNotificationManager(context).showAllClearNotification()
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to check weather on home arrival.", e)
                    } finally {
                        pendingResult.finish()
                    }
                }

                editor.putBoolean("AWAY_FROM_HOME", false)
                      .putBoolean("PENDING_HOME_ARRIVAL", false)
                      .putBoolean("HIGH_FREQ_GPS_ENABLED", false)
                      .remove("LAST_BIKE_LAT")
                      .remove("LAST_BIKE_LNG")
                      .remove("LAST_BIKE_LOCATION_TS")
                      .apply()
                return
            }
            if (lastLat != 0f && lastLng != 0f) {
                // Max speed of e-bike is ~20m/s. If 20 seconds passed, max distance is ~400m. 
                // Ignore crazy GPS jumps (>1000m)
                if (distanceMeters < 1000f) {
                    val totalMeters = prefs.getFloat("TOTAL_BIKE_METERS", 0f) + distanceMeters
                    prefs.edit().putFloat("TOTAL_BIKE_METERS", totalMeters).apply()
                }
            }

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
                if (event.activityType == DetectedActivity.ON_BICYCLE || event.activityType == DetectedActivity.IN_VEHICLE) {
                    when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (!wasBiking) {
                                editor.putBoolean("IS_CURRENTLY_BIKING", true)
                                tracker.startLocationUpdates()
                                Log.i(tag, "Bike ride started. Enabled 20-second location tracking.")
                                
                                val pendingResult = goAsync()
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        val engine = WeatherLogicEngine()
                                        val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
                                        val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()
                                        if (lat != 0.0 && lng != 0.0) {
                                            engine.checkEbikeParkingConditions(context, lat, lng)
                                            Log.i(tag, "Successfully pre-fetched home weather at start of ride.")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(tag, "Failed to pre-fetch weather.", e)
                                    } finally {
                                        pendingResult.finish()
                                    }
                                }
                            }
                        }
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (wasBiking) {
                                editor.putBoolean("IS_CURRENTLY_BIKING", false)
                                editor.putBoolean("HIGH_FREQ_GPS_ENABLED", false)
                                editor.remove("LAST_BIKE_LAT").remove("LAST_BIKE_LNG").remove("LAST_BIKE_LOCATION_TS")
                                tracker.stopLocationUpdates()
                                Log.i(tag, "Bike ride ended. Disabled location tracking.")

                                var isNearHome = prefs.getBoolean("PENDING_HOME_ARRIVAL", false)
                                
                                // Fallback manual distance check just in case geofence completely failed
                                if (!isNearHome) {
                                    val lastLat = prefs.getFloat("LAST_BIKE_LAT", 0f)
                                    val lastLng = prefs.getFloat("LAST_BIKE_LNG", 0f)
                                    val homeLat = prefs.getFloat("HOME_LAT", 0f)
                                    val homeLng = prefs.getFloat("HOME_LNG", 0f)
                                    
                                    if (lastLat != 0f && lastLng != 0f && homeLat != 0f && homeLng != 0f) {
                                        val dist = FloatArray(1)
                                        android.location.Location.distanceBetween(homeLat.toDouble(), homeLng.toDouble(), lastLat.toDouble(), lastLng.toDouble(), dist)
                                        if (dist[0] <= 250f) {
                                            isNearHome = true
                                        }
                                    }
                                }

                                if (isNearHome) {
                                    // Instantly fetch weather and trigger notification!
                                    val pendingResult = goAsync()
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        try {
                                            val engine = WeatherLogicEngine()
                                            val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
                                            val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()
                                            val alert = engine.checkEbikeParkingConditions(context, lat, lng)
                                            if (alert.level != AlertLevel.ALL_CLEAR) {
                                                EBikeNotificationManager(context).showParkingNotification(alert)
                                            } else {
                                                EBikeNotificationManager(context).showAllClearNotification()
                                            }
                                        } catch (e: Exception) {
                                            Log.e(tag, "Failed to check weather on home arrival.", e)
                                        } finally {
                                            pendingResult.finish()
                                        }
                                    }
                                    editor
                                        .putBoolean("AWAY_FROM_HOME", false)
                                        .putBoolean("PENDING_HOME_ARRIVAL", false)
                                        .putBoolean("HIGH_FREQ_GPS_ENABLED", false)
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