package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.Order
import com.aitrader.data.model.Position
import com.aitrader.data.repo.Loaded
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Book(val positions: List<Position>, val orders: List<Order>)

@HiltViewModel
class PositionsViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<Book>(pollMs = 3_000) {
    override suspend fun fetch(): Loaded<Book> {
        val p = repo.positions(); val o = repo.orders()
        return Loaded(Book(p.data, o.data), p.fromCache || o.fromCache, minOf(p.updatedAt, o.updatedAt), p.error ?: o.error)
    }

    fun exit(p: Position) = viewModelScope.launch {
        val r = repo.exit(p); say(r.message); refresh()
    }
    val venue = repo.venue
    fun setVenue(v: String) { repo.venue.value = v; refresh() }
    fun cancel(o: Order) = viewModelScope.launch { say(repo.cancelOrder(o.orderId).message); refresh() }
    fun notify(m: String) { say(m); refresh() }
}

@Composable
fun PositionsScreen(snackbar: SnackbarHostState, vm: PositionsViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Position?>(null) }
    var ticket by remember { mutableStateOf<TicketTarget?>(null) }
    val venue by vm.venue.collectAsStateCompat()

    StateContent(state, vm::refresh) { l ->
        Column {
            AccountToggle(venue, vm::setVenue)
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Positions (${l.data.positions.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Orders (${l.data.orders.size})") })
            }
            if (tab == 0) {
                val total = l.data.positions.sumOf { it.unrealizedPnl }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Unrealised", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Fmt.signedMoney(total), color = pnlColor(total), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (l.data.positions.isEmpty()) item { Text("No open positions", Modifier.padding(24.dp)) }
                    items(l.data.positions, key = { it.symbol + it.product }) { p ->
                        PositionRow(p, onExit = { confirm = p },
                            onTrade = { ticket = TicketTarget(p.symbol, p.exchange, p.ltp, if (p.qty > 0) "BUY" else "SELL", null, p.product) })
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (l.data.orders.isEmpty()) item { Text("No orders today", Modifier.padding(24.dp)) }
                    items(l.data.orders, key = { it.orderId }) { o -> OrderRow(o, onCancel = { vm.cancel(o) }) }
                }
            }
        }
    }
    confirm?.let { p ->
        ConfirmDialog("Exit ${p.symbol}?",
            "${if (p.qty > 0) "Sell" else "Buy"} ${kotlin.math.abs(p.qty)} at market (${p.product}). Requires biometric.",
            "Exit position", danger = true, onConfirm = { vm.exit(p) }, onDismiss = { confirm = null })
    }
    ticket?.let { t -> OrderTicketDialog(t, onDismiss = { ticket = null }, onDone = { ticket = null; vm.notify(it) }) }
}

@Composable
private fun PositionRow(p: Position, onExit: () -> Unit, onTrade: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.symbol, fontWeight = FontWeight.Bold)
                Text("${p.qty} @ ${Fmt.price(p.avgPrice)} · ${p.product}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("LTP ${Fmt.price(p.ltp)} · ${p.strategy}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.signedMoney(p.unrealizedPnl), color = pnlColor(p.unrealizedPnl), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onTrade, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Add") }
                    FilledTonalButton(onClick = onExit, modifier = Modifier.testTag("exit_${p.symbol}"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("Exit") }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(o: Order, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(o.side, if (o.side == "BUY") Profit else Loss)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${o.symbol} × ${o.qty}", fontWeight = FontWeight.SemiBold)
            Text("${o.type} · ${o.strategy} · ${Fmt.time(o.ts)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(o.status, style = MaterialTheme.typography.labelMedium, color = when (o.status) {
                "FILLED" -> Profit; "REJECTED" -> Loss; "CANCELLED" -> Nifty; else -> Warn })
            if (o.avgFillPrice > 0) Text(Fmt.price(o.avgFillPrice), style = MaterialTheme.typography.bodySmall)
            if (o.status == "OPEN" || o.status == "PENDING")
                TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("Cancel", color = Loss) }
        }
    }
}

/** "Engine" = whatever the engine trades now (PAPER or LIVE); "Real broker" = your actual broker
 *  account, viewable even while the engine is in PAPER. */
@Composable
fun AccountToggle(venue: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(venue == "current", { onChange("current") }, label = { Text("Engine account") })
        FilterChip(venue == "live", { onChange("live") }, label = { Text("Real broker account") },
            modifier = Modifier.testTag("realAccount"))
    }
}
