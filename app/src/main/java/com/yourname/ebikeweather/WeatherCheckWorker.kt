package com.yourname.ebikeweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WeatherCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "WeatherCheckWorker"

    override suspend fun doWork(): Result {
        if (!hasLocationPermission()) {
            Log.e(tag, "Missing location permission. Cannot perform weather check.")
            return Result.failure()
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        val locationForWeatherCheck = fetchCurrentLocation(fusedLocationClient)
            ?: fetchLastKnownLocation(fusedLocationClient)
            ?: run {
            Log.e(tag, "No location available for weather check.")
            return Result.retry()
        }

        val engine = WeatherLogicEngine()
        val alert = engine.checkEbikeParkingConditions(
            applicationContext,
            locationForWeatherCheck.latitude,
            locationForWeatherCheck.longitude
        )

        if (alert.level != AlertLevel.ALL_CLEAR) {
            EBikeNotificationManager(applicationContext).showParkingNotification(alert)
        }

        return Result.success()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun fetchCurrentLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): Location? {
        return suspendCancellableCoroutine { continuation ->
            val cts = CancellationTokenSource()
            continuation.invokeOnCancellation { cts.cancel() }

            client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Current location fetch failed.", e)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }

    private suspend fun fetchLastKnownLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): Location? {
        return suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Last known location fetch failed.", e)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }
}
