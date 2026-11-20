package com.tradelens.app.data.paper

import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.db.PaperAccountEntity
import com.tradelens.app.data.db.PaperOrderEntity
import com.tradelens.app.data.db.PaperPositionEntity
import com.tradelens.app.data.db.PaperTradeEntity
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.Charges
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.OrderRequest
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.ProductType
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.RiskDecision
import com.tradelens.app.domain.RiskGate
import com.tradelens.app.domain.RiskState
import com.tradelens.app.domain.Side
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sign

/** Pure fill-matching rules for paper orders (unit tested). */
object PaperRules {
    /** Returns the fill price for an open order at [ltp], or null if it should stay open. */
    fun fillPrice(type: OrderType, side: Side, ltp: Double, limit: Double?, trigger: Double?, slippage: Double = 0.0005): Double? = when (type) {
        OrderType.MARKET, OrderType.BRACKET -> if (limit == null) ltp * (1 + if (side == Side.BUY) slippage else -slippage)
            else if ((side == Side.BUY && ltp <= limit) || (side == Side.SELL && ltp >= limit)) limit else null
        OrderType.LIMIT -> if ((side == Side.BUY && ltp <= limit!!) || (side == Side.SELL && ltp >= limit!!)) limit else null
        OrderType.SL, OrderType.SL_M, OrderType.STOP_MARKET -> {
            val hit = if (side == Side.BUY) ltp >= trigger!! else ltp <= trigger!!
            if (!hit) null else if (type == OrderType.SL && limit != null) {
                if ((side == Side.BUY && ltp <= limit) || (side == Side.SELL && ltp >= limit)) limit else null
            } else ltp * (1 + if (side == Side.BUY) slippage else -slippage)
        }
    }
}

data class PaperSnapshot(val cash: Double, val equity: Double, val dayPnl: Double, val unrealised: Double, val halted: Boolean, val circuitBroken: Boolean)

/**
 * Virtual ₹10,00,000 portfolio. Uses the same risk gate and charge tables as live trading; crypto prices
 * (USD) are converted at the configured USD/INR rate.
 */
@Singleton
class PaperTrading @Inject constructor(private val db: AppDatabase, private val settings: SettingsStore) {
    private val dao = db.paper()
    private val lock = Mutex()
    private val lastPrices = HashMap<String, Double>()

    private fun fx(i: Instrument) = if (i.isCrypto) settings.value.usdInr else 1.0
    private fun today() = LocalDate.now(MarketHours.IST).toEpochDay()

    suspend fun account(): PaperAccountEntity {
        val a = dao.account() ?: settings.value.paperCapital.let { c ->
            PaperAccountEntity(1, c, c, c, c, today()).also { dao.saveAccount(it) }
        }
        if (a.dayStamp != today()) {
            val eq = equity(a)
            val rolled = a.copy(dayStamp = today(), startOfDayEquity = eq, ordersToday = 0, haltedForDay = false)
            dao.saveAccount(rolled); return rolled
        }
        return a
    }

    private suspend fun equity(a: PaperAccountEntity): Double = a.cash + dao.positionsNow().sumOf { p ->
        val i = AppJson.decodeFromString(Instrument.serializer(), p.instrumentJson)
        p.qty * (lastPrices[p.instrumentKey] ?: p.avgPrice) * fx(i)
    }

    suspend fun snapshot(): PaperSnapshot = lock.withLock {
        val a = account(); val eq = equity(a)
        val unreal = dao.positionsNow().sumOf { p ->
            val i = AppJson.decodeFromString(Instrument.serializer(), p.instrumentJson)
            p.qty * ((lastPrices[p.instrumentKey] ?: p.avgPrice) - p.avgPrice) * fx(i)
        }
        PaperSnapshot(a.cash, eq, eq - a.startOfDayEquity, unreal, a.haltedForDay, a.circuitBroken)
    }

    private fun riskState(a: PaperAccountEntity, eq: Double) = RiskState(a.startOfDayEquity, maxOf(a.peakEquity, eq), eq, a.ordersToday, a.haltedForDay, a.circuitBroken, settings.value.globalKill)

