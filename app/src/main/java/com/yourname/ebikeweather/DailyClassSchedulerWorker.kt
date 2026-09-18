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
                val intent = android.content.Intent(applicationContext, ClassEndReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    applicationContext,
                    101,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                try {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
                    Log.i("DailyClassScheduler", "Scheduled exact class end notification at $endTimeMillis")
                } catch (e: SecurityException) {
                    Log.e("DailyClassScheduler", "Exact alarm permission denied", e)
                }
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

