package com.tradelens.app

import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.Indicators
import com.tradelens.app.domain.Indicators.lastValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

fun series(n: Int, f: (Int) -> Double) = List(n) { i -> val c = f(i); Candle(1_700_000_000L + i * 86400L, c - 0.5, c + 1, c - 1, c, 1000.0 + i) }

class IndicatorsTest {
    private val src = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    @Test fun smaBasic() {
        val s = Indicators.sma(src, 3)
        assertTrue(s[1].isNaN()); assertEquals(2.0, s[2], 1e-9); assertEquals(9.0, s[9], 1e-9)
    }

    @Test fun emaSeededWithSma() {
        val e = Indicators.ema(src, 3)
        assertEquals(2.0, e[2], 1e-9)          // SMA seed of 1,2,3
        assertEquals(3.0, e[3], 1e-9)          // 4*0.5 + 2*0.5
        assertEquals(9.0, e[9], 1e-9)          // linear series converges to lagged value
    }

    @Test fun rsiExtremes() {
        val up = DoubleArray(30) { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(up, 14).last(), 1e-9)
        val down = DoubleArray(30) { 100.0 - it }
        assertEquals(0.0, Indicators.rsi(down, 14).last(), 1e-9)
        val flat = DoubleArray(30) { 5.0 }
        assertEquals(50.0, Indicators.rsi(flat, 14).last(), 1e-9)
    }

    @Test fun rsiWilderReference() {
        // Classic Wilder example closes; RSI(14) at bar 15 ≈ 70.53
        val c = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00)
        assertEquals(70.46, Indicators.rsi(c, 14)[14], 0.2)
    }

    @Test fun macdOfConstantIsZero() {
        val m = Indicators.macd(DoubleArray(60) { 10.0 })
        assertEquals(0.0, m.macd.lastValid(), 1e-9); assertEquals(0.0, m.hist.lastValid(), 1e-9)
    }

    @Test fun bollingerWidthMatchesStdev() {
        val b = Indicators.bollinger(src, 5, 2.0)
        val sd = Indicators.stdev(src, 5)[9]
        assertEquals(4 * sd, b.upper[9] - b.lower[9], 1e-9)
        assertEquals(8.0, b.middle[9], 1e-9)
    }

    @Test fun atrOfConstantRangeBars() {
        val c = series(40) { 100.0 }
        assertEquals(2.0, Indicators.atr(c, 14).last(), 1e-9)
    }

    @Test fun adxHighInStrongTrend() {
        val c = series(120) { 100.0 + it * 2 }
        val a = Indicators.adx(c, 14)
        assertTrue(a.adx.last() > 40); assertTrue(a.plusDi.last() > a.minusDi.last())
    }

    @Test fun supertrendFlipsWithTrend() {
        val up = series(60) { 100.0 + it }
        assertTrue(Indicators.supertrend(up).upTrend.last())
        val down = series(60) { 200.0 - it * 2 }
        assertFalse(Indicators.supertrend(down).upTrend.last())
    }

    @Test fun obvAccumulates() {
        val c = series(5) { it.toDouble() + 10 }
        val o = Indicators.obv(c)
        assertEquals(c[1].volume + c[2].volume + c[3].volume + c[4].volume, o[4], 1e-9)
    }

    @Test fun stochasticBounds() {
        val c = series(80) { 100 + 10 * sin(it / 5.0) }
        val k = Indicators.stochastic(c).k.filter { !it.isNaN() }
        assertTrue(k.all { it in 0.0..100.0 })
    }

    @Test fun vwapResetsDaily() {
        val day = 86400L
        val c = listOf(
            Candle(day * 100 - 19800, 10.0, 10.0, 10.0, 10.0, 100.0), Candle(day * 100 - 19800 + 300, 20.0, 20.0, 20.0, 20.0, 100.0),
            Candle(day * 101 - 19800, 30.0, 30.0, 30.0, 30.0, 100.0),
        )
        val v = Indicators.vwap(c)
        assertEquals(15.0, v[1], 1e-9); assertEquals(30.0, v[2], 1e-9)
    }

    @Test fun ichimokuShiftedSpans() {
        val c = series(120) { 100.0 + it }
        val ich = Indicators.ichimoku(c)
        assertTrue(ich.spanA[30].isNaN() || ich.spanA[30] > 0)
        assertFalse(ich.spanA[119].isNaN()); assertFalse(ich.spanB[119].isNaN())
        assertTrue(ich.chikou[119].isNaN())
    }

    @Test fun classicPivots() {
        val p = Indicators.pivots(110.0, 90.0, 100.0)
        assertEquals(100.0, p.p, 1e-9); assertEquals(110.0, p.r1, 1e-9); assertEquals(90.0, p.s1, 1e-9); assertEquals(120.0, p.r2, 1e-9)
    }
}
