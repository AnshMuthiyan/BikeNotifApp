package com.anshmuthiyan.ebikeweather

import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker

class RideDetailsActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvRideStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize osmdroid configuration
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_ride_details)
        
        tvRideStats = findViewById(R.id.tvRideStats)
        map = findViewById(R.id.mapView)
        map.setMultiTouchControls(true)

        val points = RideDataStore.getPoints(this)
        if (points.isEmpty()) {
            tvRideStats.text = "No ride data available."
            return
        }

        var totalDistanceMeters = 0f
        var maxSpeed = 0f
        val geoPoints = mutableListOf<GeoPoint>()

        for (i in points.indices) {
            val p = points[i]
            geoPoints.add(GeoPoint(p.lat, p.lng))
            if (p.speed > maxSpeed) {
                maxSpeed = p.speed
            }
            if (i > 0) {
                val prev = points[i - 1]
                val results = FloatArray(1)
                android.location.Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, results)
                totalDistanceMeters += results[0]
            }
        }

        val totalMiles = totalDistanceMeters * 0.000621371f
        val timeSecs = (points.last().ts - points.first().ts) / 1000f
        val timeMins = timeSecs / 60f
        val avgSpeedMph = if (timeSecs > 0) (totalMiles / (timeSecs / 3600f)) else 0f
        val maxSpeedMph = maxSpeed * 2.23694f

        tvRideStats.text = String.format(
            "Latest Ride\nDistance: %.2f mi | Time: %.1f min\nAvg Speed: %.1f mph | Max Speed: %.1f mph",
            totalMiles, timeMins, avgSpeedMph, maxSpeedMph
        )

        val line = Polyline()
        line.setPoints(geoPoints)
        line.outlinePaint.color = android.graphics.Color.BLUE
        line.outlinePaint.strokeWidth = 10f
        map.overlayManager.add(line)

        val startMarker = Marker(map)
        startMarker.position = geoPoints.first()
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        startMarker.title = "Start"
        map.overlayManager.add(startMarker)

        val endMarker = Marker(map)
        endMarker.position = geoPoints.last()
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        endMarker.title = "End"
        map.overlayManager.add(endMarker)

        map.controller.setZoom(15.0)
        map.controller.setCenter(geoPoints.last())
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
