package com.anshmuthiyan.ebikeweather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class EBikeNotificationManager(private val context: Context) {
    private val CHANNEL_ID = "EBIKE_WEATHER_ALERTS"
    private val tag = "EBikeNotificationMgr"

    companion object {
        const val NOTIF_ID_RIDE_CONFIRMATION = 1005
    }

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

    fun showAllClearNotification() {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("☀️ Weather is Clear!")
            .setContentText("You are home and no rain is expected. Safe to leave bike out.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("You are home and no rain is expected. Safe to leave bike out."))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(1003, builder.build())
            }
        }
    }

    fun showClassEndNotification() {
        val title = "Classes Ended!"
        val body = "Your classes for the day have ended."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)) 
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) 
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(1002, builder.build())
            } else {
                Log.e(tag, "Notification permission missing. Class end alert was not displayed.")
            }
        }
    }

    fun showRechargeBatteryNotification(currentMiles: Float, avgMiles: Float) {
        val title = "🔋 Time to Recharge Your E-Bike!"
        val body = String.format(
            Locale.US,
            "You've ridden %.1f miles since your last charge (your average between charges is %.1f miles). Remember to plug in your battery!",
            currentMiles,
            avgMiles
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(1004, builder.build())
            } else {
                Log.e(tag, "Notification permission missing. Battery recharge alert was not displayed.")
            }
        }
    }

    fun showRideOrDriveConfirmationNotification(ride: RideRecord) {
        val title = "🤔 Was this a Bike Ride or a Drive?"
        val body = String.format(
            Locale.US,
            "%.1f min trip (%.2f mi) had Avg %.1f mph & Max %.1f mph. Tap below to keep as a bike ride or remove as a car drive.",
            ride.durationMinutes,
            ride.distanceMiles,
            ride.avgSpeedMph,
            ride.maxSpeedMph
        )

        val bikeIntent = Intent(context, GlobalBikeReceiver::class.java).apply {
            action = GlobalBikeReceiver.ACTION_CONFIRM_BIKE_RIDE
            putExtra(GlobalBikeReceiver.EXTRA_RIDE_ID, ride.id)
        }
        val bikePendingIntent = PendingIntent.getBroadcast(
            context,
            (ride.id and 0x7FFFFFFF).toInt(),
            bikeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val driveIntent = Intent(context, GlobalBikeReceiver::class.java).apply {
            action = GlobalBikeReceiver.ACTION_CONFIRM_CAR_DRIVE
            putExtra(GlobalBikeReceiver.EXTRA_RIDE_ID, ride.id)
        }
        val drivePendingIntent = PendingIntent.getBroadcast(
            context,
            ((ride.id + 1) and 0x7FFFFFFF).toInt(),
            driveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openDetailsIntent = Intent(context, RideDetailsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(GlobalBikeReceiver.EXTRA_RIDE_ID, ride.id)
        }
        val openDetailsPendingIntent = PendingIntent.getActivity(
            context,
            ((ride.id + 2) and 0x7FFFFFFF).toInt(),
            openDetailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openDetailsPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_directions, "🚲 Bike Ride", bikePendingIntent)
            .addAction(android.R.drawable.ic_menu_delete, "🚗 Car Drive", drivePendingIntent)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(NOTIF_ID_RIDE_CONFIRMATION, builder.build())
            } else {
                Log.e(tag, "Notification permission missing. Ride/Drive confirmation was not displayed.")
            }
        }
    }

    fun cancelConfirmationNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_RIDE_CONFIRMATION)
    }
}
