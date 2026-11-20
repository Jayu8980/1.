package com.tradelens.app

import com.tradelens.app.data.broker.UpstoxBroker
import com.tradelens.app.data.broker.resampleWeekly
import com.tradelens.app.data.delta.DeltaClient
import com.tradelens.app.domain.BacktestConfig
import com.tradelens.app.domain.Backtester
import com.tradelens.app.domain.BlackScholes
import com.tradelens.app.domain.ChargeSegment
import com.tradelens.app.domain.Confluence
import com.tradelens.app.domain.Metrics
import com.tradelens.app.domain.OptionAnalytics
import com.tradelens.app.domain.OptionQuote
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Patterns
import com.tradelens.app.domain.Rules
import com.tradelens.app.domain.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class BacktestAndRulesTest {
    @Test fun parsesAndEvaluatesRules() {
        val rs = Rules.parse("RSI(14) < 30 AND CLOSE > EMA(200) OR CLOSE CROSSES_ABOVE SMA(5)")
        assertEquals(3, rs.conditions.size); assertEquals(listOf("AND", "OR"), rs.joins)
        val up = series(260) { 100.0 + it }
        val ev = Rules.Evaluator(up)
        assertTrue(ev.eval(Rules.parse("CLOSE > EMA(200)"), 259))
        assertTrue(!ev.eval(Rules.parse("RSI(14) < 30"), 259))
    }

    @Test fun backtestNoLookaheadAndChargesApplied() {
        val c = series(600) { 100 + 20 * sin(it / 15.0) + it * 0.05 }
        val cfg = BacktestConfig(Rules.parse("EMA(5) CROSSES_ABOVE EMA(20)"), Rules.parse("EMA(5) CROSSES_BELOW EMA(20)"), segment = ChargeSegment.EQ_DELIVERY, targetR = null)
        val r = Backtester.run(c, cfg)
        assertTrue(r.trades.isNotEmpty())
        // entries happen at the open of the bar AFTER the signal
        r.trades.forEach { t -> assertTrue(c.any { it.time == t.entryTime }) }
        assertTrue(r.metrics.totalCharges > 0)
        val gross = r.trades.sumOf { (it.exit - it.entry) * it.qty }
        assertEquals(r.equity.last().second - cfg.initialCapital, gross - r.metrics.totalCharges, 1.0)
    }

    @Test fun metricsDrawdown() {
        assertEquals(50.0, Metrics.maxDrawdown(listOf(100.0, 200.0, 100.0, 150.0)), 1e-9)
    }

    @Test fun blackScholesPutCallParity() {
        val s = 100.0; val k = 105.0; val t = 0.5; val v = 0.25; val r = 0.06
        val c = BlackScholes.greeks(OptionType.CE, s, k, t, v, r).price
        val p = BlackScholes.greeks(OptionType.PE, s, k, t, v, r).price
        assertEquals(s - k * Math.exp(-r * t), c - p, 1e-4)
        val iv = BlackScholes.impliedVol(OptionType.CE, c, s, k, t, r)!!
        assertEquals(v, iv, 1e-4)
    }

    @Test fun maxPainAndPcr() {
        val rows = listOf(
            OptionQuote(100.0, OptionType.CE, 5.0, 1000.0, 0.0, 0.0), OptionQuote(110.0, OptionType.CE, 2.0, 5000.0, 0.0, 0.0),
            OptionQuote(100.0, OptionType.PE, 3.0, 4000.0, 0.0, 0.0), OptionQuote(90.0, OptionType.PE, 1.0, 2000.0, 0.0, 0.0),
        )
        assertEquals(1.0, OptionAnalytics.pcr(rows), 1e-9)
        assertEquals(100.0, OptionAnalytics.maxPain(rows)!!, 1e-9)
    }

    @Test fun deltaSignatureHex() {
        // HMAC-SHA256("secret", "GET1700000000/v2/orders?state=open")
        val sig = DeltaClient.sign("secret", "GET1700000000/v2/orders?state=open")
        assertEquals(64, sig.length)
        assertEquals(sig, DeltaClient.sign("secret", "GET1700000000/v2/orders?state=open"))
        assertTrue(sig != DeltaClient.sign("secret", "GET1700000001/v2/orders?state=open"))
    }

    @Test fun upstoxProtoDecodesLtpc() {
        fun varint(v: Int) = if (v < 128) byteArrayOf(v.toByte()) else byteArrayOf(((v and 0x7f) or 0x80).toByte(), (v shr 7).toByte())
        fun dbl(tag: Int, d: Double): ByteArray { val l = java.lang.Double.doubleToLongBits(d); return byteArrayOf(((tag shl 3) or 1).toByte()) + ByteArray(8) { ((l shr (8 * it)) and 0xff).toByte() } }
        fun msg(tag: Int, body: ByteArray) = byteArrayOf(((tag shl 3) or 2).toByte()) + varint(body.size) + body
        val key = "NSE_EQ|INE002A01018".toByteArray()
        val ltpc = dbl(1, 2950.5) + dbl(4, 2900.0)
        val feed = msg(1, ltpc)
        val entry = msg(1, key) + msg(2, feed)
        val resp = msg(2, entry)
        val out = UpstoxBroker.decodeFeed(resp)
        assertEquals(1, out.size); assertEquals("NSE_EQ|INE002A01018", out[0].first); assertEquals(2950.5, out[0].second, 1e-9); assertEquals(2900.0, out[0].third, 1e-9)
    }

    @Test fun weeklyResample() {
        val d = series(14) { 100.0 + it }
        val w = resampleWeekly(d)
        assertTrue(w.size in 2..3)
        assertEquals(d.sumOf { it.volume }, w.sumOf { it.volume }, 1e-9)
    }

    @Test fun patternsAndConfluence() {
        val c = series(300) { 100 + 15 * sin(it / 10.0) }   // range-bound: repeated swing highs/lows
        val z = Patterns.supportResistance(c)
        assertTrue(z.any { it.isSupport } && z.any { !it.isSupport })
        val tf = Confluence.scoreTimeframe(Timeframe.D1, c)
        assertTrue(tf.score in 0..100)
        val strongUp = Confluence.scoreTimeframe(Timeframe.D1, series(300) { 100.0 + it * 1.5 })
        assertTrue(strongUp.score > 60)
        val strongDown = Confluence.scoreTimeframe(Timeframe.D1, series(300) { 600.0 - it * 1.5 })
        assertTrue(strongDown.score < 40)
    }
}
