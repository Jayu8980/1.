package com.aitrader

import com.aitrader.data.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {
    @Test fun cagr_doubling_over_one_year() {
        val eq = List(253) { 100.0 * Math.pow(2.0, it / 252.0) }
        assertEquals(1.0, Metrics.cagr(eq), 1e-9)
    }

    @Test fun max_drawdown() {
        assertEquals(0.5, Metrics.maxDrawdown(listOf(100.0, 200.0, 100.0, 150.0)), 1e-12)
        assertEquals(0.0, Metrics.maxDrawdown(listOf(1.0, 2.0, 3.0)), 0.0)
    }

    @Test fun sharpe_zero_for_constant_returns_and_positive_for_drift() {
        assertEquals(0.0, Metrics.sharpe(List(10) { 0.01 }), 0.0)
        assertTrue(Metrics.sharpe(listOf(0.01, 0.02, 0.0, 0.015, 0.005)) > 0)
    }

    @Test fun alpha_beta_recovers_linear_relation() {
        val b = listOf(0.01, -0.02, 0.015, 0.003, -0.007, 0.012)
        val r = b.map { 0.0002 + 1.5 * it }
        val (alpha, beta) = Metrics.alphaBeta(r, b)
        assertEquals(1.5, beta, 1e-9)
        assertEquals(0.0002 * 252, alpha, 1e-9)
    }

    @Test fun profit_factor() {
        assertEquals(2.0, Metrics.profitFactor(listOf(100.0, 100.0, -100.0)), 1e-12)
    }
}
