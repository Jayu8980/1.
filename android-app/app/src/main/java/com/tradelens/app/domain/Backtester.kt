package com.tradelens.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class BacktestConfig(
    val entry: RuleSet,
    val exit: RuleSet? = null,
    val long: Boolean = true,
    val initialCapital: Double = 1_000_000.0,
    val riskPct: Double = 1.0,
    val atrStopMult: Double = 2.0,
    val targetR: Double? = 3.0,
    val maxPositionPct: Double = 10.0,
    val slippageBps: Double = 5.0,
    val segment: ChargeSegment = ChargeSegment.EQ_DELIVERY,
    val lotSize: Int = 1,
    val barsPerYear: Double = 252.0,
)

@Serializable
data class BtTrade(
    val entryTime: Long, val exitTime: Long, val entry: Double, val exit: Double, val qty: Double,
    val pnl: Double, val charges: Double, val reason: String,
)

@Serializable
data class PerfMetrics(
    val totalReturnPct: Double,
    val cagrPct: Double,
    val maxDrawdownPct: Double,
    val sharpe: Double,
    val sortino: Double,
    val calmar: Double,
    val winRatePct: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val trades: Int,
    val totalCharges: Double,
    val alphaPct: Double = 0.0,
    val beta: Double = 0.0,
    val informationRatio: Double = 0.0,
)

data class BacktestResult(
    val metrics: PerfMetrics,
    val equity: List<Pair<Long, Double>>,
    val benchmark: List<Pair<Long, Double>>,
    val benchmarkMetrics: PerfMetrics?,
    val trades: List<BtTrade>,
)

/**
 * Bar-by-bar, no-look-ahead backtester: signals are evaluated on bar close i and filled at bar i+1 open
 * (plus slippage). Stops/targets are checked intra-bar using high/low (stop assumed first when both hit).
 * Every fill pays the real statutory charges from [Charges].
 */
object Backtester {

    fun run(c: List<Candle>, cfg: BacktestConfig, benchmark: List<Candle>? = null): BacktestResult {
        val ev = Rules.Evaluator(c)
        val atr = Indicators.atr(c, 14)
        var cash = cfg.initialCapital
        var qty = 0.0; var entryPx = 0.0; var entryTime = 0L; var stop = 0.0; var target: Double? = null; var entryCharges = 0.0
        var pendingEntry = false; var pendingExit = false
        val trades = mutableListOf<BtTrade>()
        val equity = ArrayList<Pair<Long, Double>>(c.size)
        val slip = cfg.slippageBps / 10_000.0
        val dir = if (cfg.long) 1.0 else -1.0

        fun close(i: Int, px: Double, reason: String) {
            val exitPx = px * (1 - dir * slip)
            val ch = Charges.leg(cfg.segment, if (cfg.long) Side.SELL else Side.BUY, exitPx, qty).total
            val gross = dir * (exitPx - entryPx) * qty
            cash += if (cfg.long) exitPx * qty - ch else (gross + entryPx * qty) - ch
            trades += BtTrade(entryTime, c[i].time, entryPx, exitPx, qty, gross - ch - entryCharges, ch + entryCharges, reason)
            qty = 0.0
        }

        for (i in c.indices) {
            val bar = c[i]
            if (pendingExit && qty > 0) { close(i, bar.open, "Exit rule"); pendingExit = false }
            if (pendingEntry && qty == 0.0) {
                pendingEntry = false
                val a = atr[i - 1]
                if (!a.isNaN() && a > 0) {
                    val px = bar.open * (1 + dir * slip)
                    val equityNow = cash
                    val ps = runCatching {
                        Risk.positionSize(equityNow, cfg.riskPct, px, cfg.long, atr = a, atrMult = cfg.atrStopMult, lotSize = cfg.lotSize, maxPositionPct = cfg.maxPositionPct)
                    }.getOrNull()
                    if (ps != null && ps.qty > 0) {
                        val q = min(ps.qty, floor(cash / px / cfg.lotSize) * cfg.lotSize)
                        if (q > 0) {
                            entryCharges = Charges.leg(cfg.segment, if (cfg.long) Side.BUY else Side.SELL, px, q).total
                            qty = q; entryPx = px; entryTime = bar.time; stop = ps.stop
                            target = cfg.targetR?.let { px + dir * it * abs(px - ps.stop) }
                            cash -= if (cfg.long) px * q + entryCharges else px * q + entryCharges
                        }
                    }
                }
            }
            if (qty > 0) {
                val hitStop = if (cfg.long) bar.low <= stop else bar.high >= stop
                val t = target
                val hitTarget = t != null && (if (cfg.long) bar.high >= t else bar.low <= t)
                when {
                    hitStop -> close(i, if (cfg.long) min(stop, bar.open) else max(stop, bar.open), "Stop")
                    hitTarget -> close(i, if (cfg.long) max(t!!, bar.open) else min(t!!, bar.open), "Target")
                }
            }
            if (qty > 0) {
                if (cfg.exit != null && ev.eval(cfg.exit, i)) pendingExit = true
            } else if (i < c.size - 1 && ev.eval(cfg.entry, i)) pendingEntry = true

            val mtm = if (qty > 0) (if (cfg.long) qty * bar.close else qty * entryPx + (entryPx - bar.close) * qty) else 0.0
            equity += bar.time to (cash + mtm)
        }
        if (qty > 0) {
            close(c.size - 1, c.last().close, "End of data")
            equity[equity.size - 1] = c.last().time to cash
        }

        val bench = benchmark?.takeIf { it.size > 1 }?.let { b ->
            val start = c.first().time
            val sub = b.filter { it.time >= start }
            if (sub.size < 2) null else sub.map { it.time to cfg.initialCapital * it.close / sub.first().close }
        } ?: c.map { it.time to cfg.initialCapital * it.close / c.first().close }

        val m = Metrics.compute(equity, trades, cfg.barsPerYear, bench)
        return BacktestResult(m, equity, bench, Metrics.compute(bench, emptyList(), cfg.barsPerYear, null), trades)
    }
}

