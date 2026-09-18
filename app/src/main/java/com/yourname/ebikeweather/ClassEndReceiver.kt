package com.anshmuthiyan.ebikeweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ClassEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EBikeNotificationManager(context).showClassEndNotification()
    }
}

