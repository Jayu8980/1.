package com.tradelens.app.data.market

import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.CandleEntity
import com.tradelens.app.data.delta.DeltaClient
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.DataSource
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketDepth
import com.tradelens.app.domain.OptionChain
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.domain.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

data class CandleResult(val candles: List<Candle>, val badge: SourceBadge, val error: String? = null)

/**
 * Single entry point for market data. Fallback chain: Delta (crypto) | connected broker → Yahoo (delayed) → Room cache.
 */
@Singleton
class MarketRepository @Inject constructor(
    private val brokers: BrokerRegistry,
    private val yahoo: YahooProvider,
    private val delta: DeltaClient,
    private val db: AppDatabase,
) {
    private fun chain(i: Instrument): List<DataProvider> =
        if (i.isCrypto) listOf(delta) else listOfNotNull(brokers.primary(), yahoo.takeIf { i.yahooSymbol() != null })

    suspend fun quote(i: Instrument): Quote {
        var last: Throwable? = null
        for (p in chain(i)) {
            try { return p.getQuote(i) } catch (e: Exception) { last = e }
        }
        // Offline: last cached daily close
        val cached = db.candles().latest(i.key, Timeframe.D1.label, 2)
        if (cached.isNotEmpty()) {
            val c = cached.first(); val prev = cached.getOrNull(1)?.close ?: c.open
            return Quote(i.key, c.close, c.close - prev, (c.close / prev - 1) * 100, c.open, c.high, c.low, prev, c.volume,
                SourceBadge(DataSource.CACHE, false, c.time))
        }
        throw last ?: IllegalStateException("No data source for ${i.symbol}")
    }

    suspend fun quotes(list: List<Instrument>): Map<String, Quote> {
        val out = HashMap<String, Quote>()
        val (crypto, indian) = list.partition { it.isCrypto }
        val broker = brokers.primary()
        if (broker != null && indian.isNotEmpty()) runCatching { broker.getQuotes(indian) }.getOrNull()?.forEach { out[it.instrumentKey] = it }
        for (i in list) if (!out.containsKey(i.key)) runCatching { quote(i) }.onSuccess { out[i.key] = it }
        return out
    }

    suspend fun candles(i: Instrument, tf: Timeframe, bars: Int = 400): CandleResult {
        val to = System.currentTimeMillis() / 1000
        val span = when (tf) {
            Timeframe.M1 -> 5L * 86400; Timeframe.M5 -> 20L * 86400; Timeframe.M15 -> 55L * 86400; Timeframe.H1 -> 180L * 86400
            Timeframe.D1 -> (bars * 1.5).toLong() * 86400; Timeframe.W1 -> bars * 7L * 86400
        }
        var err: String? = null
        for (p in chain(i)) {
            try {
                val c = p.getCandles(i, tf, to - span, to).takeLast(bars)
                if (c.isNotEmpty()) {
                    db.candles().upsert(c.map { CandleEntity(i.key, tf.label, it.time, it.open, it.high, it.low, it.close, it.volume, it.oi, p.source.name) })
                    return CandleResult(c, SourceBadge(p.source, p.realtime, c.last().time))
                }
            } catch (e: Exception) { err = "${p.source.label}: ${e.message}" }
        }
        val cached = db.candles().latest(i.key, tf.label, bars).map { it.toDomain() }.reversed()
        return CandleResult(cached, SourceBadge(DataSource.CACHE, false, cached.lastOrNull()?.time ?: 0), err ?: if (cached.isEmpty()) "No data" else null)
    }

    /** Long history for backtests / screener (daily). */
    suspend fun history(i: Instrument, tf: Timeframe, years: Int): List<Candle> {
        val to = System.currentTimeMillis() / 1000
        for (p in chain(i)) {
            runCatching { p.getCandles(i, tf, to - years * 365L * 86400, to) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return db.candles().latest(i.key, tf.label, 5000).map { it.toDomain() }.reversed()
    }

    /** Streams only the given (visible) instruments; each source auto-reconnects. */
    fun stream(list: List<Instrument>): Flow<Quote> {
        if (list.isEmpty()) return emptyFlow()
        val (crypto, indian) = list.partition { it.isCrypto }
        val flows = mutableListOf<Flow<Quote>>()
        if (crypto.isNotEmpty()) flows += delta.streamTicks(crypto).catch { }
        if (indian.isNotEmpty()) {
            val b = brokers.primary()
            flows += (b?.streamTicks(indian) ?: yahoo.streamTicks(indian.filter { it.yahooSymbol() != null })).catch { emitAll(yahoo.streamTicks(indian)) }
        }
        return merge(*flows.toTypedArray())
    }

    suspend fun depth(i: Instrument): MarketDepth? = if (i.isCrypto) delta.getDepth(i) else brokers.primary()?.getDepth(i)

    suspend fun optionChain(underlying: Instrument, expiry: Long? = null): OptionChain? =
        if (underlying.isCrypto) delta.getOptionChain(underlying, expiry) else brokers.primary()?.getOptionChain(underlying, expiry)

    suspend fun search(q: String, crypto: Boolean): List<Instrument> {
        if (q.isBlank()) return emptyList()
        if (crypto) return runCatching { delta.searchProducts(q).map { it.toInstrument() } }.getOrDefault(emptyList())
        val local = db.instruments().search(q.uppercase(), 40).map { it.toDomain() }.distinctBy { it.key }
        val typed = Instrument.parse(q)
        return (listOf(typed) + local).distinctBy { it.key }
    }

    fun currentSourceLabel(i: Instrument): String = chain(i).firstOrNull()?.source?.label ?: "None"
}
