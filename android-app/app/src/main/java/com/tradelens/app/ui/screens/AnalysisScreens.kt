package com.tradelens.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.tradelens.app.data.db.SavedScreenEntity
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.market.Universe
import com.tradelens.app.domain.BacktestConfig
import com.tradelens.app.domain.BacktestResult
import com.tradelens.app.domain.Backtester
import com.tradelens.app.domain.ChargeSegment
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.Metrics
import com.tradelens.app.domain.Rules
import com.tradelens.app.domain.Timeframe
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.LineChart
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Accent
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

// ---------------- Screener ----------------

data class ScreenHit(val symbol: String, val sector: String, val close: Double, val changePct: Double)

@HiltViewModel
class ScreenerViewModel @Inject constructor(private val market: MarketRepository, private val universe: Universe, private val db: AppDatabase) : ViewModel() {
    val saved = db.screens().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hits = MutableStateFlow<List<ScreenHit>>(emptyList())
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val error = MutableStateFlow<String?>(null)

    fun run(rule: String, tf: Timeframe, index: String) = viewModelScope.launch {
        val rs = runCatching { Rules.parse(rule) }.getOrElse { error.value = it.message; return@launch }
        error.value = null; hits.value = emptyList()
        val members = universe.members(index)
        progress.value = 0 to members.size
        val sem = Semaphore(4) // keep memory and request rate modest on the phone
        var done = 0
        members.map { m ->
            async {
                sem.withPermit {
                    runCatching {
                        val c = market.candles(Instrument.parse(m.symbol), tf, 260).candles
                        if (c.size > 50 && Rules.Evaluator(c).eval(rs, c.size - 1)) {
                            val chg = (c.last().close / c[c.size - 2].close - 1) * 100
                            hits.value = (hits.value + ScreenHit(m.symbol, m.sector, c.last().close, chg)).sortedByDescending { it.changePct }
                        }
                    }
                    done++; progress.value = done to members.size
                }
            }
        }.awaitAll()
        progress.value = null
    }

    fun save(name: String, rule: String, tf: Timeframe) = viewModelScope.launch { db.screens().save(SavedScreenEntity(name = name, rule = rule, timeframe = tf.label)) }
    fun delete(s: SavedScreenEntity) = viewModelScope.launch { db.screens().delete(s.id) }
}

@Composable
fun ScreenerScreen(nav: NavController, vm: ScreenerViewModel = hiltViewModel()) {
    var rule by remember { mutableStateOf("RSI(14) < 30 AND CLOSE > EMA(200)") }
    var tf by remember { mutableStateOf(Timeframe.D1) }
    var index by remember { mutableStateOf("nifty500") }
    var name by remember { mutableStateOf("") }
    val hits by vm.hits.collectAsState()
    val progress by vm.progress.collectAsState()
    val err by vm.error.collectAsState()
    val saved by vm.saved.collectAsState()
    LazyColumn(Modifier.statusBarsPadding().imePadding(), contentPadding = ListPadding) {
        item {
            SectionCard("Screener") {
                Field(rule, { rule = it }, "Conditions", singleLine = false, hint = "Operands: CLOSE OPEN HIGH LOW VOLUME SMA(n) EMA(n) RSI(n) ATR ADX VWAP MACD MACD_SIGNAL MACD_HIST STOCH_K BB_UPPER BB_LOWER SUPERTREND OBV DONCHIAN_HIGH(n) AVG_VOLUME(n) CHANGE_PCT · ops < > <= >= CROSSES_ABOVE CROSSES_BELOW · AND / OR")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("nifty50", "nifty200", "nifty500").forEach { FilterChip(index == it, { index = it }, { Text(it.uppercase()) }); Spacer(Modifier.width(4.dp)) }
                    listOf(Timeframe.D1, Timeframe.W1, Timeframe.H1).forEach { FilterChip(tf == it, { tf = it }, { Text(it.label) }); Spacer(Modifier.width(4.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.run(rule, tf, index) }, enabled = progress == null) { Text("Run") }
                    Field(name, { name = it }, "Save as", modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { if (name.isNotBlank()) vm.save(name, rule, tf) }) { Text("Save") }
                }
                progress?.let { (d, t) -> LinearProgressIndicator({ d / t.toFloat().coerceAtLeast(1f) }, Modifier.fillMaxWidth()); Text("$d / $t scanned · ${hits.size} matches", color = Muted, fontSize = 12.sp) }
                ErrorText(err)
            }
        }
        if (saved.isNotEmpty()) item {
            SectionCard("Saved screens") {
                saved.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f).clickable { rule = s.rule; tf = Timeframe.fromLabel(s.timeframe) }, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { rule = s.rule; tf = Timeframe.fromLabel(s.timeframe); vm.run(s.rule, tf, index) }) { Text("Run") }
                        TextButton(onClick = { vm.delete(s) }) { Text("✕") }
                    }
                }
            }
        }
        items(hits, key = { it.symbol }) { h ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.instrument(Instrument.parse(h.symbol))) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(Modifier.weight(1f)) { Text(h.symbol, fontWeight = FontWeight.SemiBold); Text(h.sector, color = Muted, fontSize = 11.sp) }
                Column(horizontalAlignment = Alignment.End) { Text(fmtPrice(h.close)); Text(fmtPct(h.changePct), color = pnlColor(h.changePct), fontSize = 12.sp) }
            }
            HorizontalDivider()
        }
        item { Text("Universe from NSE index constituent files (current members – note survivorship bias for historical tests).", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
    }
}

