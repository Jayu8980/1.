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
import com.tradelens.app.data.net.l
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
import com.tradelens.app.domain.OptionChain
import com.tradelens.app.domain.OptionQuote
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Order
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
import kotlinx.serialization.json.JsonArray
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Upstox API v2/v3: OAuth2, REST, protobuf market-data feed v3. */
@Singleton
class UpstoxBroker @Inject constructor(secure: SecureStore, private val lookup: InstrumentLookup) : BaseBroker(secure) {
    override val id = "upstox"
    override val displayName = "Upstox"
    override val source = DataSource.UPSTOX
    override val limiter = RateLimiter(20.0, 10)
    override val expiryHourIst = 3
    override val configFields = listOf(
        field("api_key", "API key (client_id)"), field("api_secret", "API secret", secret = true),
        field("redirect_uri", "Redirect URI", hint = "Must exactly match the app's redirect in the Upstox developer console, e.g. https://<engine>/auth/callback/upstox"),
    )
    private val base = "https://api.upstox.com"

    override fun authHeaders(rb: Request.Builder) = rb.header("Authorization", "Bearer ${requireToken()}").header("Accept", "application/json")

    override fun loginMethod(redirectUri: String) = LoginMethod.OAuth(
        "$base/v2/login/authorization/dialog?response_type=code&client_id=${cfg("api_key")}&redirect_uri=${URLEncoder.encode(cfg("redirect_uri") ?: redirectUri, "UTF-8")}&state=upstox",
    )

    override suspend fun login(params: Map<String, String>) {
        val code = params["code"] ?: throw IllegalArgumentException("code missing")
        val r = postForm("$base/v2/login/authorization/token", mapOf(
            "code" to code, "client_id" to cfg("api_key")!!, "client_secret" to cfg("api_secret")!!,
            "redirect_uri" to (cfg("redirect_uri") ?: "tradelens://auth/upstox"), "grant_type" to "authorization_code",
        ))
        saveSession(r.s("access_token") ?: throw IllegalStateException("Upstox login failed: $r"))
    }

    private suspend fun key(i: Instrument): String = when (InstrumentLookup.canonicalIndex(i)) {
        "NIFTY" -> "NSE_INDEX|Nifty 50"; "BANKNIFTY" -> "NSE_INDEX|Nifty Bank"; "FINNIFTY" -> "NSE_INDEX|Nifty Fin Service"
        "INDIAVIX" -> "NSE_INDEX|India VIX"; "SENSEX" -> "BSE_INDEX|SENSEX"; "MIDCPNIFTY" -> "NSE_INDEX|NIFTY MID SELECT"
        null -> lookup.require(id, i)
        else -> "NSE_INDEX|${i.symbol}"
    }

    override suspend fun getQuote(i: Instrument) = getQuotes(listOf(i)).first()

    override suspend fun getQuotes(list: List<Instrument>): List<Quote> {
        val keys = list.map { key(it) }
        val data = get("$base/v2/market-quote/quotes?instrument_key=${URLEncoder.encode(keys.joinToString(","), "UTF-8")}").o("data")
        val byToken = data?.values?.mapNotNull { it.obj() }?.associateBy { it.s("instrument_token") }.orEmpty()
        return list.mapIndexedNotNull { idx, i ->
            val q = byToken[keys[idx]] ?: return@mapIndexedNotNull null
            val ltp = q.d("last_price") ?: 0.0
            val prev = q.o("ohlc").d("close")?.takeIf { it > 0 } ?: (ltp - (q.d("net_change") ?: 0.0))
            Quote(i.key, ltp, ltp - prev, if (prev > 0) (ltp / prev - 1) * 100 else 0.0, q.o("ohlc").d("open") ?: 0.0, q.o("ohlc").d("high") ?: 0.0,
                q.o("ohlc").d("low") ?: 0.0, prev, q.d("volume") ?: 0.0, SourceBadge(source, true, System.currentTimeMillis() / 1000),
                q.o("depth").a("buy")?.firstOrNull().obj().d("price") ?: 0.0, q.o("depth").a("sell")?.firstOrNull().obj().d("price") ?: 0.0)
        }
    }

