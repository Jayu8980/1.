package com.tradelens.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tradelens.app.TradeLensApp
import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.llm.LlmClient
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.paper.PaperTrading
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.Patterns
import com.tradelens.app.domain.Rules
import com.tradelens.app.domain.Timeframe
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZonedDateTime

/** Evaluates price / rule / pattern alerts, advances paper orders, polls the engine for risk breaches. */
@HiltWorker
class AlertWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val db: AppDatabase, private val market: MarketRepository, private val notifier: Notifier,
    private val paper: PaperTrading, private val engine: EngineClient, private val secure: SecureStore,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val alerts = db.alerts().active()
        val instruments = (alerts.map { it.instrument } + db.paper().positionsNow().map { AppJson.decodeFromString(Instrument.serializer(), it.instrumentJson) } +
            db.paper().openOrders().map { AppJson.decodeFromString(Instrument.serializer(), it.instrumentJson) })
            .distinctBy { it.key }.filter { it.isCrypto || MarketHours.isOpen(it) || MarketHours.phase() == com.tradelens.app.domain.MarketPhase.CLOSED }
        val quotes = runCatching { market.quotes(instruments) }.getOrDefault(emptyMap())

        for (a in alerts) {
            val i = a.instrument
            val q = quotes[i.key] ?: continue
            val (hit, msg) = when (a.kind) {
                "PRICE_ABOVE" -> (q.ltp >= (a.value ?: Double.MAX_VALUE)) to "${i.symbol} crossed above ${a.value} (now ${"%.2f".format(q.ltp)})"
                "PRICE_BELOW" -> (q.ltp <= (a.value ?: -1.0)) to "${i.symbol} fell below ${a.value} (now ${"%.2f".format(q.ltp)})"
                "RULE" -> {
                    val c = market.candles(i, Timeframe.fromLabel(a.timeframe), 300).candles
                    val ok = c.size > 30 && runCatching { Rules.Evaluator(c).eval(Rules.parse(a.rule!!), c.size - 1) }.getOrDefault(false)
                    ok to "${i.symbol}: ${a.rule} is true on ${a.timeframe}"
                }
                "PATTERN" -> {
                    val c = market.candles(i, Timeframe.fromLabel(a.timeframe), 300).candles
                    val p = Patterns.all(c).filter { it.index >= c.size - 1 && (a.rule.isNullOrBlank() || it.name.contains(a.rule, true)) }
                    p.isNotEmpty() to "${i.symbol} ${a.timeframe}: ${p.joinToString { it.name }}"
                }
                else -> false to ""
            }
            val cooldownOk = a.lastTriggered == null || System.currentTimeMillis() - a.lastTriggered > 60 * 60_000
            if (hit && cooldownOk) {
                notifier.notify(TradeLensApp.CH_ALERTS, "Alert: ${i.symbol}", "$msg\nSource: ${q.badge.source.label}${if (q.badge.realtime) "" else " (delayed)"}")
                db.alerts().save(a.copy(lastTriggered = System.currentTimeMillis(), active = !a.oneShot))
            }
        }

        paper.onQuotes(quotes.values).forEach { notifier.notify(TradeLensApp.CH_FILLS, "Paper trading", it) }

        if (engine.isConfigured()) runCatching {
            val s = engine.api.status()
            val flag = "engine.lastRiskFlag"
            val state = "${s.halted}/${s.circuitBroken}/${s.globalKill}"
            if (secure.get(flag) != state && (s.halted || s.circuitBroken || s.globalKill)) {
                notifier.notify(TradeLensApp.CH_RISK, "Engine risk state", "Halted=${s.halted}, circuit breaker=${s.circuitBroken}, kill switch=${s.globalKill}. Day P&L ₹${"%.0f".format(s.dayPnl)}")
            }
            secure.put(flag, state)
            val pending = engine.api.signals("PENDING")
            val seen = secure.get("engine.seenSignals")?.split(",")?.toSet().orEmpty()
            pending.filter { it.id !in seen }.forEach {
                notifier.notify(TradeLensApp.CH_FILLS, "Signal awaiting approval", "${it.strategy}: ${it.direction} ${it.symbol} (conf ${"%.0f".format(it.confidence * 100)}%)")
            }
            secure.put("engine.seenSignals", (seen + pending.map { it.id }).toList().takeLast(200).joinToString(","))
        }
        return Result.success()
    }
}

/** Daily refresh of each connected broker's instrument master (Wi-Fi + battery-not-low). */
@HiltWorker
class InstrumentSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters, private val brokers: BrokerRegistry, private val db: AppDatabase,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        for (b in brokers.connected()) if (brokers.instrumentsAge(b) > 20 * 3600_000L) runCatching { brokers.refreshInstruments(b) }
        db.candles().pruneIntraday(System.currentTimeMillis() / 1000 - 30 * 86400)
        return Result.success()
    }
}

/** Pre-market (≈08:45 IST) and post-market (≈16:00 IST) briefs: engine-generated if available, else via the user's LLM. */
@HiltWorker
class BriefWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters, private val engine: EngineClient, private val llm: LlmClient,
    private val market: MarketRepository, private val notifier: Notifier, private val secure: SecureStore, private val settings: SettingsStore,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!settings.value.preMarketBrief) return Result.success()
        val now = ZonedDateTime.now(MarketHours.IST)
        val phase = MarketHours.phase(now)
        if (phase == com.tradelens.app.domain.MarketPhase.WEEKEND || phase == com.tradelens.app.domain.MarketPhase.HOLIDAY) return Result.success()
        val kind = when (now.hour) { 8 -> "pre-market"; 16 -> "post-market"; else -> return Result.success() }
        val key = "brief.$kind.${LocalDate.now(MarketHours.IST)}"
        if (secure.get(key) != null) return Result.success()
        val text = runCatching { engine.api.briefs().firstOrNull { it.kind == kind && it.time > now.toEpochSecond() - 6 * 3600 }?.let { b ->
            b.text + if (b.anomalies.isNotEmpty()) "\nAnomalies: " + b.anomalies.joinToString("; ") else "" } }.getOrNull()
            ?: if (llm.isConfigured()) runCatching {
                val idx = listOf("NIFTY 50", "BANKNIFTY", "INDIA VIX").map { Instrument.parse(it) }
                val q = market.quotes(idx)
                llm.brief(q.values.joinToString("\n") { "${it.instrumentKey}: ${"%.2f".format(it.ltp)} (${"%.2f".format(it.changePct)}%), source ${it.badge.source.label}${if (it.badge.realtime) "" else " delayed"}" }, kind)
            }.getOrNull() else null
        if (text != null) {
            notifier.notify(TradeLensApp.CH_REPORTS, "${kind.replaceFirstChar { it.uppercase() }} brief", text.take(1500))
            secure.put(key, "1")
        }
        return Result.success()
    }
}
