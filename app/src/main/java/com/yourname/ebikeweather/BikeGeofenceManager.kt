package com.anshmuthiyan.ebikeweather

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class BikeGeofenceManager(private val context: Context) {
    private val tag = "BikeGeofenceManager"

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceReceiver::class.java)
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun setupApartmentGeofence(homeLat: Double, homeLng: Double) {
        val geofence = Geofence.Builder()
            .setRequestId("HOME_APARTMENT")
            .setCircularRegion(homeLat, homeLng, 100f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setNotificationResponsiveness(10_000)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                    Geofence.GEOFENCE_TRANSITION_DWELL or
                    Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setLoiteringDelay(30_000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "Home geofence registered successfully.")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Home geofence registration failed.", e)
            }
    }
}