    override suspend fun getDepth(i: Instrument): MarketDepth? {
        val q = get("$base/v2/market-quote/quotes?instrument_key=${URLEncoder.encode(key(i), "UTF-8")}").o("data")?.values?.firstOrNull().obj() ?: return null
        fun side(n: String) = q.o("depth").a(n)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("quantity") ?: 0.0, it.d("orders")?.toInt() ?: 0) } }.orEmpty()
        return MarketDepth(side("buy"), side("sell"))
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val (unit, interval, chunkDays) = when (tf) {
            Timeframe.M1 -> Triple("minutes", 1, 28L); Timeframe.M5 -> Triple("minutes", 5, 28L); Timeframe.M15 -> Triple("minutes", 15, 28L)
            Timeframe.H1 -> Triple("hours", 1, 85L); Timeframe.D1 -> Triple("days", 1, 3600L); Timeframe.W1 -> Triple("weeks", 1, 3600L)
        }
        val k = URLEncoder.encode(key(i), "UTF-8")
        val out = ArrayList<Candle>()
        fun day(e: Long) = Instant.ofEpochSecond(e).atZone(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate().toString()
        var end = to
        while (end > from) {
            val start = maxOf(from, end - chunkDays * 86400)
            val r = runCatching { get("$base/v3/historical-candle/$k/$unit/$interval/${day(end)}/${day(start)}") }.getOrNull()
            r?.o("data").a("candles")?.forEach { e -> parseCandle(e as? JsonArray)?.let { out += it } }
            end = start - 86400
        }
        if (to > System.currentTimeMillis() / 1000 - 86400 && tf != Timeframe.D1 && tf != Timeframe.W1) {
            runCatching { get("$base/v3/historical-candle/intraday/$k/$unit/$interval") }.getOrNull()
                ?.o("data").a("candles")?.forEach { e -> parseCandle(e as? JsonArray)?.let { out += it } }
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    private fun parseCandle(a: JsonArray?): Candle? {
        if (a == null || a.size < 6) return null
        return Candle(OffsetDateTime.parse(a[0].toString().trim('"')).toEpochSecond(), a[1].dbl()!!, a[2].dbl()!!, a[3].dbl()!!, a[4].dbl()!!,
            a[5].dbl() ?: 0.0, a.getOrNull(6).dbl() ?: 0.0)
    }

    override suspend fun getOptionChain(underlying: Instrument, expiry: Long?): OptionChain? {
        val k = URLEncoder.encode(key(underlying), "UTF-8")
        val exp = expiry ?: get("$base/v2/option/contract?instrument_key=$k").a("data").orEmpty()
            .mapNotNull { it.obj().s("expiry") }.distinct().sorted().firstOrNull()?.let { LocalDate.parse(it).atTime(15, 30).toEpochSecond(ZoneOffset.ofHoursMinutes(5, 30)) } ?: return null
        val expStr = Instant.ofEpochSecond(exp).atZone(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate().toString()
        val rows = get("$base/v2/option/chain?instrument_key=$k&expiry_date=$expStr").a("data").orEmpty().mapNotNull { it.obj() }
        if (rows.isEmpty()) return null
        val spot = rows.first().d("underlying_spot_price") ?: 0.0
        val out = rows.flatMap { r ->
            val strike = r.d("strike_price") ?: return@flatMap emptyList()
            listOf("call_options" to OptionType.CE, "put_options" to OptionType.PE).mapNotNull { (n, t) ->
                val o = r.o(n) ?: return@mapNotNull null
                val m = o.o("market_data"); val g = o.o("option_greeks")
                OptionQuote(strike, t, m.d("ltp") ?: 0.0, m.d("oi") ?: 0.0, (m.d("oi") ?: 0.0) - (m.d("prev_oi") ?: 0.0), m.d("volume") ?: 0.0,
                    g.d("iv"), m.d("bid_price") ?: 0.0, m.d("ask_price") ?: 0.0, o.s("instrument_key") ?: "", (m.d("ltp") ?: 0.0) - (m.d("close_price") ?: 0.0))
            }
        }
        return OptionChain(underlying.symbol, spot, exp, out, SourceBadge(source, true, System.currentTimeMillis() / 1000))
    }

    /** v3 market-data feed (protobuf). LTPC mode keeps bandwidth minimal. */
    override fun streamTicks(list: List<Instrument>): Flow<Quote> = flow {
        emit(list.associateBy { key(it) })
    }.flatMapConcat { byKey ->
        val prevClose = HashMap<String, Double>()
        reconnectingSocket(
            request = {
                val url = get("$base/v3/feed/market-data-feed/authorize").o("data").s("authorized_redirect_uri") ?: throw IllegalStateException("No feed URL")
                Request.Builder().url(url).build()
            },
            onOpen = { ws ->
                val keys = byKey.keys.joinToString(",") { "\"$it\"" }
                val msg = "{\"guid\":\"${UUID.randomUUID()}\",\"method\":\"sub\",\"data\":{\"mode\":\"ltpc\",\"instrumentKeys\":[$keys]}}"
                ws.send(msg.toByteArray().toByteString())
            },
        ).let { f ->
            flow {
                f.collect { ev ->
                    val bytes = (ev as? WsEvent.Binary)?.bytes?.toByteArray() ?: return@collect
                    for ((k, ltp, cp) in decodeFeed(bytes)) {
                        val inst = byKey[k] ?: continue
                        if (cp > 0) prevClose[k] = cp
                        val pc = prevClose[k] ?: ltp
                        emit(Quote(inst.key, ltp, ltp - pc, if (pc > 0) (ltp / pc - 1) * 100 else 0.0, prevClose = pc,
                            badge = SourceBadge(source, true, System.currentTimeMillis() / 1000)))
                    }
                }
            }
        }
    }

    override suspend fun getHoldings(): List<Holding> = get("$base/v2/portfolio/long-term-holdings").a("data").orEmpty().mapNotNull { e ->
        val h = e.obj() ?: return@mapNotNull null
        Holding(h.s("tradingsymbol") ?: "", h.s("exchange") ?: "NSE", h.d("quantity") ?: 0.0, h.d("average_price") ?: 0.0,
            h.d("last_price") ?: 0.0, h.d("close_price") ?: 0.0, displayName)
    }

    override suspend fun getPositions(): List<Position> = get("$base/v2/portfolio/short-term-positions").a("data").orEmpty().mapNotNull { e ->
        val p = e.obj() ?: return@mapNotNull null
        Position("${p.s("exchange")}:${p.s("tradingsymbol")}", p.s("tradingsymbol") ?: "", p.d("quantity") ?: 0.0, p.d("average_price") ?: 0.0,
            p.d("last_price") ?: 0.0, p.d("pnl") ?: 0.0, p.d("day_pnl") ?: p.d("pnl") ?: 0.0, displayName)
    }

    override suspend fun getOrders(): List<Order> = get("$base/v2/order/retrieve-all").a("data").orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        Order(o.s("order_id") ?: "", "${o.s("exchange")}:${o.s("tradingsymbol")}", if (o.s("transaction_type") == "BUY") Side.BUY else Side.SELL,
            o.d("quantity") ?: 0.0, o.d("filled_quantity") ?: 0.0, KiteBroker.mapType(o.s("order_type")), o.d("price"), o.d("average_price"),
            KiteBroker.mapStatus(o.s("status")), KiteBroker.parseTs(o.s("order_timestamp")), displayName, o.s("status_message") ?: "")
    }

    override suspend fun getFunds(): Funds {
        val eq = get("$base/v2/user/get-funds-and-margin?segment=SEC").o("data").o("equity")
        val avail = eq.d("available_margin") ?: 0.0; val used = eq.d("used_margin") ?: 0.0
        return Funds(avail, used, avail + used, "INR", displayName)
    }

    override suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz").build()
        Http.client.newCall(req).execute().use { r ->
            val reader = android.util.JsonReader(GZIPInputStream(r.body!!.byteStream()).bufferedReader())
            val batch = ArrayList<Instrument>(2000)
            reader.beginArray()
            while (reader.hasNext()) {
                val m = HashMap<String, String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val n = reader.nextName()
                    if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); continue }
                    if (reader.peek() == android.util.JsonToken.BEGIN_ARRAY || reader.peek() == android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    m[n] = reader.nextString()
                }
                reader.endObject()
                toInstrument(m)?.let { batch += it }
                if (batch.size >= 2000) { sink(ArrayList(batch)); batch.clear() }
            }
            reader.endArray()
            if (batch.isNotEmpty()) sink(batch)
        }
    }

    private fun toInstrument(m: Map<String, String>): Instrument? {
        val seg = m["segment"] ?: return null
        val type = m["instrument_type"] ?: ""
        val exchange = when { seg.startsWith("NSE_FO") -> Exchange.NFO; seg.startsWith("BSE_FO") -> Exchange.BFO; seg.startsWith("NSE") -> Exchange.NSE
            seg.startsWith("BSE") -> Exchange.BSE; seg.startsWith("MCX") -> Exchange.MCX; else -> return null }
        val segment = when {
            seg.endsWith("INDEX") -> Segment.INDEX
            type == "EQ" -> Segment.EQUITY
            type.startsWith("FUT") -> if (exchange == Exchange.MCX) Segment.COMMODITY else Segment.FUTURES
            type == "CE" || type == "PE" -> Segment.OPTIONS
            else -> return null
        }
        return Instrument(m["trading_symbol"] ?: return null, exchange, segment, m["instrument_key"] ?: return null, m["name"] ?: "",
            m["lot_size"]?.toDoubleOrNull()?.toInt() ?: 1, m["tick_size"]?.toDoubleOrNull()?.let { if (it >= 1) it / 100 else it } ?: 0.05,
            m["expiry"]?.toLongOrNull()?.let { it / 1000 }, m["strike_price"]?.toDoubleOrNull()?.takeIf { it > 0 },
            if (type == "CE") OptionType.CE else if (type == "PE") OptionType.PE else null, m["underlying_symbol"] ?: m["name"])
    }

    override fun brokerAppLink(i: Instrument) = BrokerAppLink("in.upstox.app", "https://pro.upstox.com/")

    companion object {
        /** FeedResponse{ feeds(2): map<string, Feed> } → Feed{ ltpc(1): LTPC{ ltp(1), cp(4) } | fullFeed(2) | firstLevelWithGreeks(3){ ltpc(1) } } */
        fun decodeFeed(bytes: ByteArray): List<Triple<String, Double, Double>> {
            val out = ArrayList<Triple<String, Double, Double>>()
            val r = ProtoReader(bytes)
            while (r.hasMore()) {
                val (f, w) = r.tag()
                if (f == 2 && w == 2) {
                    val entry = r.bytes(); var k = ""; var ltp = Double.NaN; var cp = 0.0
                    while (entry.hasMore()) {
                        val (ef, ew) = entry.tag()
                        when {
                            ef == 1 && ew == 2 -> k = entry.string()
                            ef == 2 && ew == 2 -> {
                                val feed = entry.bytes()
                                while (feed.hasMore()) {
                                    val (ff, fw) = feed.tag()
                                    if ((ff == 1 || ff == 3) && fw == 2) {
                                        val ltpc = if (ff == 1) feed.bytes() else findLtpc(feed.bytes())
                                        if (ltpc != null) { val (a, b) = readLtpc(ltpc); ltp = a; cp = b }
                                    } else if (ff == 2 && fw == 2) {
                                        // FullFeed{ marketFF(1){ ltpc(1) } | indexFF(2){ ltpc(1) } }
                                        val full = feed.bytes()
                                        while (full.hasMore()) {
                                            val (uf, uw) = full.tag()
                                            if ((uf == 1 || uf == 2) && uw == 2) findLtpc(full.bytes())?.let { val (a, b) = readLtpc(it); ltp = a; cp = b } else full.skip(uw)
                                        }
                                    } else feed.skip(fw)
                                }
                            }
                            else -> entry.skip(ew)
                        }
                    }
                    if (k.isNotEmpty() && !ltp.isNaN()) out += Triple(k, ltp, cp)
                } else r.skip(w)
            }
            return out
        }

        private fun findLtpc(m: ProtoReader): ProtoReader? {
            while (m.hasMore()) { val (f, w) = m.tag(); if (f == 1 && w == 2) return m.bytes() else m.skip(w) }
            return null
        }

        private fun readLtpc(r: ProtoReader): Pair<Double, Double> {
            var ltp = Double.NaN; var cp = 0.0
            while (r.hasMore()) {
                val (f, w) = r.tag()
                when { f == 1 && w == 1 -> ltp = r.double(); f == 4 && w == 1 -> cp = r.double(); else -> r.skip(w) }
            }
            return ltp to cp
        }
    }
}
