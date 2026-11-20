package com.tradelens.app

import com.tradelens.app.domain.ChargeSegment
import com.tradelens.app.domain.Charges
import com.tradelens.app.domain.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargesTest {
    @Test fun deliveryBuyOneLakh() {
        val c = Charges.leg(ChargeSegment.EQ_DELIVERY, Side.BUY, 1000.0, 100.0)
        assertEquals(0.0, c.brokerage, 1e-9)
        assertEquals(100.0, c.stt, 1e-9)       // 0.1% of 1,00,000
        assertEquals(15.0, c.stamp, 1e-9)      // 0.015% buy
        assertEquals(2.97, c.exchange, 1e-6)
        assertEquals(0.10, c.sebi, 1e-9)
        assertEquals(0.18 * (2.97 + 0.10), c.gst, 1e-6)
        assertEquals(0.0, c.dp, 1e-9)
    }

    @Test fun deliverySellHasDpNoStamp() {
        val c = Charges.leg(ChargeSegment.EQ_DELIVERY, Side.SELL, 1000.0, 100.0)
        assertEquals(0.0, c.stamp, 1e-9); assertEquals(15.34, c.dp, 1e-9); assertEquals(100.0, c.stt, 1e-9)
    }

    @Test fun intradayBrokerageCappedAt20() {
        val big = Charges.leg(ChargeSegment.EQ_INTRADAY, Side.BUY, 1000.0, 1000.0)  // 10L turnover → 0.03% = 300 → capped 20
        assertEquals(20.0, big.brokerage, 1e-9)
        val small = Charges.leg(ChargeSegment.EQ_INTRADAY, Side.BUY, 100.0, 10.0)   // 1000 → 0.30
        assertEquals(0.30, small.brokerage, 1e-9)
        assertEquals(0.0, big.stt, 1e-9)
        assertEquals(250.0, Charges.leg(ChargeSegment.EQ_INTRADAY, Side.SELL, 1000.0, 1000.0).stt, 1e-9)
    }

    @Test fun optionsSttOnSellPremiumOnly() {
        val buy = Charges.leg(ChargeSegment.OPTIONS, Side.BUY, 100.0, 75.0)
        val sell = Charges.leg(ChargeSegment.OPTIONS, Side.SELL, 100.0, 75.0)
        assertEquals(0.0, buy.stt, 1e-9); assertEquals(8.0, sell.stt, 1e-9) // 0.1% of 7500 = 7.5 → rounded 8
        assertEquals(20.0, buy.brokerage, 1e-9)
    }

    @Test fun deltaFuturesTakerFeeWithGst() {
        val c = Charges.leg(ChargeSegment.CRYPTO_FUT, Side.BUY, 100_000.0, 0.01) // 1000 USD notional
        assertEquals(0.5, c.tradingFee, 1e-9); assertEquals(0.09, c.gst, 1e-9); assertEquals(0.59, c.total, 1e-9)
    }

    @Test fun deltaOptionFeeCappedByPremium() {
        // 1 BTC notional at 100k → 0.03% = 30; premium 100 → cap 10% = 10
        val c = Charges.leg(ChargeSegment.CRYPTO_OPT, Side.BUY, 100.0, 1.0, underlyingPrice = 100_000.0)
        assertEquals(10.0, c.tradingFee, 1e-9)
    }

    @Test fun roundTripPositive() {
        val rt = Charges.roundTrip(ChargeSegment.FUTURES, 22000.0, 22100.0, 75.0)
        assertTrue(rt.total > 40.0)
    }
}