// ---------------- No-code backtester ----------------

@HiltViewModel
class BacktestViewModel @Inject constructor(private val market: MarketRepository) : ViewModel() {
    val result = MutableStateFlow<BacktestResult?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun run(symbol: String, crypto: Boolean, entry: String, exit: String, long: Boolean, years: Int, risk: Double, atrMult: Double, targetR: Double?, segment: ChargeSegment) = viewModelScope.launch {
        busy.value = true; error.value = null; result.value = null
        runCatching {
            val i = Instrument.parse(symbol, crypto)
            val c = market.history(i, Timeframe.D1, years)
            require(c.size > 100) { "Not enough history (${c.size} bars)" }
            val bench = if (crypto) market.history(Instrument.parse("BTCUSD", true), Timeframe.D1, years) else market.history(Instrument.parse("NIFTY 50"), Timeframe.D1, years)
            Backtester.run(c, BacktestConfig(Rules.parse(entry), exit.takeIf { it.isNotBlank() }?.let { Rules.parse(it) }, long, riskPct = risk, atrStopMult = atrMult,
                targetR = targetR, segment = segment, barsPerYear = if (crypto) 365.0 else 252.0), bench)
        }.onSuccess { result.value = it }.onFailure { error.value = it.message }
        busy.value = false
    }
}

