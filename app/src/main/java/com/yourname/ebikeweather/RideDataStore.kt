package com.anshmuthiyan.ebikeweather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class RidePoint(
    val lat: Double,
    val lng: Double,
    val ts: Long,
    val speed: Float
)

data class RideRecord(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Float,
    val maxSpeedMps: Float,
    val avgSpeedMps: Float,
    val points: List<RidePoint>,
    val pendingConfirmation: Boolean = false
) {
    val distanceMiles: Float get() = distanceMeters * 0.000621371f
    val durationSeconds: Float get() = max(0L, endTime - startTime) / 1000f
    val durationMinutes: Float get() = durationSeconds / 60f
    val avgSpeedMph: Float get() = avgSpeedMps * 2.23694f
    val maxSpeedMph: Float get() = maxSpeedMps * 2.23694f
}

data class CurrentRideStats(
    val id: Long,
    val pointCount: Int,
    val totalDistanceMeters: Float,
    val durationSeconds: Float,
    val avgSpeedMps: Float,
    val maxSpeedMps: Float
) {
    val totalMiles: Float get() = totalDistanceMeters * 0.000621371f
    val durationMinutes: Float get() = durationSeconds / 60f
    val avgSpeedMph: Float get() = avgSpeedMps * 2.23694f
    val maxSpeedMph: Float get() = maxSpeedMps * 2.23694f
}

data class TotalRideAnalytics(
    val totalRides: Int,
    val totalDistanceMeters: Float,
    val totalDurationSeconds: Float,
    val overallAvgSpeedMps: Float,
    val allTimeMaxSpeedMps: Float
) {
    val totalMiles: Float get() = totalDistanceMeters * 0.000621371f
    val totalMinutes: Float get() = totalDurationSeconds / 60f
    val overallAvgSpeedMph: Float get() = overallAvgSpeedMps * 2.23694f
    val allTimeMaxSpeedMph: Float get() = allTimeMaxSpeedMps * 2.23694f
}

data class ChargeRecord(
    val timestamp: Long,
    val miles: Float
)

object RideDataStore {
    private const val CURRENT_RIDE_FILE = "current_ride.json"
    private const val LEGACY_LATEST_RIDE_FILE = "latest_ride.json"
    private const val RIDE_HISTORY_FILE = "ride_history.json"
    private const val CHARGE_HISTORY_FILE = "charge_history.json"
    private const val MAX_SAVED_RIDES = 50
    private const val RESUME_WINDOW_MS = 4 * 60 * 1000L // 4 minutes

    // Minimum distance (meters) before a ride is considered worth saving to history
    const val MIN_VALID_RIDE_DISTANCE_METERS = 80f

    /**
     * Checks whether a ride's speed and duration make it ambiguous between a bike ride and a car drive.
     * User rule:
     * - If avg speed > 15 mph OR max speed > 25 mph, check how long the ride was:
     *   if it was longer than 10 minutes, ask if it was a drive or a bike ride.
     * - Also if a shorter ride (< 10 min) has extreme speed (avg > 22 mph or max > 37 mph), ask as well.
     */
    fun isRideUncertain(stats: CurrentRideStats): Boolean {
        val speedSuspicious = stats.avgSpeedMph > 15.0f || stats.maxSpeedMph > 25.0f
        if (speedSuspicious && stats.durationMinutes >= 10.0f) {
            return true
        }
        if (stats.durationMinutes < 10.0f && (stats.avgSpeedMph > 22.0f || stats.maxSpeedMph > 37.0f)) {
            return true
        }
        return false
    }

    /**
     * Called when an activity transition ENTER occurs.
     * If a current ride was active within the last 4 minutes, resumes it instead of wiping it.
     * Otherwise finalizes the previous ride (if valid) and starts a new current ride buffer.
     * Returns true if resuming an existing recent ride, false if starting a brand new ride.
     */
    fun startOrResumeRide(context: Context): Boolean {
        migrateLegacyRideIfNeeded(context)
        val currentPoints = getCurrentRidePoints(context)
        if (currentPoints.isNotEmpty()) {
            val ageMs = System.currentTimeMillis() - currentPoints.last().ts
            if (ageMs in 0..RESUME_WINDOW_MS) {
                return true
            }
            finalizeCurrentRideIfValid(context)
        }
        clearCurrentRideBuffer(context)
        return false
    }

