package com.tradelens.app.data.broker

import com.tradelens.app.data.market.BrokerAppLink
import com.tradelens.app.data.market.LoginMethod
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fyers API v3. Streaming uses batched REST quote polling (≤ 50 symbols/request, every 3 s while the
 * market is open) because Fyers' data socket uses a proprietary binary protocol without a public spec.
 */
@Singleton
class FyersBroker @Inject constructor(secure: SecureStore) : BaseBroker(secure) {
    override val id = "fyers"
    override val displayName = "Fyers"
    override val source = DataSource.FYERS
    override val limiter = RateLimiter(8.0, 8)
    override val configFields = listOf(
        field("api_key", "App ID (e.g. XB12345-100)"), field("api_secret", "Secret key", secret = true),
        field("redirect_uri", "Redirect URI", hint = "Same as in the Fyers API dashboard, e.g. https://<engine>/auth/callback/fyers"),
    )
    private val base = "https://api-t1.fyers.in"

    override fun authHeaders(rb: Request.Builder) = rb.header("Authorization", "${cfg("api_key")}:${requireToken()}")

    override fun loginMethod(redirectUri: String) = LoginMethod.OAuth(
        "$base/api/v3/generate-authcode?client_id=${cfg("api_key")}&redirect_uri=${URLEncoder.encode(cfg("redirect_uri") ?: redirectUri, "UTF-8")}&response_type=code&state=fyers",
    )

    override suspend fun login(params: Map<String, String>) {
        val code = params["auth_code"] ?: params["code"] ?: throw IllegalArgumentException("auth_code missing")
        val body = buildJsonObject {
            put("grant_type", "authorization_code"); put("appIdHash", sha256Hex("${cfg("api_key")}:${cfg("api_secret")}")); put("code", code)
        }.toString()
        val req = Request.Builder().url("$base/api/v3/validate-authcode").post(body.toRequestBody(JSON)).build()
        val r = com.tradelens.app.data.net.parseJson(Http.execute(req, limiter)).obj()
        saveSession(r.s("access_token") ?: throw IllegalStateException("Fyers login failed: ${r.s("message")}"))
    }

    fun fyersSymbol(i: Instrument): String = when (InstrumentLookup.canonicalIndex(i)) {
        "NIFTY" -> "NSE:NIFTY50-INDEX"; "BANKNIFTY" -> "NSE:NIFTYBANK-INDEX"; "FINNIFTY" -> "NSE:FINNIFTY-INDEX"
        "INDIAVIX" -> "NSE:INDIAVIX-INDEX"; "SENSEX" -> "BSE:SENSEX-INDEX"; "MIDCPNIFTY" -> "NSE:MIDCPNIFTY-INDEX"
        null -> when {
            i.symbol.contains(":") -> i.symbol
            i.segment == Segment.EQUITY -> "${if (i.exchange == Exchange.BSE) "BSE" else "NSE"}:${i.symbol}-${if (i.exchange == Exchange.BSE) "A" else "EQ"}"
            else -> "${if (i.exchange == Exchange.MCX) "MCX" else if (i.exchange == Exchange.BFO) "BSE" else "NSE"}:${i.symbol}"
        }
        else -> "NSE:${i.symbol.replace(" ", "")}-INDEX"
    }

    override suspend fun getQuote(i: Instrument) = getQuotes(listOf(i)).first()

    override suspend fun getQuotes(list: List<Instrument>): List<Quote> = list.chunked(50).flatMap { chunk ->
        val syms = chunk.map { fyersSymbol(it) }
        val d = get("$base/data/quotes?symbols=${URLEncoder.encode(syms.joinToString(","), "UTF-8")}").a("d").orEmpty().mapNotNull { it.obj() }.associateBy { it.s("n") }
        chunk.mapIndexedNotNull { idx, i ->
            val v = d[syms[idx]].o("v") ?: return@mapIndexedNotNull null
            val ltp = v.d("lp") ?: 0.0
            Quote(i.key, ltp, v.d("ch") ?: 0.0, v.d("chp") ?: 0.0, v.d("open_price") ?: 0.0, v.d("high_price") ?: 0.0, v.d("low_price") ?: 0.0,
                v.d("prev_close_price") ?: 0.0, v.d("volume") ?: 0.0, SourceBadge(source, true, System.currentTimeMillis() / 1000), v.d("bid") ?: 0.0, v.d("ask") ?: 0.0)
        }
    }

