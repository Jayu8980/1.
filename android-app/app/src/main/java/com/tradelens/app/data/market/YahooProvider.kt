package com.tradelens.app.data.market

import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.RateLimiter
import com.tradelens.app.data.net.a
import com.tradelens.app.data.net.d
import com.tradelens.app.data.net.dbl
import com.tradelens.app.data.net.l
import com.tradelens.app.data.net.o
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.DataSource
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.domain.Timeframe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Free default: Yahoo Finance v8 chart endpoint. Data is delayed (~15 min for NSE/BSE). */
@Singleton
class YahooProvider @Inject constructor() : DataProvider {
    override val source = DataSource.YAHOO
    override val realtime = false
    private val limiter = RateLimiter(permitsPerSecond = 2.0, burst = 4)

    private fun sym(i: Instrument) = i.yahooSymbol() ?: throw IllegalArgumentException("${i.symbol} is not available on Yahoo Finance")

    private suspend fun chart(symbol: String, query: String) = Http.execute(
        Request.Builder()
            .url("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}?$query&includePrePost=false&events=div%2Csplit")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .build(),
        limiter,
    ).let { parseJson(it).obj().o("chart").a("result")?.firstOrNull().obj() ?: throw IllegalStateException("No data for $symbol") }

    override suspend fun getQuote(i: Instrument): Quote {
        val r = chart(sym(i), "interval=1d&range=5d")
        val meta = r.o("meta")
        val ltp = meta.d("regularMarketPrice") ?: throw IllegalStateException("No price for ${i.symbol}")
        val prev = meta.d("chartPreviousClose") ?: meta.d("previousClose") ?: ltp
        val closes = r.o("indicators").a("quote")?.firstOrNull().obj().a("close")?.mapNotNull { it.dbl() }.orEmpty()
        val prevDay = if (closes.size >= 2) closes[closes.size - 2] else prev
        val t = meta.l("regularMarketTime") ?: (System.currentTimeMillis() / 1000)
        return Quote(
            i.key, ltp, ltp - prevDay, if (prevDay != 0.0) (ltp / prevDay - 1) * 100 else 0.0,
            high = meta.d("regularMarketDayHigh") ?: 0.0, low = meta.d("regularMarketDayLow") ?: 0.0,
            prevClose = prevDay, volume = meta.d("regularMarketVolume") ?: 0.0,
            badge = SourceBadge(DataSource.YAHOO, false, t),
        )
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val interval = when (tf) {
            Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.H1 -> "60m"
            Timeframe.D1 -> "1d"; Timeframe.W1 -> "1wk"
        }
        // Yahoo limits intraday history: 1m ≈ 7 days, 5m/15m ≈ 60 days, 60m ≈ 730 days.
        val maxBack = when (tf) { Timeframe.M1 -> 7L; Timeframe.M5, Timeframe.M15 -> 59L; Timeframe.H1 -> 729L; else -> 36500L } * 86400
        val start = maxOf(from, to - maxBack)
        val r = chart(sym(i), "interval=$interval&period1=$start&period2=$to")
        val ts = r.a("timestamp") ?: return emptyList()
        val q = r.o("indicators").a("quote")?.firstOrNull().obj()
        val o = q.a("open"); val h = q.a("high"); val lo = q.a("low"); val c = q.a("close"); val v = q.a("volume")
        val out = ArrayList<Candle>(ts.size)
        for (k in ts.indices) {
            val cl = c?.getOrNull(k).dbl() ?: continue
            out += Candle(
                ts[k].dbl()!!.toLong(), o?.getOrNull(k).dbl() ?: cl, h?.getOrNull(k).dbl() ?: cl,
                lo?.getOrNull(k).dbl() ?: cl, cl, v?.getOrNull(k).dbl() ?: 0.0,
            )
        }
        return out
    }

    /** Polling "stream" (Yahoo has no public socket); slows down outside market hours. */
    override fun streamTicks(list: List<Instrument>): Flow<Quote> = flow {
        while (true) {
            for (i in list) runCatching { getQuote(i) }.onSuccess { emit(it) }
            delay(if (list.any { MarketHours.isOpen(it) }) 15_000 else 120_000)
        }
    }
}
