package com.anshmuthiyan.ebikeweather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RideDataStore {
    fun clearRide(context: Context) {
        val file = File(context.filesDir, "latest_ride.json")
        if (file.exists()) {
            file.delete()
        }
    }

    fun addPoint(context: Context, lat: Double, lng: Double, ts: Long, speed: Float) {
        val file = File(context.filesDir, "latest_ride.json")
        val array = if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }
        val obj = JSONObject()
        obj.put("lat", lat)
        obj.put("lng", lng)
        obj.put("ts", ts)
        obj.put("speed", speed.toDouble())
        array.put(obj)
        file.writeText(array.toString())
    }

    fun getPoints(context: Context): List<RidePoint> {
        val file = File(context.filesDir, "latest_ride.json")
        if (!file.exists()) return emptyList()
        try {
            val array = JSONArray(file.readText())
            val list = mutableListOf<RidePoint>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(RidePoint(obj.getDouble("lat"), obj.getDouble("lng"), obj.getLong("ts"), obj.getDouble("speed").toFloat()))
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

data class RidePoint(val lat: Double, val lng: Double, val ts: Long, val speed: Float)