    /** Places a paper order; MARKET orders fill immediately at [ltp]. Returns a status message. */
    suspend fun place(req: OrderRequest, ltp: Double, reason: String = "Manual"): String = lock.withLock {
        val a = account(); val eq = equity(a)
        val i = req.instrument
        val existing = dao.positionsNow().firstOrNull { it.instrumentKey == i.key }
        val reducing = existing != null && existing.qty.sign != (if (req.side == Side.BUY) 1.0 else -1.0)
        val value = req.qty * (req.price ?: ltp) * fx(i) / req.leverage
        val decision = RiskGate.checkNewOrder(riskState(a, eq), settings.value.riskLimits, value,
            existingExposureSameSymbol = abs((existing?.qty ?: 0.0) * ltp * fx(i)) / (existing?.leverage ?: 1.0), isRiskReducing = reducing)
        if (decision is RiskDecision.Block) {
            db.audit().log(AuditEntity(category = "RISK", message = "Paper order blocked: ${decision.reason}", payload = AppJson.encodeToString(OrderRequest.serializer(), req)))
            return "Blocked: ${decision.reason}"
        }
        if (req.type == OrderType.LIMIT && req.price == null) return "Limit price required"
        if ((req.type == OrderType.SL || req.type == OrderType.SL_M || req.type == OrderType.STOP_MARKET) && req.triggerPrice == null) return "Trigger price required"
        val order = PaperOrderEntity(
            instrumentJson = AppJson.encodeToString(Instrument.serializer(), i), side = req.side.name, type = req.type.name, product = req.product.name,
            qty = req.qty, price = req.price, trigger = req.triggerPrice, stopLoss = req.stopLoss, target = req.target, status = "OPEN",
            createdAt = System.currentTimeMillis(), reason = reason, leverage = req.leverage,
        )
        val id = dao.insertOrder(order)
        dao.saveAccount(a.copy(ordersToday = a.ordersToday + 1))
        db.audit().log(AuditEntity(category = "ORDER", message = "PAPER ${req.side} ${req.qty} ${i.symbol} ${req.type}", payload = AppJson.encodeToString(OrderRequest.serializer(), req)))
        lastPrices[i.key] = ltp
        val px = PaperRules.fillPrice(req.type, req.side, ltp, req.price, req.triggerPrice)
        if (px != null) { fill(order.copy(id = id), px); "Filled ${req.side} ${fmtQty(req.qty)} ${i.symbol} @ ${"%.2f".format(px)}" } else "Order #$id open"
    }

    private suspend fun fill(o: PaperOrderEntity, px: Double) {
        val i = AppJson.decodeFromString(Instrument.serializer(), o.instrumentJson)
        val side = Side.valueOf(o.side)
        val intraday = o.product == ProductType.INTRADAY.name
        val chargesInr = Charges.leg(Charges.segmentFor(i, intraday), side, px * fx(i), o.qty).total
        var a = account()
        val signed = if (side == Side.BUY) o.qty else -o.qty
        val pos = dao.positionsNow().firstOrNull { it.instrumentKey == i.key }
        var cash = a.cash - signed * px * fx(i) - chargesInr
        if (pos == null || pos.qty.sign == signed.sign) {
            val newQty = (pos?.qty ?: 0.0) + signed
            val avg = if (pos == null) px else (pos.avgPrice * abs(pos.qty) + px * o.qty) / abs(newQty)
            dao.savePosition(PaperPositionEntity(i.key, o.instrumentJson, newQty, avg, o.product, o.stopLoss ?: pos?.stopLoss, o.target ?: pos?.target,
                pos?.openedAt ?: System.currentTimeMillis(), o.leverage, if (pos == null) o.reason else pos.entryReason))
        } else {
            val closeQty = minOf(abs(pos.qty), o.qty)
            val pnl = (px - pos.avgPrice) * closeQty * pos.qty.sign * fx(i)
            dao.insertTrade(PaperTradeEntity(symbol = i.symbol, side = if (pos.qty > 0) "LONG" else "SHORT", qty = closeQty, entry = pos.avgPrice, exit = px,
                pnl = pnl - chargesInr, charges = chargesInr, openedAt = pos.openedAt, closedAt = System.currentTimeMillis(), entryReason = pos.entryReason, exitReason = o.reason))
            val remaining = pos.qty + signed
            when {
                abs(remaining) < 1e-9 -> dao.deletePosition(i.key)
                remaining.sign == pos.qty.sign -> dao.savePosition(pos.copy(qty = remaining))
                else -> dao.savePosition(pos.copy(qty = remaining, avgPrice = px, openedAt = System.currentTimeMillis(), entryReason = o.reason))
            }
        }
        dao.updateOrder(o.copy(status = "FILLED", filledAt = System.currentTimeMillis(), fillPrice = px, charges = chargesInr))
        a = account().copy(cash = cash)
        dao.saveAccount(a)
        db.audit().log(AuditEntity(category = "RESPONSE", message = "PAPER fill ${o.side} ${fmtQty(o.qty)} ${i.symbol} @ ${"%.2f".format(px)} (charges ₹${"%.2f".format(chargesInr)})"))
    }

