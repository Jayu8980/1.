package com.tradelens.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tradelens.app.data.db.AlertEntity
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.engine.AuditRow
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.llm.ChatMsg
import com.tradelens.app.data.llm.LlmClient
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.domain.Indicators
import com.tradelens.app.domain.Indicators.lastValid
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.Patterns
import com.tradelens.app.domain.Risk
import com.tradelens.app.domain.Timeframe
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Warn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------- More ----------------

@Composable
fun MoreScreen(nav: NavController) {
    val items = listOf(
        "Portfolio (all brokers + Delta)" to Routes.PORTFOLIO, "Option chain (F&O)" to Routes.OPTIONS, "Crypto analytics (Delta)" to Routes.CRYPTO,
        "Screener" to Routes.SCREENER, "Strategy backtester" to Routes.BACKTEST, "Risk tools" to Routes.RISK, "Alerts" to Routes.ALERTS,
        "Reports & leaderboard" to Routes.REPORTS, "Audit trail" to Routes.AUDIT, "Settings" to Routes.SETTINGS,
    )
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item { Text("More", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        items(items) { (label, route) ->
            Text(label, Modifier.fillMaxWidth().clickable { nav.navigate(route) }.padding(horizontal = 16.dp, vertical = 16.dp))
            HorizontalDivider()
        }
        item { Disclaimer() }
    }
}

// ---------------- Alerts ----------------

@HiltViewModel
class AlertsViewModel @Inject constructor(private val db: AppDatabase) : ViewModel() {
    val alerts = db.alerts().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun toggle(a: AlertEntity) = viewModelScope.launch { db.alerts().save(a.copy(active = !a.active)) }
    fun delete(a: AlertEntity) = viewModelScope.launch { db.alerts().delete(a.id) }
}

@Composable
fun AlertsScreen(vm: AlertsViewModel = hiltViewModel()) {
    val alerts by vm.alerts.collectAsState()
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item { Text("Alerts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        item { Text("Create alerts from any chart (Alert button). Background checks run every 15 minutes, 24/7 for crypto.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (alerts.isEmpty()) item { Text("No alerts yet", color = Muted, modifier = Modifier.padding(16.dp)) }
        items(alerts, key = { it.id }) { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.instrument.symbol, fontWeight = FontWeight.SemiBold)
                    Text(when (a.kind) { "PRICE_ABOVE" -> "Price ≥ ${a.value}"; "PRICE_BELOW" -> "Price ≤ ${a.value}"; "RULE" -> "${a.rule} (${a.timeframe})"; else -> "Pattern ${a.rule.orEmpty().ifBlank { "any" }} (${a.timeframe})" }, fontSize = 12.sp)
                    a.lastTriggered?.let { Text("Last triggered ${fmtTime(it / 1000)}", color = Muted, fontSize = 11.sp) }
                }
                Switch(a.active, { vm.toggle(a) })
                TextButton(onClick = { vm.delete(a) }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
}

// ---------------- Audit ----------------

@HiltViewModel
class AuditViewModel @Inject constructor(private val db: AppDatabase, private val engine: EngineClient) : ViewModel() {
    val local = db.audit().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val remote = MutableStateFlow<List<AuditRow>>(emptyList())
    fun loadRemote() = viewModelScope.launch { if (engine.isConfigured()) runCatching { remote.value = engine.api.audit() } }
    suspend fun csv(): String = "time,category,message,payload\n" + db.audit().all().joinToString("\n") {
        "${fmtTime(it.time / 1000, "yyyy-MM-dd HH:mm:ss")},${it.category},\"${it.message.replace("\"", "'")}\",\"${it.payload.replace("\"", "'")}\""
    }
}

