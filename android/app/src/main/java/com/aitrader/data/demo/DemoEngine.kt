package com.aitrader.data.demo

import com.aitrader.data.Metrics
import com.aitrader.data.model.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * On-device SYNTHETIC engine so the app is fully explorable without a server.
 * Nothing here is market data and nothing here can place a real order. LIVE is impossible.
 */
class DemoEngine(seed: Long = 42, private val now: () -> Long = System::currentTimeMillis) {
    private val rnd = Random(seed)
    private val startCapital = 1_000_000.0
    private var killSwitch = false
    private val audit = ArrayDeque<AuditEntry>()

    private val prices = linkedMapOf(
        "RELIANCE-EQ" to 1412.0, "HDFCBANK-EQ" to 1985.0, "ICICIBANK-EQ" to 1428.0, "INFY-EQ" to 1561.0,
        "TCS-EQ" to 3348.0, "SBIN-EQ" to 862.0, "LT-EQ" to 3710.0, "AXISBANK-EQ" to 1189.0,
        "ITC-EQ" to 418.0, "BHARTIARTL-EQ" to 2050.0, "NIFTY26DECFUT" to 25980.0,
        "Nifty 50" to 25940.0, "BTCUSD" to 61350.0,
    )

    private val positions = mutableListOf(
        Position("HDFCBANK-EQ", "NSE", "DELIVERY", 60, 1952.4, 0.0, 0.0, "trend_momentum"),
        Position("ICICIBANK-EQ", "NSE", "INTRADAY", 120, 1431.1, 0.0, 0.0, "mean_reversion"),
        Position("LT-EQ", "NSE", "DELIVERY", 25, 3655.0, 0.0, 0.0, "rs_breakout"),
        Position("NIFTY26DECFUT", "NFO", "CARRYFORWARD", 75, 25912.0, 0.0, 0.0, "trend_momentum"),
    )
    private val orders = mutableListOf<Order>()
    private var realizedToday = 0.0
    private val openPrices: Map<String, Double> = prices.toMap()

    private val strategies = mutableListOf(
        StrategyStats("trend_momentum", "Trend momentum", "Multi-TF momentum with ATR trail; trades TREND regime only",
            true, 35.0, "PAPER", listOf("TREND"), 1.21, 1.38, 412, 0.43, 48_210.0, 4.1, 9.8),
        StrategyStats("mean_reversion", "Mean reversion", "RSI(2)/Bollinger fade in liquid large caps; RANGE regime",
            true, 25.0, "PAPER", listOf("RANGE"), 0.94, 1.12, 688, 0.61, 21_480.0, 3.2, 7.4),
        StrategyStats("rs_breakout", "Relative-strength breakout", "Sector-relative strength vs NIFTY + volume breakout",
            true, 25.0, "BACKTEST", listOf("TREND", "HIGH_VOL"), 0.0, 1.05, 236, 0.39, 0.0, 0.0, 11.2),
        StrategyStats("pcr_oi_fade", "OI / PCR extremes", "Fades PCR extremes with OI confirmation in index futures",
            false, 15.0, "BACKTEST", listOf("HIGH_VOL"), 0.0, 0.71, 184, 0.52, 0.0, 0.0, 14.5,
            autoDisabledReason = "Rejected: < 200 trades and deflated Sharpe below threshold"),
    )

    private var risk = RiskConfig(
        dailyLossLimit = 15_000.0, maxDrawdownPct = 10.0, maxRiskPerTradePct = 1.0, maxPositionPct = 10.0,
        maxSectorPct = 25.0, kellyFraction = 0.25, dayLossUsed = 0.0, currentDrawdownPct = 2.3, killSwitch = false,
    )

    private val history: List<CurvePoint> = buildCurve(seed)

    init { log("engine.start", "demo engine started (synthetic data)") }

    private fun buildCurve(seed: Long): List<CurvePoint> {
        val r = Random(seed + 7)
        val day = TimeUnit.DAYS.toMillis(1)
        val start = now() - 250 * day
        var eq = startCapital; var nf = startCapital
        return (0 until 250).map { i ->
            val mkt = r.nextGaussian() * 0.009 + 0.0003
            nf *= 1 + mkt
            eq *= 1 + 0.55 * mkt + r.nextGaussian() * 0.005 + 0.00035
            CurvePoint(start + i * day, eq, nf)
        }
    }

