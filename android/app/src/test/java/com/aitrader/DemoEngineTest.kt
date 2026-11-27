package com.aitrader

import com.aitrader.data.demo.DemoEngine
import com.aitrader.data.model.BacktestRequest
import com.aitrader.data.model.StrategyUpdate
import com.aitrader.data.model.TradingMode
import org.junit.Assert.*
import org.junit.Test

class DemoEngineTest {
    private val e = DemoEngine(seed = 1, now = { 1_790_000_000_000 })

    @Test fun demo_is_always_paper_and_live_is_refused() {
        assertEquals(TradingMode.PAPER, e.dashboard().mode)
        assertFalse(e.setMode(TradingMode.LIVE).ok)
        assertTrue(e.audit().any { it.event == "mode.live_denied" })
    }

    @Test fun kill_switch_round_trip_is_audited() {
        assertTrue(e.killSwitch(true, "test").ok)
        assertTrue(e.dashboard().killSwitch)
        e.killSwitch(false, "test")
        assertFalse(e.risk().killSwitch)
        assertEquals(listOf("kill_switch.released", "kill_switch.engaged"), e.audit().take(2).map { it.event })
    }

    @Test fun exit_removes_position_and_records_order() {
        val p = e.positions().first()
        assertTrue(e.exit(p.symbol, p.product).ok)
        assertTrue(e.positions().none { it.symbol == p.symbol && it.product == p.product })
        assertEquals(p.symbol, e.orders().first().symbol)
        assertFalse(e.exit(p.symbol, p.product).ok)
    }

    @Test fun risk_validation_caps_kelly_and_per_trade_risk() {
        val r = e.risk()
        assertFalse(e.updateRisk(r.copy(kellyFraction = 0.5)).ok)
        assertFalse(e.updateRisk(r.copy(maxRiskPerTradePct = 3.0)).ok)
        assertTrue(e.updateRisk(r.copy(dailyLossLimit = 20_000.0)).ok)
        assertEquals(20_000.0, e.risk().dailyLossLimit, 0.0)
    }

    @Test fun cannot_enable_rejected_strategy_or_over_allocate() {
        assertFalse(e.updateStrategy("pcr_oi_fade", StrategyUpdate(enabled = true)).ok)
        assertFalse(e.updateStrategy("trend_momentum", StrategyUpdate(allocationPct = 90.0)).ok)
        assertTrue(e.updateStrategy("trend_momentum", StrategyUpdate(allocationPct = 30.0)).ok)
    }

    @Test fun signals_have_consistent_reward_risk() {
        e.signals().forEach { s ->
            assertTrue(s.confidence in 0.0..1.0)
            assertTrue(s.rewardRisk > 1.0)
            if (s.side == "BUY") assertTrue(s.stop < s.entry && s.target > s.entry)
            else assertTrue(s.stop > s.entry && s.target < s.entry)
        }
    }

    @Test fun backtest_is_marked_synthetic_and_deterministic() {
        val req = BacktestRequest("trend_momentum", "2023-01-01", "2026-01-01")
        val a = e.backtest(req); val b = e.backtest(req)
        assertTrue(a.synthetic)
        assertEquals(a.sharpe, b.sharpe, 0.0)
        assertEquals(a.curve.size, a.drawdown.size)
        assertTrue(a.drawdown.all { it <= 0.0 })
    }

    @Test fun broker_connect_activate_flow() {
        assertEquals("angelone", e.brokers().single { it.active }.id)
        assertFalse(e.activateBroker("zerodha").ok)                 // not connected yet
        assertFalse(e.connectBroker("upstox").ok)                   // not configured on server
        assertTrue(e.connectBroker("zerodha").ok)
        assertTrue(e.activateBroker("zerodha").ok)
        assertEquals(listOf("zerodha"), e.brokers().filter { it.active }.map { it.id })
        assertTrue(e.disconnectBroker("zerodha").ok)
        assertFalse(e.brokers().single { it.id == "zerodha" }.connected)
        assertTrue(e.connectBroker("delta").ok)
        assertTrue(e.activateBroker("delta").ok)
        assertEquals("API_KEY", e.brokers().single { it.active }.auth)
    }
}
