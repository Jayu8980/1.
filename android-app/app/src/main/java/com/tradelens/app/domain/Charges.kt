package com.tradelens.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToLong

enum class ChargeSegment { EQ_DELIVERY, EQ_INTRADAY, FUTURES, OPTIONS, COMMODITY_FUT, CRYPTO_FUT, CRYPTO_OPT }

@Serializable
data class ChargeBreakdown(
    val brokerage: Double,
    val stt: Double,
    val exchange: Double,
    val sebi: Double,
    val stamp: Double,
    val gst: Double,
    val dp: Double = 0.0,
    val tradingFee: Double = 0.0,
) {
    val total: Double get() = round2(brokerage + stt + exchange + sebi + stamp + gst + dp + tradingFee)
    operator fun plus(o: ChargeBreakdown) = ChargeBreakdown(
        brokerage + o.brokerage, stt + o.stt, exchange + o.exchange, sebi + o.sebi,
        stamp + o.stamp, gst + o.gst, dp + o.dp, tradingFee + o.tradingFee,
    )
}

/**
 * Statutory rates for Indian markets plus Delta Exchange India fees. Rates are kept in one table so they
 * can be updated when SEBI / the Union Budget / exchanges revise them. Verify against your broker's
 * current charge sheet; the engine (engine/tradelens/charges.py) uses the identical table.
 */
@Serializable
data class ChargeRates(
    // Brokerage: flat cap per executed order, and % of turnover (whichever lower). 0 for discount-broker delivery.
    val brokerageFlat: Double = 20.0,
    val brokeragePct: Double = 0.0003,
    val deliveryBrokerage: Double = 0.0,
    val sttDelivery: Double = 0.001,          // both sides
    val sttIntradaySell: Double = 0.00025,
    val sttFuturesSell: Double = 0.0002,
    val sttOptionsSell: Double = 0.001,       // on premium
    val cttCommoditySell: Double = 0.0001,
    val nseEquityTxn: Double = 0.0000297,
    val nseFuturesTxn: Double = 0.0000173,
    val nseOptionsTxn: Double = 0.0003503,    // on premium
    val mcxFuturesTxn: Double = 0.000021,
    val sebiPerCrore: Double = 10.0,
    val stampDelivery: Double = 0.00015,      // buy side
    val stampIntraday: Double = 0.00003,
    val stampFutures: Double = 0.00002,
    val stampOptions: Double = 0.00003,
    val stampCommodity: Double = 0.00002,
    val gst: Double = 0.18,
    val dpPerSellScrip: Double = 15.34,
    // Delta Exchange India (fraction of notional; options capped at % of premium)
    val deltaFutTaker: Double = 0.0005,
    val deltaFutMaker: Double = 0.0002,
    val deltaOptFee: Double = 0.0003,
    val deltaOptCapOfPremium: Double = 0.10,
)

object Charges {