    override suspend fun getDepth(i: Instrument): MarketDepth? {
        val s = fyersSymbol(i)
        val d = get("$base/data/depth?symbol=${URLEncoder.encode(s, "UTF-8")}&ohlcv_flag=1").o("d").o(s) ?: return null
        fun side(n: String) = d.a(n)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("volume") ?: 0.0, it.d("ord")?.toInt() ?: 0) } }.orEmpty()
        return MarketDepth(side("bids"), side("ask"))
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val (res, chunk) = when (tf) {
            Timeframe.M1 -> "1" to 99L; Timeframe.M5 -> "5" to 99L; Timeframe.M15 -> "15" to 99L; Timeframe.H1 -> "60" to 99L; else -> "D" to 365L
        }
        val s = URLEncoder.encode(fyersSymbol(i), "UTF-8")
        val out = ArrayList<Candle>()
        var start = from
        while (start < to) {
            val end = minOf(to, start + chunk * 86400)
            get("$base/data/history?symbol=$s&resolution=$res&date_format=0&range_from=$start&range_to=$end&cont_flag=1").a("candles")?.forEach { e ->
                val a = e as? JsonArray ?: return@forEach
                out += Candle(a[0].dbl()!!.toLong(), a[1].dbl()!!, a[2].dbl()!!, a[3].dbl()!!, a[4].dbl()!!, a[5].dbl() ?: 0.0)
            }
            start = end + 1
        }
        val sorted = out.distinctBy { it.time }.sortedBy { it.time }
        return if (tf == Timeframe.W1) resampleWeekly(sorted) else sorted
    }

    override suspend fun getOptionChain(underlying: Instrument, expiry: Long?): OptionChain? {
        val s = URLEncoder.encode(fyersSymbol(underlying), "UTF-8")
        var d = get("$base/data/options-chain-v3?symbol=$s&strikecount=20").o("data") ?: return null
        val expiries = d.a("expiryData").orEmpty().mapNotNull { it.obj().l("expiry") }
        val exp = expiry ?: expiries.firstOrNull() ?: return null
        if (expiry != null && expiries.firstOrNull() != expiry) d = get("$base/data/options-chain-v3?symbol=$s&strikecount=20&timestamp=$exp").o("data") ?: return null
        val rows = d.a("optionsChain").orEmpty().mapNotNull { it.obj() }
        val spot = rows.firstOrNull { (it.d("strike_price") ?: 0.0) <= 0 }?.d("ltp") ?: 0.0
        val out = rows.filter { (it.d("strike_price") ?: 0.0) > 0 }.map { r ->
            OptionQuote(r.d("strike_price")!!, if (r.s("option_type") == "CE") OptionType.CE else OptionType.PE, r.d("ltp") ?: 0.0, r.d("oi") ?: 0.0,
                r.d("oich") ?: 0.0, r.d("volume") ?: 0.0, null, r.d("bid") ?: 0.0, r.d("ask") ?: 0.0, r.s("symbol") ?: "", r.d("ltpch") ?: 0.0)
        }
        return OptionChain(underlying.symbol, spot, exp, out, SourceBadge(source, true, System.currentTimeMillis() / 1000))
    }

    override fun streamTicks(list: List<Instrument>): Flow<Quote> = pollingStream(list, 3000)

    override suspend fun getHoldings(): List<Holding> = get("$base/api/v3/holdings").a("holdings").orEmpty().mapNotNull { e ->
        val h = e.obj() ?: return@mapNotNull null
        val sym = h.s("symbol") ?: return@mapNotNull null
        val ltp = h.d("ltp") ?: 0.0
        // Fyers holdings carry no previous close, so day P&L for these rows is reported as 0.
        Holding(sym.substringAfter(":").substringBeforeLast("-"), sym.substringBefore(":"), h.d("quantity") ?: 0.0, h.d("costPrice") ?: 0.0, ltp, ltp, displayName)
    }

    override suspend fun getPositions(): List<Position> = get("$base/api/v3/positions").a("netPositions").orEmpty().mapNotNull { e ->
        val p = e.obj() ?: return@mapNotNull null
        Position(p.s("symbol") ?: "", p.s("symbol")?.substringAfter(":") ?: "", p.d("netQty") ?: 0.0, p.d("netAvg") ?: 0.0, p.d("ltp") ?: 0.0,
            p.d("pl") ?: 0.0, p.d("unrealized_profit") ?: 0.0, displayName)
    }

    override suspend fun getOrders(): List<Order> = get("$base/api/v3/orders").a("orderBook").orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        Order(o.s("id") ?: "", o.s("symbol") ?: "", if ((o.d("side") ?: 1.0) > 0) Side.BUY else Side.SELL, o.d("qty") ?: 0.0, o.d("filledQty") ?: 0.0,
            when (o.d("type")?.toInt()) { 2 -> OrderType.MARKET; 3 -> OrderType.SL_M; 4 -> OrderType.SL; else -> OrderType.LIMIT },
            o.d("limitPrice"), o.d("tradedPrice"),
            when (o.d("status")?.toInt()) { 1 -> OrderStatus.CANCELLED; 2 -> OrderStatus.FILLED; 5 -> OrderStatus.REJECTED; 6 -> OrderStatus.OPEN; else -> OrderStatus.PENDING },
            KiteBroker.parseTs(o.s("orderDateTime")), displayName, o.s("message") ?: "")
    }

    override suspend fun getFunds(): Funds {
        val rows = get("$base/api/v3/funds").a("fund_limit").orEmpty().mapNotNull { it.obj() }.associateBy { it.s("title") }
        val avail = rows["Available Balance"].d("equityAmount") ?: 0.0
        val used = rows["Utilized Amount"].d("equityAmount") ?: 0.0
        return Funds(avail, used, rows["Total Balance"].d("equityAmount") ?: (avail + used), "INR", displayName)
    }

    override suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit) = withContext(Dispatchers.IO) {
        for ((file, exchange) in listOf("NSE_CM" to Exchange.NSE, "BSE_CM" to Exchange.BSE, "NSE_FO" to Exchange.NFO, "MCX_COM" to Exchange.MCX)) {
            Http.client.newCall(Request.Builder().url("https://public.fyers.in/sym_details/$file.csv").build()).execute().use { r ->
                val reader = r.body!!.byteStream().bufferedReader()
                val batch = ArrayList<Instrument>(2000)
                while (true) {
                    val line = reader.readLine() ?: break
                    val c = line.split(",")
                    if (c.size < 17) continue
                    val ticker = c[9]
                    val segment = when {
                        ticker.endsWith("-INDEX") -> Segment.INDEX
                        ticker.endsWith("-EQ") || (exchange == Exchange.BSE && ticker.matches(Regex("BSE:[A-Z0-9&]+-[A-Z]+"))) -> Segment.EQUITY
                        ticker.endsWith("FUT") -> if (exchange == Exchange.MCX) Segment.COMMODITY else Segment.FUTURES
                        ticker.endsWith("CE") || ticker.endsWith("PE") -> Segment.OPTIONS
                        else -> continue
                    }
                    val sym = if (segment == Segment.EQUITY) ticker.substringAfter(":").substringBeforeLast("-") else ticker.substringAfter(":")
                    batch += Instrument(sym, exchange, segment, ticker, c[1], c[3].toDoubleOrNull()?.toInt() ?: 1, c[4].toDoubleOrNull() ?: 0.05,
                        c[8].toLongOrNull()?.takeIf { it > 0 }, c[15].toDoubleOrNull()?.takeIf { it > 0 },
                        when (c[16]) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> null }, c[13].takeIf { it.isNotBlank() })
                    if (batch.size >= 2000) { sink(ArrayList(batch)); batch.clear() }
                }
                if (batch.isNotEmpty()) sink(batch)
            }
        }
    }

    override fun brokerAppLink(i: Instrument) = BrokerAppLink("com.fyers.markets", "https://trade.fyers.in/")
}
