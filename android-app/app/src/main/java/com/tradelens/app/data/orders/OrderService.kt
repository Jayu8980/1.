package com.tradelens.app.data.orders

import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.delta.DeltaClient
import com.tradelens.app.data.engine.RelayClient
import com.tradelens.app.data.engine.RelayOrder
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.di.AppScope
import com.tradelens.app.domain.ChargeBreakdown
import com.tradelens.app.domain.Charges
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.OrderRequest
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.ProductType
import com.tradelens.app.domain.Risk
import com.tradelens.app.domain.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor

enum class Venue { PAPER, DELTA_LIVE, RELAY, BROKER_APP }

data class OrderPreview(
    val req: OrderRequest,
    val refPrice: Double,
    val notional: Double,
    val margin: Double,
    val charges: ChargeBreakdown,
    val liquidation: Double?,
    val riskReward: Double?,
    val warnings: List<String>,
    val blocked: String?,
    val slices: Int,
    val currency: String,
)

/**
 * Builds the mandatory confirmation preview and executes approved live orders. The LLM and the engine can
 * never call this directly: every live order passes through the user's one-tap approval in the UI.
 */
@Singleton
class OrderService @Inject constructor(
    private val settings: SettingsStore,
    private val secure: SecureStore,
    private val delta: DeltaClient,
    private val relay: RelayClient,
    private val brokers: BrokerRegistry,
    private val db: AppDatabase,
    @AppScope private val scope: CoroutineScope,
) {
    fun availableVenues(req: OrderRequest): List<Venue> = buildList {
        add(Venue.PAPER)
        if (req.instrument.isCrypto) { if (settings.value.deltaLiveOrders && delta.hasKeys()) add(Venue.DELTA_LIVE) }
        else {
            add(Venue.BROKER_APP)
            if (settings.value.indianOrdersViaRelay && relay.isConfigured() && brokers.primary() != null) add(Venue.RELAY)
        }
    }

    private fun ordersTodayKey() = "live.orders.${LocalDate.now(MarketHours.IST)}"
    fun liveOrdersToday() = secure.get(ordersTodayKey())?.toIntOrNull() ?: 0

    suspend fun preview(req: OrderRequest, ltp: Double, venue: Venue): OrderPreview {
        val i = req.instrument
        val cv = if (i.isCrypto) contractValue(i) else 1.0
        val px = req.price ?: req.triggerPrice ?: ltp
        val notional = px * req.qty * cv
        val seg = Charges.segmentFor(i, req.product == ProductType.INTRADAY)
        val charges = Charges.leg(seg, req.side, px * cv, req.qty)
        val lev = if (i.isCrypto) req.leverage else if (i.isDerivative) 5.0 else if (req.product == ProductType.INTRADAY) 5.0 else 1.0
        val margin = notional / lev
        val liq = if (i.isCrypto && req.leverage > 1) Risk.liquidationPrice(px, req.leverage, req.side == Side.BUY) else null
        val rr = if (req.stopLoss != null && req.target != null) Risk.riskReward(px, req.stopLoss, req.target) else null
        val w = mutableListOf<String>()
        Risk.leverageWarning(req.leverage)?.let { w += it }
        if (req.stopLoss == null) w += "No stop-loss set."
        if (!i.isCrypto && MarketHours.inEdgeWindow()) w += "Inside the first/last 5 minutes of the session – spreads are wide."
        if (!i.isCrypto && !MarketHours.isOpen(i)) w += "Market is closed; order will not fill until the next session."
        if (venue == Venue.DELTA_LIVE && delta.isTestnet) w += "Delta TESTNET – no real money."
        if (venue == Venue.DELTA_LIVE && !delta.isTestnet) w += "Delta PRODUCTION – real money."
        val s = settings.value
        val blocked = when {
            s.globalKill -> "Global kill switch is ON"
            venue == Venue.RELAY && req.type != OrderType.LIMIT -> "Relay accepts LIMIT orders only (SEBI retail-algo rules)"
            venue != Venue.PAPER && liveOrdersToday() >= s.riskLimits.maxOrdersPerDay -> "Daily live order limit reached"
            venue == Venue.DELTA_LIVE && !s.deltaLiveOrders -> "Live orders disabled"
            else -> null
        }
        val slices = if (venue != Venue.PAPER && notional > SLICE_NOTIONAL * (if (i.isCrypto) 1.0 / s.usdInr else 1.0)) ceil(notional / (SLICE_NOTIONAL * if (i.isCrypto) 1.0 / s.usdInr else 1.0)).toInt().coerceAtMost(10) else 1
        if (slices > 1) w += "Large order: will be sliced into $slices child LIMIT orders (TWAP, 15 s apart)."
        return OrderPreview(req, px, notional, margin, charges, liq, rr, w, blocked, slices, if (i.isCrypto) "$" else "₹")
    }

    /** Delta contracts are in lots of the underlying (e.g. BTCUSD = 0.001 BTC). */
    private suspend fun contractValue(i: com.tradelens.app.domain.Instrument): Double =
        runCatching { delta.products().firstOrNull { it.symbol == i.symbol }?.contractValue }.getOrNull() ?: 1.0

    /** Executes an approved live order (approval already captured by the UI). Returns a status line. */
    suspend fun executeApproved(p: OrderPreview, venue: Venue): String {
        p.blocked?.let { return "Blocked: $it" }
        val req = p.req
        db.audit().log(AuditEntity(category = "APPROVAL", message = "User approved ${venue.name} ${req.side} ${req.qty} ${req.instrument.symbol} ${req.type} @ ${req.price ?: "MKT"}",
            payload = AppJson.encodeToString(OrderRequest.serializer(), req)))
        secure.put(ordersTodayKey(), (liveOrdersToday() + 1).toString())
        val sliceQty = if (p.slices > 1) floor(req.qty / p.slices) else req.qty
        val children = if (p.slices > 1) List(p.slices) { k -> if (k == p.slices - 1) req.qty - sliceQty * (p.slices - 1) else sliceQty }.filter { it > 0 } else listOf(req.qty)
        val first = placeOne(req.copy(qty = children.first()), venue)
        if (children.size > 1) scope.launch {
            for (q in children.drop(1)) { delay(15_000); if (!settings.value.globalKill) placeOne(req.copy(qty = q), venue) }
        }
        return first
    }

    private suspend fun placeOne(req: OrderRequest, venue: Venue): String {
        db.audit().log(AuditEntity(category = "ORDER", message = "${venue.name} ${req.side} ${req.qty} ${req.instrument.symbol}", payload = AppJson.encodeToString(OrderRequest.serializer(), req)))
        return runCatching {
            when (venue) {
                Venue.DELTA_LIVE -> {
                    if (req.leverage > 1) runCatching { delta.setLeverage(req.instrument.token.toInt(), req.leverage) }
                    val o = delta.placeOrder(req, req.instrument.token.toIntOrNull() ?: delta.products().first { it.symbol == req.instrument.symbol }.id)
                    "Delta order ${o.id} ${o.status}"
                }
                Venue.RELAY -> {
                    val b = brokers.primary() ?: error("No connected broker")
                    val r = relay.place(
                        RelayOrder(b.id, req.instrument.exchange.name, req.instrument.symbol, req.instrument.token, req.side.name, req.qty.toInt(), req.price ?: error("Limit price required"),
                            req.product.name, UUID.randomUUID().toString().take(20), System.currentTimeMillis()),
                        secure.get(SecureStore.brokerKey(b.id, "access_token")),
                    )
                    "Relay: ${r.status} ${r.brokerOrderId ?: ""} ${r.message}"
                }
                else -> error("Venue ${venue.name} is not a live venue")
            }
        }.fold(
            onSuccess = { db.audit().log(AuditEntity(category = "RESPONSE", message = it)); it },
            onFailure = { val m = "Order failed: ${it.message}"; db.audit().log(AuditEntity(category = "RESPONSE", message = m)); m },
        )
    }

    companion object { const val SLICE_NOTIONAL = 500_000.0 }
}
