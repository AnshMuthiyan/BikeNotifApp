package com.anshmuthiyan.ebikeweather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RideDetailsActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvRideHeaderTitle: TextView
    private lateinit var tvRideDate: TextView
    private lateinit var tvStatDistance: TextView
    private lateinit var tvStatDuration: TextView
    private lateinit var tvStatAvgSpeed: TextView
    private lateinit var tvStatMaxSpeed: TextView
    private lateinit var btnPickRide: Button
    private lateinit var btnOlderRide: Button
    private lateinit var btnNewerRide: Button
    private lateinit var btnDeleteRide: Button

    private var rides: List<RideRecord> = emptyList()
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize osmdroid configuration
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_ride_details)

        tvRideHeaderTitle = findViewById(R.id.tvRideHeaderTitle)
        tvRideDate = findViewById(R.id.tvRideDate)
        tvStatDistance = findViewById(R.id.tvStatDistance)
        tvStatDuration = findViewById(R.id.tvStatDuration)
        tvStatAvgSpeed = findViewById(R.id.tvStatAvgSpeed)
        tvStatMaxSpeed = findViewById(R.id.tvStatMaxSpeed)
        btnPickRide = findViewById(R.id.btnPickRide)
        btnOlderRide = findViewById(R.id.btnOlderRide)
        btnNewerRide = findViewById(R.id.btnNewerRide)
        btnDeleteRide = findViewById(R.id.btnDeleteRide)

        map = findViewById(R.id.mapView)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.isTilesScaledToDpi = true
        map.setMultiTouchControls(true)

        btnOlderRide.setOnClickListener {
            if (currentIndex < rides.size - 1) {
                currentIndex += 1
                displayCurrentRide()
            }
        }

        btnNewerRide.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex -= 1
                displayCurrentRide()
            }
        }

        btnPickRide.setOnClickListener {
            showRidePickerDialog()
        }

        btnDeleteRide.setOnClickListener {
            if (rides.isNotEmpty() && currentIndex in rides.indices) {
                val target = rides[currentIndex]
                RideDataStore.deleteRide(this, target.id)
                Toast.makeText(this, "Ride deleted.", Toast.LENGTH_SHORT).show()
                loadRides()
            }
        }

        loadRides()
    }

    private fun loadRides() {
        rides = RideDataStore.getAllRides(this)
        if (currentIndex >= rides.size) {
            currentIndex = (rides.size - 1).coerceAtLeast(0)
        }
        displayCurrentRide()
    }

    private fun displayCurrentRide() {
        btnPickRide.text = "All Rides (${rides.size})"

        if (rides.isEmpty()) {
            tvRideHeaderTitle.text = "NO RIDES SAVED"
            tvRideDate.text = "Take your bike out for a spin!"
            tvStatDistance.text = "0.00 mi"
            tvStatDuration.text = "0m 0s"
            tvStatAvgSpeed.text = "0.0 mph"
            tvStatMaxSpeed.text = "0.0 mph"
            btnOlderRide.isEnabled = false
            btnNewerRide.isEnabled = false
            btnDeleteRide.isEnabled = false
            map.overlays.clear()
            val prefs = getSharedPreferences("EBikePrefs", MODE_PRIVATE)
            val homeLat = prefs.getFloat("HOME_LAT", 0f).toDouble()
            val homeLng = prefs.getFloat("HOME_LNG", 0f).toDouble()
            if (homeLat != 0.0 && homeLng != 0.0) {
                map.controller.setZoom(15.5)
                map.controller.setCenter(GeoPoint(homeLat, homeLng))
            }
            map.invalidate()
            return
        }

        val ride = rides[currentIndex]
        val isLatest = currentIndex == 0
        tvRideHeaderTitle.text = if (isLatest) {
            "LATEST RIDE (1 OF ${rides.size})"
        } else {
            "PAST RIDE (${currentIndex + 1} OF ${rides.size})"
        }

        val dateFmt = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
        val endFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        tvRideDate.text = "${dateFmt.format(Date(ride.startTime))} – ${endFmt.format(Date(ride.endTime))}"

        val totalSecs = ride.durationSeconds.toInt()
        val mins = totalSecs / 60
        val secs = totalSecs % 60

        tvStatDistance.text = String.format(Locale.US, "%.2f mi", ride.distanceMiles)
        tvStatDuration.text = String.format(Locale.US, "%dm %02ds", mins, secs)
        tvStatAvgSpeed.text = String.format(Locale.US, "%.1f mph", ride.avgSpeedMph)
        tvStatMaxSpeed.text = String.format(Locale.US, "%.1f mph", ride.maxSpeedMph)

        btnOlderRide.isEnabled = currentIndex < rides.size - 1
        btnOlderRide.alpha = if (btnOlderRide.isEnabled) 1.0f else 0.4f
        btnNewerRide.isEnabled = currentIndex > 0
        btnNewerRide.alpha = if (btnNewerRide.isEnabled) 1.0f else 0.4f
        btnDeleteRide.isEnabled = true

        // Render route on map
        map.overlays.clear()
        val geoPoints = ride.points.map { GeoPoint(it.lat, it.lng) }

        if (geoPoints.isNotEmpty()) {
            // Outer casing line for high visibility
            val casingLine = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#1E3A8A")
                outlinePaint.strokeWidth = 20f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlayManager.add(casingLine)

            // Inner bright route line
            val coreLine = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = Color.parseColor("#0EA5E9")
                outlinePaint.strokeWidth = 11f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlayManager.add(coreLine)

            // Start Marker (Green circle with white border)
            val startMarker = Marker(map).apply {
                position = geoPoints.first()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createCircleMarkerDrawable(Color.parseColor("#22C55E"))
                title = "Ride Start (${dateFmt.format(Date(ride.startTime))})"
            }
            map.overlayManager.add(startMarker)

            // End Marker (Red circle with white border)
            val endMarker = Marker(map).apply {
                position = geoPoints.last()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createCircleMarkerDrawable(Color.parseColor("#EF4444"))
                title = "Ride Finish (${endFmt.format(Date(ride.endTime))})"
            }
            map.overlayManager.add(endMarker)

            // Frame the entire route nicely in the viewport
            map.post {
                if (geoPoints.size >= 2) {
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    if (box.latNorth != box.latSouth || box.lonEast != box.lonWest) {
                        map.zoomToBoundingBox(box, true, 110)
                    } else {
                        map.controller.setZoom(16.5)
                        map.controller.setCenter(geoPoints.last())
                    }
                } else {
                    map.controller.setZoom(16.5)
                    map.controller.setCenter(geoPoints.first())
                }
            }
        }
        map.invalidate()
    }

    private fun showRidePickerDialog() {
        if (rides.isEmpty()) {
            Toast.makeText(this, "No past rides saved yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        val items = rides.mapIndexed { idx, r ->
            val tag = if (idx == 0) " [Latest]" else ""
            val mins = (r.durationSeconds / 60f).toInt()
            String.format(
                Locale.US,
                "%s%s\n%.2f mi • %d min • Avg %.1f mph (Max %.1f mph)",
                dateFmt.format(Date(r.startTime)),
                tag,
                r.distanceMiles,
                mins,
                r.avgSpeedMph,
                r.maxSpeedMph
            )
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select a Past Ride (${rides.size})")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                currentIndex = which
                displayCurrentRide()
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun createCircleMarkerDrawable(fillColor: Int): BitmapDrawable {
        val sizePx = (20 * resources.displayMetrics.density).toInt().coerceAtLeast(36)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = sizePx / 2f
        val radius = sizePx / 2f - 4f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center + 2f, radius, shadowPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius - 2f, fillPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(center, center, radius - 3f, strokePaint)

        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        loadRides()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