@Composable
fun AuditScreen(vm: AuditViewModel = hiltViewModel()) {
    val local by vm.local.collectAsState()
    val remote by vm.remote.collectAsState()
    var src by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadRemote() }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Audit trail", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilterChip(src == 0, { src = 0 }, { Text("App") }); Spacer(Modifier.width(4.dp)); FilterChip(src == 1, { src = 1 }, { Text("Engine") })
                TextButton(onClick = { scope.launch { shareText(ctx, "tradelens_audit.csv", vm.csv()) } }) { Text("Export") }
            }
        }
        val rows = if (src == 0) local.map { AuditRow(it.time / 1000, it.category, it.message, it.payload) } else remote
        items(rows) { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row { Text(r.category, color = when (r.category) { "RISK", "KILL" -> Bear; "APPROVAL" -> Warn; else -> Muted }, fontSize = 11.sp, modifier = Modifier.width(80.dp)); Text(fmtTime(r.time, "dd MMM HH:mm:ss"), color = Muted, fontSize = 11.sp) }
                Text(r.message, fontSize = 13.sp)
                if (r.payload.isNotBlank()) Text(r.payload.take(240), color = Muted, fontSize = 10.sp)
            }
            HorizontalDivider()
        }
    }
}

/** Writes [text] to cache and opens the Android share sheet (CSV export). */
fun shareText(ctx: android.content.Context, name: String, text: String) {
    val f = java.io.File(ctx.cacheDir, name).apply { writeText(text) }
    shareFile(ctx, f, if (name.endsWith(".csv")) "text/csv" else "application/pdf")
}

