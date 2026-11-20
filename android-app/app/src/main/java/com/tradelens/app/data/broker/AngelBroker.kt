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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Angel One SmartAPI: client code + PIN + TOTP login, REST + SmartStream v2 binary socket. */
@Singleton
class AngelBroker @Inject constructor(secure: SecureStore, private val lookup: InstrumentLookup) : BaseBroker(secure) {
    override val id = "angel"
    override val displayName = "Angel One"
    override val source = DataSource.ANGEL
    override val limiter = RateLimiter(3.0, 3)
    override val expiryHourIst = 5
    override val configFields = listOf(field("api_key", "SmartAPI key"), field("client_code", "Client code"))
    private val base = "https://apiconnect.angelone.in"
    private val ist = ZoneOffset.ofHoursMinutes(5, 30)

    override fun authHeaders(rb: Request.Builder) = common(rb).header("Authorization", "Bearer ${requireToken()}")

    private fun common(rb: Request.Builder) = rb.header("Content-Type", "application/json").header("Accept", "application/json")
        .header("X-UserType", "USER").header("X-SourceID", "WEB").header("X-ClientLocalIP", "127.0.0.1")
        .header("X-ClientPublicIP", "127.0.0.1").header("X-MACAddress", "02:00:00:00:00:00").header("X-PrivateKey", cfg("api_key") ?: "")

    override fun loginMethod(redirectUri: String) = LoginMethod.Credentials

    /** params: pin, totp. The PIN is sent once and never stored. */
    override suspend fun login(params: Map<String, String>) {
        val body = buildJsonObject { put("clientcode", cfg("client_code")); put("password", params["pin"]); put("totp", params["totp"]) }.toString()
        val req = common(Request.Builder().url("$base/rest/auth/angelbroking/user/v1/loginByPassword")).post(body.toRequestBody(JSON)).build()
        val r = com.tradelens.app.data.net.parseJson(Http.execute(req, limiter)).obj()
        val d = r.o("data") ?: throw IllegalStateException("Angel login failed: ${r.s("message")}")
        saveSession(d.s("jwtToken") ?: error("no jwt"), mapOf("refresh_token" to (d.s("refreshToken") ?: ""), "feed_token" to (d.s("feedToken") ?: "")))
    }

    private fun exch(i: Instrument) = when (i.exchange) { Exchange.NFO -> "NFO"; Exchange.BFO -> "BFO"; Exchange.MCX -> "MCX"; Exchange.BSE -> "BSE"; else -> "NSE" }

    private suspend fun tok(i: Instrument): String = when (InstrumentLookup.canonicalIndex(i)) {
        "NIFTY" -> "99926000"; "BANKNIFTY" -> "99926009"; "FINNIFTY" -> "99926037"; "INDIAVIX" -> "99926017"; "SENSEX" -> "99919000"
        "MIDCPNIFTY" -> "99926074"; null -> lookup.require(id, i); else -> lookup.require(id, i)
    }

    override suspend fun getQuote(i: Instrument) = getQuotes(listOf(i)).first()

