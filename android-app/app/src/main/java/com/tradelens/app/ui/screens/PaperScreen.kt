package com.tradelens.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.PaperOrderEntity
import com.tradelens.app.data.db.PaperTradeEntity
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.paper.PaperSnapshot
import com.tradelens.app.data.paper.PaperTrading
import com.tradelens.app.domain.Instrument
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaperViewModel @Inject constructor(private val db: AppDatabase, private val paper: PaperTrading, private val market: MarketRepository) : ViewModel() {
    val positions = db.paper().positions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val orders = db.paper().orders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trades = db.paper().trades().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val snap = MutableStateFlow<PaperSnapshot?>(null)

    /** Streams live prices for open positions/orders (Delta for crypto, broker/Yahoo for India) and matches fills. */
    suspend fun live() {
        val insts = (positions.value.map { it.instrumentJson } + orders.value.filter { it.status == "OPEN" }.map { it.instrumentJson })
            .distinct().map { AppJson.decodeFromString(Instrument.serializer(), it) }
        snap.value = paper.snapshot()
        if (insts.isEmpty()) return
        paper.onQuotes(market.quotes(insts).values); snap.value = paper.snapshot()
        market.stream(insts).collect { q -> paper.onQuotes(listOf(q)); snap.value = paper.snapshot() }
    }

    fun cancel(o: PaperOrderEntity) = viewModelScope.launch { paper.cancel(o) }
    fun flatten() = viewModelScope.launch { paper.flatten("Manual flatten"); snap.value = paper.snapshot() }
    fun reset() = viewModelScope.launch { paper.reset(); snap.value = paper.snapshot() }
    fun restart() = viewModelScope.launch { paper.restartAfterBreaker(); snap.value = paper.snapshot() }
    fun note(t: PaperTradeEntity, n: String) = viewModelScope.launch { db.paper().setNotes(t.id, n) }
    fun price(key: String) = paper.price(key)
}

@Composable
fun PaperScreen(nav: NavController, vm: PaperViewModel = hiltViewModel()) {
    val positions by vm.positions.collectAsState()
    val orders by vm.orders.collectAsState()
    val trades by vm.trades.collectAsState()
    val snap by vm.snap.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<PaperTradeEntity?>(null) }
    LaunchedEffect(positions.size, orders.count { it.status == "OPEN" }) { vm.live() }

    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset paper portfolio?") },
        text = { Text("Deletes all paper positions, orders and the journal and restores the starting capital.") },
        confirmButton = { TextButton(onClick = { vm.reset(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
    noteFor?.let { t ->
        var n by remember { mutableStateOf(t.notes) }
        AlertDialog(onDismissRequest = { noteFor = null }, title = { Text("Journal note – ${t.symbol}") }, text = { Field(n, { n = it }, "What did you learn?", singleLine = false) },
            confirmButton = { TextButton(onClick = { vm.note(t, n); noteFor = null }) { Text("Save") } })
    }

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            SectionCard("Paper portfolio (virtual ₹)") {
                snap?.let {
                    KV("Equity", fmtMoney(it.equity)); KV("Cash", fmtMoney(it.cash))
                    KV("Day P&L", fmtMoney(it.dayPnl), pnlColor(it.dayPnl)); KV("Unrealised", fmtMoney(it.unrealised), pnlColor(it.unrealised))
                    KV("Realised (journal)", fmtMoney(trades.sumOf { t -> t.pnl }), pnlColor(trades.sumOf { t -> t.pnl }))
                    if (it.circuitBroken) { Text("Drawdown circuit breaker tripped.", color = Bear); TextButton(onClick = vm::restart) { Text("Manual restart") } }
                    else if (it.halted) Text("Halted for today (daily loss limit).", color = Bear)
                }
                Row { TextButton(onClick = vm::flatten) { Text("Flatten all") }; TextButton(onClick = { confirmReset = true }) { Text("Reset") } }
                Text("Place paper orders from any chart → Trade. Uses live Delta prices for crypto and real charges.", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            TabRow(tab) { listOf("Positions", "Orders", "Journal").forEachIndexed { k, l -> Tab(tab == k, { tab = k }, text = { Text(l) }) } }
        }
        when (tab) {
            0 -> items(positions, key = { it.instrumentKey }) { p ->
                val i = AppJson.decodeFromString(Instrument.serializer(), p.instrumentJson)
                val ltp = vm.price(p.instrumentKey) ?: p.avgPrice
                val pnl = (ltp - p.avgPrice) * p.qty
                Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.instrument(i)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row { Text("${i.symbol} ${if (p.qty > 0) "LONG" else "SHORT"} ${kotlin.math.abs(p.qty)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(fmtPrice(pnl), color = pnlColor(pnl)) }
                    Text("Avg ${fmtPrice(p.avgPrice)} · LTP ${fmtPrice(ltp)} · SL ${fmtPrice(p.stopLoss)} · TG ${fmtPrice(p.target)}${if (i.isCrypto) " (USD)" else ""}", color = Muted, fontSize = 12.sp)
                }
                HorizontalDivider()
            }
            1 -> items(orders, key = { it.id }) { o ->
                val i = AppJson.decodeFromString(Instrument.serializer(), o.instrumentJson)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${o.side} ${o.qty} ${i.symbol} ${o.type}", fontWeight = FontWeight.SemiBold)
                        Text("${o.status}${o.fillPrice?.let { " @ ${fmtPrice(it)}" } ?: o.price?.let { " lim ${fmtPrice(it)}" } ?: ""} · charges ₹${"%.2f".format(o.charges)} · ${fmtTime(o.createdAt / 1000)}", color = Muted, fontSize = 12.sp)
                        if (o.reason.isNotBlank()) Text(o.reason, color = Muted, fontSize = 11.sp)
                    }
                    if (o.status == "OPEN") TextButton(onClick = { vm.cancel(o) }) { Text("Cancel") }
                }
                HorizontalDivider()
            }
            else -> items(trades, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable { noteFor = t }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row { Text("${t.symbol} ${t.side} ${t.qty}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(fmtMoney(t.pnl), color = pnlColor(t.pnl)) }
                    Text("${fmtPrice(t.entry)} → ${fmtPrice(t.exit)} · charges ₹${"%.2f".format(t.charges)} · ${fmtTime(t.closedAt / 1000)}", color = Muted, fontSize = 12.sp)
                    Text("Entry: ${t.entryReason} · Exit: ${t.exitReason}", color = Muted, fontSize = 11.sp)
                    if (t.notes.isNotBlank()) Text("📝 ${t.notes}", fontSize = 12.sp)
                }
                HorizontalDivider()
            }
        }
    }
}
