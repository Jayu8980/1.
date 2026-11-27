package com.aitrader.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aitrader.MainActivity
import com.aitrader.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class Channel(val id: String, val title: String, val importance: Int) {
    FILLS("fills", "Fills & orders", NotificationManager.IMPORTANCE_DEFAULT),
    STOPS("stops", "Stops hit", NotificationManager.IMPORTANCE_HIGH),
    RISK("risk", "Risk breaches & kill switch", NotificationManager.IMPORTANCE_HIGH),
    ERRORS("errors", "Engine errors", NotificationManager.IMPORTANCE_HIGH),
}

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val ctx: Context) {
    fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Channel.entries.forEach { nm.createNotificationChannel(NotificationChannel(it.id, it.title, it.importance)) }
    }

    fun notify(channel: Channel, title: String, body: String, route: String = "logs") {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val pi = PendingIntent.getActivity(ctx, route.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel.id)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true)
            .setPriority(if (channel.importance >= NotificationManager.IMPORTANCE_HIGH) NotificationCompat.PRIORITY_HIGH
                         else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(ctx).notify((title + System.currentTimeMillis()).hashCode(), n)
    }
}