    @Synchronized fun tick() {
        prices.keys.toList().forEach { k ->
            val p = prices.getValue(k)
            prices[k] = (p * (1 + rnd.nextGaussian() * 0.0006) / 0.05).roundToInt() * 0.05
        }
        val unreal = positions.sumOf { (prices.getValue(it.symbol) - it.avgPrice) * it.qty }
        val dayPnl = dayPnl()
        risk = risk.copy(dayLossUsed = maxOf(0.0, -dayPnl), killSwitch = killSwitch)
        if (-dayPnl > risk.dailyLossLimit && !killSwitch) {
            killSwitch = true
            log("risk.breach", "daily loss limit hit (unrealised $unreal) — kill switch engaged")
        }
    }

    private fun dayPnl(): Double = realizedToday + positions.sumOf { p ->
        val ref = if (p.product == "INTRADAY") p.avgPrice else openPrices.getValue(p.symbol)
        (prices.getValue(p.symbol) - ref) * p.qty
    }

    @Synchronized fun dashboard(): Dashboard {
        tick()
        val last = history.last()
        val dp = dayPnl()
        val live = CurvePoint(now(), last.equity + dp, last.nifty * (prices.getValue("NIFTY26DECFUT") / 25980.0))
        val curve = history + live
        return Dashboard(TradingMode.PAPER, killSwitch, dp, dp / last.equity * 100, live.equity - startCapital,
            live.equity, positions.size, curve, now(), autoTrade = autoTrade, autoOpenTrades = if (autoTrade) 1 else 0)
    }

    @Synchronized fun positions(): List<Position> = positions.map { it.copy(ltp = prices.getValue(it.symbol)) }
    @Synchronized fun orders(): List<Order> = orders.sortedByDescending { it.ts }

    @Synchronized fun exit(symbol: String, product: String): ActionResult {
        if (killSwitch) log("order.exit", "exit allowed under kill switch: $symbol")
        val p = positions.firstOrNull { it.symbol == symbol && it.product == product }
            ?: return ActionResult(false, "No open position in $symbol")
        val px = prices.getValue(symbol)
        val ref = if (product == "INTRADAY") p.avgPrice else openPrices.getValue(symbol)
        realizedToday += (px - ref) * p.qty
        positions.remove(p)
        orders += Order("D${orders.size + 1}", symbol, if (p.qty > 0) "SELL" else "BUY", abs(p.qty), "MARKET",
            null, "FILLED", px, p.strategy, now())
        log("order.ack", "exit $symbol ${p.qty} @ $px (paper)")
        return ActionResult(true, "Exited $symbol @ %.2f".format(px))
    }

    @Synchronized fun signals(): List<Signal> {
        val t = now()
        fun s(i: Int, sym: String, side: String, strat: String, conf: Double, regime: String, reason: String,
              stopPct: Double, rr: Double, edge: Double, status: String): Signal {
            val e = prices.getValue(sym)
            val dir = if (side == "BUY") 1 else -1
            val stop = e * (1 - dir * stopPct); val target = e + dir * rr * abs(e - stop)
            return Signal("sig$i", sym, side, strat, conf, regime, reason, e, stop, target, edge, status, t - i * 420_000L)
        }
        return listOf(
            s(1, "RELIANCE-EQ", "BUY", "trend_momentum", 0.68, "TREND",
                "20/50 EMA aligned on 15m & 1d, ADX 31, RS vs NIFTY +4.2% (20d), breadth 64%", 0.012, 2.4, 38.0, "TAKEN"),
            s(2, "INFY-EQ", "SELL", "mean_reversion", 0.57, "RANGE",
                "RSI(2)=96 at upper Bollinger band, IV flat, no results within 5 sessions", 0.009, 1.6, 9.0, "SKIPPED_COST"),
            s(3, "SBIN-EQ", "BUY", "rs_breakout", 0.49, "TREND",
                "Breakout above 52w high on 2.1x volume; PSU bank sector RS rising", 0.015, 2.0, 22.0, "SKIPPED_CONFIDENCE"),
            s(4, "BHARTIARTL-EQ", "BUY", "trend_momentum", 0.71, "UNCERTAIN",
                "Momentum valid, but regime classifier confidence 0.41 < 0.6", 0.011, 2.2, 31.0, "SKIPPED_REGIME"),
            s(5, "AXISBANK-EQ", "BUY", "mean_reversion", 0.63, "RANGE",
                "Oversold at range support; would breach 25% banking sector cap", 0.008, 1.8, 17.0, "VETOED"),
            s(6, "ITC-EQ", "BUY", "mean_reversion", 0.66, "RANGE",
                "3-day pullback in uptrend, low vol, FII net buyers 3 sessions", 0.007, 1.9, 21.0, "TAKEN"),
        )
    }

