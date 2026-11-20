package com.tradelens.app

import com.tradelens.app.data.paper.PaperRules
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.Risk
import com.tradelens.app.domain.RiskDecision
import com.tradelens.app.domain.RiskGate
import com.tradelens.app.domain.RiskLimits
import com.tradelens.app.domain.RiskState
import com.tradelens.app.domain.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTest {
    private val limits = RiskLimits(dailyLossPct = 2.0, maxDrawdownPct = 10.0, maxOrdersPerDay = 5, maxPositionPct = 10.0)

    @Test fun positionSizeFromRisk() {
        val p = Risk.positionSize(1_000_000.0, 1.0, 100.0, true, stop = 95.0, maxPositionPct = 100.0)
        assertEquals(2000.0, p.qty, 1e-9); assertEquals(10_000.0, p.riskAmount, 1e-9)
    }

    @Test fun positionSizeCappedAndLotRounded() {
        val p = Risk.positionSize(1_000_000.0, 1.0, 100.0, true, atr = 1.0, atrMult = 2.0, lotSize = 75, maxPositionPct = 10.0)
        assertEquals(1000.0 - 1000.0 % 75, p.qty, 1e-9)      // 10% cap = 1000 units → 13 lots of 75
        assertEquals(98.0, p.stop, 1e-9)
        assertTrue(p.cappedBy != null)
    }

    @Test fun liquidation() {
        assertEquals(100.0 * (1 - 0.1 + 0.005), Risk.liquidationPrice(100.0, 10.0, true), 1e-9)
        assertEquals(100.0 * (1 + 0.1 - 0.005), Risk.liquidationPrice(100.0, 10.0, false), 1e-9)
    }

    @Test fun kellyCapped() {
        assertTrue(Risk.kellyFraction(0.6, 2.0, 1.0) <= 0.25 * (0.6 - 0.4 / 2) + 1e-9)
        assertEquals(0.0, Risk.kellyFraction(0.3, 1.0), 1e-9)
    }

    @Test fun dailyLossHalts() {
        val d = RiskGate.evaluateEquity(RiskState(100.0, 100.0, 97.9), limits)
        assertTrue(d is RiskDecision.FlattenAndHalt && !d.requireManualRestart)
    }

    @Test fun drawdownBreakerNeedsManualRestart() {
        val d = RiskGate.evaluateEquity(RiskState(90.0, 100.0, 89.0), limits)
        assertTrue(d is RiskDecision.FlattenAndHalt && d.requireManualRestart)
    }

    @Test fun blocksWhenKilledHaltedOrOverLimits() {
        val ok = RiskState(1000.0, 1000.0, 1000.0)
        assertTrue(RiskGate.checkNewOrder(ok.copy(globalKill = true), limits, 10.0) is RiskDecision.Block)
        assertTrue(RiskGate.checkNewOrder(ok.copy(haltedForDay = true), limits, 10.0) is RiskDecision.Block)
        assertTrue(RiskGate.checkNewOrder(ok.copy(haltedForDay = true), limits, 10.0, isRiskReducing = true) is RiskDecision.Allow)
        assertTrue(RiskGate.checkNewOrder(ok.copy(ordersToday = 5), limits, 10.0) is RiskDecision.Block)
        assertTrue(RiskGate.checkNewOrder(ok, limits, 150.0) is RiskDecision.Block)
        assertTrue(RiskGate.checkNewOrder(ok, limits, 50.0) is RiskDecision.Allow)
        assertTrue(RiskGate.checkNewOrder(ok, limits, 50.0, marginUsedAfter = 600.0, marginTotal = 1000.0) is RiskDecision.Block)
    }

    @Test fun paperFillRules() {
        assertNull(PaperRules.fillPrice(OrderType.LIMIT, Side.BUY, 101.0, 100.0, null))
        assertEquals(100.0, PaperRules.fillPrice(OrderType.LIMIT, Side.BUY, 99.0, 100.0, null)!!, 1e-9)
        assertNull(PaperRules.fillPrice(OrderType.SL_M, Side.SELL, 96.0, null, 95.0))
        assertEquals(94.0 * (1 - 0.0005), PaperRules.fillPrice(OrderType.SL_M, Side.SELL, 94.0, null, 95.0)!!, 1e-9)
        assertEquals(100.0 * 1.0005, PaperRules.fillPrice(OrderType.MARKET, Side.BUY, 100.0, null, null)!!, 1e-9)
    }
}
