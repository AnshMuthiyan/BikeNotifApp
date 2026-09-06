package com.anshmuthiyan.ebikeweather

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GlobalBikeTracker(private val context: Context) {
    private val tag = "GlobalBikeTracker"

    companion object {
        const val ACTION_BIKE_LOCATION_UPDATE = "com.anshmuthiyan.ebikeweather.ACTION_BIKE_LOCATION_UPDATE"
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val transitionPendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GlobalBikeReceiver::class.java)
        PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private val locationPendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GlobalBikeReceiver::class.java).apply {
            action = ACTION_BIKE_LOCATION_UPDATE
        }
        PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
        val request = ActivityTransitionRequest(transitions)
        ActivityRecognition.getClient(context).requestActivityTransitionUpdates(request, transitionPendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "Activity transition tracking started.")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Activity transition registration failed.", e)
            }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20_000L)
            .setMinUpdateIntervalMillis(20_000L)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationPendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "20-second biking location updates started.")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Failed to start biking location updates.", e)
            }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationPendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "Biking location updates stopped.")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Failed to stop biking location updates.", e)
            }
    }
}