    @Synchronized fun strategies(): List<StrategyStats> = strategies.toList()

    @Synchronized fun updateStrategy(id: String, u: StrategyUpdate): ActionResult {
        val i = strategies.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return ActionResult(false, "unknown strategy")
        val cur = strategies[i]
        if (u.enabled == true && cur.autoDisabledReason != null)
            return ActionResult(false, "Cannot enable: ${cur.autoDisabledReason}")
        val newAlloc = u.allocationPct ?: cur.allocationPct
        val total = strategies.filterIndexed { j, _ -> j != i }.sumOf { it.allocationPct } + newAlloc
        if (total > 100.0) return ActionResult(false, "Total allocation would be %.0f%% (> 100%%)".format(total))
        strategies[i] = cur.copy(enabled = u.enabled ?: cur.enabled, allocationPct = newAlloc)
        log("strategy.update", "$id enabled=${strategies[i].enabled} alloc=${strategies[i].allocationPct}")
        return ActionResult(true, "Updated")
    }

    @Synchronized fun risk(): RiskConfig { tick(); return risk }

    @Synchronized fun updateRisk(r: RiskConfig): ActionResult {
        val problems = buildList {
            if (r.maxRiskPerTradePct !in 0.1..2.0) add("risk per trade must be 0.1–2%")
            if (r.kellyFraction !in 0.0..0.25) add("Kelly fraction capped at 0.25")
            if (r.maxDrawdownPct !in 1.0..30.0) add("max drawdown must be 1–30%")
            if (r.dailyLossLimit <= 0) add("daily loss limit must be > 0")
        }
        if (problems.isNotEmpty()) return ActionResult(false, problems.joinToString("; "))
        risk = r.copy(dayLossUsed = risk.dayLossUsed, currentDrawdownPct = risk.currentDrawdownPct, killSwitch = killSwitch)
        log("risk.update", "limits updated")
        return ActionResult(true, "Risk limits saved")
    }

    @Synchronized fun killSwitch(engaged: Boolean, reason: String): ActionResult {
        killSwitch = engaged
        risk = risk.copy(killSwitch = engaged)
        log(if (engaged) "kill_switch.engaged" else "kill_switch.released", reason)
        return ActionResult(true, if (engaged) "Kill switch ENGAGED — new orders blocked" else "Kill switch released")
    }

    @Synchronized fun setMode(mode: TradingMode): ActionResult {
        if (mode == TradingMode.LIVE) {
            log("mode.live_denied", "LIVE impossible in demo mode")
            return ActionResult(false, "LIVE requires a connected engine. Demo mode is paper-only.")
        }
        return ActionResult(true, "PAPER")
    }

    fun backtest(req: BacktestRequest): Tearsheet {
        val r = Random(req.strategyId.hashCode().toLong() + req.from.hashCode())
        val n = 756
        val day = TimeUnit.DAYS.toMillis(1)
        val start = now() - n * day
        var eq = 1.0; var nf = 1.0
        val curve = ArrayList<CurvePoint>(n); val trades = ArrayList<Double>()
        for (i in 0 until n) {
            val m = r.nextGaussian() * 0.0095 + 0.0004
            nf *= 1 + m
            val s = 0.45 * m + r.nextGaussian() * 0.006 + 0.0004
            eq *= 1 + s
            if (r.nextDouble() < 0.4) trades += s * 1_000_000
            curve += CurvePoint(start + i * day, eq * 1_000_000, nf * 1_000_000)
        }
        val e = curve.map { it.equity }; val b = curve.map { it.nifty }
        val rets = Metrics.returns(e)
        val (alpha, beta) = Metrics.alphaBeta(rets, Metrics.returns(b))
        val sh = Metrics.sharpe(rets)
        val monthly = e.chunked(21).map { it.last() / it.first() - 1 }
        return Tearsheet(req.strategyId, req.from, req.to, Metrics.cagr(e), sh, Metrics.sortino(rets),
            Metrics.maxDrawdown(e) * 100, Metrics.calmar(e), Metrics.profitFactor(trades), trades.average(),
            alpha, beta, 1.7, trades.size, sh * 0.62, curve, Metrics.drawdowns(e).map { it * 100 }, monthly,
            synthetic = true)
    }

