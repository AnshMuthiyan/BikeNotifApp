package com.anshmuthiyan.ebikeweather

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HomeArrivalWork {
    private const val tag = "HomeArrivalWork"
    private const val workName = "home_arrival_weather_check"

    fun enqueue(context: Context) {
        val work = OneTimeWorkRequestBuilder<WeatherCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            work
        )
        Log.i(tag, "Home arrival detected. Queued one weather check.")
    }
}