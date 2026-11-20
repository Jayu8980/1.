package com.tradelens.app.domain

import kotlinx.serialization.Serializable

enum class Exchange { NSE, BSE, NFO, BFO, MCX, CDS, DELTA }

enum class Segment { EQUITY, INDEX, FUTURES, OPTIONS, COMMODITY, CRYPTO_PERP, CRYPTO_FUTURE, CRYPTO_OPTION }

enum class OptionType { CE, PE }

enum class Timeframe(val label: String, val seconds: Long) {
    M1("1m", 60), M5("5m", 300), M15("15m", 900), H1("1h", 3600), D1("1D", 86400), W1("1W", 604800);

    companion object {
        fun fromLabel(l: String) = entries.firstOrNull { it.label == l } ?: D1
    }
}

/** Unified instrument model across every broker and Delta Exchange. */
@Serializable
data class Instrument(
    val symbol: String,
    val exchange: Exchange,
    val segment: Segment,
    val token: String = "",
    val name: String = symbol,
    val lotSize: Int = 1,
    val tickSize: Double = 0.05,
    val expiry: Long? = null,
    val strike: Double? = null,
    val optionType: OptionType? = null,
    val underlying: String? = null,
) {
    val key: String get() = "${exchange.name}:$symbol"
    val isCrypto: Boolean get() = exchange == Exchange.DELTA
    val isDerivative: Boolean get() = segment in setOf(Segment.FUTURES, Segment.OPTIONS, Segment.CRYPTO_FUTURE, Segment.CRYPTO_OPTION, Segment.CRYPTO_PERP)

    /** Yahoo Finance ticker for the free fallback chain. */
    fun yahooSymbol(): String? = when {
        exchange == Exchange.DELTA -> null
        segment == Segment.INDEX -> INDEX_YAHOO[symbol.uppercase()]
        exchange == Exchange.NSE -> "$symbol.NS"
        exchange == Exchange.BSE -> "$symbol.BO"
        else -> null
    }

    companion object {
        val INDEX_YAHOO = mapOf(
            "NIFTY 50" to "^NSEI", "NIFTY" to "^NSEI", "NIFTY50" to "^NSEI",
            "BANKNIFTY" to "^NSEBANK", "NIFTY BANK" to "^NSEBANK",
            "SENSEX" to "^BSESN", "INDIA VIX" to "^INDIAVIX", "INDIAVIX" to "^INDIAVIX",
            "NIFTY IT" to "^CNXIT", "NIFTY AUTO" to "^CNXAUTO", "NIFTY PHARMA" to "^CNXPHARMA",
            "NIFTY FMCG" to "^CNXFMCG", "NIFTY METAL" to "^CNXMETAL", "NIFTY REALTY" to "^CNXREALTY",
            "NIFTY ENERGY" to "^CNXENERGY", "NIFTY PSU BANK" to "^CNXPSUBANK", "NIFTY MIDCAP 100" to "^CNXMIDCAP",
            "FINNIFTY" to "NIFTY_FIN_SERVICE.NS",
        )

        /** Parses user input like "RELIANCE", "RELIANCE.NS", "TCS.BO", "NIFTY 50", "BTCUSD" (delta). */
        fun parse(input: String, crypto: Boolean = false): Instrument {
            val s = input.trim().uppercase()
            if (crypto) return Instrument(s, Exchange.DELTA, Segment.CRYPTO_PERP, lotSize = 1, tickSize = 0.5)
            if (INDEX_YAHOO.containsKey(s)) return Instrument(s, Exchange.NSE, Segment.INDEX, tickSize = 0.05)
            return when {
                s.endsWith(".NS") -> Instrument(s.removeSuffix(".NS"), Exchange.NSE, Segment.EQUITY)
                s.endsWith(".BO") -> Instrument(s.removeSuffix(".BO"), Exchange.BSE, Segment.EQUITY)
                else -> Instrument(s, Exchange.NSE, Segment.EQUITY)
            }
        }
    }
}

@Serializable
data class Candle(
    val time: Long, // epoch seconds (bar open)
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val oi: Double = 0.0,
)

enum class DataSource(val label: String) { YAHOO("Yahoo"), UPSTOX("Upstox"), ANGEL("Angel One"), FYERS("Fyers"), DHAN("Dhan"), KITE("Kite"), DELTA("Delta"), ENGINE("Engine"), CACHE("Cache") }

/** Every price shown in the UI carries where it came from and whether it is real-time. */
@Serializable
data class SourceBadge(val source: DataSource, val realtime: Boolean, val asOf: Long)

@Serializable
data class Quote(
    val instrumentKey: String,
    val ltp: Double,
    val change: Double,
    val changePct: Double,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val prevClose: Double = 0.0,
    val volume: Double = 0.0,
    val badge: SourceBadge,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
)

@Serializable
data class DepthLevel(val price: Double, val qty: Double, val orders: Int = 0)

@Serializable
data class MarketDepth(val bids: List<DepthLevel>, val asks: List<DepthLevel>)

enum class Side { BUY, SELL }
enum class OrderType { MARKET, LIMIT, SL, SL_M, STOP_MARKET, BRACKET }
enum class ProductType { DELIVERY, INTRADAY, NRML, CRYPTO }
enum class OrderStatus { OPEN, PENDING, FILLED, PARTIAL, CANCELLED, REJECTED }

@Serializable
data class OrderRequest(
    val instrument: Instrument,
    val side: Side,
    val qty: Double,
    val type: OrderType,
    val product: ProductType,
    val price: Double? = null,
    val triggerPrice: Double? = null,
    val stopLoss: Double? = null,
    val target: Double? = null,
    val leverage: Double = 1.0,
    val tag: String = "tradelens",
)

@Serializable
data class Order(
    val id: String,
    val instrumentKey: String,
    val side: Side,
    val qty: Double,
    val filledQty: Double,
    val type: OrderType,
    val price: Double?,
    val avgPrice: Double?,
    val status: OrderStatus,
    val time: Long,
    val broker: String,
    val message: String = "",
)

@Serializable
data class Position(
    val instrumentKey: String,
    val symbol: String,
    val qty: Double,
    val avgPrice: Double,
    val ltp: Double,
    val pnl: Double,
    val dayPnl: Double = 0.0,
    val broker: String,
    val leverage: Double = 1.0,
    val liquidationPrice: Double? = null,
    val margin: Double = 0.0,
)

@Serializable
data class Holding(
    val symbol: String,
    val exchange: String,
    val qty: Double,
    val avgPrice: Double,
    val ltp: Double,
    val prevClose: Double,
    val broker: String,
    val sector: String = "Other",
) {
    val invested get() = qty * avgPrice
    val current get() = qty * ltp
    val pnl get() = current - invested
    val dayPnl get() = qty * (ltp - prevClose)
}

@Serializable
data class Funds(val available: Double, val used: Double, val total: Double, val currency: String = "INR", val broker: String)

@Serializable
data class OptionQuote(
    val strike: Double,
    val type: OptionType,
    val ltp: Double,
    val oi: Double,
    val oiChange: Double,
    val volume: Double,
    val iv: Double? = null,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val symbol: String = "",
    val priceChange: Double = 0.0,
)

@Serializable
data class OptionChain(
    val underlying: String,
    val spot: Double,
    val expiry: Long,
    val rows: List<OptionQuote>,
    val badge: SourceBadge,
)
