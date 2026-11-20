package com.tradelens.app.data.broker

import com.tradelens.app.data.market.BrokerAppLink
import com.tradelens.app.data.market.LoginMethod
import com.tradelens.app.data.market.WsEvent
import com.tradelens.app.data.market.reconnectingSocket
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.RateLimiter
import com.tradelens.app.data.net.a
import com.tradelens.app.data.net.d
import com.tradelens.app.data.net.dbl
import com.tradelens.app.data.net.o
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.s
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.DataSource
import com.tradelens.app.domain.DepthLevel
import com.tradelens.app.domain.Exchange
import com.tradelens.app.domain.Funds
import com.tradelens.app.domain.Holding
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketDepth
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Order
import com.tradelens.app.domain.OrderStatus
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.Position
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.Segment
import com.tradelens.app.domain.Side
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.domain.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Zerodha Kite Connect v3. */
@Singleton
class KiteBroker @Inject constructor(secure: SecureStore) : BaseBroker(secure) {
    override val id = "kite"
    override val displayName = "Zerodha Kite"
    override val source = DataSource.KITE
    override val limiter = RateLimiter(3.0, 3)
    override val configFields = listOf(
        field("api_key", "API key"), field("api_secret", "API secret", secret = true,
            hint = "Set the redirect URL in the Kite developer console to your engine's /auth/callback/kite (it forwards to tradelens://auth/kite)"),
    )
    private val base = "https://api.kite.trade"
    private val tokenCache = HashMap<String, Long>() // "NSE:INFY" -> instrument_token

    override fun authHeaders(rb: Request.Builder) = rb.header("X-Kite-Version", "3")
        .header("Authorization", "token ${cfg("api_key")}:${requireToken()}")

    override fun loginMethod(redirectUri: String) = LoginMethod.OAuth("https://kite.zerodha.com/connect/login?v=3&api_key=${cfg("api_key")}")

    override suspend fun login(params: Map<String, String>) {
        val rt = params["request_token"] ?: throw IllegalArgumentException("request_token missing")
        val key = cfg("api_key")!!; val secret = cfg("api_secret")!!
        val r = postForm("$base/session/token", mapOf("api_key" to key, "request_token" to rt, "checksum" to sha256Hex(key + rt + secret)),
            headers = mapOf("X-Kite-Version" to "3"))
        saveSession(r.o("data").s("access_token") ?: throw IllegalStateException("Kite login failed: $r"))
    }

    private fun kiteExchange(i: Instrument) = i.exchange.name
    private fun indexSymbol(i: Instrument) = if (i.segment == Segment.INDEX) when (i.symbol.uppercase()) {
        "NIFTY", "NIFTY50" -> "NIFTY 50"; "BANKNIFTY" -> "NIFTY BANK"; else -> i.symbol
    } else i.symbol

    override suspend fun getQuote(i: Instrument): Quote = getQuotes(listOf(i)).first()

    override suspend fun getQuotes(list: List<Instrument>): List<Quote> {
        if (list.isEmpty()) return emptyList()
        val keys = list.map { "${kiteExchange(it)}:${indexSymbol(it)}" }
        val r = get("$base/quote?" + keys.joinToString("&") { "i=" + java.net.URLEncoder.encode(it, "UTF-8") }).o("data")
        return list.mapIndexedNotNull { idx, i ->
            val q = r.o(keys[idx]) ?: return@mapIndexedNotNull null
            q.d("instrument_token")?.let { tokenCache[keys[idx]] = it.toLong() }
            val ltp = q.d("last_price") ?: 0.0; val prev = q.o("ohlc").d("close") ?: ltp
            val depth = q.o("depth")
            Quote(i.key, ltp, ltp - prev, if (prev > 0) (ltp / prev - 1) * 100 else 0.0, q.o("ohlc").d("open") ?: 0.0,
                q.o("ohlc").d("high") ?: 0.0, q.o("ohlc").d("low") ?: 0.0, prev, q.d("volume") ?: 0.0,
                SourceBadge(DataSource.KITE, true, System.currentTimeMillis() / 1000),
                depth.a("buy")?.firstOrNull().obj().d("price") ?: 0.0, depth.a("sell")?.firstOrNull().obj().d("price") ?: 0.0)
        }
    }

