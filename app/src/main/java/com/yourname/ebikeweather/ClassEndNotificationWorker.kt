package com.anshmuthiyan.ebikeweather

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ClassEndNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        EBikeNotificationManager(applicationContext).showClassEndNotification()
        return Result.success()
    }
}

