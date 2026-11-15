package com.tubepilot.pipeline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tubepilot.core.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channels and helpers. Deep links carry a "route" extra the app's activity reads. */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply { description = "Videos and scripts waiting for your decision" },
                    NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Failures, blocks, budget and flagged comments" },
                    NotificationChannel(CH_WORK, "Background work", NotificationManager.IMPORTANCE_LOW).apply { description = "Rendering and uploading progress" },
                    NotificationChannel(CH_REPORTS, "Reports", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Weekly analytics report" },
                ),
            )
        }
    }

    private fun contentIntent(route: String): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            putExtra(EXTRA_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (canPost()) NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun builder(channel: String, title: String, text: String, route: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(contentIntent(route))
        .setAutoCancel(true)

    suspend fun approvalNeeded(projectId: String, title: String, what: String) {
        if (!settings.current().notifyApprovals) return
        post(projectId.hashCode(), builder(CH_APPROVALS, "Approval needed: $what", title, "approvals/$projectId").setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    suspend fun problem(projectId: String?, title: String, reason: String) {
        if (!settings.current().notifyFailures) return
        post((projectId ?: title).hashCode() + 1, builder(CH_ALERTS, title, reason, projectId?.let { "approvals/$it" } ?: "dashboard").build())
    }

    suspend fun published(projectId: String, title: String, detail: String) {
        post(projectId.hashCode() + 2, builder(CH_ALERTS, "Uploaded: $title", detail, "dashboard").build())
    }

    suspend fun weeklyReport(headline: String) {
        if (!settings.current().notifyWeeklyReport) return
        post(ID_REPORT, builder(CH_REPORTS, "Your weekly report is ready", headline, "analytics").build())
    }

    suspend fun flaggedComments(count: Int) {
        if (!settings.current().notifyFlaggedComments || count <= 0) return
        post(ID_COMMENTS, builder(CH_ALERTS, "$count comment(s) need your attention", "Open the Comments inbox to review them", "comments").build())
    }

    /** Ongoing notification used for foreground workers. */
    fun workNotification(title: String, text: String, progress: Int): Notification = NotificationCompat.Builder(context, CH_WORK)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(contentIntent("dashboard"))
        .apply { if (progress in 0..100) setProgress(100, progress, false) else setProgress(0, 0, true) }
        .build()

    @android.annotation.SuppressLint("MissingPermission")
    fun updateWork(id: Int, notification: Notification) {
        if (canPost()) NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val EXTRA_ROUTE = "tubepilot.route"
        const val CH_APPROVALS = "approvals"
        const val CH_ALERTS = "alerts"
        const val CH_WORK = "work"
        const val CH_REPORTS = "reports"
        private const val ID_REPORT = 9001
        private const val ID_COMMENTS = 9002
    }
}
