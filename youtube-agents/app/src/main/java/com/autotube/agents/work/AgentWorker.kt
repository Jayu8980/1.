package com.autotube.agents.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.autotube.agents.App
import com.autotube.agents.core.ActivityLog
import com.autotube.agents.core.Agents
import com.autotube.agents.core.Orchestrator

/** Runs one agent cycle in the background as a foreground job (survives screen-off). */
class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try { setForeground(foreground("Agents are working…")) } catch (_: Exception) { }
        return try {
            Orchestrator.runCycle(applicationContext) { status ->
                val nm = applicationContext.getSystemService(NotificationManager::class.java)
                try { nm.notify(App.NOTIF_ID_WORK, App.workNotification(applicationContext, status)) } catch (_: SecurityException) { }
            }
            Result.success()
        } catch (e: Exception) {
            ActivityLog(applicationContext).error(Agents.SYSTEM, "Cycle crashed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Agents are working…")

    private fun foreground(text: String): ForegroundInfo {
        val n = App.workNotification(applicationContext, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(App.NOTIF_ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(App.NOTIF_ID_WORK, n)
    }
}