    fun analyst(symbol: String): Analysis = Analysis(
        symbol,
        "SYNTHETIC SAMPLE — connect the engine for real LLM summaries of news, results and filings. " +
            "$symbol: last quarter revenue grew in line with estimates; margins stable; management guided " +
            "steady growth. No pending corporate actions in the next 5 sessions.",
        sentiment = 0.18,
        keyPoints = listOf("Results in line with consensus", "Margin trend flat", "No event risk in the next week",
            "Advisory only — the analyst never places orders"),
        sources = listOf("demo"), generatedAt = now(), synthetic = true,
    )

    private val brokers = linkedMapOf(
        "angelone" to BrokerInfo("angelone", "Angel One (SmartAPI)", "TOTP", listOf("NSE", "BSE", "NFO", "BFO", "MCX"),
            active = true, configured = true, connected = true, account = "DEMO01", message = "Connected (daily TOTP login)"),
        "zerodha" to BrokerInfo("zerodha", "Zerodha (Kite Connect)", "OAUTH", listOf("NSE", "BSE", "NFO", "BFO", "MCX"),
            active = false, configured = true, connected = false, message = "Not connected"),
        "upstox" to BrokerInfo("upstox", "Upstox", "OAUTH", listOf("NSE", "BSE", "NFO", "BFO", "MCX"),
            requiredSettings = listOf("ENGINE_UPSTOX_CLIENT_ID", "ENGINE_UPSTOX_CLIENT_SECRET"),
            active = false, configured = false, connected = false, message = "API keys not set on server"),
        "dhan" to BrokerInfo("dhan", "Dhan", "TOKEN", listOf("NSE", "BSE", "NFO", "BFO", "MCX"),
            active = false, configured = true, connected = false, message = "Not connected"),
        "fyers" to BrokerInfo("fyers", "Fyers", "OAUTH", listOf("NSE", "BSE", "NFO", "BFO", "MCX"),
            active = false, configured = true, connected = false, message = "Not connected"),
        "delta" to BrokerInfo("delta", "Delta Exchange", "API_KEY", listOf("CRYPTO_FUT", "CRYPTO_OPT"),
            active = false, configured = true, connected = false, message = "Not connected"),
    )

    @Synchronized fun brokers(): List<BrokerInfo> = brokers.values.toList()

    @Synchronized fun connectBroker(id: String): ConnectResult {
        val b = brokers[id] ?: return ConnectResult(false, null, "unknown broker")
        if (!b.configured) return ConnectResult(false, null,
            "${b.name} is not configured on the server: set ${b.requiredSettings.joinToString()}")
        brokers[id] = b.copy(connected = true, account = "DEMO-${id.uppercase().take(3)}", message = "Connected (simulated)")
        log("broker.connected", "$id (simulated)")
        return ConnectResult(true, null, "${b.name} connected (demo — no real login)")
    }

    @Synchronized fun activateBroker(id: String): ActionResult {
        val b = brokers[id] ?: return ActionResult(false, "unknown broker")
        if (!b.connected) return ActionResult(false, "${b.name} is not connected")
        brokers.replaceAll { k, v -> v.copy(active = k == id) }
        log("broker.activated", id)
        return ActionResult(true, "${b.name} is now the live broker")
    }

    @Synchronized fun disconnectBroker(id: String): ActionResult {
        val b = brokers[id] ?: return ActionResult(false, "unknown broker")
        brokers[id] = b.copy(connected = false, account = "", message = "Not connected")
        log("broker.disconnected", id)
        return ActionResult(true, "Disconnected ${b.name}")
    }

    private val watch = mutableListOf(
        ContractDto("Nifty 50", "99926000", "NSE"), ContractDto("RELIANCE-EQ", "2885", "NSE"),
        ContractDto("HDFCBANK-EQ", "1333", "NSE"), ContractDto("ICICIBANK-EQ", "4963", "NSE"),
        ContractDto("INFY-EQ", "1594", "NSE"), ContractDto("SBIN-EQ", "3045", "NSE"),
        ContractDto("BTCUSD", "27", "DELTA", "FUTURE", 1, "0.5", "0.001", mapOf("delta" to "BTCUSD")),
    )

