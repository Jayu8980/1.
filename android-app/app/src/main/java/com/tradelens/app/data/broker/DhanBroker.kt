package com.tradelens.app.data.broker

import com.tradelens.app.data.market.BrokerAppLink
import com.tradelens.app.data.market.LoginMethod
import com.tradelens.app.data.market.WsEvent
import com.tradelens.app.data.market.reconnectingSocket
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.RateLimiter
import com.tradelens.app.data.net.a
import com.tradelens.app.data.net.arr
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** DhanHQ v2: access-token auth, REST + live market feed (binary). Data APIs may require Dhan's paid data plan. */
@Singleton
class DhanBroker @Inject constructor(secure: SecureStore, private val lookup: InstrumentLookup) : BaseBroker(secure) {
    override val id = "dhan"
    override val displayName = "Dhan"
    override val source = DataSource.DHAN
    override val limiter = RateLimiter(5.0, 5)
    override val expiryHourIst = 8
    override val configFields = listOf(field("client_id", "Dhan client ID"))
    private val base = "https://api.dhan.co/v2"
    private val ist = ZoneOffset.ofHoursMinutes(5, 30)

    override fun authHeaders(rb: Request.Builder) = rb.header("access-token", requireToken()).header("client-id", cfg("client_id") ?: "")
        .header("Content-Type", "application/json").header("Accept", "application/json")

    override fun loginMethod(redirectUri: String) = LoginMethod.AccessToken

    /** params: access_token (generated on web.dhan.co → My Profile → DhanHQ Trading APIs; valid 24 h). */
    override suspend fun login(params: Map<String, String>) {
        val t = params["access_token"]?.trim() ?: throw IllegalArgumentException("access token missing")
        saveSession(t)
        runCatching { getFunds() }.onFailure { logout(); throw IllegalStateException("Dhan rejected the token: ${it.message}") }
    }

    private fun segment(i: Instrument) = when {
        i.segment == Segment.INDEX -> "IDX_I"
        i.exchange == Exchange.NSE -> "NSE_EQ"; i.exchange == Exchange.BSE -> "BSE_EQ"; i.exchange == Exchange.NFO -> "NSE_FNO"
        i.exchange == Exchange.BFO -> "BSE_FNO"; i.exchange == Exchange.MCX -> "MCX_COMM"; else -> "NSE_EQ"
    }

    private fun instrumentType(i: Instrument) = when (i.segment) {
        Segment.INDEX -> "INDEX"; Segment.EQUITY -> "EQUITY"; Segment.FUTURES -> if (i.underlying != null && InstrumentLookup.canonicalIndex(i) == null) "FUTSTK" else "FUTIDX"
        Segment.OPTIONS -> "OPTIDX"; Segment.COMMODITY -> "FUTCOM"; else -> "EQUITY"
    }

    private suspend fun secId(i: Instrument): String = when (InstrumentLookup.canonicalIndex(i)) {
        "NIFTY" -> "13"; "BANKNIFTY" -> "25"; "FINNIFTY" -> "27"; "INDIAVIX" -> "21"; "SENSEX" -> "51"; "MIDCPNIFTY" -> "442"
        else -> lookup.require(id, i)
    }

    override suspend fun getQuote(i: Instrument) = getQuotes(listOf(i)).first()

