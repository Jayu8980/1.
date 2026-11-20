package com.tradelens.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Bias { BULLISH, BEARISH, NEUTRAL }

data class PatternHit(
    val name: String,
    val bias: Bias,
    val index: Int,          // bar index where the pattern completes
    val strength: Double,    // 0..1
    val detail: String = "",
)

data class Zone(val low: Double, val high: Double, val touches: Int, val isSupport: Boolean) {
    val mid get() = (low + high) / 2
}

data class Pivot(val index: Int, val price: Double, val isHigh: Boolean)

/** Automatic pattern detection on OHLC candles. */
object Patterns {

    // ---------- Candlestick patterns ----------

    fun candlestick(c: List<Candle>, lookback: Int = 30): List<PatternHit> {
        val out = mutableListOf<PatternHit>()
        if (c.size < 3) return out
        val atr = Indicators.atr(c, 14)
        val sma20 = Indicators.sma(Indicators.closes(c), 20)
        val from = max(2, c.size - lookback)
        for (i in from until c.size) {
            val x = c[i]; val p = c[i - 1]; val pp = c[i - 2]
            val range = x.high - x.low
            if (range <= 0) continue
            val body = abs(x.close - x.open)
            val upper = x.high - max(x.open, x.close)
            val lower = min(x.open, x.close) - x.low
            val a = if (atr[i].isNaN()) range else atr[i]
            val downtrend = !sma20[i].isNaN() && x.close < sma20[i]
            val uptrend = !sma20[i].isNaN() && x.close > sma20[i]
            val pBody = abs(p.close - p.open)

            if (body <= 0.1 * range && range > 0.3 * a) out += PatternHit("Doji", Bias.NEUTRAL, i, 0.4, "Indecision")
            if (lower >= 2 * body && upper <= 0.3 * max(body, range * 0.1) && body > 0) {
                if (downtrend) out += PatternHit("Hammer", Bias.BULLISH, i, 0.6, "Long lower wick after decline")
                else if (uptrend) out += PatternHit("Hanging Man", Bias.BEARISH, i, 0.5, "Long lower wick after advance")
            }
            if (upper >= 2 * body && lower <= 0.3 * max(body, range * 0.1) && body > 0) {
                if (uptrend) out += PatternHit("Shooting Star", Bias.BEARISH, i, 0.6, "Long upper wick after advance")
                else if (downtrend) out += PatternHit("Inverted Hammer", Bias.BULLISH, i, 0.45, "Long upper wick after decline")
            }
            if (p.close < p.open && x.close > x.open && x.close >= p.open && x.open <= p.close && body > pBody)
                out += PatternHit("Bullish Engulfing", Bias.BULLISH, i, if (downtrend) 0.75 else 0.55)
            if (p.close > p.open && x.close < x.open && x.open >= p.close && x.close <= p.open && body > pBody)
                out += PatternHit("Bearish Engulfing", Bias.BEARISH, i, if (uptrend) 0.75 else 0.55)
            if (p.close < p.open && x.close > x.open && x.open < p.low && x.close > (p.open + p.close) / 2 && x.close < p.open)
                out += PatternHit("Piercing Line", Bias.BULLISH, i, 0.6)
            if (p.close > p.open && x.close < x.open && x.open > p.high && x.close < (p.open + p.close) / 2 && x.close > p.open)
                out += PatternHit("Dark Cloud Cover", Bias.BEARISH, i, 0.6)
            if (p.close < p.open && x.close > x.open && x.high < p.open && x.low > p.close)
                out += PatternHit("Bullish Harami", Bias.BULLISH, i, 0.45)
            if (p.close > p.open && x.close < x.open && x.high < p.close && x.low > p.open)
                out += PatternHit("Bearish Harami", Bias.BEARISH, i, 0.45)

            val ppBody = abs(pp.close - pp.open); val ppRange = pp.high - pp.low
            val pRange = p.high - p.low
            if (pp.close < pp.open && ppBody > 0.6 * ppRange && pBody < 0.3 * max(pRange, 1e-9) &&
                x.close > x.open && x.close > (pp.open + pp.close) / 2)
                out += PatternHit("Morning Star", Bias.BULLISH, i, 0.8)
            if (pp.close > pp.open && ppBody > 0.6 * ppRange && pBody < 0.3 * max(pRange, 1e-9) &&
                x.close < x.open && x.close < (pp.open + pp.close) / 2)
                out += PatternHit("Evening Star", Bias.BEARISH, i, 0.8)
            if (pp.close > pp.open && p.close > p.open && x.close > x.open && p.close > pp.close && x.close > p.close &&
                p.open > pp.open && x.open > p.open && body > 0.5 * range)
                out += PatternHit("Three White Soldiers", Bias.BULLISH, i, 0.7)
            if (pp.close < pp.open && p.close < p.open && x.close < x.open && p.close < pp.close && x.close < p.close &&
                p.open < pp.open && x.open < p.open && body > 0.5 * range)
                out += PatternHit("Three Black Crows", Bias.BEARISH, i, 0.7)
        }
        return out
    }