    @Synchronized fun watchlist(): List<WatchItem> = watch.map { c ->
        WatchItem(c, prices[c.symbol], now(), if (c.exchange == "DELTA") "demo-delta" else "demo")
    }

    /** One simulated tick per watchlist symbol. */
    @Synchronized fun quotes(): List<Quote> {
        tick()
        return watch.mapNotNull { c -> prices[c.symbol]?.let {
            Quote(c.key, c.symbol, c.exchange, it, now(), if (c.exchange == "DELTA") "demo-delta" else "demo") } }
    }

    fun search(q: String, exchange: String): List<InstrumentHit> {
        val universe = listOf("ASIANPAINT", "BAJFINANCE", "HINDUNILVR", "KOTAKBANK", "MARUTI", "SUNPHARMA", "TITAN",
            "ULTRACEMCO", "WIPRO", "TATAMOTORS", "TATASTEEL", "ADANIENT", "ONGC", "NTPC", "POWERGRID")
        return if (exchange == "DELTA") listOf("BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD").filter { it.contains(q.uppercase()) }
            .map { InstrumentHit(it, it.hashCode().toString(), "DELTA", "FUTURE", 1, "0.5",
                if (it == "BTCUSD") "0.001" else "0.01", mapOf("delta" to it), "$it perpetual (demo)") }
        else universe.filter { it.contains(q.uppercase()) }.map {
            InstrumentHit("$it-EQ", (abs(it.hashCode()) % 50000).toString(), exchange, description = "$it (demo)") }
    }

    @Synchronized fun addWatch(c: ContractDto): ActionResult {
        if (watch.any { it.key == c.key }) return ActionResult(false, "${c.symbol} already in watchlist")
        watch += c
        prices.putIfAbsent(c.symbol, if (c.exchange == "DELTA") 3000.0 else 500.0 + rnd.nextInt(3000))
        return ActionResult(true, "Added ${c.symbol}")
    }

    @Synchronized fun removeWatch(key: String): ActionResult =
        if (watch.removeAll { it.key == key }) ActionResult(true, "Removed") else ActionResult(false, "Not in watchlist")

    private var autoTrade = false

    @Synchronized fun autotrade() = AutoTradeStatus(autoTrade, TradingMode.PAPER, killSwitch,
        openTrades = if (autoTrade) listOf(AutoTradeView("T1", "trend_momentum", "RELIANCE-EQ", "BUY", 40, 40, "paper",
            "OPEN", 1408.5, 1391.6, 1442.3, false)) else emptyList())

    @Synchronized fun autotradeOn(): ActionResult {
        if (killSwitch) return ActionResult(false, "Release the kill switch first")
        autoTrade = true; log("autotrade.on", "demo")
        return ActionResult(true, "Auto-trade ON — PAPER only (demo)")
    }

    @Synchronized fun autotradeOff(): ActionResult {
        autoTrade = false; log("autotrade.off", "demo")
        return ActionResult(true, "Auto-trade OFF — no new entries; open trades still protected")
    }

    @Synchronized fun promote(id: String): ActionResult =
        ActionResult(false, "Promotion gate: needs 28 days in PAPER, 30 paper trades, positive expectancy (demo)")

    @Synchronized fun demote(id: String): ActionResult {
        val i = strategies.indexOfFirst { it.id == id }
        if (i >= 0) strategies[i] = strategies[i].copy(stage = "PAPER")
        return ActionResult(true, "$id back to PAPER")
    }

    @Synchronized fun audit(): List<AuditEntry> = audit.toList().reversed()

    private fun log(event: String, data: String) {
        val prev = audit.lastOrNull()?.hash ?: "0".repeat(64)
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("$prev$event$data".toByteArray())
            .joinToString("") { "%02x".format(it) }
        audit.addLast(AuditEntry(java.time.Instant.ofEpochMilli(now()).toString(), event, data, h))
        if (audit.size > 500) audit.removeFirst()
    }
}

private fun Random.nextGaussian(): Double {
    var u: Double; var v: Double; var s: Double
    do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
    return u * kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s)
}
