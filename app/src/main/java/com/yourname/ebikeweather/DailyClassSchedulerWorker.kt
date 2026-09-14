package com.anshmuthiyan.ebikeweather

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class DailyClassSchedulerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val endTimeMillis = CalendarWeatherWindow.getTodayClassesEndTime(applicationContext)
        
        if (endTimeMillis != null) {
            val nowMillis = System.currentTimeMillis()
            if (endTimeMillis > nowMillis) {
                val delayMillis = endTimeMillis - nowMillis
                val work = OneTimeWorkRequestBuilder<ClassEndNotificationWorker>()
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build()
                
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    "class_end_notification",
                    ExistingWorkPolicy.REPLACE,
                    work
                )
                Log.i("DailyClassScheduler", "Scheduled class end notification in ${delayMillis}ms")
            } else {
                Log.i("DailyClassScheduler", "Classes already ended today.")
            }
        } else {
            Log.i("DailyClassScheduler", "No classes found today.")
        }
        
        return Result.success()
    }
}