    // ---------- Swing pivots, S/R zones, breakouts ----------

    fun pivots(c: List<Candle>, left: Int = 5, right: Int = 5): List<Pivot> {
        val out = mutableListOf<Pivot>()
        for (i in left until c.size - right) {
            var isH = true; var isL = true
            for (j in i - left..i + right) {
                if (j == i) continue
                if (c[j].high >= c[i].high) isH = false
                if (c[j].low <= c[i].low) isL = false
            }
            if (isH) out += Pivot(i, c[i].high, true)
            if (isL) out += Pivot(i, c[i].low, false)
        }
        return out
    }

    /** Clusters swing points within 0.5×ATR into zones; returns strongest zones sorted by price. */
    fun supportResistance(c: List<Candle>, maxZones: Int = 6): List<Zone> {
        if (c.size < 20) return emptyList()
        val atr = Indicators.atr(c, 14).let { a -> a.lastOrNull { !it.isNaN() } ?: (c.last().high - c.last().low) }
        val tol = max(atr * 0.5, c.last().close * 0.002)
        val pts = pivots(c, 3, 3).map { it.price }.sorted()
        val clusters = mutableListOf<MutableList<Double>>()
        for (p in pts) {
            val last = clusters.lastOrNull()
            if (last != null && p - last.first() <= tol * 2) last += p else clusters += mutableListOf(p)
        }
        val price = c.last().close
        return clusters.filter { it.size >= 2 }
            .sortedByDescending { it.size }
            .take(maxZones)
            .map { cl -> Zone(cl.min(), cl.max(), cl.size, cl.average() < price) }
            .sortedBy { it.mid }
    }

    fun breakouts(c: List<Candle>, zones: List<Zone>): List<PatternHit> {
        if (c.size < 25) return emptyList()
        val i = c.size - 1
        val x = c[i]; val p = c[i - 1]
        val avgVol = (i - 20 until i).map { c[it].volume }.average()
        val volOk = avgVol <= 0 || x.volume > 1.5 * avgVol
        val out = mutableListOf<PatternHit>()
        for (z in zones) {
            if (p.close <= z.high && x.close > z.high)
                out += PatternHit("Breakout above ${fmt(z.high)}", Bias.BULLISH, i, if (volOk) 0.75 else 0.5,
                    if (volOk) "Volume confirmed" else "Low volume – may fail")
            if (p.close >= z.low && x.close < z.low)
                out += PatternHit("Breakdown below ${fmt(z.low)}", Bias.BEARISH, i, if (volOk) 0.75 else 0.5,
                    if (volOk) "Volume confirmed" else "Low volume – may fail")
        }
        val (dh, dl) = Indicators.donchian(c.subList(0, i), 20)
        if (dh.isNotEmpty() && !dh.last().isNaN()) {
            if (x.close > dh.last()) out += PatternHit("20-bar high breakout", Bias.BULLISH, i, 0.55)
            if (x.close < dl.last()) out += PatternHit("20-bar low breakdown", Bias.BEARISH, i, 0.55)
        }
        return out
    }

    // ---------- Chart patterns ----------

