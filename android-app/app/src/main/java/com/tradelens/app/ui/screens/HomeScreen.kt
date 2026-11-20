package com.tradelens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.EngineStatus
import com.tradelens.app.data.engine.ToggleBody
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.market.Universe
import com.tradelens.app.data.paper.PaperSnapshot
import com.tradelens.app.data.paper.PaperTrading
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.Quote
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.SourceChip
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

data class HomeState(
    val indices: List<Quote> = emptyList(),
    val sectors: List<Pair<String, Quote>> = emptyList(),
    val gainers: List<Quote> = emptyList(),
    val losers: List<Quote> = emptyList(),
    val engine: EngineStatus? = null,
    val engineError: String? = null,
    val paper: PaperSnapshot? = null,
    val flows: List<com.tradelens.app.data.engine.FlowRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val market: MarketRepository, private val universe: Universe, private val engine: EngineClient,
    private val paper: PaperTrading, val settings: SettingsStore, private val db: AppDatabase,
) : ViewModel() {
    val state = MutableStateFlow(HomeState())

    fun refresh() = viewModelScope.launch {
        state.value = state.value.copy(loading = true, error = null)
        runCatching {
            val idx = listOf("NIFTY 50", "BANKNIFTY", "SENSEX", "INDIA VIX", "FINNIFTY").map { Instrument.parse(it) }
            val sec = Universe.SECTOR_INDICES.map { Instrument.parse(it) }
            val q = market.quotes(idx + sec)
            val nifty = universe.members("nifty50").take(50).map { Instrument.parse(it.symbol) }
            val movers = nifty.chunked(10).map { ch -> viewModelScope.async { market.quotes(ch).values.toList() } }.awaitAll().flatten()
            state.value = state.value.copy(
                indices = idx.mapNotNull { q[it.key] },
                sectors = sec.mapNotNull { s -> q[s.key]?.let { s.symbol.removePrefix("NIFTY ") to it } },
                gainers = movers.sortedByDescending { it.changePct }.take(5),
                losers = movers.sortedBy { it.changePct }.take(5),
            )
        }.onFailure { state.value = state.value.copy(error = it.message) }
        state.value = state.value.copy(paper = runCatching { paper.snapshot() }.getOrNull(), loading = false)
        if (engine.isConfigured()) runCatching { engine.api.status() }
            .onSuccess { state.value = state.value.copy(engine = it, engineError = null) }
            .onFailure { state.value = state.value.copy(engineError = "Engine unreachable: ${it.message}") }
        if (engine.isConfigured()) runCatching { engine.api.flows() }.onSuccess { state.value = state.value.copy(flows = it) }
        runCatching { if (engine.isConfigured()) MarketHours.holidays = engine.api.holidays().map { java.time.LocalDate.parse(it) }.toSet() }
    }

    /** Global kill switch: halts engine strategies, flattens paper positions and blocks all new orders in the app. */
    fun setKill(on: Boolean) = viewModelScope.launch {
        settings.update { it.copy(globalKill = on) }
        db.audit().log(AuditEntity(category = "KILL", message = "Global kill switch ${if (on) "ON" else "OFF"}"))
        if (on) paper.flatten("Global kill switch")
        if (engine.isConfigured()) runCatching { engine.api.globalKill(ToggleBody(on)) }
            .onFailure { state.value = state.value.copy(engineError = "Could not reach engine to ${if (on) "engage" else "release"} kill switch: ${it.message}") }
        refresh()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val settings by vm.settings.state.collectAsState()
    var confirmKill by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }

    if (confirmKill) AlertDialog(
        onDismissRequest = { confirmKill = false },
        title = { Text(if (settings.globalKill) "Release kill switch?" else "Engage GLOBAL KILL SWITCH?") },
        text = { Text(if (settings.globalKill) "New orders will be allowed again. Engine strategies stay halted until you restart them." else
            "Halts every strategy on the engine, flattens paper positions and blocks all new orders from this app.") },
        confirmButton = { TextButton(onClick = { vm.setKill(!settings.globalKill); confirmKill = false }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } },
    )

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TradeLens", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("NSE ${MarketHours.phase().name.lowercase().replace('_', '-')} · crypto 24/7", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { vm.refresh() }) { Text(if (s.loading) "…" else "Refresh") }
            }
        }
        item {
            Button(
                onClick = { confirmKill = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (settings.globalKill) Bear else Bear.copy(alpha = 0.25f)),
            ) { Text(if (settings.globalKill) "KILL SWITCH ENGAGED – tap to release" else "GLOBAL KILL SWITCH", fontWeight = FontWeight.Bold) }
        }
        item { ErrorText(s.error) }
        item {
            SectionCard("Indices") {
                s.indices.forEach { q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { nav.navigate(Routes.instrument(Instrument.parse(q.instrumentKey.substringAfter(':')))) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(q.instrumentKey.substringAfter(':'), Modifier.weight(1f))
                        SourceChip(q.badge)
                        Text(fmtPrice(q.ltp), Modifier.width(96.dp).padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                        Text(fmtPct(q.changePct), color = pnlColor(q.changePct), modifier = Modifier.width(64.dp))
                    }
                }
            }
        }
        item {
            SectionCard("Sector heatmap") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.sectors.sortedByDescending { it.second.changePct }.forEach { (name, q) ->
                        val intensity = min(abs(q.changePct) / 3.0, 1.0).toFloat() * 0.7f + 0.15f
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background((if (q.changePct >= 0) Bull else Bear).copy(alpha = intensity)).padding(10.dp)) {
                            Column { Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(fmtPct(q.changePct), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { MoversCard("Top gainers (NIFTY 50)", s.gainers, nav) }
                Box(Modifier.weight(1f)) { MoversCard("Top losers", s.losers, nav) }
            }
        }
        item {
            SectionCard("Paper portfolio", trailing = { TextButton(onClick = { nav.navigate(Routes.PAPER) }) { Text("Open") } }) {
                s.paper?.let {
                    KV("Equity", fmtMoney(it.equity)); KV("Day P&L", fmtMoney(it.dayPnl), pnlColor(it.dayPnl)); KV("Unrealised", fmtMoney(it.unrealised), pnlColor(it.unrealised))
                    if (it.halted) Text("Halted for the day by risk limits", color = Bear)
                }
            }
        }
        item {
            SectionCard("Strategy engine", trailing = { TextButton(onClick = { nav.navigate(Routes.ENGINE) }) { Text("Open") } }) {
                val e = s.engine
                when {
                    e != null -> {
                        KV("Mode", e.mode.uppercase()); KV("Regime", e.regime); KV("Equity", fmtMoney(e.equity)); KV("Day P&L", "${fmtMoney(e.dayPnl)} (${fmtPct(e.dayPnlPct)})", pnlColor(e.dayPnl))
                        KV("Drawdown", fmtPct(-e.drawdownPct), pnlColor(-e.drawdownPct))
                        if (e.halted || e.circuitBroken || e.globalKill) Text("Engine halted (daily-loss=${e.halted}, circuit-breaker=${e.circuitBroken}, kill=${e.globalKill})", color = Bear)
                    }
                    s.engineError != null -> Text(s.engineError!!, color = Bear, fontSize = 13.sp)
                    else -> Text("Connect your Python engine in Settings → Engine to see signals, regimes and live risk.", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            SectionCard("FII / DII flows") {
                if (s.flows.isEmpty()) Text("Shown when the engine is connected (it fetches NSE's provisional FII/DII cash figures after 6 pm IST).", color = Muted, fontSize = 12.sp)
                s.flows.take(5).forEach { f -> KV(f.date, "FII ${fmtMoney(f.fiiNet * 1e7)}  DII ${fmtMoney(f.diiNet * 1e7)}", pnlColor(f.fiiNet + f.diiNet)) }
            }
        }
        item { Disclaimer() }
    }
}

@Composable
private fun MoversCard(title: String, list: List<Quote>, nav: NavController) {
    SectionCard(title) {
        list.forEach { q ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { nav.navigate(Routes.instrument(Instrument.parse(q.instrumentKey.substringAfter(':')))) }) {
                Text(q.instrumentKey.substringAfter(':'), Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                Text(fmtPct(q.changePct), color = pnlColor(q.changePct), fontSize = 12.sp)
            }
        }
    }
}