@Composable
fun BacktestScreen(vm: BacktestViewModel = hiltViewModel()) {
    var symbol by remember { mutableStateOf("RELIANCE") }
    var crypto by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("EMA(20) CROSSES_ABOVE EMA(50) AND ADX(14) > 20") }
    var exit by remember { mutableStateOf("EMA(20) CROSSES_BELOW EMA(50)") }
    var long by remember { mutableStateOf(true) }
    var years by remember { mutableStateOf("8") }
    var risk by remember { mutableStateOf("1") }
    var atr by remember { mutableStateOf("2") }
    var target by remember { mutableStateOf("3") }
    var seg by remember { mutableStateOf(ChargeSegment.EQ_DELIVERY) }
    val r by vm.result.collectAsState()
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()
    LazyColumn(Modifier.statusBarsPadding().imePadding(), contentPadding = ListPadding) {
        item {
            SectionCard("No-code backtester (daily bars, next-bar-open fills, real charges)") {
                Field(symbol, { symbol = it }, "Symbol")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(!crypto, { crypto = false; seg = ChargeSegment.EQ_DELIVERY }, { Text("India") }); Spacer(Modifier.width(4.dp))
                    FilterChip(crypto, { crypto = true; seg = ChargeSegment.CRYPTO_FUT }, { Text("Delta") }); Spacer(Modifier.width(12.dp))
                    FilterChip(long, { long = true }, { Text("Long") }); Spacer(Modifier.width(4.dp)); FilterChip(!long, { long = false }, { Text("Short") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ChargeSegment.entries.forEach { FilterChip(seg == it, { seg = it }, { Text(it.name, fontSize = 10.sp) }); Spacer(Modifier.width(4.dp)) }
                }
                Field(entry, { entry = it }, "Entry rule", singleLine = false)
                Field(exit, { exit = it }, "Exit rule (optional)", singleLine = false)
                Row {
                    Field(years, { years = it }, "Years", Modifier.weight(1f), number = true); Spacer(Modifier.width(6.dp))
                    Field(risk, { risk = it }, "Risk %", Modifier.weight(1f), number = true)
                }
                Row {
                    Field(atr, { atr = it }, "ATR stop ×", Modifier.weight(1f), number = true); Spacer(Modifier.width(6.dp))
                    Field(target, { target = it }, "Target R (blank=none)", Modifier.weight(1f), number = true)
                }
                Button(enabled = !busy, onClick = {
                    vm.run(symbol, crypto, entry, exit, long, years.toIntOrNull() ?: 5, risk.toDoubleOrNull() ?: 1.0, atr.toDoubleOrNull() ?: 2.0, target.toDoubleOrNull(), seg)
                }) { Text(if (busy) "Running…" else "Run backtest") }
                ErrorText(err)
            }
        }
        r?.let { res ->
            val m = res.metrics; val b = res.benchmarkMetrics
            item {
                SectionCard("Equity vs ${if (crypto) "BTC" else "NIFTY 50"} buy & hold") {
                    LineChart(listOf(res.equity.map { it.second } to Accent, res.benchmark.map { it.second } to Muted))
                    Text("— strategy   — benchmark (price index; TRI adds ~1.2%/yr dividends)", color = Muted, fontSize = 11.sp)
                    Text("Drawdown", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    LineChart(listOf(Metrics.drawdownSeries(res.equity.map { it.second }) to Bear), fillBelowZero = true)
                }
            }
            item {
                SectionCard("Results") {
                    KV("Total return", "${fmtPct(m.totalReturnPct)}  (B&H ${fmtPct(b?.totalReturnPct)})", pnlColor(m.totalReturnPct))
                    KV("CAGR", "${fmtPct(m.cagrPct)}  (B&H ${fmtPct(b?.cagrPct)})")
                    KV("Max drawdown", "${"%.1f".format(m.maxDrawdownPct)}%  (B&H ${"%.1f".format(b?.maxDrawdownPct ?: 0.0)}%)")
                    KV("Sharpe / Sortino", "%.2f / %.2f".format(m.sharpe, m.sortino)); KV("Calmar", "%.2f".format(m.calmar))
                    KV("Alpha (ann.) / Beta", "%.2f%% / %.2f".format(m.alphaPct, m.beta)); KV("Information ratio", "%.2f".format(m.informationRatio))
                    KV("Trades", "${m.trades}"); KV("Win rate", "%.1f%%".format(m.winRatePct)); KV("Profit factor", "%.2f".format(m.profitFactor))
                    KV("Expectancy / trade", fmtMoney(m.expectancy)); KV("Total charges paid", fmtMoney(m.totalCharges))
                    if (m.trades < 200) Text("Only ${m.trades} trades – below the 200-trade minimum for statistical confidence. Use the engine's walk-forward + PBO tests before trusting this.", color = Bear, fontSize = 12.sp)
                }
            }
            item {
                SectionCard("Monthly returns") {
                    Metrics.monthly(res.equity).entries.groupBy { it.key.first }.toSortedMap().forEach { (y, ms) ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text("$y ", fontSize = 11.sp, modifier = Modifier.width(40.dp))
                            ms.sortedBy { it.key.second }.forEach { e -> Text("%+.1f ".format(e.value), color = pnlColor(e.value), fontSize = 11.sp, modifier = Modifier.width(42.dp)) }
                        }
                    }
                }
            }
            item {
                SectionCard("Trades (last 30)") {
                    res.trades.takeLast(30).reversed().forEach { t ->
                        Row { Text("${fmtTime(t.entryTime, "dd MMM yy")} → ${fmtTime(t.exitTime, "dd MMM yy")}", fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(t.reason, color = Muted, fontSize = 11.sp); Text("  " + fmtMoney(t.pnl), color = pnlColor(t.pnl), fontSize = 11.sp) }
                    }
                }
            }
        }
        item { Disclaimer() }
    }
}
