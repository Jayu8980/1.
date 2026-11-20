package com.tradelens.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.tradelens.app.data.db.WatchlistEntity
import com.tradelens.app.data.db.WatchlistItemEntity
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.Timeframe
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.SourceChip
import com.tradelens.app.ui.components.Sparkline
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(private val db: AppDatabase, private val market: MarketRepository) : ViewModel() {
    val lists = db.watchlists().lists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val sparks = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val search = MutableStateFlow<List<Instrument>>(emptyList())

    fun items(listId: Long) = db.watchlists().items(listId)

    fun loadSnapshot(items: List<Instrument>) = viewModelScope.launch {
        quotes.value = quotes.value + market.quotes(items)
        for (i in items) if (!sparks.value.containsKey(i.key)) {
            val c = runCatching { market.candles(i, Timeframe.D1, 30).candles }.getOrDefault(emptyList())
            sparks.value = sparks.value + (i.key to c.map { it.close })
        }
    }

    /** Streams only the instruments currently visible on screen; cancelled when they scroll away. */
    suspend fun stream(visible: List<Instrument>) {
        market.stream(visible).collect { q -> quotes.value = quotes.value + (q.instrumentKey to q) }
    }

    fun addList(name: String) = viewModelScope.launch { db.watchlists().insertList(WatchlistEntity(name = name, position = lists.value.size)) }
    fun deleteList(id: Long) = viewModelScope.launch { db.watchlists().clearList(id); db.watchlists().deleteList(id) }
    fun add(listId: Long, i: Instrument) = viewModelScope.launch {
        db.watchlists().insertItem(WatchlistItemEntity(listId = listId, instrumentJson = AppJson.encodeToString(Instrument.serializer(), i), position = 999))
    }
    fun remove(item: WatchlistItemEntity) = viewModelScope.launch { db.watchlists().deleteItem(item) }
    fun search(q: String, crypto: Boolean) = viewModelScope.launch { search.value = market.search(q, crypto) }
}

@Composable
fun WatchlistScreen(nav: NavController, vm: WatchlistViewModel = hiltViewModel()) {
    val lists by vm.lists.collectAsState()
    var selected by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    val list = lists.getOrNull(selected.coerceAtMost((lists.size - 1).coerceAtLeast(0)))
    val items by (list?.let { vm.items(it.id) } ?: MutableStateFlow(emptyList())).collectAsState(initial = emptyList())
    val quotes by vm.quotes.collectAsState()
    val sparks by vm.sparks.collectAsState()
    val listState = rememberLazyListState()
    val visible by remember(items) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { items.getOrNull(it.index)?.instrument } } }

    LaunchedEffect(items) { vm.loadSnapshot(items.map { it.instrument }) }
    LaunchedEffect(visible.map { it.key }) { if (visible.isNotEmpty()) vm.stream(visible) }

    Column(Modifier.statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScrollableTabRow(selectedTabIndex = selected.coerceIn(0, (lists.size - 1).coerceAtLeast(0)), modifier = Modifier.weight(1f), edgePadding = 8.dp) {
                lists.forEachIndexed { k, l -> Tab(selected = k == selected, onClick = { selected = k }, text = { Text(l.name) }) }
            }
            IconButton(onClick = { newList = true }) { Icon(Icons.Default.Add, "New list") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { adding = true }) { Text("+ Add symbol") }
            Spacer(Modifier.weight(1f))
            if (list != null && lists.size > 1) TextButton(onClick = { vm.deleteList(list.id); selected = 0 }) { Text("Delete list") }
        }
        LazyColumn(state = listState) {
            items(items, key = { it.id }) { item ->
                val i = item.instrument
                val q = quotes[i.key]
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.instrument(i)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.symbol, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(i.exchange.name, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp)); SourceChip(q?.badge)
                        }
                    }
                    Sparkline(sparks[i.key].orEmpty())
                    Column(Modifier.width(100.dp), horizontalAlignment = Alignment.End) {
                        Text(fmtPrice(q?.ltp), fontWeight = FontWeight.SemiBold)
                        Text(fmtPct(q?.changePct), color = pnlColor(q?.changePct ?: 0.0), fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Default.Delete, "Remove", tint = Muted) }
                }
                HorizontalDivider()
            }
        }
    }

    if (newList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newList = false }, title = { Text("New watchlist") }, text = { Field(name, { name = it }, "Name") },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.addList(name); newList = false }) { Text("Create") } })
    }
    if (adding && list != null) SymbolSearchDialog(vm, onPick = { vm.add(list.id, it); adding = false }, onDismiss = { adding = false })
}

@Composable
fun SymbolSearchDialog(vm: WatchlistViewModel, onPick: (Instrument) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var crypto by remember { mutableStateOf(false) }
    val results by vm.search.collectAsState()
    LaunchedEffect(q, crypto) { if (q.length >= 2) { kotlinx.coroutines.delay(300); vm.search(q, crypto) } }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add symbol") },
        text = {
            Column {
                Row { FilterChip(!crypto, { crypto = false }, { Text("India") }); Spacer(Modifier.width(8.dp)); FilterChip(crypto, { crypto = true }, { Text("Delta crypto") }) }
                Field(q, { q = it }, if (crypto) "e.g. BTCUSD, ETH" else "e.g. RELIANCE, NIFTY 50, TCS.BO")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.key + it.token }) { i ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 8.dp)) {
                            Text("${i.symbol}  ·  ${i.exchange}", fontWeight = FontWeight.SemiBold)
                            Text("${i.segment}  ${i.name.take(40)}", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
