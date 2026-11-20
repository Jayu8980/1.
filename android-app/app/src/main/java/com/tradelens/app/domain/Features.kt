package com.tradelens.app.domain

import com.tradelens.app.domain.Indicators.lastValid
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Feature vector for on-device ML scoring. MUST stay identical to engine/tradelens/ml/features.py
 * (the engine exports the feature names with the ONNX model; unknown names score as 0).
 */
object Features {
    val NAMES = listOf("ret_1", "ret_5", "ret_20", "vol_20", "rsi_14", "macd_hist_norm", "adx_14", "atr_pct", "bb_pos", "ema_20_50", "price_ema_200", "vol_ratio_20")

    fun compute(c: List<Candle>): Map<String, Double> {
        if (c.size < 60) return emptyMap()
        val cl = Indicators.closes(c)
        val n = cl.size - 1
        fun ret(k: Int) = if (n - k >= 0) ln(cl[n] / cl[n - k]) else 0.0
        val lr = (1..20).map { ln(cl[n - it + 1] / cl[n - it]) }
        val mean = lr.average()
        val vol = sqrt(lr.sumOf { (it - mean) * (it - mean) } / lr.size)
        val atr = Indicators.atr(c, 14).lastValid()
        val bb = Indicators.bollinger(cl, 20, 2.0)
        val up = bb.upper.lastValid(); val lo = bb.lower.lastValid()
        val e20 = Indicators.ema(cl, 20).lastValid(); val e50 = Indicators.ema(cl, 50).lastValid()
        val e200 = Indicators.ema(cl, 200).lastValid()
        val vols = c.takeLast(21)
        val avgVol = vols.dropLast(1).map { it.volume }.average()
        return mapOf(
            "ret_1" to ret(1), "ret_5" to ret(5), "ret_20" to ret(20), "vol_20" to vol,
            "rsi_14" to Indicators.rsi(cl, 14).lastValid() / 100.0,
            "macd_hist_norm" to Indicators.macd(cl).hist.lastValid() / cl[n],
            "adx_14" to Indicators.adx(c, 14).adx.lastValid() / 100.0,
            "atr_pct" to atr / cl[n],
            "bb_pos" to if (up > lo) (cl[n] - lo) / (up - lo) else 0.5,
            "ema_20_50" to e20 / e50 - 1,
            "price_ema_200" to if (e200.isNaN()) 0.0 else cl[n] / e200 - 1,
            "vol_ratio_20" to if (avgVol > 0) c.last().volume / avgVol else 1.0,
        ).mapValues { if (it.value.isNaN() || it.value.isInfinite()) 0.0 else it.value }
    }
}
