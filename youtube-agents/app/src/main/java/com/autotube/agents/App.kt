package com.autotube.agents

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.autotube.agents.ui.MainActivity
import com.autotube.agents.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "Agent activity", NotificationManager.IMPORTANCE_LOW))
        Scheduler.apply(this)
    }

    companion object {
        const val CHANNEL_WORK = "agent_work"
        const val NOTIF_ID_WORK = 42

        fun workNotification(context: Context, text: String): Notification {
            val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AutoTube Agents")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
