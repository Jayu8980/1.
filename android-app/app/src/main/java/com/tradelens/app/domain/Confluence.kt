package com.tradelens.app.domain

import com.tradelens.app.domain.Indicators.lastValid
import kotlin.math.roundToInt

data class ConfluenceFactor(val name: String, val score: Double, val weight: Double, val reason: String)

data class TimeframeScore(val timeframe: Timeframe, val score: Int, val factors: List<ConfluenceFactor>)

data class ConfluenceResult(val score: Int, val bias: Bias, val perTimeframe: List<TimeframeScore>) {
    val label: String get() = when {
        score >= 75 -> "Strong bullish"
        score >= 60 -> "Bullish"
        score > 40 -> "Neutral"
        score > 25 -> "Bearish"
        else -> "Strong bearish"
    }
}

/**
 * Multi-timeframe confluence. Each factor scores -1 (fully bearish) .. +1 (fully bullish); the weighted
 * mean is mapped to 0..100 where 50 is neutral.
 */
object Confluence {

    val DEFAULT_WEIGHTS = mapOf(Timeframe.M15 to 0.2, Timeframe.H1 to 0.3, Timeframe.D1 to 0.5)

    fun scoreTimeframe(tf: Timeframe, c: List<Candle>): TimeframeScore {
        val f = mutableListOf<ConfluenceFactor>()
        if (c.size < 30) return TimeframeScore(tf, 50, listOf(ConfluenceFactor("Data", 0.0, 1.0, "Not enough bars (${c.size})")))
        val cl = Indicators.closes(c)
        val price = cl.last()
        val e20 = Indicators.ema(cl, 20).lastValid()
        val e50 = Indicators.ema(cl, 50).lastValid()
        val e200 = Indicators.ema(cl, 200).lastValid()

        var trend = 0.0; val parts = mutableListOf<String>()
        if (!e20.isNaN()) { trend += if (price > e20) 1.0 else -1.0; parts += "price ${if (price > e20) ">" else "<"} EMA20" }
        if (!e50.isNaN()) { trend += if (e20 > e50) 1.0 else -1.0; parts += "EMA20 ${if (e20 > e50) ">" else "<"} EMA50" }
        if (!e200.isNaN()) { trend += if (price > e200) 1.0 else -1.0; parts += "price ${if (price > e200) ">" else "<"} EMA200" }
        val trendN = if (!e200.isNaN()) 3.0 else if (!e50.isNaN()) 2.0 else 1.0
        f += ConfluenceFactor("Trend (EMA stack)", trend / trendN, 0.3, parts.joinToString(", "))

        val rsi = Indicators.rsi(cl, 14).lastValid()
        if (!rsi.isNaN()) {
            val s = when {
                rsi > 70 -> 0.3   // strong but stretched
                rsi > 55 -> 0.8
                rsi >= 45 -> 0.0
                rsi >= 30 -> -0.8
                else -> -0.3      // oversold: weak but bounce-prone
            }
            f += ConfluenceFactor("RSI(14)", s, 0.15, "RSI %.1f%s".format(rsi, if (rsi > 70) " overbought" else if (rsi < 30) " oversold" else ""))
        }

        val m = Indicators.macd(cl)
        val h = m.hist.lastValid(); val hPrev = m.hist.getOrNull(m.hist.size - 2) ?: Double.NaN
        if (!h.isNaN()) {
            val rising = !hPrev.isNaN() && h > hPrev
            val s = (if (h > 0) 0.6 else -0.6) + (if (rising) 0.4 else -0.4)
            f += ConfluenceFactor("MACD", s, 0.15, "Histogram ${"%.3f".format(h)} ${if (rising) "rising" else "falling"}")
        }

        val st = Indicators.supertrend(c)
        f += ConfluenceFactor("Supertrend(10,3)", if (st.upTrend.last()) 1.0 else -1.0, 0.15, if (st.upTrend.last()) "Uptrend" else "Downtrend")

        val adx = Indicators.adx(c)
        val a = adx.adx.lastValid(); val pdi = adx.plusDi.lastValid(); val mdi = adx.minusDi.lastValid()
        if (!a.isNaN()) {
            val dir = if (pdi > mdi) 1.0 else -1.0
            val strength = (a / 40.0).coerceIn(0.0, 1.0)
            f += ConfluenceFactor("ADX(14)", dir * strength, 0.1, "ADX %.1f, +DI %.1f vs -DI %.1f".format(a, pdi, mdi))
        }

        val pats = Patterns.all(c).filter { it.index >= c.size - 3 }
        if (pats.isNotEmpty()) {
            val s = pats.sumOf { (if (it.bias == Bias.BULLISH) 1 else if (it.bias == Bias.BEARISH) -1 else 0) * it.strength } / pats.size
            f += ConfluenceFactor("Patterns", s.coerceIn(-1.0, 1.0), 0.15, pats.joinToString { it.name })
        }

        val wSum = f.sumOf { it.weight }
        val raw = f.sumOf { it.score * it.weight } / wSum
        return TimeframeScore(tf, ((raw + 1) * 50).roundToInt().coerceIn(0, 100), f)
    }

    fun combine(perTf: List<TimeframeScore>, weights: Map<Timeframe, Double> = DEFAULT_WEIGHTS): ConfluenceResult {
        if (perTf.isEmpty()) return ConfluenceResult(50, Bias.NEUTRAL, emptyList())
        var w = 0.0; var s = 0.0
        for (t in perTf) { val wt = weights[t.timeframe] ?: (1.0 / perTf.size); w += wt; s += wt * t.score }
        val score = (s / w).roundToInt()
        val bias = when { score >= 60 -> Bias.BULLISH; score <= 40 -> Bias.BEARISH; else -> Bias.NEUTRAL }
        return ConfluenceResult(score, bias, perTf)
    }
}
