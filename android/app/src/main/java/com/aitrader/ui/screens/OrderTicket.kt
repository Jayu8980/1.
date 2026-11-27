package com.aitrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.ActionResult
import com.aitrader.data.model.OrderTicket
import com.aitrader.data.model.TradingMode
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.collectAsStateCompat
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TicketTarget(val symbol: String, val exchange: String, val ltp: Double?, val side: String = "BUY",
                        val qty: Int? = null, val product: String? = null)

@HiltViewModel
class TicketViewModel @Inject constructor(private val repo: TradingRepository) : ViewModel() {
    /** null = still checking. Re-read from the engine every time the ticket opens. */
    val live = MutableStateFlow<Boolean?>(null)
    val broker = MutableStateFlow("")
    val sending = MutableStateFlow(false)
    val result = MutableStateFlow<ActionResult?>(null)

    fun load() = viewModelScope.launch {
        live.value = null; result.value = null
        runCatching { repo.autotrade() }.onSuccess { live.value = it.data.mode == TradingMode.LIVE }
            .onFailure { live.value = false }
        runCatching { repo.brokers() }.onSuccess { l -> broker.value = l.data.firstOrNull { it.active }?.name ?: "" }
    }

    fun send(t: OrderTicket) = viewModelScope.launch {
        sending.value = true
        result.value = repo.placeOrder(t)
        sending.value = false
    }
}

/** Validates the ticket; returns an error message or null. Pure, unit-tested. */
fun validateTicket(qty: String, type: String, price: String, trigger: String): String? {
    val q = qty.toIntOrNull() ?: return "Enter a whole-number quantity"
    if (q <= 0) return "Quantity must be > 0"
    if (type in setOf("LIMIT", "STOPLOSS_LIMIT") && (price.toDoubleOrNull() ?: 0.0) <= 0) return "Enter a limit price"
    if (type in setOf("STOPLOSS_LIMIT", "STOPLOSS_MARKET") && (trigger.toDoubleOrNull() ?: 0.0) <= 0) return "Enter a trigger price"
    return null
}

@Composable
fun OrderTicketDialog(target: TicketTarget, onDismiss: () -> Unit, onDone: (String) -> Unit,
                      vm: TicketViewModel = hiltViewModel()) {
    LaunchedEffect(target) { vm.load() }
    val live by vm.live.collectAsStateCompat()
    val broker by vm.broker.collectAsStateCompat()
    val sending by vm.sending.collectAsStateCompat()
    val result by vm.result.collectAsStateCompat()
    var side by remember { mutableStateOf(target.side) }
    var qty by remember { mutableStateOf(target.qty?.toString() ?: "") }
    var type by remember { mutableStateOf("LIMIT") }
    var price by remember { mutableStateOf(target.ltp?.let { "%.2f".format(it) } ?: "") }
    var trigger by remember { mutableStateOf("") }
    var product by remember {
        mutableStateOf(target.product ?: if (target.exchange in setOf("NFO", "BFO", "DELTA")) "CARRYFORWARD" else "INTRADAY")
    }
    var confirmReal by remember { mutableStateOf(false) }
    LaunchedEffect(result) { result?.let { if (it.ok) onDone(it.message) } }

    val error = validateTicket(qty, type, price, trigger)
    val isLive = live == true
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${target.symbol} · ${target.exchange}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The single most important line on this screen.
                Box(Modifier.fillMaxWidth().background(if (isLive) LiveRed else PaperBlue, MaterialTheme.shapes.small)
                    .padding(10.dp).testTag("venueBanner")) {
                    Text(when (live) {
                        null -> "Checking engine mode…"
                        true -> "REAL MONEY · ${broker.ifBlank { "live broker" }}"
                        false -> "PAPER · simulated, no real order"
                    }, color = Color.White, fontWeight = FontWeight.Bold)
                }
                target.ltp?.let { Text("LTP ${"%.2f".format(it)}", style = MaterialTheme.typography.labelMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BUY", "SELL").forEach { s ->
                        FilterChip(side == s, { side = s }, label = { Text(s) }, modifier = Modifier.testTag("side_$s"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (s == "BUY") Profit.copy(alpha = .3f) else Loss.copy(alpha = .3f)))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("LIMIT" to "Limit", "MARKET" to "Market", "STOPLOSS_LIMIT" to "SL", "STOPLOSS_MARKET" to "SL-M")
                        .forEach { (v, l) -> FilterChip(type == v, { type = v }, label = { Text(l) }) }
                }
                OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("qty"))
                if (type == "LIMIT" || type == "STOPLOSS_LIMIT")
                    OutlinedTextField(price, { price = it }, label = { Text("Limit price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (type.startsWith("STOPLOSS"))
                    OutlinedTextField(trigger, { trigger = it }, label = { Text("Trigger price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (type == "MARKET" && isLive)
                    Text("Sent as a LIMIT 0.5% through LTP (protection against bad fills).", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val products = if (target.exchange in setOf("NFO", "BFO", "DELTA")) listOf("CARRYFORWARD" to "NRML", "INTRADAY" to "MIS")
                                   else listOf("INTRADAY" to "Intraday", "DELIVERY" to "Delivery")
                    products.forEach { (v, l) -> FilterChip(product == v, { product = v }, label = { Text(l) }) }
                }
                val q = qty.toIntOrNull(); val px = price.toDoubleOrNull() ?: target.ltp
                if (q != null && px != null) Text("≈ value ${com.aitrader.ui.components.Fmt.money(q * px)}",
                    style = MaterialTheme.typography.labelMedium)
                (error ?: result?.takeIf { !it.ok }?.message)?.let { Text(it, color = Loss, style = MaterialTheme.typography.bodySmall) }
                Text("Checked by the engine's risk limits and kill switch. Requires biometric.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { if (isLive) confirmReal = true else vm.send(ticket(target, side, qty, type, price, trigger, product, false)) },
                enabled = error == null && live != null && !sending, modifier = Modifier.testTag("sendOrder"),
                colors = ButtonDefaults.buttonColors(containerColor = if (side == "BUY") Profit else Loss)) {
                Text(if (sending) "Sending…" else "$side ${if (isLive) "(REAL)" else "(paper)"}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })

    if (confirmReal) AlertDialog(onDismissRequest = { confirmReal = false },
        title = { Text("Send a REAL order?", color = LiveRed) },
        text = { Text("$side $qty ${target.symbol} ($type, $product) will be sent to ${broker.ifBlank { "your broker" }} with real money.") },
        confirmButton = {
            Button(onClick = { confirmReal = false; vm.send(ticket(target, side, qty, type, price, trigger, product, true)) },
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed), modifier = Modifier.testTag("confirmReal")) {
                Text("Send real order")
            }
        },
        dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Back") } })
}

private fun ticket(t: TicketTarget, side: String, qty: String, type: String, price: String, trigger: String,
                   product: String, live: Boolean) = OrderTicket(
    t.symbol, t.exchange, side, qty.toInt(), type,
    price.toDoubleOrNull().takeIf { type == "LIMIT" || type == "STOPLOSS_LIMIT" },
    trigger.toDoubleOrNull().takeIf { type.startsWith("STOPLOSS") }, product, live)
