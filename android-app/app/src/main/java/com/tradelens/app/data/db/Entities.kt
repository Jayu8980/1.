package com.tradelens.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.Exchange
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Segment

@Entity(tableName = "instruments", primaryKeys = ["broker", "exchange", "symbol"], indices = [Index("symbol"), Index("underlying")])
data class InstrumentEntity(
    val broker: String,
    val exchange: String,
    val symbol: String,
    val segment: String,
    val token: String,
    val name: String,
    val lotSize: Int,
    val tickSize: Double,
    val expiry: Long?,
    val strike: Double?,
    val optionType: String?,
    val underlying: String?,
) {
    fun toDomain() = Instrument(
        symbol, Exchange.valueOf(exchange), Segment.valueOf(segment), token, name, lotSize, tickSize, expiry, strike,
        optionType?.let { OptionType.valueOf(it) }, underlying,
    )

    companion object {
        fun from(broker: String, i: Instrument) = InstrumentEntity(
            broker, i.exchange.name, i.symbol, i.segment.name, i.token, i.name, i.lotSize, i.tickSize, i.expiry, i.strike, i.optionType?.name, i.underlying,
        )
    }
}

@Entity(tableName = "candles", primaryKeys = ["instrumentKey", "tf", "time"])
data class CandleEntity(
    val instrumentKey: String, val tf: String, val time: Long,
    val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double, val oi: Double,
    val source: String,
) {
    fun toDomain() = Candle(time, open, high, low, close, volume, oi)
}

@Entity(tableName = "watchlists")
data class WatchlistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val position: Int = 0)

@Entity(tableName = "watchlist_items", indices = [Index("listId")])
data class WatchlistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val instrumentJson: String,
    val position: Int = 0,
) {
    val instrument: Instrument get() = AppJson.decodeFromString(Instrument.serializer(), instrumentJson)
}

@Entity(tableName = "paper_orders")
data class PaperOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrumentJson: String,
    val side: String,
    val type: String,
    val product: String,
    val qty: Double,
    val price: Double?,
    val trigger: Double?,
    val stopLoss: Double?,
    val target: Double?,
    val status: String,
    val createdAt: Long,
    val filledAt: Long? = null,
    val fillPrice: Double? = null,
    val charges: Double = 0.0,
    val reason: String = "",
    val leverage: Double = 1.0,
)

@Entity(tableName = "paper_positions", primaryKeys = ["instrumentKey"])
data class PaperPositionEntity(
    val instrumentKey: String,
    val instrumentJson: String,
    val qty: Double,           // signed: + long, - short
    val avgPrice: Double,
    val product: String,
    val stopLoss: Double?,
    val target: Double?,
    val openedAt: Long,
    val leverage: Double = 1.0,
    val entryReason: String = "",
)

@Entity(tableName = "paper_trades")
data class PaperTradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,
    val qty: Double,
    val entry: Double,
    val exit: Double,
    val pnl: Double,
    val charges: Double,
    val openedAt: Long,
    val closedAt: Long,
    val entryReason: String,
    val exitReason: String,
    val notes: String = "",
)

@Entity(tableName = "paper_account")
data class PaperAccountEntity(
    @PrimaryKey val id: Int = 1,
    val cash: Double,
    val initialCapital: Double,
    val startOfDayEquity: Double,
    val peakEquity: Double,
    val dayStamp: Long,
    val ordersToday: Int = 0,
    val haltedForDay: Boolean = false,
    val circuitBroken: Boolean = false,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrumentJson: String,
    val kind: String,            // PRICE_ABOVE, PRICE_BELOW, RULE, PATTERN
    val value: Double? = null,
    val rule: String? = null,
    val timeframe: String = "1D",
    val active: Boolean = true,
    val oneShot: Boolean = true,
    val lastTriggered: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val instrument: Instrument get() = AppJson.decodeFromString(Instrument.serializer(), instrumentJson)
}

@Entity(tableName = "audit_log", indices = [Index("time")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val category: String,        // ORDER, RESPONSE, APPROVAL, RISK, AUTH, KILL
    val message: String,
    val payload: String = "",
)

@Entity(tableName = "saved_screens")
data class SavedScreenEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val rule: String, val timeframe: String = "1D")