    override suspend fun getQuotes(list: List<Instrument>): List<Quote> {
        val ids = list.map { segment(it) to secId(it) }
        val body = buildJsonObject {
            ids.groupBy({ it.first }, { it.second }).forEach { (seg, l) -> putJsonArray(seg) { l.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toLong())) } } }
        }
        val data = postJson("$base/marketfeed/quote", body.toString()).o("data")
        return list.mapIndexedNotNull { idx, i ->
            val q = data.o(ids[idx].first).o(ids[idx].second) ?: return@mapIndexedNotNull null
            val ltp = q.d("last_price") ?: 0.0; val prev = q.o("ohlc").d("close") ?: ltp
            Quote(i.key, ltp, ltp - prev, if (prev > 0) (ltp / prev - 1) * 100 else 0.0, q.o("ohlc").d("open") ?: 0.0, q.o("ohlc").d("high") ?: 0.0,
                q.o("ohlc").d("low") ?: 0.0, prev, q.d("volume") ?: 0.0, SourceBadge(source, true, System.currentTimeMillis() / 1000),
                q.o("depth").a("buy")?.firstOrNull().obj().d("price") ?: 0.0, q.o("depth").a("sell")?.firstOrNull().obj().d("price") ?: 0.0)
        }
    }

    override suspend fun getDepth(i: Instrument): MarketDepth? {
        val seg = segment(i); val sid = secId(i)
        val q = postJson("$base/marketfeed/quote", "{\"$seg\":[$sid]}").o("data").o(seg).o(sid) ?: return null
        fun side(n: String) = q.o("depth").a(n)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("quantity") ?: 0.0, it.d("orders")?.toInt() ?: 0) } }.orEmpty()
        return MarketDepth(side("buy"), side("sell"))
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val sid = secId(i); val out = ArrayList<Candle>()
        val daily = tf == Timeframe.D1 || tf == Timeframe.W1
        val chunk = if (daily) 3650L else 89L
        val dfmt = DateTimeFormatter.ofPattern("yyyy-MM-dd"); val tfmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        var start = from
        while (start < to) {
            val end = minOf(to, start + chunk * 86400)
            val body = buildJsonObject {
                put("securityId", sid); put("exchangeSegment", segment(i)); put("instrument", instrumentType(i)); put("oi", i.isDerivative)
                if (daily) {
                    put("expiryCode", 0)
                    put("fromDate", LocalDateTime.ofEpochSecond(start, 0, ist).format(dfmt)); put("toDate", LocalDateTime.ofEpochSecond(end + 86400, 0, ist).format(dfmt))
                } else {
                    put("interval", when (tf) { Timeframe.M1 -> "1"; Timeframe.M5 -> "5"; Timeframe.M15 -> "15"; else -> "60" })
                    put("fromDate", LocalDateTime.ofEpochSecond(start, 0, ist).format(tfmt)); put("toDate", LocalDateTime.ofEpochSecond(end, 0, ist).format(tfmt))
                }
            }
            val r = postJson("$base/charts/${if (daily) "historical" else "intraday"}", body.toString())
            val ts = r.a("timestamp").orEmpty()
            val o = r.a("open"); val h = r.a("high"); val l = r.a("low"); val c = r.a("close"); val v = r.a("volume"); val oi = r.a("open_interest")
            for (k in ts.indices) out += Candle(ts[k].dbl()!!.toLong(), o!![k].dbl()!!, h!![k].dbl()!!, l!![k].dbl()!!, c!![k].dbl()!!, v?.getOrNull(k).dbl() ?: 0.0, oi?.getOrNull(k).dbl() ?: 0.0)
            start = end + 1
        }
        val s = out.distinctBy { it.time }.sortedBy { it.time }
        return if (tf == Timeframe.W1) resampleWeekly(s) else s
    }

    override suspend fun getOptionChain(underlying: Instrument, expiry: Long?): OptionChain? {
        val sid = secId(underlying).toLong(); val seg = if (underlying.segment == Segment.INDEX) "IDX_I" else segment(underlying)
        val expStr = expiry?.let { Instant.ofEpochSecond(it).atZone(ist).toLocalDate().toString() }
            ?: postJson("$base/optionchain/expirylist", "{\"UnderlyingScrip\":$sid,\"UnderlyingSeg\":\"$seg\"}").a("data")?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return null
        val d = postJson("$base/optionchain", "{\"UnderlyingScrip\":$sid,\"UnderlyingSeg\":\"$seg\",\"Expiry\":\"$expStr\"}").o("data") ?: return null
        val rows = d.o("oc")?.entries.orEmpty().flatMap { (k, v) ->
            val strike = k.toDoubleOrNull() ?: return@flatMap emptyList()
            listOf("ce" to OptionType.CE, "pe" to OptionType.PE).mapNotNull { (n, t) ->
                val x = v.obj().o(n) ?: return@mapNotNull null
                OptionQuote(strike, t, x.d("last_price") ?: 0.0, x.d("oi") ?: 0.0, (x.d("oi") ?: 0.0) - (x.d("previous_oi") ?: 0.0), x.d("volume") ?: 0.0,
                    x.d("implied_volatility"), x.d("top_bid_price") ?: 0.0, x.d("top_ask_price") ?: 0.0, "", (x.d("last_price") ?: 0.0) - (x.d("previous_close_price") ?: 0.0))
            }
        }.sortedBy { it.strike }
        val exp = LocalDate.parse(expStr).atTime(15, 30).toEpochSecond(ist)
        return OptionChain(underlying.symbol, d.d("last_price") ?: 0.0, exp, rows, SourceBadge(source, true, System.currentTimeMillis() / 1000))
    }

    private fun segCode(seg: String) = when (seg) { "IDX_I" -> 0; "NSE_EQ" -> 1; "NSE_FNO" -> 2; "BSE_EQ" -> 4; "MCX_COMM" -> 5; "BSE_FNO" -> 8; else -> 1 }

    /** Live market feed v2: ticker (code 2) and prev-close (code 6) little-endian packets. */
    override fun streamTicks(list: List<Instrument>): Flow<Quote> = flow {
        emit(list.associateBy { segCode(segment(it)) to secId(it).toInt() })
    }.flatMapConcat { byId ->
        val prev = HashMap<Pair<Int, Int>, Double>()
        reconnectingSocket(
            request = { Request.Builder().url("wss://api-feed.dhan.co?version=2&token=${requireToken()}&clientId=${cfg("client_id")}&authType=2").build() },
            onOpen = { ws ->
                byId.entries.chunked(100).forEach { chunk ->
                    val items = chunk.joinToString(",") { (k, i) -> "{\"ExchangeSegment\":\"${segment(i)}\",\"SecurityId\":\"${k.second}\"}" }
                    ws.send("{\"RequestCode\":15,\"InstrumentCount\":${chunk.size},\"InstrumentList\":[$items]}")
                }
            },
        ).let { f ->
            flow {
                f.collect { ev ->
                    val b = (ev as? WsEvent.Binary)?.bytes?.toByteArray() ?: return@collect
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    var off = 0
                    while (off + 8 <= b.size) {
                        val code = b[off].toInt() and 0xff
                        val len = bb.getShort(off + 1).toInt() and 0xffff
                        val key = (b[off + 3].toInt() and 0xff) to bb.getInt(off + 4)
                        val inst = byId[key]
                        if (inst != null && code == 6 && off + 12 <= b.size) prev[key] = bb.getFloat(off + 8).toDouble()
                        if (inst != null && code == 2 && off + 12 <= b.size) {
                            val ltp = bb.getFloat(off + 8).toDouble(); val pc = prev[key] ?: ltp
                            emit(Quote(inst.key, ltp, ltp - pc, if (pc > 0) (ltp / pc - 1) * 100 else 0.0, prevClose = pc,
                                badge = SourceBadge(source, true, System.currentTimeMillis() / 1000)))
                        }
                        off += if (len >= 8) len else b.size
                    }
                }
            }
        }
    }

    override suspend fun getHoldings(): List<Holding> {
        val r = authHeaders(Request.Builder().url("$base/holdings")).get().build()
        val arr = com.tradelens.app.data.net.parseJson(Http.execute(r, limiter)).arr().orEmpty()
        return arr.mapNotNull { e ->
            val h = e.obj() ?: return@mapNotNull null
            val avg = h.d("avgCostPrice") ?: 0.0
            Holding(h.s("tradingSymbol") ?: "", h.s("exchange") ?: "NSE", h.d("totalQty") ?: 0.0, avg, h.d("lastTradedPrice") ?: avg, h.d("lastTradedPrice") ?: avg, displayName)
        }
    }

    override suspend fun getPositions(): List<Position> {
        val r = authHeaders(Request.Builder().url("$base/positions")).get().build()
        return com.tradelens.app.data.net.parseJson(Http.execute(r, limiter)).arr().orEmpty().mapNotNull { e ->
            val p = e.obj() ?: return@mapNotNull null
            val qty = p.d("netQty") ?: 0.0
            Position("${p.s("exchangeSegment")}:${p.s("tradingSymbol")}", p.s("tradingSymbol") ?: "", qty, p.d("costPrice") ?: p.d("buyAvg") ?: 0.0,
                0.0, (p.d("unrealizedProfit") ?: 0.0) + (p.d("realizedProfit") ?: 0.0), p.d("unrealizedProfit") ?: 0.0, displayName)
        }
    }

    override suspend fun getOrders(): List<Order> {
        val r = authHeaders(Request.Builder().url("$base/orders")).get().build()
        return com.tradelens.app.data.net.parseJson(Http.execute(r, limiter)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            Order(o.s("orderId") ?: "", "${o.s("exchangeSegment")}:${o.s("tradingSymbol")}", if (o.s("transactionType") == "BUY") Side.BUY else Side.SELL,
                o.d("quantity") ?: 0.0, o.d("filledQty") ?: 0.0, KiteBroker.mapType(o.s("orderType")?.replace("STOP_LOSS_MARKET", "SL-M")?.replace("STOP_LOSS", "SL")),
                o.d("price"), o.d("averageTradedPrice"), KiteBroker.mapStatus(o.s("orderStatus")), KiteBroker.parseTs(o.s("createTime")), displayName,
                o.s("omsErrorDescription") ?: "")
        }
    }

    override suspend fun getFunds(): Funds {
        val r = authHeaders(Request.Builder().url("$base/fundlimit")).get().build()
        val f = com.tradelens.app.data.net.parseJson(Http.execute(r, limiter)).obj()
        val avail = f.d("availabelBalance") ?: f.d("availableBalance") ?: 0.0; val used = f.d("utilizedAmount") ?: 0.0
        return Funds(avail, used, f.d("sodLimit") ?: (avail + used), "INR", displayName)
    }

    override suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit) = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url("https://images.dhan.co/api-data/api-scrip-master.csv").build()).execute().use { r ->
            val reader = r.body!!.byteStream().bufferedReader()
            val header = reader.readLine()?.split(",") ?: return@use
            val ix = header.withIndex().associate { it.value.trim() to it.index }
            fun col(c: List<String>, n: String) = ix[n]?.let { c.getOrNull(it) }?.trim().orEmpty()
            val batch = ArrayList<Instrument>(2000)
            while (true) {
                val line = reader.readLine() ?: break
                val c = line.split(",")
                val exch = col(c, "SEM_EXM_EXCH_ID"); val seg = col(c, "SEM_SEGMENT"); val name = col(c, "SEM_INSTRUMENT_NAME")
                val exchange = when { exch == "NSE" && seg == "D" -> Exchange.NFO; exch == "BSE" && seg == "D" -> Exchange.BFO; exch == "NSE" -> Exchange.NSE
                    exch == "BSE" -> Exchange.BSE; exch == "MCX" -> Exchange.MCX; else -> continue }
                val segment = when {
                    seg == "I" || name == "INDEX" -> Segment.INDEX
                    seg == "E" && name == "EQUITY" -> Segment.EQUITY
                    name.startsWith("FUT") -> if (exchange == Exchange.MCX) Segment.COMMODITY else Segment.FUTURES
                    name.startsWith("OPT") -> Segment.OPTIONS
                    else -> continue
                }
                val opt = col(c, "SEM_OPTION_TYPE")
                batch += Instrument(col(c, "SEM_TRADING_SYMBOL").ifBlank { continue }, exchange, segment, col(c, "SEM_SMST_SECURITY_ID"),
                    col(c, "SEM_CUSTOM_SYMBOL"), col(c, "SEM_LOT_UNITS").toDoubleOrNull()?.toInt() ?: 1, col(c, "SEM_TICK_SIZE").toDoubleOrNull()?.let { if (it >= 1) it / 100 else it } ?: 0.05,
                    col(c, "SEM_EXPIRY_DATE").takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)).atTime(15, 30).toEpochSecond(ist) }.getOrNull() },
                    col(c, "SEM_STRIKE_PRICE").toDoubleOrNull()?.takeIf { it > 0 }, when (opt) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> null },
                    col(c, "SEM_TRADING_SYMBOL").substringBefore("-").takeIf { segment != Segment.EQUITY })
                if (batch.size >= 2000) { sink(ArrayList(batch)); batch.clear() }
            }
            if (batch.isNotEmpty()) sink(batch)
        }
    }

    override fun brokerAppLink(i: Instrument) = BrokerAppLink("com.dhan.live", "https://web.dhan.co/")
}
