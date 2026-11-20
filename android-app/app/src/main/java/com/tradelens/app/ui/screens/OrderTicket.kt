package com.tradelens.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.orders.OrderPreview
import com.tradelens.app.data.orders.OrderService
import com.tradelens.app.data.orders.Venue
import com.tradelens.app.data.paper.PaperTrading
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.OrderRequest
import com.tradelens.app.domain.OrderType
import com.tradelens.app.domain.ProductType
import com.tradelens.app.domain.Side
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Warn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrderViewModel @Inject constructor(val orders: OrderService, val paper: PaperTrading, val brokers: BrokerRegistry) : ViewModel()

/** Opens the broker's own app (or its web terminal) – the default, SEBI-safe way to place Indian orders. */
fun openInBrokerApp(ctx: Context, brokers: BrokerRegistry, i: Instrument) {
    val b = brokers.primary() ?: brokers.all.first()
    val link = b.brokerAppLink(i)
    val launch = ctx.packageManager.getLaunchIntentForPackage(link.packageName)
    if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    else CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(link.webUrl))
}

@Composable
fun OrderTicket(
    instrument: Instrument, ltp: Double, defaultSide: Side = Side.BUY, defaultStop: Double? = null, defaultTarget: Double? = null, defaultQty: Double? = null,
    reason: String = "Manual", onDismiss: () -> Unit, onResult: (String) -> Unit, vm: OrderViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var side by remember { mutableStateOf(defaultSide) }
    var type by remember { mutableStateOf(if (instrument.isCrypto) OrderType.MARKET else OrderType.LIMIT) }
    var qty by remember { mutableStateOf((defaultQty ?: instrument.lotSize.toDouble()).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }) }
    var price by remember { mutableStateOf("%.2f".format(ltp)) }
    var trigger by remember { mutableStateOf("") }
    var sl by remember { mutableStateOf(defaultStop?.let { "%.2f".format(it) } ?: "") }
    var tg by remember { mutableStateOf(defaultTarget?.let { "%.2f".format(it) } ?: "") }
    var lev by remember { mutableStateOf("1") }
    var product by remember { mutableStateOf(if (instrument.isCrypto) ProductType.CRYPTO else if (instrument.isDerivative) ProductType.NRML else ProductType.DELIVERY) }
    var confirm by remember { mutableStateOf<Pair<OrderPreview, Venue>?>(null) }

    fun build() = OrderRequest(
        instrument, side, qty.toDoubleOrNull() ?: 0.0, type, product,
        price = price.toDoubleOrNull().takeIf { type == OrderType.LIMIT || type == OrderType.SL || (type == OrderType.BRACKET && it != null) },
        triggerPrice = trigger.toDoubleOrNull().takeIf { type == OrderType.SL || type == OrderType.SL_M || type == OrderType.STOP_MARKET },
        stopLoss = sl.toDoubleOrNull(), target = tg.toDoubleOrNull(), leverage = lev.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0, tag = "tradelens",
    )
    val venues = vm.orders.availableVenues(build())

    val c = confirm
    if (c != null) {
        val (p, venue) = c
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (venue == Venue.PAPER) "Confirm paper order" else "Confirm LIVE order", color = if (venue == Venue.PAPER) androidx.compose.ui.graphics.Color.Unspecified else Warn) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KV("Instrument", "${instrument.symbol} (${instrument.exchange})")
                    KV("Side / type", "${p.req.side} ${p.req.type}")
                    KV("Size", "${p.req.qty}")
                    KV("Price", p.currency + fmtPrice(p.refPrice))
                    KV("Notional", p.currency + fmtPrice(p.notional))
                    KV("Leverage", "${p.req.leverage}x")
                    KV("Margin required", p.currency + fmtPrice(p.margin))
                    KV("Stop-loss", p.req.stopLoss?.let { fmtPrice(it) } ?: "none")
                    KV("Target", p.req.target?.let { fmtPrice(it) } ?: "none")
                    p.riskReward?.let { KV("Risk : reward", "1 : %.2f".format(it)) }
                    KV("Est. charges", p.currency + "%.2f".format(p.charges.total))
                    p.liquidation?.let { KV("Est. liquidation", p.currency + fmtPrice(it), Bear) }
                    p.warnings.forEach { Text("⚠ $it", color = Warn, fontSize = 12.sp) }
                    p.blocked?.let { Text("BLOCKED: $it", color = Bear, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = {
                TextButton(enabled = p.blocked == null, onClick = {
                    scope.launch {
                        val msg = if (venue == Venue.PAPER) vm.paper.place(p.req, ltp, reason) else vm.orders.executeApproved(p, venue)
                        onResult(msg); confirm = null; onDismiss()
                    }
                }) { Text("Approve") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${instrument.symbol} · LTP ${fmtPrice(ltp)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row { FilterChip(side == Side.BUY, { side = Side.BUY }, { Text("BUY") }); Spacer(Modifier.width(8.dp)); FilterChip(side == Side.SELL, { side = Side.SELL }, { Text("SELL") }) }
                Row(Modifier.horizontalScrollCompat()) {
                    OrderType.entries.filter { it != OrderType.SL_M || !instrument.isCrypto }.forEach { t ->
                        FilterChip(type == t, { type = t }, { Text(t.name.replace('_', '-')) }); Spacer(Modifier.width(4.dp))
                    }
                }
                if (!instrument.isCrypto) Row {
                    listOf(ProductType.DELIVERY, ProductType.INTRADAY, ProductType.NRML).forEach { pt -> FilterChip(product == pt, { product = pt }, { Text(pt.name) }); Spacer(Modifier.width(4.dp)) }
                }
                Field(qty, { qty = it }, if (instrument.isCrypto) "Contracts / units" else "Quantity (lot ${instrument.lotSize})", number = true)
                if (type == OrderType.LIMIT || type == OrderType.SL || type == OrderType.BRACKET) Field(price, { price = it }, "Limit price", number = true)
                if (type == OrderType.SL || type == OrderType.SL_M || type == OrderType.STOP_MARKET) Field(trigger, { trigger = it }, "Trigger price", number = true)
                Field(sl, { sl = it }, "Stop-loss", number = true)
                Field(tg, { tg = it }, "Target", number = true)
                if (instrument.isCrypto) Field(lev, { lev = it }, "Leverage (x)", number = true)
                if (!instrument.isCrypto) Text("Indian broker orders: by default use “Open in broker app”. Relay orders (LIMIT only) are available when enabled in Settings.", fontSize = 11.sp)
            }
        },
        confirmButton = {
            Column {
                venues.forEach { v ->
                    TextButton(onClick = {
                        if (v == Venue.BROKER_APP) { openInBrokerApp(ctx, vm.brokers, instrument); onDismiss() }
                        else scope.launch { confirm = vm.orders.preview(build(), ltp, v) to v }
                    }) {
                        Text(when (v) { Venue.PAPER -> "Paper trade"; Venue.DELTA_LIVE -> "Live (Delta)"; Venue.RELAY -> "Live via relay (LIMIT)"; Venue.BROKER_APP -> "Open in broker app" })
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    LaunchedEffect(type) { if (type == OrderType.MARKET) price = "%.2f".format(ltp) }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.fillMaxWidth().horizontalScroll(rememberScrollState())
