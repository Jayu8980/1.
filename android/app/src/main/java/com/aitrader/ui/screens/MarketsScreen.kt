package com.aitrader.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    Box(Modifier.fillMaxSize()) {
        StateContent(state, vm::refresh) { l ->
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)) {
                item { FeedBanner(feedState) }
                items(l.data, key = { it.contract.key }) { w -> QuoteRow(w.contract, quotes[w.contract.key], onRemove = { vm.remove(w.contract) }) }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            .testTag("addSymbol")) { Icon(Icons.Filled.Add, "Add symbol") }
    }
    if (adding) AddSymbolDialog(vm, onDismiss = { adding = false })
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
private fun QuoteRow(c: ContractDto, q: Quote?, onRemove: () -> Unit) {
    val up = q?.prev?.let { q.ltp > it }
    val flash by animateColorAsState(
        when (up) { true -> Profit.copy(alpha = 0.18f); false -> Loss.copy(alpha = 0.18f); null -> Color.Transparent },
        tween(400), label = "flash")
    val chg = q?.let { (it.ltp / it.sessionOpen - 1) * 100 } ?: 0.0
    Column(Modifier.testTag("quote_${c.symbol}")) {
        Row(Modifier.fillMaxWidth().background(flash, RoundedCornerShape(8.dp)).padding(vertical = 10.dp, horizontal = 4.dp),
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
