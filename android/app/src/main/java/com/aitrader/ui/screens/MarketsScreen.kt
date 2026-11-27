package com.aitrader.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.ContractDto
import com.aitrader.data.model.InstrumentHit
import com.aitrader.data.model.Quote
import com.aitrader.data.model.WatchItem
import com.aitrader.data.repo.FeedState
import com.aitrader.data.repo.MarketFeed
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MarketsViewModel @Inject constructor(private val repo: TradingRepository, val feed: MarketFeed) :
    ResourceViewModel<List<WatchItem>>() {
    val results = MutableStateFlow<List<InstrumentHit>>(emptyList())
    val searching = MutableStateFlow(false)
    private var searchJob: Job? = null

    override suspend fun fetch() = repo.watchlist().also { l ->
        // seed prices the engine already had, so the list isn't blank before the first tick
        l.data.forEach { w -> w.ltp?.let { feed.apply(w.contract.key, w.contract.symbol, w.contract.exchange, it,
            w.ts ?: System.currentTimeMillis(), w.source ?: "snapshot") } }
    }

    fun search(q: String, exchange: String) {
        searchJob?.cancel()
        if (q.length < 2) { results.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(300)   // debounce typing
            searching.value = true
            results.value = runCatching { repo.search(q, exchange) }.getOrElse { say(repo.describe(it)); emptyList() }
            searching.value = false
        }
    }

    fun notify(m: String) = say(m)
    fun add(h: InstrumentHit) = viewModelScope.launch { say(repo.addWatch(h.toContract()).message); refresh() }
    fun remove(c: ContractDto) = viewModelScope.launch {
        val r = repo.removeWatch(c); if (r.ok) feed.drop(c.key); say(r.message); refresh()
    }
}

@Composable
fun MarketsScreen(snackbar: SnackbarHostState, vm: MarketsViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    DisposableEffect(Unit) { vm.feed.acquire(); onDispose { vm.feed.release() } }
    val state by vm.state.collectAsStateCompat()
    val quotes by vm.feed.quotes.collectAsStateCompat()
    val feedState by vm.feed.state.collectAsStateCompat()
    var adding by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<TicketTarget?>(null) }
    val layout = LocalAppearance.current.layout
    var view by rememberSaveable { mutableStateOf(when (layout) { LayoutStyle.PRO -> "table"; LayoutStyle.CARDS -> "heat"; else -> "list" }) }

    Box(Modifier.fillMaxSize()) {
        StateContent(state, vm::refresh) { l ->
            MarketsContent(l.data, quotes, feedState, view, { view = it },
                onTrade = { w -> ticket = TicketTarget(w.contract.symbol, w.contract.exchange, quotes[w.contract.key]?.ltp) },
                onRemove = { w -> vm.remove(w.contract) })
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            .testTag("addSymbol")) { Icon(Icons.Filled.Add, "Add symbol") }
    }
    if (adding) AddSymbolDialog(vm, onDismiss = { adding = false })
    ticket?.let { t ->
        if (t.symbol == "Nifty 50") { ticket = null; return@let }      // index: not tradable
        OrderTicketDialog(t, onDismiss = { ticket = null }, onDone = { msg -> ticket = null; vm.notify(msg) })
    }
}

@Composable
fun MarketsContent(items: List<WatchItem>, quotes: Map<String, Quote>, feed: FeedState, view: String,
                   onView: (String) -> Unit, onTrade: (WatchItem) -> Unit, onRemove: (WatchItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FeedBanner(feed) }
            Segmented(listOf("list" to "List", "heat" to "Heatmap", "table" to "Table"), view, onView)
        }
        when (view) {
            "heat" -> LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(items, key = { it.contract.key }) { w ->
                    val q = quotes[w.contract.key]
                    HeatTile(w.contract.symbol, q?.let { priceText(w.contract, it.ltp) } ?: "—",
                        q?.let { (it.ltp / it.sessionOpen - 1) * 100 } ?: 0.0, Modifier.height(96.dp)) { onTrade(w) }
                }
            }
            "table" -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        listOf("SYMBOL" to 1.5f, "LTP" to 1.2f, "CHG" to .9f, "SRC" to .8f).forEach { (h, w) ->
                            Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted,
                                textAlign = if (h == "SYMBOL") TextAlign.Start else TextAlign.End)
                        }
                    }
                }
                items(items, key = { it.contract.key }) { w -> TableRow(w, quotes[w.contract.key]) { onTrade(w) } }
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                items(items, key = { it.contract.key }) { w ->
                    QuoteRow(w.contract, quotes[w.contract.key], onRemove = { onRemove(w) }, onTrade = { onTrade(w) })
                }
            }
        }
    }
}