fun shareFile(ctx: android.content.Context, f: java.io.File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- Risk tools ----------------

@HiltViewModel
class RiskToolsViewModel @Inject constructor(private val market: MarketRepository) : ViewModel() {
    val atr = MutableStateFlow<Pair<Double, Double>?>(null)
    fun loadAtr(sym: String, crypto: Boolean) = viewModelScope.launch {
        val c = market.candles(Instrument.parse(sym, crypto), Timeframe.D1, 60).candles
        if (c.size > 15) atr.value = c.last().close to Indicators.atr(c, 14).lastValid()
    }
}

@Composable
fun RiskToolsScreen(vm: RiskToolsViewModel = hiltViewModel()) {
    var capital by remember { mutableStateOf("1000000") }
    var riskPct by remember { mutableStateOf("1") }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var atrMult by remember { mutableStateOf("2") }
    var lot by remember { mutableStateOf("1") }
    var long by remember { mutableStateOf(true) }
    var sym by remember { mutableStateOf("RELIANCE") }
    var crypto by remember { mutableStateOf(false) }
    var lev by remember { mutableStateOf("10") }
    val atr by vm.atr.collectAsState()
    LaunchedEffect(atr) { atr?.let { if (entry.isBlank()) entry = "%.2f".format(it.first) } }

    LazyColumn(Modifier.statusBarsPadding().imePadding(), contentPadding = ListPadding) {
        item {
            SectionCard("Position size (risk % + ATR stop)") {
                Row { FilterChip(long, { long = true }, { Text("Long") }); Spacer(Modifier.width(6.dp)); FilterChip(!long, { long = false }, { Text("Short") }) }
                Field(capital, { capital = it }, "Capital (₹)", number = true)
                Field(riskPct, { riskPct = it }, "Risk per trade %", number = true)
                Row { Field(sym, { sym = it }, "Symbol for ATR", modifier = Modifier.weight(1f)); TextButton(onClick = { vm.loadAtr(sym, crypto) }) { Text("Fetch ATR") } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(crypto, { crypto = it }); Text(" Delta crypto symbol", fontSize = 12.sp) }
                atr?.let { Text("Last ${fmtPrice(it.first)} · ATR(14) ${fmtPrice(it.second)}", color = Muted, fontSize = 12.sp) }
                Field(entry, { entry = it }, "Entry", number = true)
                Field(stop, { stop = it }, "Stop (blank = ATR × multiplier)", number = true)
                Field(atrMult, { atrMult = it }, "ATR multiplier", number = true)
                Field(target, { target = it }, "Target (optional)", number = true)
                Field(lot, { lot = it }, "Lot size", number = true)
                val r = runCatching {
                    Risk.positionSize(capital.toDouble(), riskPct.toDouble(), entry.toDouble(), long, stop.toDoubleOrNull(), atr?.second, atrMult.toDoubleOrNull() ?: 2.0, lot.toIntOrNull() ?: 1)
                }
                r.getOrNull()?.let { p ->
                    KV("Quantity", "${p.qty.toLong()} (${p.lots} lots)"); KV("Stop", fmtPrice(p.stop)); KV("Amount at risk", fmtMoney(p.riskAmount))
                    KV("Position value", fmtMoney(p.positionValue)); KV("% of capital", "%.1f%%".format(p.capitalPct))
                    p.cappedBy?.let { Text("Capped: $it", color = Warn, fontSize = 12.sp) }
                    target.toDoubleOrNull()?.let { t -> KV("Risk : reward", "1 : %.2f".format(Risk.riskReward(entry.toDouble(), p.stop, t))) }
                } ?: ErrorText(r.exceptionOrNull()?.message?.takeIf { entry.isNotBlank() })
            }
        }
        item {
            SectionCard("Liquidation calculator (Delta, isolated)") {
                Field(lev, { lev = it }, "Leverage (x)", number = true)
                val e = entry.toDoubleOrNull(); val l = lev.toDoubleOrNull()
                if (e != null && l != null && l >= 1) {
                    KV("Long liquidation ≈", fmtPrice(Risk.liquidationPrice(e, l, true)), Bear)
                    KV("Short liquidation ≈", fmtPrice(Risk.liquidationPrice(e, l, false)), Bear)
                    Risk.leverageWarning(l)?.let { Text(it, color = Warn, fontSize = 12.sp) }
                    Text("Uses 0.5% maintenance margin; check the contract's actual MMR on Delta.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Disclaimer() }
    }
}

// ---------------- AI chat ----------------

@HiltViewModel
class ChatViewModel @Inject constructor(private val llm: LlmClient, private val market: MarketRepository) : ViewModel() {
    val msgs = MutableStateFlow<List<ChatMsg>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var context = ""

    fun init(i: Instrument) = viewModelScope.launch {
        if (context.isNotEmpty()) return@launch
        val r = market.candles(i, Timeframe.D1, 300)
        val st = InstrumentState(candles = r.candles, badge = r.badge, zones = Patterns.supportResistance(r.candles), patterns = Patterns.all(r.candles), loading = false)
        context = AnalysisSummary.build(i, st).toString()
    }

    fun send(text: String) = viewModelScope.launch {
        val history = msgs.value + ChatMsg("user", text)
        msgs.value = history; busy.value = true; error.value = null
        runCatching { llm.chat(context, history) }.onSuccess { msgs.value = history + ChatMsg("assistant", it) }.onFailure { error.value = it.message }
        busy.value = false
    }
}

@Composable
fun ChatScreen(instrument: Instrument, vm: ChatViewModel = hiltViewModel()) {
    LaunchedEffect(instrument.key) { vm.init(instrument) }
    val msgs by vm.msgs.collectAsState()
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Text("Ask about ${instrument.symbol}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), state = list) {
            item { Text("The AI sees only the indicators, patterns and levels computed for this chart. It cannot place orders or change limits.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(msgs) { m ->
                SectionCard(if (m.role == "user") "You" else "AI") { Text(m.text, fontSize = 14.sp) }
            }
            item { if (busy) Text("Thinking…", color = Muted, modifier = Modifier.padding(16.dp)); ErrorText(err); Disclaimer() }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(input, { input = it }, "Question", modifier = Modifier.weight(1f), singleLine = false)
            Button(enabled = !busy && input.isNotBlank(), onClick = { vm.send(input.trim()); input = "" }, modifier = Modifier.padding(start = 6.dp)) { Text("Send") }
        }
    }
}
