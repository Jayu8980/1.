package com.tradelens.app.data.broker

import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.Segment
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves broker-specific tokens from the cached instrument master (Room). */
@Singleton
class InstrumentLookup @Inject constructor(private val db: AppDatabase) {
    suspend fun token(broker: String, i: Instrument): String? {
        if (i.token.isNotBlank() && i.token != "0") return i.token
        return db.instruments().find(broker, i.exchange.name, i.symbol)?.token
    }

    suspend fun require(broker: String, i: Instrument): String = token(broker, i)
        ?: throw IllegalStateException("${i.symbol} not found in $broker instrument master – open Settings → Brokers → Refresh instruments")

    companion object {
        /** Canonical index name used by per-broker index token tables. */
        fun canonicalIndex(i: Instrument): String? {
            if (i.segment != Segment.INDEX) return null
            return when (i.symbol.uppercase().replace(" ", "")) {
                "NIFTY", "NIFTY50" -> "NIFTY"
                "BANKNIFTY", "NIFTYBANK" -> "BANKNIFTY"
                "FINNIFTY", "NIFTYFINSERVICE" -> "FINNIFTY"
                "INDIAVIX" -> "INDIAVIX"
                "SENSEX" -> "SENSEX"
                "MIDCPNIFTY", "NIFTYMIDCAPSELECT" -> "MIDCPNIFTY"
                else -> i.symbol.uppercase().replace(" ", "")
            }
        }
    }
}

/** Minimal protobuf wire-format reader (used for the Upstox v3 market feed). */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    fun hasMore() = pos < end
    fun varint(): Long {
        var shift = 0; var result = 0L
        while (true) {
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
    fun double(): Double { var v = 0L; for (k in 0 until 8) v = v or ((buf[pos + k].toLong() and 0xff) shl (8 * k)); pos += 8; return java.lang.Double.longBitsToDouble(v) }
    fun bytes(): ProtoReader { val len = varint().toInt(); val r = ProtoReader(buf, pos, pos + len); pos += len; return r }
    fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len); pos += len; return s }
    fun skip(wire: Int) {
        when (wire) { 0 -> varint(); 1 -> pos += 8; 2 -> pos += varint().toInt(); 5 -> pos += 4; else -> pos = end }
    }
}