/** Brief tick flash: tints for ~450 ms after each new tick, then fades out. */
@Composable
private fun tickFlash(q: Quote?): Color {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(q?.ts) { if (q?.prev != null && q.prev != q.ltp) { on = true; kotlinx.coroutines.delay(450); on = false } }
    val up = q?.prev?.let { q.ltp > it }
    val target = if (!on || up == null) Color.Transparent else if (up) Profit.copy(alpha = .16f) else Loss.copy(alpha = .16f)
    return animateColorAsState(target, tween(if (on) 120 else 600), label = "flash").value
}

private fun priceText(c: ContractDto, v: Double) = if (c.exchange == "DELTA") "$" + "%,.2f".format(v) else "%,.2f".format(v)

@Composable
private fun TableRow(w: WatchItem, q: Quote?, onTrade: () -> Unit) {
    val up = q?.prev?.let { q.ltp > it }
    val flash = tickFlash(q)
    val chg = q?.let { (it.ltp / it.sessionOpen - 1) * 100 } ?: 0.0
    Row(Modifier.fillMaxWidth().background(flash, RoundedCornerShape(6.dp)).clickable(onClick = onTrade)
        .padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(w.contract.symbol.removeSuffix("-EQ"), Modifier.weight(1.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(q?.let { priceText(w.contract, it.ltp) } ?: "—", Modifier.weight(1.2f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium.merge(Numeric), fontWeight = FontWeight.Bold,
            color = when (up) { true -> Profit; false -> Loss; null -> MaterialTheme.colorScheme.onSurface })
        Text(if (q != null) "%+.2f%%".format(chg) else "", Modifier.weight(.9f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall.merge(Numeric), color = pnlColor(chg))
        Text(q?.source?.let { sourceLabel(it).substringBefore(' ') } ?: "", Modifier.weight(.8f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted, maxLines = 1)
    }
    HorizontalDivider(color = LocalTokens.current.glassBorder)
}

@Composable
private fun FeedBanner(s: FeedState) {
    val (text, color) = when (s) {
        FeedState.Idle -> "Feed idle" to Nifty
        FeedState.Connecting -> "Connecting to live feed…" to Warn
        is FeedState.Live -> {
            val src = s.sources.keys.joinToString { sourceLabel(it) }.ifBlank { "no source" }
            ("LIVE · $src" + if (s.unassigned.isNotEmpty()) " · ${s.unassigned.size} waiting for a broker login" else "") to Profit
        }
        is FeedState.Error -> "${s.message} · retry in ${s.retryInSec}s" to Loss
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.testTag("feedState"))
    }
}

fun sourceLabel(s: String) = when {
    s == "delta-ws" -> "Delta WS"
    s == "angelone-ws" -> "Angel One WS"
    s.startsWith("poll-") -> "polling ${s.removePrefix("poll-")}"
    s.startsWith("demo") -> "demo"
    else -> s
}

@Composable
private fun QuoteRow(c: ContractDto, q: Quote?, onRemove: () -> Unit, onTrade: () -> Unit) {
    val up = q?.prev?.let { q.ltp > it }
    val flash = tickFlash(q)
    val chg = q?.let { (it.ltp / it.sessionOpen - 1) * 100 } ?: 0.0
    Column(Modifier.testTag("quote_${c.symbol}")) {
        Row(Modifier.fillMaxWidth().background(flash, RoundedCornerShape(8.dp)).clickable(onClick = onTrade)
            .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.symbol, fontWeight = FontWeight.SemiBold)
                Text(c.exchange + (q?.let { " · ${sourceLabel(it.source)} · ${Fmt.time(it.ts)}" } ?: " · waiting for data"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(q?.let { if (c.exchange == "DELTA") "$" + "%,.2f".format(it.ltp) else Fmt.price(it.ltp) } ?: "—",
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = when (up) { true -> Profit; false -> Loss; null -> MaterialTheme.colorScheme.onSurface })
                if (q != null) Text(Fmt.pct(chg) + " session", style = MaterialTheme.typography.labelSmall, color = pnlColor(chg))
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove ${c.symbol}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun AddSymbolDialog(vm: MarketsViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var ex by remember { mutableStateOf("NSE") }
    val results by vm.results.collectAsStateCompat()
    val searching by vm.searching.collectAsStateCompat()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to watchlist") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NSE", "BSE", "NFO", "DELTA").forEach { e ->
                        FilterChip(ex == e, { ex = e; vm.search(q, e) }, label = { Text(e) })
                    }
                }
                OutlinedTextField(q, { q = it; vm.search(it, ex) }, label = { Text("Symbol") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("searchBox"))
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (ex == "DELTA") "Delta products are public." else "NSE/BSE/NFO search needs Angel One logged in on the engine.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.exchange + it.symbol }) { h ->
                        ListItem(headlineContent = { Text(h.symbol) },
                            supportingContent = { Text(h.description.ifBlank { h.instrument }) },
                            trailingContent = { TextButton(onClick = { vm.add(h); onDismiss() }) { Text("Add") } })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