    override suspend fun getQuotes(list: List<Instrument>): List<Quote> {
        val toks = list.map { exch(it) to tok(it) }
        val body = buildJsonObject {
            put("mode", "FULL")
            putJsonObject("exchangeTokens") {
                toks.groupBy({ it.first }, { it.second }).forEach { (e, ts) -> putJsonArray(e) { ts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            }
        }
        val fetched = postJson("$base/rest/secure/angelbroking/market/v1/quote/", body.toString()).o("data").a("fetched").orEmpty().mapNotNull { it.obj() }
            .associateBy { it.s("symbolToken") }
        return list.mapIndexedNotNull { idx, i ->
            val q = fetched[toks[idx].second] ?: return@mapIndexedNotNull null
            val ltp = q.d("ltp") ?: 0.0; val prev = q.d("close") ?: ltp
            Quote(i.key, ltp, q.d("netChange") ?: (ltp - prev), q.d("percentChange") ?: 0.0, q.d("open") ?: 0.0, q.d("high") ?: 0.0, q.d("low") ?: 0.0,
                prev, q.d("tradeVolume") ?: 0.0, SourceBadge(source, true, System.currentTimeMillis() / 1000),
                q.o("depth").a("buy")?.firstOrNull().obj().d("price") ?: 0.0, q.o("depth").a("sell")?.firstOrNull().obj().d("price") ?: 0.0)
        }
    }

    override suspend fun getDepth(i: Instrument): MarketDepth? {
        val body = "{\"mode\":\"FULL\",\"exchangeTokens\":{\"${exch(i)}\":[\"${tok(i)}\"]}}"
        val q = postJson("$base/rest/secure/angelbroking/market/v1/quote/", body).o("data").a("fetched")?.firstOrNull().obj() ?: return null
        fun side(n: String) = q.o("depth").a(n)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("quantity") ?: 0.0, it.d("orders")?.toInt() ?: 0) } }.orEmpty()
        return MarketDepth(side("buy"), side("sell"))
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val (interval, chunk) = when (tf) {
            Timeframe.M1 -> "ONE_MINUTE" to 30L; Timeframe.M5 -> "FIVE_MINUTE" to 100L; Timeframe.M15 -> "FIFTEEN_MINUTE" to 200L
            Timeframe.H1 -> "ONE_HOUR" to 400L; else -> "ONE_DAY" to 2000L
        }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val t = tok(i); val out = ArrayList<Candle>()
        var start = from
        while (start < to) {
            val end = minOf(to, start + chunk * 86400)
            val body = buildJsonObject {
                put("exchange", exch(i)); put("symboltoken", t); put("interval", interval)
                put("fromdate", LocalDateTime.ofEpochSecond(start, 0, ist).format(fmt)); put("todate", LocalDateTime.ofEpochSecond(end, 0, ist).format(fmt))
            }
            postJson("$base/rest/secure/angelbroking/historical/v1/getCandleData", body.toString()).a("data")?.forEach { e ->
                val a = e as? JsonArray ?: return@forEach
                out += Candle(OffsetDateTime.parse(a[0].toString().trim('"')).toEpochSecond(), a[1].dbl()!!, a[2].dbl()!!, a[3].dbl()!!, a[4].dbl()!!, a[5].dbl() ?: 0.0)
            }
            start = end + 60
        }
        val s = out.distinctBy { it.time }.sortedBy { it.time }
        return if (tf == Timeframe.W1) resampleWeekly(s) else s
    }

    private fun exchangeType(i: Instrument) = when (i.exchange) { Exchange.NFO -> 2; Exchange.BSE -> 3; Exchange.BFO -> 4; Exchange.MCX -> 5; else -> 1 }

    /** SmartStream v2, LTP/quote packets are little-endian; prices are in paise. */
    override fun streamTicks(list: List<Instrument>): Flow<Quote> = flow {
        emit(list.associateBy { tok(it) })
    }.flatMapConcat { byTok ->
        reconnectingSocket(
            request = {
                Request.Builder().url("wss://smartapisocket.angelone.in/smart-stream")
                    .header("Authorization", requireToken()).header("x-api-key", cfg("api_key") ?: "")
                    .header("x-client-code", cfg("client_code") ?: "").header("x-feed-token", cfg("feed_token") ?: "").build()
            },
            onOpen = { ws ->
                val groups = list.groupBy { exchangeType(it) }
                val tl = groups.entries.joinToString(",") { (e, l) -> "{\"exchangeType\":$e,\"tokens\":[${l.joinToString(",") { "\"" + byTok.entries.first { en -> en.value == it }.key + "\"" }}]}" }
                ws.send("{\"correlationID\":\"tradelens\",\"action\":1,\"params\":{\"mode\":2,\"tokenList\":[$tl]}}")
            },
            heartbeat = { it.send("ping") }, heartbeatMs = 30_000,
        ).let { f ->
            flow {
                f.collect { ev ->
                    val b = (ev as? WsEvent.Binary)?.bytes?.toByteArray() ?: return@collect
                    if (b.size < 51) return@collect
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    val token = String(b, 2, 25).trim { it <= ' ' || it == '\u0000' }
                    val inst = byTok[token] ?: return@collect
                    val ltp = bb.getLong(43) / 100.0
                    val close = if (b.size >= 123) bb.getLong(115) / 100.0 else ltp
                    val vol = if (b.size >= 75) bb.getLong(67).toDouble() else 0.0
                    emit(Quote(inst.key, ltp, ltp - close, if (close > 0) (ltp / close - 1) * 100 else 0.0, prevClose = close, volume = vol,
                        badge = SourceBadge(source, true, System.currentTimeMillis() / 1000)))
                }
            }
        }
    }

    override suspend fun getHoldings(): List<Holding> = get("$base/rest/secure/angelbroking/portfolio/v1/getAllHolding").o("data").a("holdings").orEmpty().mapNotNull { e ->
        val h = e.obj() ?: return@mapNotNull null
        Holding(h.s("tradingsymbol")?.removeSuffix("-EQ") ?: "", h.s("exchange") ?: "NSE", h.d("quantity") ?: 0.0, h.d("averageprice") ?: 0.0,
            h.d("ltp") ?: 0.0, h.d("close") ?: 0.0, displayName)
    }

    override suspend fun getPositions(): List<Position> = get("$base/rest/secure/angelbroking/order/v1/getPosition").a("data").orEmpty().mapNotNull { e ->
        val p = e.obj() ?: return@mapNotNull null
        Position("${p.s("exchange")}:${p.s("tradingsymbol")}", p.s("tradingsymbol") ?: "", p.d("netqty") ?: 0.0, p.d("avgnetprice") ?: p.d("netprice") ?: 0.0,
            p.d("ltp") ?: 0.0, p.d("pnl") ?: p.d("unrealised") ?: 0.0, broker = displayName)
    }

    override suspend fun getOrders(): List<Order> = get("$base/rest/secure/angelbroking/order/v1/getOrderBook").a("data").orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        Order(o.s("orderid") ?: "", "${o.s("exchange")}:${o.s("tradingsymbol")}", if (o.s("transactiontype") == "BUY") Side.BUY else Side.SELL,
            o.d("quantity") ?: 0.0, o.d("filledshares") ?: 0.0, KiteBroker.mapType(o.s("ordertype")?.replace("STOPLOSS_LIMIT", "SL")?.replace("STOPLOSS_MARKET", "SL-M")),
            o.d("price"), o.d("averageprice"), KiteBroker.mapStatus(o.s("status")), parseAngelTime(o.s("updatetime")), displayName, o.s("text") ?: "")
    }

    private fun parseAngelTime(s: String?): Long = s?.let {
        runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)).toEpochSecond(ist) }.getOrNull()
    } ?: 0L

    override suspend fun getFunds(): Funds {
        val d = get("$base/rest/secure/angelbroking/user/v1/getRMS").o("data")
        val avail = d.d("availablecash") ?: d.d("net") ?: 0.0; val used = d.d("utiliseddebits") ?: 0.0
        return Funds(avail, used, avail + used, "INR", displayName)
    }

    override suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json").build()
        Http.client.newCall(req).execute().use { r ->
            val reader = android.util.JsonReader(r.body!!.byteStream().bufferedReader())
            val batch = ArrayList<Instrument>(2000)
            val fmt = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH)
            reader.beginArray()
            while (reader.hasNext()) {
                val m = HashMap<String, String>()
                reader.beginObject()
                while (reader.hasNext()) { val n = reader.nextName(); if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull() } else m[n] = reader.nextString() }
                reader.endObject()
                val seg = m["exch_seg"] ?: continue
                val type = m["instrumenttype"] ?: ""
                val symbol = m["symbol"] ?: continue
                val exchange = when (seg) { "NSE" -> Exchange.NSE; "BSE" -> Exchange.BSE; "NFO" -> Exchange.NFO; "BFO" -> Exchange.BFO; "MCX" -> Exchange.MCX; else -> continue }
                val segment = when {
                    type == "AMXIDX" -> Segment.INDEX
                    type.isEmpty() && (symbol.endsWith("-EQ") || seg == "BSE") -> Segment.EQUITY
                    type.startsWith("FUT") -> if (exchange == Exchange.MCX) Segment.COMMODITY else Segment.FUTURES
                    type.startsWith("OPT") -> Segment.OPTIONS
                    else -> continue
                }
                val optType = if (segment == Segment.OPTIONS) (if (symbol.endsWith("CE")) OptionType.CE else OptionType.PE) else null
                batch += Instrument(if (segment == Segment.EQUITY) symbol.removeSuffix("-EQ") else symbol, exchange, segment, m["token"] ?: continue, m["name"] ?: symbol,
                    m["lotsize"]?.toDoubleOrNull()?.toInt() ?: 1, (m["tick_size"]?.toDoubleOrNull() ?: 5.0) / 100,
                    m["expiry"]?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it.lowercase().replaceFirstChar { c -> c }.let { s -> s.substring(0, 2) + s.substring(2, 3).uppercase() + s.substring(3) }, fmt).atTime(15, 30).toEpochSecond(ist) }.getOrNull() },
                    m["strike"]?.toDoubleOrNull()?.takeIf { it > 0 }?.div(100), optType, m["name"])
                if (batch.size >= 2000) { sink(ArrayList(batch)); batch.clear() }
            }
            reader.endArray()
            if (batch.isNotEmpty()) sink(batch)
        }
    }

    override fun brokerAppLink(i: Instrument) = BrokerAppLink("com.msf.angelmobile", "https://www.angelone.in/trade/markets/equity/overview")
}