    fun clearCurrentRideBuffer(context: Context) {
        val file = File(context.filesDir, CURRENT_RIDE_FILE)
        if (file.exists()) {
            file.delete()
        }
    }

    fun addPoint(context: Context, lat: Double, lng: Double, ts: Long, speedMps: Float): CurrentRideStats {
        migrateLegacyRideIfNeeded(context)
        val file = File(context.filesDir, CURRENT_RIDE_FILE)
        val array = if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }

        val cleanSpeed = if (speedMps.isNaN() || speedMps.isInfinite() || speedMps < 0f || speedMps > 60f) 0f else speedMps
        val obj = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("ts", ts)
            put("speed", cleanSpeed.toDouble())
        }
        array.put(obj)
        file.writeText(array.toString())

        val points = parsePointsArray(array)
        val stats = computeStats(points)

        // Live-upsert into ride history if it has at least 2 points and >= 80m distance
        if (points.size >= 2 && stats.totalDistanceMeters >= MIN_VALID_RIDE_DISTANCE_METERS) {
            val uncertain = isRideUncertain(stats)
            val record = RideRecord(
                id = points.first().ts,
                startTime = points.first().ts,
                endTime = points.last().ts,
                distanceMeters = stats.totalDistanceMeters,
                maxSpeedMps = stats.maxSpeedMps,
                avgSpeedMps = stats.avgSpeedMps,
                points = points,
                pendingConfirmation = uncertain
            )
            upsertRideInHistory(context, record)
        }

        return stats
    }

    /**
     * Finalizes the current ride buffer into history and returns the finalized RideRecord (if valid).
     */
    fun finalizeCurrentRideIfValid(context: Context): RideRecord? {
        val points = getCurrentRidePoints(context)
        var finalizedRecord: RideRecord? = null
        if (points.size >= 2) {
            val stats = computeStats(points)
            if (stats.totalDistanceMeters >= MIN_VALID_RIDE_DISTANCE_METERS) {
                val uncertain = isRideUncertain(stats)
                finalizedRecord = RideRecord(
                    id = points.first().ts,
                    startTime = points.first().ts,
                    endTime = points.last().ts,
                    distanceMeters = stats.totalDistanceMeters,
                    maxSpeedMps = stats.maxSpeedMps,
                    avgSpeedMps = stats.avgSpeedMps,
                    points = points,
                    pendingConfirmation = uncertain
                )
                upsertRideInHistory(context, finalizedRecord)
            }
        }
        clearCurrentRideBuffer(context)
        return finalizedRecord
    }

    fun confirmRideAsBike(context: Context, rideId: Long) {
        val file = File(context.filesDir, RIDE_HISTORY_FILE)
        if (!file.exists()) return
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optLong("id") == rideId) {
                    obj.put("pendingConfirmation", false)
                }
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Deletes a ride from history (and current buffer if matching), AND subtracts its distance
     * from the battery odometer (TOTAL_BIKE_METERS) if the ride occurred since the last battery charge reset.
     */
    fun deleteRide(context: Context, rideId: Long): RideRecord? {
        val allRides = getAllRides(context)
        val targetRide = allRides.find { it.id == rideId }

        // If current_ride.json matches this rideId, clear it too
        val currentPts = getCurrentRidePoints(context)
        if (currentPts.isNotEmpty() && currentPts.first().ts == rideId) {
            clearCurrentRideBuffer(context)
        }

        val file = File(context.filesDir, RIDE_HISTORY_FILE)
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                val updated = JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optLong("id") != rideId) {
                        updated.put(obj)
                    }
                }
                file.writeText(updated.toString())
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Subtract deleted ride's distance from TOTAL_BIKE_METERS if it occurred after the last charge reset
        if (targetRide != null) {
            val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
            val lastChargeResetTs = prefs.getLong("LAST_CHARGE_RESET_TS", 0L)
            if (targetRide.endTime >= lastChargeResetTs) {
                val currentMeters = prefs.getFloat("TOTAL_BIKE_METERS", 0f)
                val newMeters = (currentMeters - targetRide.distanceMeters).coerceAtLeast(0f)
                prefs.edit().putFloat("TOTAL_BIKE_METERS", newMeters).apply()
            }
        }

        return targetRide
    }

    fun getCurrentRidePoints(context: Context): List<RidePoint> {
        val file = File(context.filesDir, CURRENT_RIDE_FILE)
        if (!file.exists()) return emptyList()
        return try {
            parsePointsArray(JSONArray(file.readText()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCurrentRideStats(context: Context): CurrentRideStats {
        return computeStats(getCurrentRidePoints(context))
    }

    fun getAllRides(context: Context): List<RideRecord> {
        migrateLegacyRideIfNeeded(context)
        val file = File(context.filesDir, RIDE_HISTORY_FILE)
        val historyList = mutableListOf<RideRecord>()
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val pts = parsePointsArray(obj.getJSONArray("points"))
                    if (pts.size >= 2) {
                        historyList.add(
                            RideRecord(
                                id = obj.optLong("id", pts.first().ts),
                                startTime = obj.optLong("startTime", pts.first().ts),
                                endTime = obj.optLong("endTime", pts.last().ts),
                                distanceMeters = obj.optDouble("distanceMeters", 0.0).toFloat(),
                                maxSpeedMps = obj.optDouble("maxSpeedMps", 0.0).toFloat(),
                                avgSpeedMps = obj.optDouble("avgSpeedMps", 0.0).toFloat(),
                                points = pts,
                                pendingConfirmation = obj.optBoolean("pendingConfirmation", false)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore corrupted history file
            }
        }

        // Also check if there's an active current ride not yet in history
        val currentPts = getCurrentRidePoints(context)
        if (currentPts.size >= 2) {
            val currentId = currentPts.first().ts
            if (historyList.none { it.id == currentId }) {
                val stats = computeStats(currentPts)
                if (stats.totalDistanceMeters >= 20f) {
                    historyList.add(
                        RideRecord(
                            id = currentId,
                            startTime = currentPts.first().ts,
                            endTime = currentPts.last().ts,
                            distanceMeters = stats.totalDistanceMeters,
                            maxSpeedMps = stats.maxSpeedMps,
                            avgSpeedMps = stats.avgSpeedMps,
                            points = currentPts,
                            pendingConfirmation = isRideUncertain(stats)
                        )
                    )
                }
            }
        }

        return historyList.sortedByDescending { it.startTime }
    }

    /**
     * Computes aggregate analytics across all saved rides.
     * Automatically reflects any ride deletions or drive removals.
     */
    fun getTotalAnalytics(context: Context): TotalRideAnalytics {
        val rides = getAllRides(context)
        if (rides.isEmpty()) {
            return TotalRideAnalytics(0, 0f, 0f, 0f, 0f)
        }
        var totalDistMeters = 0f
        var totalDurSeconds = 0f
        var maxSpeedMps = 0f

        for (r in rides) {
            totalDistMeters += r.distanceMeters
            totalDurSeconds += r.durationSeconds
            if (r.maxSpeedMps > maxSpeedMps) {
                maxSpeedMps = r.maxSpeedMps
            }
        }

        val overallAvgMps = if (totalDurSeconds > 0f) totalDistMeters / totalDurSeconds else 0f
        return TotalRideAnalytics(
            totalRides = rides.size,
            totalDistanceMeters = totalDistMeters,
            totalDurationSeconds = totalDurSeconds,
            overallAvgSpeedMps = overallAvgMps,
            allTimeMaxSpeedMps = maxSpeedMps
        )
    }

    private fun upsertRideInHistory(context: Context, record: RideRecord) {
        val file = File(context.filesDir, RIDE_HISTORY_FILE)
        val existing = mutableListOf<JSONObject>()
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optLong("id") != record.id) {
                        existing.add(obj)
                    }
                }
            } catch (e: Exception) {
                // Start fresh array on parse error
            }
        }

        val ptsArray = JSONArray()
        for (p in record.points) {
            ptsArray.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("ts", p.ts)
                put("speed", p.speed.toDouble())
            })
        }

        val recordObj = JSONObject().apply {
            put("id", record.id)
            put("startTime", record.startTime)
            put("endTime", record.endTime)
            put("distanceMeters", record.distanceMeters.toDouble())
            put("maxSpeedMps", record.maxSpeedMps.toDouble())
            put("avgSpeedMps", record.avgSpeedMps.toDouble())
            put("pendingConfirmation", record.pendingConfirmation)
            put("points", ptsArray)
        }
        existing.add(0, recordObj)
        existing.sortByDescending { it.optLong("startTime", 0L) }

        val finalArray = JSONArray()
        for (item in existing.take(MAX_SAVED_RIDES)) {
            finalArray.put(item)
        }
        file.writeText(finalArray.toString())
    }

    private fun migrateLegacyRideIfNeeded(context: Context) {
        val legacyFile = File(context.filesDir, LEGACY_LATEST_RIDE_FILE)
        if (!legacyFile.exists()) return
        try {
            val pts = parsePointsArray(JSONArray(legacyFile.readText()))
            if (pts.size >= 2) {
                val stats = computeStats(pts)
                if (stats.totalDistanceMeters >= 30f) {
                    val record = RideRecord(
                        id = pts.first().ts,
                        startTime = pts.first().ts,
                        endTime = pts.last().ts,
                        distanceMeters = stats.totalDistanceMeters,
                        maxSpeedMps = stats.maxSpeedMps,
                        avgSpeedMps = stats.avgSpeedMps,
                        points = pts,
                        pendingConfirmation = isRideUncertain(stats)
                    )
                    upsertRideInHistory(context, record)
                }
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            legacyFile.delete()
        }
    }

    private fun parsePointsArray(array: JSONArray): List<RidePoint> {
        val list = mutableListOf<RidePoint>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val rawSpeed = obj.optDouble("speed", 0.0).toFloat()
            // Filter out legacy 99f default speed bug if present
            val cleanSpeed = if (rawSpeed > 60f || rawSpeed < 0f || rawSpeed.isNaN()) 0f else rawSpeed
            list.add(
                RidePoint(
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    ts = obj.getLong("ts"),
                    speed = cleanSpeed
                )
            )
        }
        return list
    }

    private fun computeStats(points: List<RidePoint>): CurrentRideStats {
        if (points.isEmpty()) {
            return CurrentRideStats(0L, 0, 0f, 0f, 0f, 0f)
        }
        var totalDistanceMeters = 0f
        var maxSpeedMps = 0f
        for (i in points.indices) {
            val p = points[i]
            if (p.speed > maxSpeedMps && p.speed <= 55f) {
                maxSpeedMps = p.speed
            }
            if (i > 0) {
                val prev = points[i - 1]
                val results = FloatArray(1)
                android.location.Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, results)
                val segmentDist = results[0]
                if (segmentDist < 1000f) {
                    totalDistanceMeters += segmentDist
                    val dt = (p.ts - prev.ts) / 1000f
                    if (dt > 0f && p.speed == 0f) {
                        val segSpeed = segmentDist / dt
                        if (segSpeed > maxSpeedMps && segSpeed <= 55f) {
                            maxSpeedMps = segSpeed
                        }
                    }
                }
            }
        }
        val durationSeconds = max(0L, points.last().ts - points.first().ts) / 1000f
        val avgSpeedMps = if (durationSeconds > 0f) totalDistanceMeters / durationSeconds else 0f
        return CurrentRideStats(
            id = points.first().ts,
            pointCount = points.size,
            totalDistanceMeters = totalDistanceMeters,
            durationSeconds = durationSeconds,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps
        )
    }

    // --- Battery Charge History & Odometer Analytics ---

    fun getChargeHistory(context: Context): List<ChargeRecord> {
        val file = File(context.filesDir, CHARGE_HISTORY_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<ChargeRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val miles = obj.optDouble("miles", 0.0).toFloat()
                if (miles > 0f) {
                    list.add(ChargeRecord(obj.optLong("ts", 0L), miles))
                }
            }
            list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun recordChargeCycle(context: Context, distanceMeters: Float): List<ChargeRecord> {
        val miles = distanceMeters * 0.000621371f
        val existing = getChargeHistory(context).toMutableList()
        // Only record into average if at least 0.5 miles were ridden since last reset
        if (miles >= 0.5f) {
            existing.add(0, ChargeRecord(System.currentTimeMillis(), miles))
            val arr = JSONArray()
            for (rec in existing.take(30)) {
                arr.put(JSONObject().apply {
                    put("ts", rec.timestamp)
                    put("miles", rec.miles.toDouble())
                })
            }
            File(context.filesDir, CHARGE_HISTORY_FILE).writeText(arr.toString())
        }
        return existing
    }

    fun getAverageMilesBetweenCharges(context: Context): Float? {
        val history = getChargeHistory(context)
        if (history.isEmpty()) return null
        return history.map { it.miles }.average().toFloat()
    }
}
