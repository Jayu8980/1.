package com.tradelens.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin technical indicators. Every function returns an array aligned with the input
 * (same length); warm-up values are Double.NaN.
 */
object Indicators {

    fun closes(c: List<Candle>) = DoubleArray(c.size) { c[it].close }

    fun sma(src: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        if (period <= 0 || src.size < period) return out
        var sum = 0.0
        for (i in src.indices) {
            sum += src[i]
            if (i >= period) sum -= src[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** EMA seeded with the SMA of the first [period] values. NaN inputs at the start are skipped. */
    fun ema(src: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        if (period <= 0) return out
        val start = src.indexOfFirst { !it.isNaN() }
        if (start < 0 || src.size - start < period) return out
        val k = 2.0 / (period + 1)
        var prev = 0.0
        for (i in start until start + period) prev += src[i]
        prev /= period
        out[start + period - 1] = prev
        for (i in start + period until src.size) {
            prev = src[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's smoothing (RMA), used by RSI / ATR / ADX. */
    fun rma(src: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        val start = src.indexOfFirst { !it.isNaN() }
        if (start < 0 || src.size - start < period) return out
        var prev = 0.0
        for (i in start until start + period) prev += src[i]
        prev /= period
        out[start + period - 1] = prev
        for (i in start + period until src.size) {
            prev = (prev * (period - 1) + src[i]) / period
            out[i] = prev
        }
        return out
    }

    fun stdev(src: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        val m = sma(src, period)
        for (i in period - 1 until src.size) {
            var s = 0.0
            for (j in i - period + 1..i) { val d = src[j] - m[i]; s += d * d }
            out[i] = sqrt(s / period)
        }
        return out
    }

    /** Session VWAP: resets each calendar day (IST for Indian instruments, UTC for crypto). */
    fun vwap(c: List<Candle>, utcOffsetSec: Long = 19800): DoubleArray {
        val out = DoubleArray(c.size) { Double.NaN }
        var pv = 0.0; var vol = 0.0; var day = Long.MIN_VALUE
        for (i in c.indices) {
            val d = Math.floorDiv(c[i].time + utcOffsetSec, 86400L)
            if (d != day) { day = d; pv = 0.0; vol = 0.0 }
            val tp = (c[i].high + c[i].low + c[i].close) / 3.0
            pv += tp * c[i].volume; vol += c[i].volume
            out[i] = if (vol > 0) pv / vol else tp
        }
        return out
    }

    fun rsi(src: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        if (src.size <= period) return out
        val gains = DoubleArray(src.size) { Double.NaN }
        val losses = DoubleArray(src.size) { Double.NaN }
        for (i in 1 until src.size) {
            val ch = src[i] - src[i - 1]
            gains[i] = max(ch, 0.0); losses[i] = max(-ch, 0.0)
        }
        val ag = rma(gains, period); val al = rma(losses, period)
        for (i in src.indices) {
            if (ag[i].isNaN() || al[i].isNaN()) continue
            out[i] = when {
                al[i] == 0.0 && ag[i] == 0.0 -> 50.0
                al[i] == 0.0 -> 100.0
                else -> 100.0 - 100.0 / (1.0 + ag[i] / al[i])
            }
        }
        return out
    }

    data class Macd(val macd: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(src: DoubleArray, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val f = ema(src, fast); val s = ema(src, slow)
        val m = DoubleArray(src.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val sig = ema(m, signal)
        val h = DoubleArray(src.size) { if (m[it].isNaN() || sig[it].isNaN()) Double.NaN else m[it] - sig[it] }
        return Macd(m, sig, h)
    }

    data class Stoch(val k: DoubleArray, val d: DoubleArray)

    fun stochastic(c: List<Candle>, kPeriod: Int = 14, smoothK: Int = 3, dPeriod: Int = 3): Stoch {
        val raw = DoubleArray(c.size) { Double.NaN }
        for (i in kPeriod - 1 until c.size) {
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - kPeriod + 1..i) { hh = max(hh, c[j].high); ll = min(ll, c[j].low) }
            raw[i] = if (hh == ll) 50.0 else 100.0 * (c[i].close - ll) / (hh - ll)
        }
        val k = smaSkipNaN(raw, smoothK)
        val d = smaSkipNaN(k, dPeriod)
        return Stoch(k, d)
    }

    private fun smaSkipNaN(src: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        for (i in src.indices) {
            if (i < p - 1) continue
            var s = 0.0; var ok = true
            for (j in i - p + 1..i) { if (src[j].isNaN()) { ok = false; break }; s += src[j] }
            if (ok) out[i] = s / p
        }
        return out
    }

    data class Bands(val upper: DoubleArray, val middle: DoubleArray, val lower: DoubleArray)

    fun bollinger(src: DoubleArray, period: Int = 20, mult: Double = 2.0): Bands {
        val m = sma(src, period); val sd = stdev(src, period)
        return Bands(
            DoubleArray(src.size) { m[it] + mult * sd[it] }, m,
            DoubleArray(src.size) { m[it] - mult * sd[it] },
        )
    }

    fun trueRange(c: List<Candle>): DoubleArray = DoubleArray(c.size) { i ->
        if (i == 0) c[0].high - c[0].low
        else max(c[i].high - c[i].low, max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close)))
    }

    fun atr(c: List<Candle>, period: Int = 14): DoubleArray = rma(trueRange(c), period)

    data class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    fun adx(c: List<Candle>, period: Int = 14): Adx {
        val n = c.size
        val pdm = DoubleArray(n) { Double.NaN }; val mdm = DoubleArray(n) { Double.NaN }
        val tr = DoubleArray(n) { Double.NaN }
        val trAll = trueRange(c)
        for (i in 1 until n) {
            val up = c[i].high - c[i - 1].high; val dn = c[i - 1].low - c[i].low
            pdm[i] = if (up > dn && up > 0) up else 0.0
            mdm[i] = if (dn > up && dn > 0) dn else 0.0
            tr[i] = trAll[i]
        }
        val str = rma(tr, period); val sp = rma(pdm, period); val sm = rma(mdm, period)
        val pdi = DoubleArray(n) { if (str[it].isNaN() || str[it] == 0.0) Double.NaN else 100 * sp[it] / str[it] }
        val mdi = DoubleArray(n) { if (str[it].isNaN() || str[it] == 0.0) Double.NaN else 100 * sm[it] / str[it] }
        val dx = DoubleArray(n) {
            if (pdi[it].isNaN()) Double.NaN else { val s = pdi[it] + mdi[it]; if (s == 0.0) 0.0 else 100 * abs(pdi[it] - mdi[it]) / s }
        }
        return Adx(rma(dx, period), pdi, mdi)
    }

    data class Supertrend(val line: DoubleArray, val upTrend: BooleanArray)

    fun supertrend(c: List<Candle>, period: Int = 10, mult: Double = 3.0): Supertrend {
        val n = c.size
        val a = atr(c, period)
        val line = DoubleArray(n) { Double.NaN }
        val up = BooleanArray(n) { true }
        var finalUpper = Double.NaN; var finalLower = Double.NaN
        for (i in c.indices) {
            if (a[i].isNaN()) continue
            val hl2 = (c[i].high + c[i].low) / 2
            val bu = hl2 + mult * a[i]; val bl = hl2 - mult * a[i]
            val prevClose = if (i > 0) c[i - 1].close else c[i].close
            finalUpper = if (finalUpper.isNaN() || bu < finalUpper || prevClose > finalUpper) bu else finalUpper
            finalLower = if (finalLower.isNaN() || bl > finalLower || prevClose < finalLower) bl else finalLower
            val prevUp = if (i > 0) up[i - 1] else true
            up[i] = when {
                prevUp && c[i].close < finalLower -> false
                !prevUp && c[i].close > finalUpper -> true
                else -> prevUp
            }
            line[i] = if (up[i]) finalLower else finalUpper
        }
        return Supertrend(line, up)
    }

    fun obv(c: List<Candle>): DoubleArray {
        val out = DoubleArray(c.size)
        for (i in 1 until c.size) {
            out[i] = out[i - 1] + when {
                c[i].close > c[i - 1].close -> c[i].volume
                c[i].close < c[i - 1].close -> -c[i].volume
                else -> 0.0
            }
        }
        return out
    }

    data class Ichimoku(val tenkan: DoubleArray, val kijun: DoubleArray, val spanA: DoubleArray, val spanB: DoubleArray, val chikou: DoubleArray)

    /** Spans are already shifted forward by [disp] bars (values beyond the last bar are dropped). */
    fun ichimoku(c: List<Candle>, tenkanP: Int = 9, kijunP: Int = 26, spanBP: Int = 52, disp: Int = 26): Ichimoku {
        val n = c.size
        fun mid(i: Int, p: Int): Double {
            if (i < p - 1) return Double.NaN
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - p + 1..i) { hh = max(hh, c[j].high); ll = min(ll, c[j].low) }
            return (hh + ll) / 2
        }
        val t = DoubleArray(n) { mid(it, tenkanP) }
        val k = DoubleArray(n) { mid(it, kijunP) }
        val a = DoubleArray(n) { Double.NaN }; val b = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val src = i - disp
            if (src >= 0) {
                a[i] = if (t[src].isNaN() || k[src].isNaN()) Double.NaN else (t[src] + k[src]) / 2
                b[i] = mid(src, spanBP)
            }
        }
        val ch = DoubleArray(n) { if (it + disp < n) c[it + disp].close else Double.NaN }
        return Ichimoku(t, k, a, b, ch)
    }

    data class Pivots(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

    /** Classic floor pivots from the previous period's H/L/C. */
    fun pivots(high: Double, low: Double, close: Double): Pivots {
        val p = (high + low + close) / 3
        return Pivots(
            p, 2 * p - low, p + (high - low), high + 2 * (p - low),
            2 * p - high, p - (high - low), low - 2 * (high - p),
        )
    }

    fun donchian(c: List<Candle>, period: Int): Pair<DoubleArray, DoubleArray> {
        val hi = DoubleArray(c.size) { Double.NaN }; val lo = DoubleArray(c.size) { Double.NaN }
        for (i in period - 1 until c.size) {
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) { hh = max(hh, c[j].high); ll = min(ll, c[j].low) }
            hi[i] = hh; lo[i] = ll
        }
        return hi to lo
    }

    fun DoubleArray.lastValid(): Double = (size - 1 downTo 0).firstOrNull { !this[it].isNaN() }?.let { this[it] } ?: Double.NaN
}
