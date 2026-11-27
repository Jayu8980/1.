package com.aitrader.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.aitrader.di.AppEntryPoint
import com.aitrader.widget.PnlWidget
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync: refreshes the offline cache and the widget, and raises local
 * notifications for new fills, stop-outs, risk breaches and kill-switch changes.
 * (FCM push from the engine is the real-time path once google-services.json is added.)
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
        val repo = ep.repository(); val n = ep.notifier()
        val prefs = applicationContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        return try {
            val dash = repo.dashboard().data
            val risk = repo.risk().data
            val orders = repo.orders().data
            val wasKilled = prefs.getBoolean("kill", false)
            if (dash.killSwitch && !wasKilled) {
                n.notify(Channel.RISK, "Kill switch engaged", "All new orders are blocked.", "risk")
                repo.addAlert("kill", "Kill switch engaged", "Detected by background sync")
            }
            val seenBreaches = prefs.getStringSet("breaches", emptySet())!!
            risk.breaches.filter { it !in seenBreaches }.forEach {
                n.notify(Channel.RISK, "Risk breach", it, "risk"); repo.addAlert("risk", "Risk breach", it)
            }
            val lastTs = prefs.getLong("lastOrderTs", 0)
            orders.filter { it.ts > lastTs && it.status == "FILLED" }.forEach { o ->
                val stop = o.strategy.contains("stop", true)
                n.notify(if (stop) Channel.STOPS else Channel.FILLS, "${o.side} ${o.symbol} filled",
                    "${o.qty} @ %.2f (${o.strategy})".format(o.avgFillPrice), "positions")
                repo.addAlert(if (stop) "stop" else "fill", "${o.side} ${o.symbol}", "${o.qty} @ ${o.avgFillPrice}")
            }
            val seenIntents = prefs.getStringSet("intents", emptySet())!!
            val pend = runCatching { repo.intents().data.filter { it.status == "PENDING" } }.getOrDefault(emptyList())
            pend.filter { it.id !in seenIntents }.forEach {
                n.notify(Channel.RISK, "Approval needed: ${it.summary}", "Requested by ${it.requestedBy}: ${it.reason}", "approvals")
            }
            prefs.edit().putStringSet("intents", pend.map { it.id }.toSet()).apply()
            prefs.edit().putBoolean("kill", dash.killSwitch).putStringSet("breaches", risk.breaches.toSet())
                .putLong("lastOrderTs", orders.maxOfOrNull { it.ts } ?: lastTs).apply()
            PnlWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= 2) {
                n.notify(Channel.ERRORS, "Engine unreachable", repo.describe(e)); Result.success()
            } else Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
