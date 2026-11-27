package com.aitrader.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TradingMode { PAPER, LIVE }

@Serializable
data class CurvePoint(val t: Long, val equity: Double, val nifty: Double)

@Serializable
data class Dashboard(
    val mode: TradingMode,
    val killSwitch: Boolean,
    val dayPnl: Double,
    val dayPnlPct: Double,
    val totalPnl: Double,
    val equity: Double,
    val openPositions: Int,
    val curve: List<CurvePoint>,
    val updatedAt: Long,
)

@Serializable
data class Position(
    val symbol: String,
    val exchange: String,
    val product: String,
    val qty: Int,
    val avgPrice: Double,
    val ltp: Double,
    val realizedPnl: Double,
    val strategy: String,
) {
    val unrealizedPnl: Double get() = (ltp - avgPrice) * qty
}

@Serializable
data class Order(
    val orderId: String,
    val symbol: String,
    val side: String,
    val qty: Int,
    val type: String,
    val price: Double?,
    val status: String,
    val avgFillPrice: Double,
    val strategy: String,
    val ts: Long,
)

@Serializable
data class Signal(
    val id: String,
    val symbol: String,
    val side: String,
    val strategy: String,
    val confidence: Double,        // calibrated probability from meta-model
    val regime: String,            // TREND / RANGE / HIGH_VOL
    val reason: String,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val expectedEdgeBps: Double,   // after costs
    val status: String,            // TAKEN / SKIPPED_COST / SKIPPED_CONFIDENCE / SKIPPED_REGIME / VETOED
    val ts: Long,
) {
    val rewardRisk: Double get() = if (entry == stop) 0.0 else kotlin.math.abs(target - entry) / kotlin.math.abs(entry - stop)
}

@Serializable
data class StrategyStats(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val allocationPct: Double,
    val stage: String,             // BACKTEST / PAPER / LIVE_10 / LIVE
    val regimes: List<String>,
    val liveSharpe: Double,
    val backtestSharpe: Double,
    val trades: Int,
    val winRate: Double,
    val pnl: Double,
    val drawdownPct: Double,
    val backtestMaxDdPct: Double,
    val autoDisabledReason: String? = null,
)

@Serializable
data class StrategyUpdate(val enabled: Boolean? = null, val allocationPct: Double? = null)

@Serializable
data class BacktestRequest(val strategyId: String, val from: String, val to: String)

@Serializable
data class Tearsheet(
    val strategyId: String,
    val from: String,
    val to: String,
    val cagr: Double,
    val sharpe: Double,
    val sortino: Double,
    val maxDdPct: Double,
    val calmar: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val alpha: Double,
    val beta: Double,
    val costDragPct: Double,
    val trades: Int,
    val deflatedSharpe: Double,
    val curve: List<CurvePoint>,
    val drawdown: List<Double>,
    val monthlyReturns: List<Double>,
    val synthetic: Boolean = false,
)

@Serializable
data class RiskConfig(
    val dailyLossLimit: Double,
    val maxDrawdownPct: Double,
    val maxRiskPerTradePct: Double,
    val maxPositionPct: Double,
    val maxSectorPct: Double,
    val kellyFraction: Double,
    val dayLossUsed: Double,
    val currentDrawdownPct: Double,
    val killSwitch: Boolean,
    val breaches: List<String> = emptyList(),
)

@Serializable
data class Analysis(
    val symbol: String,
    val summary: String,
    val sentiment: Double,          // -1..1, fed to the meta-model as a feature only
    val keyPoints: List<String>,
    val sources: List<String>,
    val generatedAt: Long,
    val synthetic: Boolean = false,
)

@Serializable
data class AuditEntry(val ts: String, val event: String, val data: String, val hash: String)

@Serializable data class LoginRequest(val username: String, val password: String, val devicePublicKey: String)
@Serializable data class LoginResponse(val token: String, val expiresIn: Long)
@Serializable data class ModeRequest(val mode: TradingMode, val confirmPhrase: String)
@Serializable data class KillSwitchRequest(val engaged: Boolean, val reason: String)
@Serializable data class ExitRequest(val symbol: String, val exchange: String, val product: String)
@Serializable data class ActionResult(val ok: Boolean, val message: String = "")