    /**
     * Charges for a single executed leg.
     * @param price per-unit price (option premium for options)
     * @param qty   units (shares, or lots × lot size)
     * @param underlyingPrice needed for Delta options (fee is on underlying notional)
     */
    fun leg(
        segment: ChargeSegment,
        side: Side,
        price: Double,
        qty: Double,
        rates: ChargeRates = ChargeRates(),
        isMaker: Boolean = false,
        underlyingPrice: Double = 0.0,
    ): ChargeBreakdown {
        val turnover = price * qty
        val buy = side == Side.BUY
        val sebi = turnover * rates.sebiPerCrore / 1e7
        return when (segment) {
            ChargeSegment.EQ_DELIVERY -> {
                val brk = rates.deliveryBrokerage
                val exch = turnover * rates.nseEquityTxn
                val stt = turnover * rates.sttDelivery
                val stamp = if (buy) turnover * rates.stampDelivery else 0.0
                val dp = if (!buy) rates.dpPerSellScrip else 0.0
                ChargeBreakdown(brk, round0(stt), exch, sebi, round0(stamp), rates.gst * (brk + exch + sebi + dp), dp)
            }
            ChargeSegment.EQ_INTRADAY -> {
                val brk = min(rates.brokerageFlat, turnover * rates.brokeragePct)
                val exch = turnover * rates.nseEquityTxn
                val stt = if (!buy) turnover * rates.sttIntradaySell else 0.0
                val stamp = if (buy) turnover * rates.stampIntraday else 0.0
                ChargeBreakdown(brk, round0(stt), exch, sebi, round0(stamp), rates.gst * (brk + exch + sebi))
            }
            ChargeSegment.FUTURES -> {
                val brk = min(rates.brokerageFlat, turnover * rates.brokeragePct)
                val exch = turnover * rates.nseFuturesTxn
                val stt = if (!buy) turnover * rates.sttFuturesSell else 0.0
                val stamp = if (buy) turnover * rates.stampFutures else 0.0
                ChargeBreakdown(brk, round0(stt), exch, sebi, round0(stamp), rates.gst * (brk + exch + sebi))
            }
            ChargeSegment.OPTIONS -> {
                val brk = rates.brokerageFlat
                val exch = turnover * rates.nseOptionsTxn
                val stt = if (!buy) turnover * rates.sttOptionsSell else 0.0
                val stamp = if (buy) turnover * rates.stampOptions else 0.0
                ChargeBreakdown(brk, round0(stt), exch, sebi, round0(stamp), rates.gst * (brk + exch + sebi))
            }
            ChargeSegment.COMMODITY_FUT -> {
                val brk = min(rates.brokerageFlat, turnover * rates.brokeragePct)
                val exch = turnover * rates.mcxFuturesTxn
                val ctt = if (!buy) turnover * rates.cttCommoditySell else 0.0
                val stamp = if (buy) turnover * rates.stampCommodity else 0.0
                ChargeBreakdown(brk, round0(ctt), exch, sebi, round0(stamp), rates.gst * (brk + exch + sebi))
            }
            ChargeSegment.CRYPTO_FUT -> {
                val fee = turnover * if (isMaker) rates.deltaFutMaker else rates.deltaFutTaker
                ChargeBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, rates.gst * fee, tradingFee = fee)
            }
            ChargeSegment.CRYPTO_OPT -> {
                val notional = (if (underlyingPrice > 0) underlyingPrice else price) * qty
                val fee = min(notional * rates.deltaOptFee, turnover * rates.deltaOptCapOfPremium)
                ChargeBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, rates.gst * fee, tradingFee = fee)
            }
        }
    }

    fun roundTrip(segment: ChargeSegment, entry: Double, exit: Double, qty: Double, long: Boolean = true, rates: ChargeRates = ChargeRates()): ChargeBreakdown {
        val a = leg(segment, if (long) Side.BUY else Side.SELL, entry, qty, rates)
        val b = leg(segment, if (long) Side.SELL else Side.BUY, exit, qty, rates)
        return a + b
    }

    fun segmentFor(i: Instrument, intraday: Boolean): ChargeSegment = when (i.segment) {
        Segment.EQUITY, Segment.INDEX -> if (intraday) ChargeSegment.EQ_INTRADAY else ChargeSegment.EQ_DELIVERY
        Segment.FUTURES -> ChargeSegment.FUTURES
        Segment.OPTIONS -> ChargeSegment.OPTIONS
        Segment.COMMODITY -> ChargeSegment.COMMODITY_FUT
        Segment.CRYPTO_PERP, Segment.CRYPTO_FUTURE -> ChargeSegment.CRYPTO_FUT
        Segment.CRYPTO_OPTION -> ChargeSegment.CRYPTO_OPT
    }

    private fun round0(v: Double) = v.roundToLong().toDouble()
}

internal fun round2(v: Double) = Math.round(v * 100.0) / 100.0
