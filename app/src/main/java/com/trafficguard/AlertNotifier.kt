package com.trafficguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object AlertNotifier {
    private const val CHANNEL_ID = "risk_alert_channel"
    private var nextNotifId = 1000

    fun notifySuspicious(context: Context, title: String, message: String) {
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.notify(nextNotifId++, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "위험 탐지 경고",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }
}
