package com.tradelens.app.data.delta

import com.tradelens.app.data.market.DataProvider
import com.tradelens.app.data.market.WsEvent
import com.tradelens.app.data.market.reconnectingSocket
import com.tradelens.app.data.net.ApiException
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.RateLimiter
import com.tradelens.app.data.net.a
import com.tradelens.app.data.net.arr
import com.tradelens.app.data.net.d
import com.tradelens.app.data.net.dbl
import com.tradelens.app.data.net.l
import com.tradelens.app.data.net.o
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.data.net.s
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.DataSource
import com.tradelens.app.domain.DepthLevel
import com.tradelens.app.domain.Exchange
import com.tradelens.app.domain.Funds
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketDepth
import com.tradelens.app.domain.OptionChain
import com.tradelens.app.domain.OptionQuote
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Order
import com.tradelens.app.domain.OrderRequest
import com.tradelens.app.domain.OrderStatus
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.Position
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.Segment
import com.tradelens.app.domain.Side
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.domain.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class DeltaProduct(
    val id: Int, val symbol: String, val description: String, val contractType: String,
    val underlying: String, val contractValue: Double, val tickSize: Double, val strike: Double?, val settlement: Long?,
) {
    fun toInstrument() = Instrument(
        symbol, Exchange.DELTA,
        when (contractType) {
            "perpetual_futures" -> Segment.CRYPTO_PERP
            "call_options", "put_options" -> Segment.CRYPTO_OPTION
            else -> Segment.CRYPTO_FUTURE
        },
        token = id.toString(), name = description, lotSize = 1, tickSize = tickSize, expiry = settlement, strike = strike,
        optionType = when (contractType) { "call_options" -> OptionType.CE; "put_options" -> OptionType.PE; else -> null },
        underlying = underlying,
    )
}

data class DeltaTicker(
    val symbol: String, val contractType: String, val mark: Double, val close: Double, val open: Double, val high: Double, val low: Double,
    val spot: Double, val fundingRate: Double, val oi: Double, val oiValueUsd: Double, val volume: Double, val turnoverUsd: Double,
    val bestBid: Double, val bestAsk: Double, val markIv: Double?, val strike: Double?, val underlying: String,
    val delta: Double?, val gamma: Double?, val theta: Double?, val vega: Double?, val oiChange6h: Double?, val settlement: Long?,
) {
    val changePct get() = if (open > 0) (close / open - 1) * 100 else 0.0
    val basisPct get() = if (spot > 0) (mark / spot - 1) * 100 else 0.0
}

data class DeltaBalance(val asset: String, val balance: Double, val available: Double, val blocked: Double)

/**
 * Delta Exchange India client (https://api.india.delta.exchange — NOT api.delta.exchange which is Delta Global).
 * Private calls are signed: signature = hex(HMAC_SHA256(secret, method + timestamp + path + query + body)).
 */
