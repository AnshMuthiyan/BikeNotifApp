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
        
        // Schedule next check for exactly tomorrow at 1:00 AM
        val now = java.util.Calendar.getInstance()
        val tomorrow = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val delayToTomorrow = tomorrow.timeInMillis - now.timeInMillis
        val nextDailyCheck = OneTimeWorkRequestBuilder<DailyClassSchedulerWorker>()
            .setInitialDelay(delayToTomorrow, TimeUnit.MILLISECONDS)
            .build()
            
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "daily_class_scheduler_chain",
            ExistingWorkPolicy.REPLACE,
            nextDailyCheck
        )
        
        return Result.success()
    }
}

