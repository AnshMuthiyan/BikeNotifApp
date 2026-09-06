package com.anshmuthiyan.ebikeweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object CalendarWeatherWindow {
    private const val fallbackHours = 24L

    fun forecastEnd(context: Context, now: LocalDateTime): LocalDateTime {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return now.plusHours(fallbackHours)
        }

        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val tomorrowStart = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
        val followingDayStart = tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(tomorrowStart.toString())
            .appendPath(followingDayStart.toString())
            .build()

        return try {
            context.contentResolver.query(
                instancesUri,
                arrayOf(CalendarContract.Instances.BEGIN),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val eventStart = cursor.getLong(0)
                    java.time.Instant.ofEpochMilli(eventStart)
                        .atZone(zone)
                        .toLocalDateTime()
                } else {
                    now.plusHours(fallbackHours)
                }
            } ?: now.plusHours(fallbackHours)
        } catch (securityException: SecurityException) {
            now.plusHours(fallbackHours)
        }
    }
}