    override suspend fun getDepth(i: Instrument): MarketDepth? {
        val k = "${kiteExchange(i)}:${indexSymbol(i)}"
        val d = get("$base/quote?i=${java.net.URLEncoder.encode(k, "UTF-8")}").o("data").o(k).o("depth") ?: return null
        fun side(n: String) = d.a(n)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("quantity") ?: 0.0, it.d("orders")?.toInt() ?: 0) } }.orEmpty()
        return MarketDepth(side("buy"), side("sell"))
    }

    private suspend fun instrumentToken(i: Instrument): Long {
        i.token.toLongOrNull()?.let { return it }
        val k = "${kiteExchange(i)}:${indexSymbol(i)}"
        tokenCache[k]?.let { return it }
        get("$base/quote/ltp?i=${java.net.URLEncoder.encode(k, "UTF-8")}").o("data").o(k).d("instrument_token")?.let { tokenCache[k] = it.toLong(); return it.toLong() }
        throw IllegalStateException("Unknown Kite instrument $k")
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val interval = when (tf) { Timeframe.M1 -> "minute"; Timeframe.M5 -> "5minute"; Timeframe.M15 -> "15minute"; Timeframe.H1 -> "60minute"; else -> "day" }
        val maxDays = when (tf) { Timeframe.M1 -> 60L; Timeframe.M5, Timeframe.M15 -> 100L; Timeframe.H1 -> 400L; else -> 2000L }
        val tok = instrumentToken(i)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val out = ArrayList<Candle>()
        var start = from
        while (start < to) {
            val end = minOf(to, start + maxDays * 86400)
            val f = LocalDateTime.ofEpochSecond(start + 19800, 0, java.time.ZoneOffset.UTC).format(fmt)
            val t = LocalDateTime.ofEpochSecond(end + 19800, 0, java.time.ZoneOffset.UTC).format(fmt)
            val r = get("$base/instruments/historical/$tok/$interval?from=${f.replace(" ", "+")}&to=${t.replace(" ", "+")}&oi=1")
            r.o("data").a("candles")?.forEach { e ->
                val a = e as? kotlinx.serialization.json.JsonArray ?: return@forEach
                out += Candle(OffsetDateTime.parse(a[0].toString().trim('"'), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toEpochSecond(),
                    a[1].dbl()!!, a[2].dbl()!!, a[3].dbl()!!, a[4].dbl()!!, a[5].dbl() ?: 0.0, a.getOrNull(6).dbl() ?: 0.0)
            }
            start = end + 1
        }
        val sorted = out.distinctBy { it.time }.sortedBy { it.time }
        return if (tf == Timeframe.W1) resampleWeekly(sorted) else sorted
    }

    /** Binary ticker (quote mode, 44-byte packets). */
    override fun streamTicks(list: List<Instrument>): Flow<Quote> = flow {
        val tokens = list.associateBy { instrumentToken(it) }
        emit(tokens)
    }.flatMapConcat { tokens ->
        reconnectingSocket(
            request = { Request.Builder().url("wss://ws.kite.trade?api_key=${cfg("api_key")}&access_token=${requireToken()}").build() },
            onOpen = { ws ->
                val ids = tokens.keys.joinToString(",")
                ws.send("{\"a\":\"subscribe\",\"v\":[$ids]}"); ws.send("{\"a\":\"mode\",\"v\":[\"quote\",[$ids]]}")
            },
        ).let { f ->
            kotlinx.coroutines.flow.flow {
                f.collect { ev ->
                    val b = (ev as? WsEvent.Binary)?.bytes?.toByteArray() ?: return@collect
                    if (b.size < 2) return@collect
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                    val n = bb.short.toInt()
                    repeat(n) {
                        if (bb.remaining() < 2) return@collect
                        val len = bb.short.toInt()
                        val start = bb.position()
                        if (len >= 8 && bb.remaining() >= len) {
                            val tok = bb.int.toLong() and 0xffffffffL
                            val inst = tokens[tok]
                            val div = if ((tok and 0xff).toInt() == 3) 10_000_000.0 else 100.0
                            val ltp = bb.int / div
                            if (inst != null) {
                                var close = ltp; var vol = 0.0; var o = 0.0; var h = 0.0; var l = 0.0
                                if (len >= 44) {
                                    bb.position(start + 16); vol = bb.int.toDouble()
                                    bb.position(start + 28); o = bb.int / div; h = bb.int / div; l = bb.int / div; close = bb.int / div
                                } else if (len >= 28 && inst.segment == Segment.INDEX) {
                                    h = bb.int / div; l = bb.int / div; o = bb.int / div; close = bb.int / div
                                }
                                emit(Quote(inst.key, ltp, ltp - close, if (close > 0) (ltp / close - 1) * 100 else 0.0, o, h, l, close, vol,
                                    SourceBadge(DataSource.KITE, true, System.currentTimeMillis() / 1000)))
                            }
                        }
                        bb.position(start + len)
                    }
                }
            }
        }
    }

    override suspend fun getHoldings(): List<Holding> = get("$base/portfolio/holdings").a("data").orEmpty().mapNotNull { e ->
        val h = e.obj() ?: return@mapNotNull null
        Holding(h.s("tradingsymbol") ?: "", h.s("exchange") ?: "NSE", (h.d("quantity") ?: 0.0) + (h.d("t1_quantity") ?: 0.0),
            h.d("average_price") ?: 0.0, h.d("last_price") ?: 0.0, h.d("close_price") ?: 0.0, displayName)
    }

    override suspend fun getPositions(): List<Position> = get("$base/portfolio/positions").o("data").a("net").orEmpty().mapNotNull { e ->
        val p = e.obj() ?: return@mapNotNull null
        Position("${p.s("exchange")}:${p.s("tradingsymbol")}", p.s("tradingsymbol") ?: "", p.d("quantity") ?: 0.0, p.d("average_price") ?: 0.0,
            p.d("last_price") ?: 0.0, p.d("pnl") ?: 0.0, p.d("m2m") ?: 0.0, displayName)
    }

    override suspend fun getOrders(): List<Order> = get("$base/orders").a("data").orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        Order(o.s("order_id") ?: "", "${o.s("exchange")}:${o.s("tradingsymbol")}", if (o.s("transaction_type") == "BUY") Side.BUY else Side.SELL,
            o.d("quantity") ?: 0.0, o.d("filled_quantity") ?: 0.0, mapType(o.s("order_type")), o.d("price"), o.d("average_price"),
            mapStatus(o.s("status")), parseTs(o.s("order_timestamp")), displayName, o.s("status_message") ?: "")
    }

    override suspend fun getFunds(): Funds {
        val eq = get("$base/user/margins").o("data").o("equity")
        val avail = eq.d("net") ?: 0.0; val used = eq.o("utilised").d("debits") ?: 0.0
        return Funds(avail, used, avail + used, "INR", displayName)
    }

    override suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/instruments").header("X-Kite-Version", "3").build()
        Http.client.newCall(req).execute().use { r ->
            val stream = r.body!!.byteStream().let { if (r.header("Content-Encoding") == "gzip") GZIPInputStream(it) else it }
            val reader = stream.bufferedReader()
            val header = reader.readLine()?.split(",") ?: return@use
            val idx = header.withIndex().associate { it.value to it.index }
            val batch = ArrayList<Instrument>(2000)
            while (true) {
                val line = reader.readLine() ?: break
                val c = line.split(",")
                if (c.size < header.size) continue
                val exch = c[idx["exchange"]!!]
                val type = c[idx["instrument_type"]!!]
                val seg = c[idx["segment"]!!]
                val exchange = runCatching { Exchange.valueOf(exch) }.getOrNull() ?: continue
                val segment = when {
                    seg == "INDICES" -> Segment.INDEX
                    type == "EQ" -> Segment.EQUITY
                    type == "FUT" -> if (exchange == Exchange.MCX) Segment.COMMODITY else Segment.FUTURES
                    type == "CE" || type == "PE" -> Segment.OPTIONS
                    else -> continue
                }
                val expiry = c[idx["expiry"]!!].takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).atTime(15, 30).toEpochSecond(java.time.ZoneOffset.ofHoursMinutes(5, 30)) }
                batch += Instrument(c[idx["tradingsymbol"]!!], exchange, segment, c[idx["instrument_token"]!!], c[idx["name"]!!].trim('"').ifBlank { c[idx["tradingsymbol"]!!] },
                    c[idx["lot_size"]!!].toIntOrNull() ?: 1, c[idx["tick_size"]!!].toDoubleOrNull() ?: 0.05, expiry,
                    c[idx["strike"]!!].toDoubleOrNull()?.takeIf { it > 0 }, if (type == "CE") OptionType.CE else if (type == "PE") OptionType.PE else null,
                    c[idx["name"]!!].trim('"').takeIf { segment != Segment.EQUITY })
                if (batch.size >= 2000) { sink(ArrayList(batch)); batch.clear() }
            }
            if (batch.isNotEmpty()) sink(batch)
        }
    }

    override fun brokerAppLink(i: Instrument) = BrokerAppLink("com.zerodha.kite3", "https://kite.zerodha.com/chart/web/tvc/${i.exchange.name}/${i.symbol}/${i.token}")

    companion object {
        fun mapType(t: String?) = when (t) { "MARKET" -> OrderType.MARKET; "SL" -> OrderType.SL; "SL-M" -> OrderType.SL_M; else -> OrderType.LIMIT }
        fun mapStatus(s: String?) = when (s?.uppercase()) {
            "COMPLETE", "TRADED", "FILLED" -> OrderStatus.FILLED; "CANCELLED" -> OrderStatus.CANCELLED; "REJECTED" -> OrderStatus.REJECTED
            "OPEN", "TRIGGER PENDING", "PENDING" -> OrderStatus.OPEN; else -> OrderStatus.PENDING
        }
        fun parseTs(s: String?): Long = s?.let {
            runCatching { LocalDateTime.parse(it.replace(" ", "T").take(19)).toEpochSecond(java.time.ZoneOffset.ofHoursMinutes(5, 30)) }.getOrNull()
        } ?: 0L
    }
}

/** Resamples daily bars into ISO weeks (Mon-start). */
fun resampleWeekly(daily: List<Candle>): List<Candle> {
    if (daily.isEmpty()) return daily
    val out = ArrayList<Candle>()
    var cur: Candle? = null; var curWeek = Long.MIN_VALUE
    for (c in daily) {
        val days = Math.floorDiv(c.time + 19800, 86400L)
        val week = Math.floorDiv(days + 3, 7L) // epoch day 0 was a Thursday
        if (week != curWeek) { cur?.let { out += it }; cur = c; curWeek = week }
        else cur = cur!!.copy(high = maxOf(cur.high, c.high), low = minOf(cur.low, c.low), close = c.close, volume = cur.volume + c.volume, oi = c.oi)
    }
    cur?.let { out += it }
    return out
}
