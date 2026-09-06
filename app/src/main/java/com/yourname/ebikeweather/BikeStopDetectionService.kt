package com.yourname.ebikeweather

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BikeStopDetectionService : Service() {
    private val tag = "BikeStopDetectionService"
    private val timeoutMs = 90_000L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var lastObservedLocation: android.location.Location? = null
    private var weatherCheckStarted = false

    private val timeoutRunnable = Runnable {
        if (!weatherCheckStarted) {
            Log.w(tag, "Stop detection timed out. Proceeding with best known location.")
            stopLocationUpdates()
            runWeatherCheck(lastObservedLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastObservedLocation = location
                val currentSpeed = location.speed 

                if (currentSpeed < 1.0f) {
                    stopLocationUpdates()
                    runWeatherCheck(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        return START_NOT_STICKY
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    private fun runWeatherCheck(location: android.location.Location?) {
        val safeLocation = location
        if (weatherCheckStarted) return
        weatherCheckStarted = true

        CoroutineScope(Dispatchers.IO).launch {
            if (safeLocation == null) {
                Log.e(tag, "No location available for weather check. Stopping service.")
                stopSelf()
                return@launch
            }

            val engine = WeatherLogicEngine()
            val alert = engine.checkEbikeParkingConditions(this@BikeStopDetectionService, safeLocation.latitude, safeLocation.longitude)

            if (alert.level != AlertLevel.ALL_CLEAR) {
                val notificationManager = EBikeNotificationManager(this@BikeStopDetectionService)
                notificationManager.showParkingNotification(alert)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}