    fun chartPatterns(c: List<Candle>): List<PatternHit> {
        if (c.size < 40) return emptyList()
        val piv = pivots(c, 4, 4)
        val highs = piv.filter { it.isHigh }; val lows = piv.filter { !it.isHigh }
        val atr = Indicators.atr(c, 14).let { a -> a.lastOrNull { !it.isNaN() } ?: 1.0 }
        val tol = atr * 0.8
        val last = c.size - 1
        val price = c.last().close
        val out = mutableListOf<PatternHit>()

        if (highs.size >= 2) {
            val (h1, h2) = highs.takeLast(2)
            val trough = c.subList(h1.index, h2.index + 1).minOf { it.low }
            if (abs(h1.price - h2.price) <= tol && h2.index - h1.index >= 5 && (min(h1.price, h2.price) - trough) > 2 * atr) {
                val confirmed = price < trough
                out += PatternHit("Double Top", Bias.BEARISH, last, if (confirmed) 0.8 else 0.5,
                    "Peaks ${fmt(h1.price)} / ${fmt(h2.price)}, neckline ${fmt(trough)}${if (confirmed) " – broken" else ""}")
            }
        }
        if (lows.size >= 2) {
            val (l1, l2) = lows.takeLast(2)
            val peak = c.subList(l1.index, l2.index + 1).maxOf { it.high }
            if (abs(l1.price - l2.price) <= tol && l2.index - l1.index >= 5 && (peak - max(l1.price, l2.price)) > 2 * atr) {
                val confirmed = price > peak
                out += PatternHit("Double Bottom", Bias.BULLISH, last, if (confirmed) 0.8 else 0.5,
                    "Lows ${fmt(l1.price)} / ${fmt(l2.price)}, neckline ${fmt(peak)}${if (confirmed) " – broken" else ""}")
            }
        }
        if (highs.size >= 3) {
            val (ls, hd, rs) = highs.takeLast(3)
            if (hd.price > ls.price + tol && hd.price > rs.price + tol && abs(ls.price - rs.price) <= tol * 1.5) {
                val neck = c.subList(ls.index, rs.index + 1).minOf { it.low }
                out += PatternHit("Head & Shoulders", Bias.BEARISH, last, if (price < neck) 0.85 else 0.55, "Neckline ${fmt(neck)}")
            }
        }
        if (lows.size >= 3) {
            val (ls, hd, rs) = lows.takeLast(3)
            if (hd.price < ls.price - tol && hd.price < rs.price - tol && abs(ls.price - rs.price) <= tol * 1.5) {
                val neck = c.subList(ls.index, rs.index + 1).maxOf { it.high }
                out += PatternHit("Inverse Head & Shoulders", Bias.BULLISH, last, if (price > neck) 0.85 else 0.55, "Neckline ${fmt(neck)}")
            }
        }
        // Triangles from the last 3 swing highs/lows
        if (highs.size >= 3 && lows.size >= 3) {
            val hs = highs.takeLast(3); val ls = lows.takeLast(3)
            val hSlope = slope(hs); val lSlope = slope(ls)
            val flat = atr * 0.05
            when {
                abs(hSlope) < flat && lSlope > flat -> out += PatternHit("Ascending Triangle", Bias.BULLISH, last, 0.6, "Flat top ~${fmt(hs.map { it.price }.average())}")
                hSlope < -flat && abs(lSlope) < flat -> out += PatternHit("Descending Triangle", Bias.BEARISH, last, 0.6, "Flat base ~${fmt(ls.map { it.price }.average())}")
                hSlope < -flat && lSlope > flat -> out += PatternHit("Symmetrical Triangle", Bias.NEUTRAL, last, 0.5, "Contracting range – wait for breakout")
            }
        }
        return out
    }

    private fun slope(p: List<Pivot>): Double {
        val n = p.size
        val mx = p.map { it.index.toDouble() }.average(); val my = p.map { it.price }.average()
        var num = 0.0; var den = 0.0
        for (q in p) { num += (q.index - mx) * (q.price - my); den += (q.index - mx) * (q.index - mx) }
        return if (den == 0.0 || n < 2) 0.0 else num / den
    }

    // ---------- Divergences ----------

    fun divergences(c: List<Candle>): List<PatternHit> {
        if (c.size < 50) return emptyList()
        val closes = Indicators.closes(c)
        val rsi = Indicators.rsi(closes, 14)
        val macd = Indicators.macd(closes).hist
        val piv = pivots(c, 3, 3).filter { it.index >= c.size - 60 }
        val out = mutableListOf<PatternHit>()
        val lows = piv.filter { !it.isHigh }.takeLast(2)
        val highs = piv.filter { it.isHigh }.takeLast(2)
        fun check(name: String, osc: DoubleArray) {
            if (lows.size == 2) {
                val (a, b) = lows
                if (!osc[a.index].isNaN() && !osc[b.index].isNaN() && b.price < a.price && osc[b.index] > osc[a.index])
                    out += PatternHit("Bullish $name divergence", Bias.BULLISH, b.index, 0.65, "Lower low in price, higher low in $name")
            }
            if (highs.size == 2) {
                val (a, b) = highs
                if (!osc[a.index].isNaN() && !osc[b.index].isNaN() && b.price > a.price && osc[b.index] < osc[a.index])
                    out += PatternHit("Bearish $name divergence", Bias.BEARISH, b.index, 0.65, "Higher high in price, lower high in $name")
            }
        }
        check("RSI", rsi); check("MACD", macd)
        return out
    }

    fun all(c: List<Candle>): List<PatternHit> {
        val zones = supportResistance(c)
        return candlestick(c, 5) + breakouts(c, zones) + chartPatterns(c) + divergences(c)
    }

    internal fun fmt(d: Double) = if (d >= 1000) "%.0f".format(d) else "%.2f".format(d)
}
