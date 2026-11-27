package com.aitrader.data

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Portfolio statistics from a daily equity series. Pure functions, unit-tested. */
object Metrics {
    fun returns(equity: List<Double>): List<Double> = equity.zipWithNext { a, b -> b / a - 1 }

    fun cagr(equity: List<Double>, periodsPerYear: Int = 252): Double {
        if (equity.size < 2) return 0.0
        val years = (equity.size - 1).toDouble() / periodsPerYear
        return (equity.last() / equity.first()).pow(1 / years) - 1
    }

    fun sharpe(r: List<Double>, periodsPerYear: Int = 252, rf: Double = 0.0): Double {
        if (r.size < 2) return 0.0
        val ex = r.map { it - rf / periodsPerYear }
        val m = ex.average(); val sd = stdev(ex)
        return if (sd < 1e-12) 0.0 else m / sd * sqrt(periodsPerYear.toDouble())
    }

    fun sortino(r: List<Double>, periodsPerYear: Int = 252): Double {
        if (r.size < 2) return 0.0
        val downside = sqrt(r.map { minOf(it, 0.0).pow(2) }.average())
        return if (downside < 1e-12) 0.0 else r.average() / downside * sqrt(periodsPerYear.toDouble())
    }

    /** Drawdown series as negative fractions (0 at highs). */
    fun drawdowns(equity: List<Double>): List<Double> {
        var peak = Double.MIN_VALUE
        return equity.map { peak = max(peak, it); it / peak - 1 }
    }

    fun maxDrawdown(equity: List<Double>): Double = -(drawdowns(equity).minOrNull() ?: 0.0)

    fun calmar(equity: List<Double>): Double = maxDrawdown(equity).let { if (it == 0.0) 0.0 else cagr(equity) / it }

    fun profitFactor(tradePnls: List<Double>): Double {
        val gains = tradePnls.filter { it > 0 }.sum(); val losses = -tradePnls.filter { it < 0 }.sum()
        return if (losses == 0.0) Double.POSITIVE_INFINITY else gains / losses
    }

    /** Returns (alpha annualised, beta) of strategy vs benchmark daily returns. */
    fun alphaBeta(r: List<Double>, b: List<Double>, periodsPerYear: Int = 252): Pair<Double, Double> {
        val n = minOf(r.size, b.size); if (n < 2) return 0.0 to 0.0
        val x = b.take(n); val y = r.take(n)
        val mx = x.average(); val my = y.average()
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / (n - 1)
        val varX = x.sumOf { (it - mx).pow(2) } / (n - 1)
        val beta = if (varX == 0.0) 0.0 else cov / varX
        return (my - beta * mx) * periodsPerYear to beta
    }

    fun stdev(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m).pow(2) } / (x.size - 1))
    }
}
