package com.anshmuthiyan.ebikeweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "EBikePrefs"

    private val requestBasicPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val activityGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (fineLocationGranted && activityGranted) {
            checkBackgroundLocationPermission()
            if (!notificationsGranted) {
                Toast.makeText(this, "Notifications are off. Weather alerts cannot be shown.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Core permissions required to function.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Please select 'Allow all the time' in settings.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkBasicPermissions()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val btnSetHome = findViewById<Button>(R.id.btnSetHome)
        val tvCurrentLocation = findViewById<TextView>(R.id.tvCurrentLocation)
        val etPrecipProb = findViewById<EditText>(R.id.etPrecipProb)
        val btnSaveSettings = findViewById<Button>(R.id.btnSaveSettings)

        tvCurrentLocation.text = "Home: ${prefs.getFloat("HOME_LAT", 0f)}, ${prefs.getFloat("HOME_LNG", 0f)}"
        etPrecipProb.setText(prefs.getInt("RAIN_SEVERITY_THRESHOLD", 55).toString())

        btnSetHome.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            prefs.edit()
                                .putFloat("HOME_LAT", location.latitude.toFloat())
                                .putFloat("HOME_LNG", location.longitude.toFloat())
                                .apply()
                            tvCurrentLocation.text = "Home: ${location.latitude}, ${location.longitude}"
                            Toast.makeText(this, "Home Location Updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(this, "Grant Location permissions first.", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveSettings.setOnClickListener {
            val severityThreshold = etPrecipProb.text.toString().toIntOrNull() ?: 55
            prefs.edit()
                .putInt("RAIN_SEVERITY_THRESHOLD", severityThreshold.coerceIn(10, 100))
                .apply()

            val lat = prefs.getFloat("HOME_LAT", 0f).toDouble()
            val lng = prefs.getFloat("HOME_LNG", 0f).toDouble()
            
            if (lat != 0.0 && lng != 0.0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(this, "Notification permission is required for weather alerts.", Toast.LENGTH_LONG).show()
                }

                val globalTracker = GlobalBikeTracker(this)
                globalTracker.start()
                
                Toast.makeText(this, "Settings Saved & Engines Started!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Set your Home location first!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.READ_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestBasicPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}