object Metrics {
    fun returns(eq: List<Double>): DoubleArray = DoubleArray(max(eq.size - 1, 0)) { eq[it + 1] / eq[it] - 1 }

    fun maxDrawdown(eq: List<Double>): Double {
        var peak = Double.NEGATIVE_INFINITY; var dd = 0.0
        for (v in eq) { peak = max(peak, v); dd = max(dd, (peak - v) / peak) }
        return dd * 100
    }

    fun drawdownSeries(eq: List<Double>): List<Double> {
        var peak = Double.NEGATIVE_INFINITY
        return eq.map { peak = max(peak, it); -(peak - it) / peak * 100 }
    }

    fun compute(equity: List<Pair<Long, Double>>, trades: List<BtTrade>, barsPerYear: Double, bench: List<Pair<Long, Double>>?): PerfMetrics {
        val eq = equity.map { it.second }
        if (eq.size < 2) return PerfMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val r = returns(eq)
        val mean = r.average(); val sd = sqrt(r.map { (it - mean) * (it - mean) }.average())
        val downs = r.filter { it < 0 }
        val dsd = if (downs.isEmpty()) 0.0 else sqrt(downs.map { it * it }.sum() / r.size)
        val years = max((equity.last().first - equity.first().first) / (365.25 * 86400), 1.0 / barsPerYear)
        val total = eq.last() / eq.first() - 1
        val cagr = (eq.last() / eq.first()).pow(1 / years) - 1
        val mdd = maxDrawdown(eq)
        val wins = trades.filter { it.pnl > 0 }; val losses = trades.filter { it.pnl <= 0 }
        val gp = wins.sumOf { it.pnl }; val gl = -losses.sumOf { it.pnl }
        var alpha = 0.0; var beta = 0.0; var ir = 0.0
        if (bench != null && bench.size > 2) {
            val bmap = bench.toMap()
            val aligned = equity.mapNotNull { (t, v) -> bmap[t]?.let { v to it } }
            if (aligned.size > 2) {
                val rs = returns(aligned.map { it.first }); val rb = returns(aligned.map { it.second })
                val mb = rb.average(); val ms = rs.average()
                var cov = 0.0; var vb = 0.0
                for (k in rs.indices) { cov += (rs[k] - ms) * (rb[k] - mb); vb += (rb[k] - mb) * (rb[k] - mb) }
                beta = if (vb == 0.0) 0.0 else cov / vb
                alpha = (ms - beta * mb) * barsPerYear * 100
                val active = DoubleArray(rs.size) { rs[it] - rb[it] }
                val am = active.average(); val asd = sqrt(active.map { (it - am) * (it - am) }.average())
                ir = if (asd == 0.0) 0.0 else am / asd * sqrt(barsPerYear)
            }
        }
        return PerfMetrics(
            totalReturnPct = total * 100,
            cagrPct = cagr * 100,
            maxDrawdownPct = mdd,
            sharpe = if (sd == 0.0) 0.0 else mean / sd * sqrt(barsPerYear),
            sortino = if (dsd == 0.0) 0.0 else mean / dsd * sqrt(barsPerYear),
            calmar = if (mdd == 0.0) 0.0 else cagr * 100 / mdd,
            winRatePct = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            profitFactor = if (gl == 0.0) (if (gp > 0) Double.POSITIVE_INFINITY else 0.0) else gp / gl,
            expectancy = if (trades.isEmpty()) 0.0 else trades.sumOf { it.pnl } / trades.size,
            trades = trades.size,
            totalCharges = trades.sumOf { it.charges },
            alphaPct = alpha, beta = beta, informationRatio = ir,
        )
    }

    /** Calendar-month returns (yyyy, month1..12) → %. */
    fun monthly(equity: List<Pair<Long, Double>>): Map<Pair<Int, Int>, Double> {
        val out = linkedMapOf<Pair<Int, Int>, Double>()
        var prevEnd: Double? = null; var curKey: Pair<Int, Int>? = null; var last = 0.0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        for ((t, v) in equity) {
            cal.timeInMillis = t * 1000
            val k = cal.get(java.util.Calendar.YEAR) to (cal.get(java.util.Calendar.MONTH) + 1)
            if (k != curKey) {
                if (curKey != null) { out[curKey] = (last / (prevEnd ?: last) - 1) * 100; prevEnd = last }
                if (prevEnd == null) prevEnd = v
                curKey = k
            }
            last = v
        }
        if (curKey != null && prevEnd != null) out[curKey] = (last / prevEnd - 1) * 100
        return out
    }
}