    /**
     * Processes live quotes: fills resting orders, triggers SL/targets, updates peak equity and enforces the
     * daily-loss / drawdown circuit breakers. Returns human-readable events (fills, breaches) for notifications.
     */
    suspend fun onQuotes(quotes: Collection<Quote>): List<String> = lock.withLock {
        val events = mutableListOf<String>()
        quotes.forEach { lastPrices[it.instrumentKey] = it.ltp }
        for (o in dao.openOrders()) {
            val i = AppJson.decodeFromString(Instrument.serializer(), o.instrumentJson)
            val ltp = lastPrices[i.key] ?: continue
            val px = PaperRules.fillPrice(OrderType.valueOf(o.type), Side.valueOf(o.side), ltp, o.price, o.trigger) ?: continue
            fill(o, px); events += "Paper fill: ${o.side} ${fmtQty(o.qty)} ${i.symbol} @ ${"%.2f".format(px)}"
        }
        for (p in dao.positionsNow()) {
            val ltp = lastPrices[p.instrumentKey] ?: continue
            val long = p.qty > 0
            val hitSl = p.stopLoss?.let { if (long) ltp <= it else ltp >= it } == true
            val hitTg = p.target?.let { if (long) ltp >= it else ltp <= it } == true
            if (hitSl || hitTg) {
                val exit = PaperOrderEntity(instrumentJson = p.instrumentJson, side = if (long) "SELL" else "BUY", type = "MARKET", product = p.product,
                    qty = abs(p.qty), price = null, trigger = null, stopLoss = null, target = null, status = "OPEN", createdAt = System.currentTimeMillis(),
                    reason = if (hitSl) "Stop loss" else "Target")
                val id = dao.insertOrder(exit)
                fill(exit.copy(id = id), ltp)
                events += "${if (hitSl) "Stop loss" else "Target"} hit on ${p.instrumentKey.substringAfter(':')} @ ${"%.2f".format(ltp)}"
            }
        }
        val a = account(); val eq = equity(a)
        val peak = maxOf(a.peakEquity, eq)
        when (val d = RiskGate.evaluateEquity(riskState(a, eq), settings.value.riskLimits)) {
            is RiskDecision.FlattenAndHalt -> if (!a.haltedForDay || (d.requireManualRestart && !a.circuitBroken)) {
                flattenLocked("Risk: ${d.reason}")
                dao.saveAccount(account().copy(haltedForDay = true, circuitBroken = a.circuitBroken || d.requireManualRestart, peakEquity = peak))
                db.audit().log(AuditEntity(category = "RISK", message = "Paper portfolio flattened & halted: ${d.reason}"))
                events += "RISK BREACH – ${d.reason}. Paper positions flattened and trading halted."
            }
            else -> if (peak != a.peakEquity) dao.saveAccount(a.copy(peakEquity = peak))
        }
        events
    }

    private suspend fun flattenLocked(reason: String) {
        for (p in dao.positionsNow()) {
            val ltp = lastPrices[p.instrumentKey] ?: p.avgPrice
            val o = PaperOrderEntity(instrumentJson = p.instrumentJson, side = if (p.qty > 0) "SELL" else "BUY", type = "MARKET", product = p.product,
                qty = abs(p.qty), price = null, trigger = null, stopLoss = null, target = null, status = "OPEN", createdAt = System.currentTimeMillis(), reason = reason)
            fill(o.copy(id = dao.insertOrder(o)), ltp)
        }
        dao.openOrders().forEach { dao.updateOrder(it.copy(status = "CANCELLED", reason = reason)) }
    }

    suspend fun flatten(reason: String) = lock.withLock { flattenLocked(reason) }

    suspend fun cancel(o: PaperOrderEntity) = lock.withLock { if (o.status == "OPEN") dao.updateOrder(o.copy(status = "CANCELLED")) }

    suspend fun restartAfterBreaker() = lock.withLock {
        val a = account(); val eq = equity(a)
        dao.saveAccount(a.copy(circuitBroken = false, haltedForDay = false, peakEquity = eq, startOfDayEquity = eq))
        db.audit().log(AuditEntity(category = "KILL", message = "Paper trading manually restarted"))
    }

    suspend fun reset() = lock.withLock { dao.reset(settings.value.paperCapital, today()); lastPrices.clear() }

    fun price(key: String) = lastPrices[key]

    private fun fmtQty(q: Double) = if (q == Math.floor(q)) q.toLong().toString() else "%.4f".format(q)
}
