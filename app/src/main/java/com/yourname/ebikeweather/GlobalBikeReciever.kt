package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult
import android.util.Log
import kotlinx.coroutines.launch

class GlobalBikeReceiver : BroadcastReceiver() {
    private val tag = "GlobalBikeReceiver"

    companion object {
        const val ACTION_CONFIRM_BIKE_RIDE = "com.anshmuthiyan.ebikeweather.ACTION_CONFIRM_BIKE_RIDE"
        const val ACTION_CONFIRM_CAR_DRIVE = "com.anshmuthiyan.ebikeweather.ACTION_CONFIRM_CAR_DRIVE"
        const val EXTRA_RIDE_ID = "extra_ride_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)

        // Handle user notification response: "🚲 Bike Ride"
        if (intent.action == ACTION_CONFIRM_BIKE_RIDE) {
            val rideId = intent.getLongExtra(EXTRA_RIDE_ID, -1L)
            if (rideId != -1L) {
                RideDataStore.confirmRideAsBike(context, rideId)
                EBikeNotificationManager(context).cancelConfirmationNotification()
                Toast.makeText(context, "Confirmed as Bike Ride!", Toast.LENGTH_SHORT).show()

                checkBatteryNotificationIfNeeded(context, prefs, prefs.getFloat("TOTAL_BIKE_METERS", 0f))
                val pendingWeatherRideId = prefs.getLong("PENDING_WEATHER_ON_CONFIRM_RIDE_ID", -1L)
                if (pendingWeatherRideId == rideId) {
                    prefs.edit().remove("PENDING_WEATHER_ON_CONFIRM_RIDE_ID").apply()
                    val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
                    val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()
                    if (lat != 0.0 && lng != 0.0) {
                        triggerHomeWeatherNotificationWithCooldown(context, prefs, lat, lng)
                    }
                }
            }
            return
        }

        // Handle user notification response: "🚗 Car Drive"
        if (intent.action == ACTION_CONFIRM_CAR_DRIVE) {
            val rideId = intent.getLongExtra(EXTRA_RIDE_ID, -1L)
            if (rideId != -1L) {
                RideDataStore.deleteRide(context, rideId)
                EBikeNotificationManager(context).cancelConfirmationNotification()
                val pendingWeatherRideId = prefs.getLong("PENDING_WEATHER_ON_CONFIRM_RIDE_ID", -1L)
                if (pendingWeatherRideId == rideId) {
                    prefs.edit().remove("PENDING_WEATHER_ON_CONFIRM_RIDE_ID").apply()
                }
                Toast.makeText(context, "Removed car drive & updated analytics!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (intent.action == GlobalBikeTracker.ACTION_BIKE_LOCATION_UPDATE) {
            val isBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
            if (!isBiking) {
                return
            }

            val location = LocationResult.extractResult(intent)?.lastLocation ?: return
            val now = System.currentTimeMillis()

            val homeLat = prefs.getFloat("HOME_LAT", 0f)
            val homeLng = prefs.getFloat("HOME_LNG", 0f)
            var isNearHome = prefs.getBoolean("PENDING_HOME_ARRIVAL", false)
            val isHighFreq = prefs.getBoolean("HIGH_FREQ_GPS_ENABLED", false)
            var hasLeftHomeOnRide = prefs.getBoolean("HAS_LEFT_HOME_ON_RIDE", false)
            var awayPingCount = prefs.getInt("AWAY_PING_COUNT", 0)

            var distanceMeters = 0f
            val lastLat = prefs.getFloat("LAST_BIKE_LAT", 0f)
            val lastLng = prefs.getFloat("LAST_BIKE_LNG", 0f)
            val lastTs = prefs.getLong("LAST_BIKE_LOCATION_TS", 0L)
            var lastMovingTs = prefs.getLong("LAST_MOVING_TS", now)

            if (lastLat != 0f && lastLng != 0f) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastLat.toDouble(),
                    lastLng.toDouble(),
                    location.latitude,
                    location.longitude,
                    results
                )
                distanceMeters = results[0]
            } else {
                lastMovingTs = now
            }

            // Ignore extreme GPS teleport glitches (> 1000m in a single step)
            if (lastLat != 0f && lastLng != 0f && distanceMeters >= 1000f) {
                Log.w(tag, "Ignored GPS jump of $distanceMeters meters.")
                return
            }

            if (distanceMeters >= 12f) {
                lastMovingTs = now
            }

            var currentSpeedMps: Float? = if (location.hasSpeed() && location.speed > 0f) location.speed else null
            if (currentSpeedMps == null && lastTs > 0L) {
                val timeDiffSeconds = (now - lastTs) / 1000f
                if (timeDiffSeconds > 0f) {
                    currentSpeedMps = distanceMeters / timeDiffSeconds
                }
            }
            val speedForPoint = currentSpeedMps ?: 0f

            // Record point and get cumulative ride statistics
            val rideStats = RideDataStore.addPoint(
                context,
                location.latitude,
                location.longitude,
                now,
                speedForPoint
            )

            // Update odometer for valid steps
            val totalMeters = if (lastLat != 0f && lastLng != 0f) {
                val updated = prefs.getFloat("TOTAL_BIKE_METERS", 0f) + distanceMeters
                prefs.edit().putFloat("TOTAL_BIKE_METERS", updated).apply()
                updated
            } else {
                prefs.getFloat("TOTAL_BIKE_METERS", 0f)
            }

            prefs.edit()
                .putFloat("LAST_BIKE_LAT", location.latitude.toFloat())
                .putFloat("LAST_BIKE_LNG", location.longitude.toFloat())
                .putLong("LAST_BIKE_LOCATION_TS", now)
                .putLong("LAST_MOVING_TS", lastMovingTs)
                .apply()

            // Mid-ride Car Detection:
            // If the ride is already > 10 minutes AND has avgSpeed > 15 mph or maxSpeed > 25 mph (2+ measures),
            // OR has clear highway car speeds (avg > 24 mph & max > 36 mph after >= 2 minutes),
            // immediately assume it's a car ride, roll back the odometer, and stop tracking.
            val isLongRideCar = rideStats.durationMinutes > 10.0f &&
                RideDataStore.classifyRide(rideStats) == RideClassification.CAR_DRIVE
            val isFastCar = rideStats.durationMinutes >= 2.0f &&
                rideStats.avgSpeedMph > 24.0f &&
                rideStats.maxSpeedMph > 36.0f

            if (isLongRideCar || isFastCar) {
                Log.i(
                    tag,
                    "2+ car measures met mid-ride (time=${rideStats.durationMinutes}m, avg=${rideStats.avgSpeedMph}mph, max=${rideStats.maxSpeedMph}mph). Assuming car ride & rolling back odometer."
                )
                val fallbackOdo = (totalMeters - rideStats.totalDistanceMeters).coerceAtLeast(0f)
                val originalOdo = prefs.getFloat("ODOMETER_AT_RIDE_START", fallbackOdo)
                prefs.edit()
                    .putBoolean("IS_CURRENTLY_BIKING", false)
                    .putBoolean("IS_ON_BICYCLE_ACTIVE", false)
                    .putBoolean("IS_IN_VEHICLE_ACTIVE", false)
                    .putBoolean("HIGH_FREQ_GPS_ENABLED", false)
                    .putBoolean("AWAY_FROM_HOME", false)
                    .putBoolean("HAS_LEFT_HOME_ON_RIDE", false)
                    .putInt("AWAY_PING_COUNT", 0)
                    .putBoolean("PENDING_HOME_ARRIVAL", false)
                    .putFloat("TOTAL_BIKE_METERS", originalOdo)
                    .remove("LAST_BIKE_LAT")
                    .remove("LAST_BIKE_LNG")
                    .remove("LAST_BIKE_LOCATION_TS")
                    .remove("LAST_MOVING_TS")
                    .apply()
                GlobalBikeTracker(context).stopLocationUpdates()
                RideDataStore.cancelCurrentRideAsCar(context)
                return
            }

            // Evaluate distance to home
            if (homeLat != 0f && homeLng != 0f) {
                val distHome = FloatArray(1)
                android.location.Location.distanceBetween(
                    homeLat.toDouble(),
                    homeLng.toDouble(),
                    location.latitude,
                    location.longitude,
                    distHome
                )

                // Require at least 2 pings > 250m from home before marking that the user has left home.
                if (distHome[0] > 250f) {
                    awayPingCount += 1
                    val editor = prefs.edit().putInt("AWAY_PING_COUNT", awayPingCount)
                    if (awayPingCount >= 2 && !hasLeftHomeOnRide) {
                        hasLeftHomeOnRide = true
                        editor.putBoolean("HAS_LEFT_HOME_ON_RIDE", true)
                        editor.putBoolean("AWAY_FROM_HOME", true)
                        Log.i(tag, "Confirmed rider is away from home (>250m for $awayPingCount pings).")
                    }
                    editor.apply()
                }

                if (hasLeftHomeOnRide) {
                    // 1/3 mile = 536 meters: switch to 5-second GPS only when returning toward home
                    if (distHome[0] <= 536f && !isHighFreq) {
                        GlobalBikeTracker(context).startHighFrequencyLocationUpdates()
                        prefs.edit().putBoolean("HIGH_FREQ_GPS_ENABLED", true).apply()
                        Log.i(tag, "Returning within 1/3 mile of home. Upgraded to 5-second GPS checks.")
                    }

                    // Trigger arrival only if rider actually left home AND rode at least 200m total
                    if (!isNearHome && distHome[0] <= 150f && rideStats.totalDistanceMeters >= 200f) {
                        isNearHome = true
                        prefs.edit().putBoolean("PENDING_HOME_ARRIVAL", true).apply()
                        Log.i(tag, "Arrived within 150m of home after a valid ride!")
                    }
                } else {
                    isNearHome = false
                }
            }

            // Check if rider arrived home OR has been completely stationary for 2.5+ minutes after a trip
            val stoppedAfterTrip = rideStats.totalDistanceMeters >= 150f && (now - lastMovingTs >= 150_000L)
            val arrivedAtHome = isNearHome && hasLeftHomeOnRide && rideStats.totalDistanceMeters >= 200f

            if (arrivedAtHome || stoppedAfterTrip) {
                Log.i(tag, "Trip ended (arrivedAtHome=$arrivedAtHome, stoppedAfterTrip=$stoppedAfterTrip). Evaluating 2-of-3 ride vs drive rule.")
                // finalizeCurrentRideIfValid returns null and rolls back odometer if 2+ measures indicate a CAR_DRIVE
                val finalizedRide = RideDataStore.finalizeCurrentRideIfValid(context)
                GlobalBikeTracker(context).stopLocationUpdates()

                prefs.edit()
                    .putBoolean("IS_CURRENTLY_BIKING", false)
                    .putBoolean("IS_ON_BICYCLE_ACTIVE", false)
                    .putBoolean("IS_IN_VEHICLE_ACTIVE", false)
                    .putBoolean("AWAY_FROM_HOME", false)
                    .putBoolean("HAS_LEFT_HOME_ON_RIDE", false)
                    .putInt("AWAY_PING_COUNT", 0)
                    .putBoolean("PENDING_HOME_ARRIVAL", false)
                    .putBoolean("HIGH_FREQ_GPS_ENABLED", false)
                    .remove("LAST_BIKE_LAT")
                    .remove("LAST_BIKE_LNG")
                    .remove("LAST_BIKE_LOCATION_TS")
                    .remove("LAST_MOVING_TS")
                    .apply()

                if (finalizedRide != null) {
                    if (finalizedRide.pendingConfirmation) {
                        if (arrivedAtHome) {
                            prefs.edit().putLong("PENDING_WEATHER_ON_CONFIRM_RIDE_ID", finalizedRide.id).apply()
                        }
                        EBikeNotificationManager(context).showRideOrDriveConfirmationNotification(finalizedRide)
                    } else {
                        checkBatteryNotificationIfNeeded(context, prefs, prefs.getFloat("TOTAL_BIKE_METERS", 0f))
                        if (arrivedAtHome) {
                            triggerHomeWeatherNotificationWithCooldown(context, prefs, location.latitude, location.longitude)
                        }
                    }
                }
                return
            }

            Log.d(tag, "Biking location update recorded. Dist=${rideStats.totalDistanceMeters}m, Avg=${rideStats.avgSpeedMph}mph, Max=${rideStats.maxSpeedMph}mph")
            return
        }

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            val editor = prefs.edit()
            val tracker = GlobalBikeTracker(context)

            for (event in result.transitionEvents) {
                if (event.activityType == DetectedActivity.ON_BICYCLE || event.activityType == DetectedActivity.IN_VEHICLE) {
                    val isBicycleEvent = event.activityType == DetectedActivity.ON_BICYCLE
                    when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                            if (isBicycleEvent) {
                                editor.putBoolean("IS_ON_BICYCLE_ACTIVE", true)
                            } else {
                                editor.putBoolean("IS_IN_VEHICLE_ACTIVE", true)
                            }

                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (!wasBiking) {
                                val resumed = RideDataStore.startOrResumeRide(context)
                                if (!resumed) {
                                    val currentOdo = prefs.getFloat("TOTAL_BIKE_METERS", 0f)
                                    editor.putFloat("ODOMETER_AT_RIDE_START", currentOdo)
                                    editor.putBoolean("HAS_LEFT_HOME_ON_RIDE", false)
                                    editor.putInt("AWAY_PING_COUNT", 0)
                                    editor.putBoolean("PENDING_HOME_ARRIVAL", false)
                                    editor.putLong("LAST_MOVING_TS", System.currentTimeMillis())
                                    editor.remove("LAST_BIKE_LAT")
                                    editor.remove("LAST_BIKE_LNG")
                                    editor.remove("LAST_BIKE_LOCATION_TS")
                                    Log.i(tag, "New bike ride started. Enabled 20-second location tracking.")
                                } else {
                                    editor.putLong("LAST_MOVING_TS", System.currentTimeMillis())
                                    Log.i(tag, "Resumed recent bike ride within 4-minute window.")
                                }

                                editor.putBoolean("IS_CURRENTLY_BIKING", true)
                                tracker.startLocationUpdates()

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
                            var bikeActive = prefs.getBoolean("IS_ON_BICYCLE_ACTIVE", false)
                            var vehicleActive = prefs.getBoolean("IS_IN_VEHICLE_ACTIVE", false)
                            if (isBicycleEvent) {
                                bikeActive = false
                                editor.putBoolean("IS_ON_BICYCLE_ACTIVE", false)
                            } else {
                                vehicleActive = false
                                editor.putBoolean("IS_IN_VEHICLE_ACTIVE", false)
                            }

                            // If the other activity type is still active, keep tracking
                            if (bikeActive || vehicleActive) {
                                Log.i(tag, "One activity exited, but the other is still active. Continuing ride tracking.")
                                continue
                            }

                            val wasBiking = prefs.getBoolean("IS_CURRENTLY_BIKING", false)
                            if (wasBiking) {
                                val hasLeftHomeOnRide = prefs.getBoolean("HAS_LEFT_HOME_ON_RIDE", false)
                                var isNearHome = prefs.getBoolean("PENDING_HOME_ARRIVAL", false)

                                if (!isNearHome && hasLeftHomeOnRide) {
                                    val lastLat = prefs.getFloat("LAST_BIKE_LAT", 0f)
                                    val lastLng = prefs.getFloat("LAST_BIKE_LNG", 0f)
                                    val homeLat = prefs.getFloat("HOME_LAT", 0f)
                                    val homeLng = prefs.getFloat("HOME_LNG", 0f)

                                    if (lastLat != 0f && lastLng != 0f && homeLat != 0f && homeLng != 0f) {
                                        val dist = FloatArray(1)
                                        android.location.Location.distanceBetween(
                                            homeLat.toDouble(),
                                            homeLng.toDouble(),
                                            lastLat.toDouble(),
                                            lastLng.toDouble(),
                                            dist
                                        )
                                        if (dist[0] <= 200f) {
                                            isNearHome = true
                                        }
                                    }
                                }

                                // finalizeCurrentRideIfValid returns null and rolls back odometer if 2+ measures indicate CAR_DRIVE
                                val finalizedRide = RideDataStore.finalizeCurrentRideIfValid(context)

                                editor.putBoolean("IS_CURRENTLY_BIKING", false)
                                editor.putBoolean("HIGH_FREQ_GPS_ENABLED", false)
                                editor.remove("LAST_BIKE_LAT")
                                    .remove("LAST_BIKE_LNG")
                                    .remove("LAST_BIKE_LOCATION_TS")
                                    .remove("LAST_MOVING_TS")
                                tracker.stopLocationUpdates()
                                Log.i(tag, "Bike ride ended. Disabled location tracking.")

                                if (finalizedRide != null) {
                                    val arrivedHomeValid = isNearHome && hasLeftHomeOnRide && finalizedRide.distanceMeters >= 200f
                                    if (finalizedRide.pendingConfirmation) {
                                        if (arrivedHomeValid) {
                                            editor.putLong("PENDING_WEATHER_ON_CONFIRM_RIDE_ID", finalizedRide.id)
                                        }
                                        EBikeNotificationManager(context).showRideOrDriveConfirmationNotification(finalizedRide)
                                        Log.i(tag, "Sent Ride vs Drive confirmation notification for 1-flag uncertain ride.")
                                    } else {
                                        checkBatteryNotificationIfNeeded(context, prefs, prefs.getFloat("TOTAL_BIKE_METERS", 0f))
                                        if (arrivedHomeValid) {
                            val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
                            val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()
                            triggerHomeWeatherNotificationWithCooldown(context, prefs, lat, lng)
                                        }
                                    }
                                }

                                editor
                                    .putBoolean("AWAY_FROM_HOME", false)
                                    .putBoolean("HAS_LEFT_HOME_ON_RIDE", false)
                                    .putInt("AWAY_PING_COUNT", 0)
                                    .putBoolean("PENDING_HOME_ARRIVAL", false)
                            }
                        }
                    }
                }
            }
            editor.apply()
        }
    }

    private fun triggerHomeWeatherNotificationWithCooldown(
        context: Context,
        prefs: SharedPreferences,
        lat: Double,
        lng: Double
    ) {
        val now = System.currentTimeMillis()
        val lastNotifTs = prefs.getLong("LAST_WEATHER_NOTIF_TS", 0L)
        if (now - lastNotifTs < 10 * 60 * 1000L) {
            Log.i(tag, "Skipping weather notification; one was already sent within the last 10 minutes.")
            return
        }
        prefs.edit().putLong("LAST_WEATHER_NOTIF_TS", now).apply()

        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val engine = WeatherLogicEngine()
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
    }

    private fun checkBatteryNotificationIfNeeded(
        context: Context,
        prefs: SharedPreferences,
        totalMeters: Float
    ) {
        val alreadyNotified = prefs.getBoolean("BATTERY_NOTIF_SENT_FOR_CYCLE", false)
        if (alreadyNotified) return

        val avgMiles = RideDataStore.getAverageMilesBetweenCharges(context) ?: return
        if (avgMiles <= 0f) return

        val currentMiles = totalMeters * 0.000621371f
        // Notify when the rider reaches 90% of their historical average miles between charges
        if (currentMiles >= avgMiles * 0.90f) {
            prefs.edit().putBoolean("BATTERY_NOTIF_SENT_FOR_CYCLE", true).apply()
            EBikeNotificationManager(context).showRechargeBatteryNotification(currentMiles, avgMiles)
        }
    }
}