@Singleton
class DeltaClient @Inject constructor(
    private val secure: SecureStore,
    private val settings: SettingsStore,
) : DataProvider {
    override val source = DataSource.DELTA
    override val realtime = true
    private val limiter = RateLimiter(permitsPerSecond = 8.0, burst = 10)
    private var clockSkewSec = 0L
    @Volatile private var productCache: List<DeltaProduct>? = null

    val isTestnet get() = settings.value.deltaTestnet
    val restBase get() = if (isTestnet) "https://cdn-ind.testnet.deltaex.org" else "https://api.india.delta.exchange"
    val wsUrl get() = if (isTestnet) "wss://socket-ind.testnet.deltaex.org" else "wss://socket.india.delta.exchange"
    fun hasKeys() = secure.get(SecureStore.DELTA_KEY) != null && secure.get(SecureStore.DELTA_SECRET) != null

    // ---------------- low level ----------------

    private suspend fun publicGet(path: String, query: String = ""): JsonObject {
        val url = restBase + path + (if (query.isNotEmpty()) "?$query" else "")
        val body = Http.execute(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA).build(), limiter)
        return parseJson(body).obj() ?: JsonObject(emptyMap())
    }

    /** Signed request; re-signs and retries once on SignatureExpired after re-syncing the clock. */
    private suspend fun signed(method: String, path: String, query: String = "", body: String = "", retried: Boolean = false): JsonObject {
        val key = secure.get(SecureStore.DELTA_KEY) ?: throw IllegalStateException("Delta API key not set")
        val secret = secure.get(SecureStore.DELTA_SECRET) ?: throw IllegalStateException("Delta API secret not set")
        val ts = (System.currentTimeMillis() / 1000 + clockSkewSec).toString()
        val q = if (query.isNotEmpty()) "?$query" else ""
        val sig = sign(secret, method + ts + path + q + body)
        val rb = Request.Builder().url(restBase + path + q)
            .header("api-key", key).header("timestamp", ts).header("signature", sig)
            .header("User-Agent", UA).header("Content-Type", "application/json").header("Accept", "application/json")
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete(body.toRequestBody(JSON))
            else -> rb.method(method, body.toRequestBody(JSON))
        }
        return try {
            parseJson(Http.execute(rb.build(), limiter, retries = 1)).obj() ?: JsonObject(emptyMap())
        } catch (e: ApiException) {
            val expired = e.body?.contains("SignatureExpired", true) == true || e.body?.contains("expired_signature", true) == true
            if (expired && !retried) { syncClock(); signed(method, path, query, body, true) } else throw e
        }
    }

    /** Uses the server Date header to correct device clock skew (signatures older than 5 s are rejected). */
    suspend fun syncClock() {
        runCatching {
            val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url("$restBase/v2/products?page_size=1").head().build()).execute()
            }
            resp.use { r ->
                r.headers.getDate("Date")?.let { clockSkewSec = (it.time - System.currentTimeMillis()) / 1000 }
            }
        }
    }

    // ---------------- public data ----------------

    suspend fun products(force: Boolean = false): List<DeltaProduct> {
        productCache?.takeIf { !force }?.let { return it }
        val out = mutableListOf<DeltaProduct>()
        var after: String? = null
        do {
            val r = publicGet("/v2/products", "page_size=500&states=live" + (after?.let { "&after=$it" } ?: ""))
            r.a("result")?.forEach { e ->
                val p = e.obj() ?: return@forEach
                out += DeltaProduct(
                    p.l("id")?.toInt() ?: 0, p.s("symbol") ?: return@forEach, p.s("description") ?: "",
                    p.s("contract_type") ?: "", p.o("underlying_asset").s("symbol") ?: "",
                    p.d("contract_value") ?: 1.0, p.d("tick_size") ?: 0.5, p.d("strike_price"),
                    p.s("settlement_time")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
                )
            }
            after = r.o("meta").s("after")
        } while (after != null && out.size < 20_000)
        productCache = out
        return out
    }

    suspend fun searchProducts(q: String): List<DeltaProduct> {
        val u = q.trim().uppercase()
        return products().filter { it.symbol.contains(u) || it.description.uppercase().contains(u) }
            .sortedWith(compareBy({ if (it.contractType == "perpetual_futures") 0 else 1 }, { it.symbol.length })).take(100)
    }

    private fun parseTicker(t: JsonObject): DeltaTicker {
        val g = t.o("greeks"); val q = t.o("quotes")
        return DeltaTicker(
            t.s("symbol") ?: "", t.s("contract_type") ?: "", t.d("mark_price") ?: t.d("close") ?: 0.0, t.d("close") ?: 0.0,
            t.d("open") ?: 0.0, t.d("high") ?: 0.0, t.d("low") ?: 0.0, t.d("spot_price") ?: 0.0, t.d("funding_rate") ?: 0.0,
            t.d("oi") ?: 0.0, t.d("oi_value_usd") ?: 0.0, t.d("volume") ?: 0.0, t.d("turnover_usd") ?: 0.0,
            q.d("best_bid") ?: 0.0, q.d("best_ask") ?: 0.0, q.d("mark_iv")?.let { it * if (it < 5) 100 else 1 },
            t.d("strike_price"), t.s("underlying_asset_symbol") ?: "",
            g.d("delta"), g.d("gamma"), g.d("theta"), g.d("vega"), t.d("oi_change_usd_6h"),
            t.s("settlement_time")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
        )
    }

    suspend fun ticker(symbol: String): DeltaTicker = parseTicker(publicGet("/v2/tickers/$symbol").o("result") ?: throw IllegalStateException("No ticker for $symbol"))

    suspend fun tickers(contractTypes: String = "perpetual_futures", underlying: String? = null, expiryDate: String? = null): List<DeltaTicker> {
        val q = buildString {
            append("contract_types=$contractTypes")
            underlying?.let { append("&underlying_asset_symbols=$it") }
            expiryDate?.let { append("&expiry_date=$it") }
        }
        return publicGet("/v2/tickers", q).a("result")?.mapNotNull { it.obj()?.let(::parseTicker) }.orEmpty()
    }

    override suspend fun getQuote(i: Instrument): Quote {
        val t = ticker(i.symbol)
        return Quote(
            i.key, t.mark, t.close - t.open, t.changePct, t.open, t.high, t.low, t.open, t.volume,
            SourceBadge(DataSource.DELTA, true, System.currentTimeMillis() / 1000), t.bestBid, t.bestAsk,
        )
    }

    fun resolution(tf: Timeframe) = when (tf) {
        Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.H1 -> "1h"; Timeframe.D1 -> "1d"; Timeframe.W1 -> "1w"
    }

    /** Paginates backwards in 2000-candle windows to cover long history. */
    suspend fun candles(symbol: String, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val step = tf.seconds * 2000
        val out = HashMap<Long, Candle>()
        var end = to
        while (end > from) {
            val start = maxOf(from, end - step)
            val r = publicGet("/v2/history/candles", "symbol=${java.net.URLEncoder.encode(symbol, "UTF-8")}&resolution=${resolution(tf)}&start=$start&end=$end")
            val rows = r.a("result").orEmpty()
            rows.forEach { e ->
                val c = e.obj() ?: return@forEach
                val t = c.l("time") ?: return@forEach
                out[t] = Candle(t, c.d("open") ?: 0.0, c.d("high") ?: 0.0, c.d("low") ?: 0.0, c.d("close") ?: 0.0, c.d("volume") ?: 0.0)
            }
            if (rows.isEmpty() && end != to) break
            end = start
        }
        return out.values.sortedBy { it.time }
    }

    override suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long) = candles(i.symbol, tf, from, to)

    /** Funding-rate history via Delta's FUNDING:<symbol> candle series (values are % per 8h). */
    suspend fun fundingHistory(symbol: String, days: Int = 30): List<Pair<Long, Double>> {
        val to = System.currentTimeMillis() / 1000
        return candles("FUNDING:$symbol", Timeframe.H1, to - days * 86400L, to).map { it.time to it.close }
    }

    suspend fun orderbook(symbol: String, depth: Int = 20): MarketDepth {
        val r = publicGet("/v2/l2orderbook/$symbol", "depth=$depth").o("result")
        fun side(k: String) = r.a(k)?.mapNotNull { e -> e.obj()?.let { DepthLevel(it.d("price") ?: 0.0, it.d("size") ?: 0.0) } }.orEmpty()
        return MarketDepth(side("buy"), side("sell"))
    }

    override suspend fun getDepth(i: Instrument) = orderbook(i.symbol)

    /** BTC/ETH options chain for the nearest (or given) expiry built from /v2/tickers. */
    override suspend fun getOptionChain(underlying: Instrument, expiry: Long?): OptionChain? {
        val u = underlying.underlying ?: underlying.symbol.removeSuffix("USD").removeSuffix("USDT")
        val all = tickers("call_options,put_options", u)
        if (all.isEmpty()) return null
        val exp = expiry ?: all.mapNotNull { it.settlement }.filter { it > System.currentTimeMillis() / 1000 }.minOrNull() ?: return null
        val rows = all.filter { it.settlement == exp && it.strike != null }.map {
            OptionQuote(
                it.strike!!, if (it.contractType == "call_options") OptionType.CE else OptionType.PE, it.mark, it.oi,
                it.oiChange6h ?: 0.0, it.volume, it.markIv, it.bestBid, it.bestAsk, it.symbol, it.close - it.open,
            )
        }.sortedBy { it.strike }
        val spot = all.firstOrNull { it.spot > 0 }?.spot ?: 0.0
        return OptionChain(u, spot, exp, rows, SourceBadge(DataSource.DELTA, true, System.currentTimeMillis() / 1000))
    }

    suspend fun optionExpiries(underlying: String): List<Long> =
        tickers("call_options,put_options", underlying).mapNotNull { it.settlement }.distinct().sorted()

    override fun streamTicks(list: List<Instrument>): Flow<Quote> {
        val symbols = list.filter { it.isCrypto }.map { it.symbol }
        val byKey = list.associateBy { it.symbol }
        return tickerStream(symbols).mapNotNull { t ->
            val i = byKey[t.symbol] ?: return@mapNotNull null
            Quote(i.key, t.mark, t.close - t.open, t.changePct, t.open, t.high, t.low, t.open, t.volume,
                SourceBadge(DataSource.DELTA, true, System.currentTimeMillis() / 1000), t.bestBid, t.bestAsk)
        }
    }

    /** v2/ticker channel; also used for funding/OI live updates. */
    fun tickerStream(symbols: List<String>): Flow<DeltaTicker> = reconnectingSocket(
        request = { Request.Builder().url(wsUrl).build() },
        onOpen = { ws ->
            val msg = buildJsonObject {
                put("type", "subscribe")
                putJsonObject("payload") {
                    putJsonArray("channels") {
                        add(buildJsonObject { put("name", "v2/ticker"); put("symbols", buildJsonArray { symbols.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
                    }
                }
            }
            ws.send(msg.toString())
        },
        heartbeat = { it.send("{\"type\":\"ping\"}") },
    ).mapNotNull { ev ->
        val txt = (ev as? WsEvent.Text)?.text ?: return@mapNotNull null
        val o = runCatching { parseJson(txt).obj() }.getOrNull() ?: return@mapNotNull null
        if (o.s("type") != "v2/ticker") null else parseTicker(o)
    }

    // ---------------- private data ----------------

    suspend fun testConnection(): String {
        syncClock()
        val bals = balances()
        return "Connected to ${if (isTestnet) "Testnet" else "Production"} – ${bals.size} wallet asset(s)"
    }

    suspend fun balances(): List<DeltaBalance> = signed("GET", "/v2/wallet/balances").a("result")?.mapNotNull { e ->
        val b = e.obj() ?: return@mapNotNull null
        DeltaBalance(b.s("asset_symbol") ?: "", b.d("balance") ?: 0.0, b.d("available_balance") ?: 0.0, b.d("blocked_margin") ?: 0.0)
    }.orEmpty()

    suspend fun funds(): Funds {
        val b = balances()
        val main = b.firstOrNull { it.asset == "USD" } ?: b.maxByOrNull { it.balance } ?: DeltaBalance("USD", 0.0, 0.0, 0.0)
        return Funds(main.available, main.blocked, main.balance, main.asset, "Delta")
    }

    suspend fun positions(): List<Position> {
        val r = signed("GET", "/v2/positions/margined")
        val list = r.a("result").orEmpty().mapNotNull { it.obj() }
        if (list.isEmpty()) return emptyList()
        val contractValues = products().associate { it.symbol to it.contractValue }
        return list.mapNotNull { p ->
            val sym = p.s("product_symbol") ?: return@mapNotNull null
            val size = p.d("size") ?: 0.0
            if (size == 0.0) return@mapNotNull null
            val entry = p.d("entry_price") ?: 0.0
            val mark = p.d("mark_price") ?: runCatching { ticker(sym).mark }.getOrDefault(entry)
            val cv = contractValues[sym] ?: 1.0
            Position(
                "DELTA:$sym", sym, size, entry, mark,
                p.d("unrealized_pnl") ?: (mark - entry) * size * cv, broker = "Delta",
                leverage = p.d("leverage") ?: 1.0, liquidationPrice = p.d("liquidation_price"), margin = p.d("margin") ?: 0.0,
            )
        }
    }

    suspend fun openOrders(): List<Order> {
        val out = mutableListOf<Order>()
        var after: String? = null
        do {
            val r = signed("GET", "/v2/orders", "states=open,pending" + (after?.let { "&after=$it" } ?: ""))
            r.a("result")?.forEach { e -> e.obj()?.let { out += parseOrder(it) } }
            after = r.o("meta").s("after")
        } while (after != null && out.size < 500)
        return out
    }

    private fun parseOrder(o: JsonObject) = Order(
        o.l("id")?.toString() ?: "", "DELTA:${o.s("product_symbol")}", if (o.s("side") == "buy") Side.BUY else Side.SELL,
        o.d("size") ?: 0.0, (o.d("size") ?: 0.0) - (o.d("unfilled_size") ?: 0.0),
        when (o.s("order_type")) { "market_order" -> OrderType.MARKET; else -> if (o.s("stop_order_type") != null) OrderType.STOP_MARKET else OrderType.LIMIT },
        o.d("limit_price"), o.d("average_fill_price"),
        when (o.s("state")) { "open" -> OrderStatus.OPEN; "pending" -> OrderStatus.PENDING; "closed" -> OrderStatus.FILLED; "cancelled" -> OrderStatus.CANCELLED; else -> OrderStatus.OPEN },
        o.s("created_at")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: 0L, "Delta",
    )

    /**
     * Places a live order. Callers MUST have passed the risk gate and obtained user approval; the client
     * itself refuses Production unless the user manually unlocked it in settings.
     */
    suspend fun placeOrder(req: OrderRequest, productId: Int): Order {
        val s = settings.value
        check(s.deltaLiveOrders) { "Live orders are disabled (Settings → Delta → Live orders)" }
        check(isTestnet || s.deltaProductionUnlocked) { "Production orders are locked. Unlock Production manually in Settings." }
        val body = buildJsonObject {
            put("product_id", productId)
            put("size", req.qty.toLong())
            put("side", if (req.side == Side.BUY) "buy" else "sell")
            when (req.type) {
                OrderType.MARKET -> put("order_type", "market_order")
                OrderType.LIMIT -> { put("order_type", "limit_order"); put("limit_price", req.price.toString()) }
                OrderType.STOP_MARKET, OrderType.SL_M -> { put("order_type", "market_order"); put("stop_order_type", "stop_loss_order"); put("stop_price", req.triggerPrice.toString()) }
                OrderType.SL -> { put("order_type", "limit_order"); put("limit_price", req.price.toString()); put("stop_order_type", "stop_loss_order"); put("stop_price", req.triggerPrice.toString()) }
                OrderType.BRACKET -> {
                    put("order_type", if (req.price != null) "limit_order" else "market_order")
                    req.price?.let { put("limit_price", it.toString()) }
                    req.stopLoss?.let { put("bracket_stop_loss_price", it.toString()) }
                    req.target?.let { put("bracket_take_profit_price", it.toString()) }
                }
            }
            put("client_order_id", req.tag.take(32))
        }
        val r = signed("POST", "/v2/orders", body = body.toString())
        return parseOrder(r.o("result") ?: throw IllegalStateException("Order rejected: $r"))
    }

    suspend fun cancelOrder(orderId: String, productId: Int) {
        signed("DELETE", "/v2/orders", body = buildJsonObject { put("id", orderId.toLong()); put("product_id", productId) }.toString())
    }

    suspend fun setLeverage(productId: Int, leverage: Double) {
        signed("POST", "/v2/products/$productId/orders/leverage", body = buildJsonObject { put("leverage", leverage.toString()) }.toString())
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val UA = "TradeLens/1.0 (Android)"

        fun sign(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun expiryLabel(epoch: Long): String = DateTimeFormatter.ofPattern("dd-MM-yyyy").format(Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate())
    }
}
