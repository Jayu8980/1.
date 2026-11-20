package com.tradelens.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

data class PositionSize(
    val qty: Double,
    val lots: Int,
    val stop: Double,
    val riskAmount: Double,
    val positionValue: Double,
    val capitalPct: Double,
    val cappedBy: String?,
)

object Risk {

    /**
     * Quantity such that hitting the stop loses [riskPct]% of [capital]. Stop is either explicit or
     * entry ∓ atrMult×ATR. Quantity is rounded down to whole lots and capped at [maxPositionPct] of capital.
     */
    fun positionSize(
        capital: Double,
        riskPct: Double,
        entry: Double,
        long: Boolean,
        stop: Double? = null,
        atr: Double? = null,
        atrMult: Double = 2.0,
        lotSize: Int = 1,
        maxPositionPct: Double = 10.0,
        leverage: Double = 1.0,
    ): PositionSize {
        require(capital > 0 && entry > 0) { "capital and entry must be positive" }
        val sl = stop ?: run {
            requireNotNull(atr) { "stop or ATR required" }
            if (long) entry - atrMult * atr else entry + atrMult * atr
        }
        val perUnit = abs(entry - sl)
        require(perUnit > 0) { "stop must differ from entry" }
        val riskAmt = capital * riskPct / 100.0
        var qty = riskAmt / perUnit
        var capped: String? = null
        val maxValue = capital * maxPositionPct / 100.0 * leverage
        if (qty * entry > maxValue) { qty = maxValue / entry; capped = "max ${maxPositionPct}% per position" }
        val lots = floor(qty / lotSize).toInt()
        val finalQty = (lots * lotSize).toDouble()
        if (lots == 0 && capped == null) capped = "risk budget smaller than 1 lot"
        return PositionSize(finalQty, lots, sl, finalQty * perUnit, finalQty * entry, finalQty * entry / leverage / capital * 100, capped)
    }

    fun riskReward(entry: Double, stop: Double, target: Double): Double {
        val risk = abs(entry - stop)
        return if (risk == 0.0) 0.0 else abs(target - entry) / risk
    }

    /**
     * Estimated liquidation price for an isolated-margin perpetual/future (Delta style).
     * Long: entry × (1 − 1/lev + mmr); Short: entry × (1 + 1/lev − mmr).
     */
    fun liquidationPrice(entry: Double, leverage: Double, long: Boolean, maintenanceMargin: Double = 0.005): Double {
        require(leverage >= 1)
        return if (long) entry * (1 - 1 / leverage + maintenanceMargin) else entry * (1 + 1 / leverage - maintenanceMargin)
    }

    fun leverageWarning(leverage: Double): String? = when {
        leverage >= 50 -> "Extreme leverage: a ${"%.1f".format(100 / leverage)}% adverse move liquidates you."
        leverage >= 20 -> "Very high leverage – liquidation within a ${"%.1f".format(100 / leverage)}% move."
        leverage >= 10 -> "High leverage – keep size small and always use a stop."
        else -> null
    }

    /** Fractional Kelly with a hard cap (default ≤ 0.25 Kelly). */
    fun kellyFraction(winRate: Double, payoff: Double, fraction: Double = 0.25): Double {
        if (payoff <= 0) return 0.0
        val k = winRate - (1 - winRate) / payoff
        return max(0.0, k * fraction.coerceAtMost(0.25))
    }
}

@Serializable
data class RiskLimits(
    val dailyLossPct: Double = 2.0,
    val maxDrawdownPct: Double = 10.0,
    val maxOrdersPerDay: Int = 50,
    val maxPositionPct: Double = 10.0,
    val maxSectorPct: Double = 25.0,
    val maxMarginUtilPct: Double = 50.0,
    val riskPerTradePct: Double = 1.0,
)

@Serializable
data class RiskState(
    val startOfDayEquity: Double,
    val peakEquity: Double,
    val equity: Double,
    val ordersToday: Int = 0,
    val haltedForDay: Boolean = false,
    val circuitBroken: Boolean = false,
    val globalKill: Boolean = false,
)

sealed interface RiskDecision {
    data object Allow : RiskDecision
    data class Block(val reason: String) : RiskDecision
    data class FlattenAndHalt(val reason: String, val requireManualRestart: Boolean) : RiskDecision
}

/** Pure risk-gate logic shared by paper trading and the live-order confirmation flow. */
object RiskGate {

    fun evaluateEquity(state: RiskState, limits: RiskLimits): RiskDecision {
        val dayLoss = (state.startOfDayEquity - state.equity) / state.startOfDayEquity * 100
        val dd = (state.peakEquity - state.equity) / state.peakEquity * 100
        return when {
            dd >= limits.maxDrawdownPct -> RiskDecision.FlattenAndHalt("Max drawdown %.2f%% ≥ %.1f%%".format(dd, limits.maxDrawdownPct), true)
            dayLoss >= limits.dailyLossPct -> RiskDecision.FlattenAndHalt("Daily loss %.2f%% ≥ %.1f%%".format(dayLoss, limits.dailyLossPct), false)
            else -> RiskDecision.Allow
        }
    }

    fun checkNewOrder(
        state: RiskState,
        limits: RiskLimits,
        orderValue: Double,
        existingExposureSameSymbol: Double = 0.0,
        sectorExposure: Double = 0.0,
        marginUsedAfter: Double = 0.0,
        marginTotal: Double = 0.0,
        isRiskReducing: Boolean = false,
    ): RiskDecision {
        if (state.globalKill) return RiskDecision.Block("Global kill switch is ON")
        if (state.circuitBroken) return RiskDecision.Block("Drawdown circuit breaker tripped – manual restart required")
        if (state.haltedForDay && !isRiskReducing) return RiskDecision.Block("Trading halted for the day (daily loss limit)")
        if (isRiskReducing) return RiskDecision.Allow
        if (state.ordersToday >= limits.maxOrdersPerDay) return RiskDecision.Block("Daily order limit ${limits.maxOrdersPerDay} reached")
        val eq = state.equity
        if ((existingExposureSameSymbol + orderValue) / eq * 100 > limits.maxPositionPct)
            return RiskDecision.Block("Position would exceed ${limits.maxPositionPct}% of capital")
        if (sectorExposure > 0 && (sectorExposure + orderValue) / eq * 100 > limits.maxSectorPct)
            return RiskDecision.Block("Sector exposure would exceed ${limits.maxSectorPct}%")
        if (marginTotal > 0 && marginUsedAfter / marginTotal * 100 > limits.maxMarginUtilPct)
            return RiskDecision.Block("Margin utilisation would exceed ${limits.maxMarginUtilPct}%")
        return evaluateEquity(state, limits).let { if (it is RiskDecision.FlattenAndHalt) RiskDecision.Block(it.reason) else RiskDecision.Allow }
    }
}
