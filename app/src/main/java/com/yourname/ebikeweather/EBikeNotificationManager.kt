package com.anshmuthiyan.ebikeweather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class EBikeNotificationManager(private val context: Context) {
    private val CHANNEL_ID = "EBIKE_WEATHER_ALERTS"
    private val tag = "EBikeNotificationMgr"

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "E-Bike Parking Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifies you to move your bike if rain or snow is expected."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC 
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showParkingNotification(alert: WeatherAlert) {
        if (alert.level == AlertLevel.ALL_CLEAR) return

        val title: String
        val body: String

        when (alert.level) {
            AlertLevel.IMMEDIATE_THREAT -> {
                title = "⚠️ Put Bike Under Walkway"
                body = "You are home and ${alert.weatherType.lowercase()} is expected in ${alert.hoursUntil}h. Move your bike under the walkway now (score ${alert.rainSeverityScore}/100, ${alert.precipitationProbabilityPercent}% chance)."
            }
            AlertLevel.DELAYED_THREAT -> {
                title = "⛅ Rain Expected After You Arrive"
                body = "You are home. ${alert.weatherType} is expected in ${alert.hoursUntil}h, so bring your bike under the walkway before then (score ${alert.rainSeverityScore}/100, ${alert.precipitationProbabilityPercent}% chance)."
            }
            else -> return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)) 
            .setPriority(NotificationCompat.PRIORITY_HIGH) 
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(1001, builder.build())
            } else {
                Log.e(tag, "Notification permission missing. Alert was not displayed.")
            }
        }
    }
}