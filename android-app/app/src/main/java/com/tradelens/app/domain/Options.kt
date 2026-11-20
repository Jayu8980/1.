package com.tradelens.app.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class Greeks(val price: Double, val delta: Double, val gamma: Double, val theta: Double, val vega: Double, val rho: Double)

/** Black-Scholes(-Merton) pricing and Greeks. theta is per calendar day, vega per 1 vol point. */
object BlackScholes {

    fun cdf(x: Double): Double {
        // Abramowitz-Stegun 7.1.26 with erf; max error ~1.5e-7
        val t = 1.0 / (1.0 + 0.3275911 * abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x / 2)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }

    fun pdf(x: Double) = exp(-x * x / 2) / sqrt(2 * Math.PI)

    fun greeks(type: OptionType, spot: Double, strike: Double, years: Double, vol: Double, rate: Double = 0.065, div: Double = 0.0): Greeks {
        if (years <= 0 || vol <= 0) {
            val intrinsic = if (type == OptionType.CE) max(spot - strike, 0.0) else max(strike - spot, 0.0)
            val d = if (type == OptionType.CE) (if (spot > strike) 1.0 else 0.0) else (if (spot < strike) -1.0 else 0.0)
            return Greeks(intrinsic, d, 0.0, 0.0, 0.0, 0.0)
        }
        val sq = sqrt(years)
        val d1 = (ln(spot / strike) + (rate - div + vol * vol / 2) * years) / (vol * sq)
        val d2 = d1 - vol * sq
        val dfR = exp(-rate * years); val dfQ = exp(-div * years)
        val gamma = dfQ * pdf(d1) / (spot * vol * sq)
        val vega = spot * dfQ * pdf(d1) * sq / 100
        return if (type == OptionType.CE) {
            val price = spot * dfQ * cdf(d1) - strike * dfR * cdf(d2)
            val theta = (-spot * dfQ * pdf(d1) * vol / (2 * sq) - rate * strike * dfR * cdf(d2) + div * spot * dfQ * cdf(d1)) / 365
            Greeks(price, dfQ * cdf(d1), gamma, theta, vega, strike * years * dfR * cdf(d2) / 100)
        } else {
            val price = strike * dfR * cdf(-d2) - spot * dfQ * cdf(-d1)
            val theta = (-spot * dfQ * pdf(d1) * vol / (2 * sq) + rate * strike * dfR * cdf(-d2) - div * spot * dfQ * cdf(-d1)) / 365
            Greeks(price, dfQ * (cdf(d1) - 1), gamma, theta, vega, -strike * years * dfR * cdf(-d2) / 100)
        }
    }

    /** Implied volatility by bisection (robust for deep ITM/OTM). Returns null if price is outside no-arb bounds. */
    fun impliedVol(type: OptionType, price: Double, spot: Double, strike: Double, years: Double, rate: Double = 0.065): Double? {
        if (price <= 0 || years <= 0) return null
        val intrinsic = if (type == OptionType.CE) max(spot - strike * exp(-rate * years), 0.0) else max(strike * exp(-rate * years) - spot, 0.0)
        if (price < intrinsic - 1e-6) return null
        var lo = 0.001; var hi = 5.0
        if (greeks(type, spot, strike, years, hi, rate).price < price) return null
        repeat(100) {
            val mid = (lo + hi) / 2
            if (greeks(type, spot, strike, years, mid, rate).price > price) hi = mid else lo = mid
            if (hi - lo < 1e-6) return (lo + hi) / 2
        }
        return (lo + hi) / 2
    }
}

enum class OiBuildup(val label: String) { LONG_BUILDUP("Long buildup"), SHORT_BUILDUP("Short buildup"), SHORT_COVERING("Short covering"), LONG_UNWINDING("Long unwinding"), NONE("—") }

object OptionAnalytics {

    fun pcr(rows: List<OptionQuote>): Double {
        val ce = rows.filter { it.type == OptionType.CE }.sumOf { it.oi }
        val pe = rows.filter { it.type == OptionType.PE }.sumOf { it.oi }
        return if (ce == 0.0) 0.0 else pe / ce
    }

    /** Strike at which total option-writer payout is minimal (where buyers lose most). */
    fun maxPain(rows: List<OptionQuote>): Double? {
        val strikes = rows.map { it.strike }.distinct().sorted()
        if (strikes.isEmpty()) return null
        return strikes.minByOrNull { s ->
            rows.sumOf { r ->
                when (r.type) {
                    OptionType.CE -> max(s - r.strike, 0.0) * r.oi
                    OptionType.PE -> max(r.strike - s, 0.0) * r.oi
                }
            }
        }
    }

    fun buildup(priceChange: Double, oiChange: Double): OiBuildup = when {
        priceChange > 0 && oiChange > 0 -> OiBuildup.LONG_BUILDUP
        priceChange < 0 && oiChange > 0 -> OiBuildup.SHORT_BUILDUP
        priceChange > 0 && oiChange < 0 -> OiBuildup.SHORT_COVERING
        priceChange < 0 && oiChange < 0 -> OiBuildup.LONG_UNWINDING
        else -> OiBuildup.NONE
    }

    fun yearsTo(expiryEpochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): Double =
        max((expiryEpochSec - nowSec).toDouble(), 0.0) / (365.0 * 86400)

    /** IV rank of [current] within a history of IVs (0..100). */
    fun ivRank(current: Double, history: List<Double>): Double {
        if (history.isEmpty()) return 50.0
        val lo = history.min(); val hi = history.max()
        return if (hi == lo) 50.0 else (current - lo) / (hi - lo) * 100
    }
}
