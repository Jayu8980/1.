package com.tradelens.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AlertEntity
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.EngineSignal
import com.tradelens.app.data.llm.AiAnalysis
import com.tradelens.app.data.llm.LlmClient
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.ml.MlScore
import com.tradelens.app.data.ml.OnnxScorer
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.Confluence
import com.tradelens.app.domain.ConfluenceResult
import com.tradelens.app.domain.Indicators
import com.tradelens.app.domain.Indicators.lastValid
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.PatternHit
import com.tradelens.app.domain.Patterns
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.domain.Timeframe
import com.tradelens.app.domain.Zone
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.chart.CandleChart
import com.tradelens.app.ui.chart.DrawTool
import com.tradelens.app.ui.chart.Drawing
import com.tradelens.app.ui.chart.Overlay
import com.tradelens.app.ui.chart.SubPane
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.Loading
import com.tradelens.app.ui.components.Pill
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.SourceChip
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Warn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

data class InstrumentState(
    val tf: Timeframe = Timeframe.D1,
    val candles: List<Candle> = emptyList(),
    val badge: SourceBadge? = null,
    val quote: Quote? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val patterns: List<PatternHit> = emptyList(),
    val zones: List<Zone> = emptyList(),
    val pivots: Indicators.Pivots? = null,
    val confluence: ConfluenceResult? = null,
    val ml: MlScore? = null,
    val ai: AiAnalysis? = null,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val signals: List<EngineSignal> = emptyList(),
    val regime: String? = null,
)

/** Builds the compact JSON summary sent to the LLM (no raw candles beyond the last 20). */
object AnalysisSummary {
    fun build(i: Instrument, s: InstrumentState): JsonObject {
        val c = s.candles
        val cl = Indicators.closes(c)
        return buildJsonObject {
            put("symbol", i.symbol); put("exchange", i.exchange.name); put("timeframe", s.tf.label)
            put("dataSource", s.badge?.source?.label ?: "unknown"); put("realtime", s.badge?.realtime ?: false)
            s.quote?.let { put("ltp", it.ltp); put("changePct", it.changePct) }
            putJsonArray("last20Bars") { c.takeLast(20).forEach { b -> add(buildJsonObject { put("t", b.time); put("o", b.open); put("h", b.high); put("l", b.low); put("c", b.close); put("v", b.volume) }) } }
            if (c.size > 30) putJsonObject("indicators") {
                put("ema20", Indicators.ema(cl, 20).lastValid()); put("ema50", Indicators.ema(cl, 50).lastValid()); put("ema200", Indicators.ema(cl, 200).lastValid())
                put("rsi14", Indicators.rsi(cl, 14).lastValid())
                val m = Indicators.macd(cl); put("macd", m.macd.lastValid()); put("macdSignal", m.signal.lastValid()); put("macdHist", m.hist.lastValid())
                val bb = Indicators.bollinger(cl); put("bbUpper", bb.upper.lastValid()); put("bbLower", bb.lower.lastValid())
                put("atr14", Indicators.atr(c, 14).lastValid())
                val a = Indicators.adx(c); put("adx14", a.adx.lastValid()); put("plusDI", a.plusDi.lastValid()); put("minusDI", a.minusDi.lastValid())
                val st = Indicators.supertrend(c); put("supertrend", st.line.lastValid()); put("supertrendUp", st.upTrend.last())
                put("vwap", Indicators.vwap(c).lastValid())
                val sk = Indicators.stochastic(c); put("stochK", sk.k.lastValid()); put("stochD", sk.d.lastValid())
            }
            putJsonArray("patterns") { s.patterns.takeLast(10).forEach { p -> add(buildJsonObject { put("name", p.name); put("bias", p.bias.name); put("barsAgo", c.size - 1 - p.index); put("detail", p.detail) }) } }
            putJsonArray("zones") { s.zones.forEach { z -> add(buildJsonObject { put("low", z.low); put("high", z.high); put("touches", z.touches); put("type", if (z.isSupport) "support" else "resistance") }) } }
            s.pivots?.let { p -> putJsonObject("pivots") { put("P", p.p); put("R1", p.r1); put("R2", p.r2); put("S1", p.s1); put("S2", p.s2) } }
            s.confluence?.let { cf ->
                putJsonObject("confluence") {
                    put("score", cf.score); put("label", cf.label)
                    cf.perTimeframe.forEach { t -> put(t.timeframe.label, t.score) }
                }
            }
            s.regime?.let { put("marketRegime", it) }
            s.ml?.let { put("mlProbUp", it.probUp) }
            putJsonArray("strategySignals") { s.signals.forEach { g -> add(buildJsonObject { put("strategy", g.strategy); put("direction", g.direction); put("confidence", g.confidence) }) } }
        }
    }
}

@HiltViewModel
class InstrumentViewModel @Inject constructor(
    private val market: MarketRepository, private val llm: LlmClient, private val engine: EngineClient, private val scorer: OnnxScorer, private val db: AppDatabase,
) : ViewModel() {
    val state = MutableStateFlow(InstrumentState())
    lateinit var instrument: Instrument

    fun init(i: Instrument) {
        if (::instrument.isInitialized && instrument.key == i.key) return
        instrument = i; load(Timeframe.D1)
        viewModelScope.launch { runCatching { market.quote(i) }.onSuccess { state.value = state.value.copy(quote = it) } }
        viewModelScope.launch {
            if (engine.isConfigured()) runCatching {
                val sig = engine.api.signals().filter { it.symbol.equals(i.symbol, true) }
                val reg = engine.api.status().regime
                state.value = state.value.copy(signals = sig, regime = reg)
            }
        }
    }

    suspend fun stream() = market.stream(listOf(instrument)).collect { q -> state.value = state.value.copy(quote = q) }

    fun load(tf: Timeframe) = viewModelScope.launch {
        state.value = state.value.copy(tf = tf, loading = true, error = null, ai = null)
        val r = market.candles(instrument, tf, if (tf == Timeframe.D1 || tf == Timeframe.W1) 600 else 500)
        val c = r.candles
        val zones = Patterns.supportResistance(c)
        val pats = (Patterns.candlestick(c, 40) + Patterns.breakouts(c, zones) + Patterns.chartPatterns(c) + Patterns.divergences(c)).sortedBy { it.index }
        val prev = c.getOrNull(c.size - 2)
        state.value = state.value.copy(
            candles = c, badge = r.badge, loading = false, error = r.error.takeIf { c.isEmpty() } ?: r.error?.let { "Showing ${r.badge.source.label}: $it" },
            zones = zones, patterns = pats, pivots = prev?.let { Indicators.pivots(it.high, it.low, it.close) },
            ml = runCatching { scorer.score(c) }.getOrNull(),
        )
        confluence()
    }

    private fun confluence() = viewModelScope.launch {
        val tfs = listOf(Timeframe.M15, Timeframe.H1, Timeframe.D1)
        val per = tfs.map { tf -> async { Confluence.scoreTimeframe(tf, if (tf == state.value.tf) state.value.candles else market.candles(instrument, tf, 300).candles) } }.map { it.await() }
        state.value = state.value.copy(confluence = Confluence.combine(per))
    }

    fun analyse() = viewModelScope.launch {
        state.value = state.value.copy(aiLoading = true, aiError = null)
        runCatching { llm.analyse(AnalysisSummary.build(instrument, state.value).toString()) }
            .onSuccess { state.value = state.value.copy(ai = it, aiLoading = false) }
            .onFailure { state.value = state.value.copy(aiError = it.message, aiLoading = false) }
    }

    fun addAlert(kind: String, value: Double?, rule: String?) = viewModelScope.launch {
        db.alerts().save(AlertEntity(instrumentJson = AppJson.encodeToString(Instrument.serializer(), instrument), kind = kind, value = value, rule = rule, timeframe = state.value.tf.label))
    }
}

private enum class SubKind { NONE, RSI, MACD, STOCH, ADX, OBV, ATR }

@Composable
fun InstrumentScreen(instrument: Instrument, nav: NavController, vm: InstrumentViewModel = hiltViewModel()) {
    LaunchedEffect(instrument.key) { vm.init(instrument) }
    LaunchedEffect(instrument.key) { vm.stream() }
    val s by vm.state.collectAsState()
    var overlays by remember { mutableStateOf(setOf("EMA20", "EMA50")) }
    var sub by remember { mutableStateOf(SubKind.RSI) }
    var tool by remember { mutableStateOf(DrawTool.NONE) }
    val drawings = remember(instrument.key, s.tf) { mutableStateListOf<Drawing>() }
    var ticket by remember { mutableStateOf<Triple<com.tradelens.app.domain.Side, Double?, Double?>?>(null) }
    var alertDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val c = s.candles
    val cl = remember(c) { Indicators.closes(c) }
    val overlayList = remember(c, overlays) {
        buildList {
            if ("EMA20" in overlays) add(Overlay(Indicators.ema(cl, 20), Color(0xFF7AA2F7)))
            if ("EMA50" in overlays) add(Overlay(Indicators.ema(cl, 50), Color(0xFFFF9E64)))
            if ("EMA200" in overlays) add(Overlay(Indicators.ema(cl, 200), Color(0xFFE0AF68)))
            if ("SMA20" in overlays) add(Overlay(Indicators.sma(cl, 20), Color(0xFF9ECE6A)))
            if ("BB" in overlays) Indicators.bollinger(cl).let { add(Overlay(it.upper, Color(0xFF565F89), true)); add(Overlay(it.middle, Color(0xFF565F89))); add(Overlay(it.lower, Color(0xFF565F89), true)) }
            if ("VWAP" in overlays) add(Overlay(Indicators.vwap(c, if (instrument.isCrypto) 0 else 19800), Color(0xFFBB9AF7)))
            if ("Supertrend" in overlays) add(Overlay(Indicators.supertrend(c).line, Color(0xFF2EC4B6), true))
            if ("Ichimoku" in overlays) Indicators.ichimoku(c).let { add(Overlay(it.tenkan, Color(0xFF7DCFFF))); add(Overlay(it.kijun, Color(0xFFF7768E))); add(Overlay(it.spanA, Bull.copy(alpha = 0.6f), true)); add(Overlay(it.spanB, Bear.copy(alpha = 0.6f), true)) }
        }
    }
    val subPane = remember(c, sub) {
        when (sub) {
            SubKind.NONE -> null
            SubKind.RSI -> SubPane.Oscillator("RSI(14)", Indicators.rsi(cl), listOf(30.0, 50.0, 70.0), 0.0, 100.0)
            SubKind.MACD -> Indicators.macd(cl).let { SubPane.Histogram("MACD(12,26,9)", it.hist, it.macd, it.signal) }
            SubKind.STOCH -> SubPane.Oscillator("Stoch %K", Indicators.stochastic(c).k, listOf(20.0, 80.0), 0.0, 100.0)
            SubKind.ADX -> SubPane.Oscillator("ADX(14)", Indicators.adx(c).adx, listOf(20.0, 25.0, 40.0), 0.0, 100.0)
            SubKind.OBV -> SubPane.Oscillator("OBV", Indicators.obv(c), emptyList())
            SubKind.ATR -> SubPane.Oscillator("ATR(14)", Indicators.atr(c), emptyList())
        }
    }
    val ltp = s.quote?.ltp ?: c.lastOrNull()?.close ?: 0.0

    ticket?.let { (side, stop, target) ->
        OrderTicket(instrument, ltp, side, stop, target, reason = "Manual from chart", onDismiss = { ticket = null }, onResult = { toast = it })
    }
    toast?.let { AlertDialog(onDismissRequest = { toast = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { toast = null }) { Text("OK") } }) }
    if (alertDialog) AlertDialogEditor(ltp, onDismiss = { alertDialog = false }) { k, v, r -> vm.addAlert(k, v, r); alertDialog = false; toast = "Alert saved" }

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(instrument.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SourceChip(s.quote?.badge ?: s.badge)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtPrice(ltp), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(8.dp))
                    s.quote?.let { Text("${fmtPrice(it.change)} (${fmtPct(it.changePct)})", color = pnlColor(it.change)) }
                }
                Text("${instrument.exchange} · ${instrument.segment}${if (instrument.lotSize > 1) " · lot ${instrument.lotSize}" else ""}", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Timeframe.entries.forEach { tf -> FilterChip(s.tf == tf, { vm.load(tf) }, { Text(tf.label) }); Spacer(Modifier.width(4.dp)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                listOf("EMA20", "EMA50", "EMA200", "SMA20", "BB", "VWAP", "Supertrend", "Ichimoku").forEach { o ->
                    FilterChip(o in overlays, { overlays = if (o in overlays) overlays - o else overlays + o }, { Text(o, fontSize = 11.sp) }); Spacer(Modifier.width(4.dp))
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                SubKind.entries.forEach { k -> FilterChip(sub == k, { sub = k }, { Text(k.name, fontSize = 11.sp) }); Spacer(Modifier.width(4.dp)) }
                Spacer(Modifier.width(12.dp))
                DrawTool.entries.forEach { t -> FilterChip(tool == t, { tool = t }, { Text(if (t == DrawTool.NONE) "Pan" else t.name, fontSize = 11.sp) }); Spacer(Modifier.width(4.dp)) }
                TextButton(onClick = { drawings.clear() }) { Text("Clear", fontSize = 11.sp) }
            }
        }
        item {
            when {
                s.loading -> Loading()
                c.isEmpty() -> ErrorText(s.error ?: "No data")
                else -> CandleChart(c, overlayList, s.zones, subPane, tool, drawings, { drawings.add(it) })
            }
            ErrorText(s.error.takeIf { c.isNotEmpty() })
            Text("Pinch to zoom · drag to pan · long-press for crosshair · pick a drawing tool and tap points", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Button(onClick = { vm.analyse() }) { Text("Analyse (AI)") }; Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { nav.navigate(Routes.chat(instrument)) }) { Text("Chat") }; Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { ticket = Triple(com.tradelens.app.domain.Side.BUY, null, null) }) { Text("Trade") }; Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { alertDialog = true }) { Text("Alert") }; Spacer(Modifier.width(6.dp))
                if (instrument.segment == com.tradelens.app.domain.Segment.INDEX || instrument.isCrypto || instrument.segment == com.tradelens.app.domain.Segment.EQUITY)
                    OutlinedButton(onClick = { nav.navigate(Routes.options(instrument)) }) { Text("Options") }
            }
        }
        if (s.aiLoading || s.ai != null || s.aiError != null) item {
            SectionCard("AI analysis") {
                if (s.aiLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.aiError?.let { Text(it, color = Bear) }
                s.ai?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(a.trendBias.uppercase(), when (a.trendBias) { "bullish" -> Bull; "bearish" -> Bear; else -> Muted })
                        Spacer(Modifier.width(8.dp)); Text("Confidence ${a.confidence}%", color = Muted, fontSize = 12.sp)
                    }
                    if (a.summary.isNotBlank()) Text(a.summary, modifier = Modifier.padding(vertical = 4.dp))
                    KV("Support", a.keyLevels.support.joinToString { fmtPrice(it) }); KV("Resistance", a.keyLevels.resistance.joinToString { fmtPrice(it) })
                    Text("Bullish case", fontWeight = FontWeight.SemiBold, color = Bull); Text(a.bullishCase, fontSize = 13.sp)
                    Text("Bearish case", fontWeight = FontWeight.SemiBold, color = Bear); Text(a.bearishCase, fontSize = 13.sp)
                    if (a.setup.direction != "none") {
                        Text("Possible setup (${a.setup.direction})", fontWeight = FontWeight.SemiBold)
                        KV("Entry", fmtPrice(a.setup.entry)); KV("Stop", fmtPrice(a.setup.stopLoss)); KV("Targets", a.setup.targets.joinToString { fmtPrice(it) })
                        KV("R:R", a.setup.riskReward?.let { "%.2f".format(it) } ?: "—")
                        if (a.setup.trigger.isNotBlank()) Text("Trigger: ${a.setup.trigger}", fontSize = 12.sp)
                        TextButton(onClick = { ticket = Triple(if (a.setup.direction == "long") com.tradelens.app.domain.Side.BUY else com.tradelens.app.domain.Side.SELL, a.setup.stopLoss, a.setup.targets.firstOrNull()) }) { Text("Open order ticket (you decide)") }
                    }
                    if (a.risks.isNotEmpty()) { Text("Risks", fontWeight = FontWeight.SemiBold, color = Warn); a.risks.forEach { Text("• $it", fontSize = 13.sp) } }
                    if (a.uncertainty.isNotBlank()) Text("Uncertainty: ${a.uncertainty}", color = Muted, fontSize = 12.sp)
                    Disclaimer()
                }
            }
        }
        s.confluence?.let { cf ->
            item {
                SectionCard("Confluence score", trailing = { Pill("${cf.score}/100 · ${cf.label}", if (cf.score >= 60) Bull else if (cf.score <= 40) Bear else Muted) }) {
                    LinearProgressIndicator({ cf.score / 100f }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    cf.perTimeframe.forEach { t ->
                        Text("${t.timeframe.label}: ${t.score}", fontWeight = FontWeight.SemiBold)
                        t.factors.forEach { f -> Text("  ${if (f.score > 0.1) "▲" else if (f.score < -0.1) "▼" else "•"} ${f.name}: ${f.reason}", fontSize = 12.sp, color = pnlColor(f.score)) }
                    }
                }
            }
        }
        s.ml?.let { m -> item { SectionCard("On-device ML score") { KV("Model", m.model); KV("P(up next N bars)", "%.1f%%".format(m.probUp * 100)); KV("Signal (threshold ${"%.2f".format(m.threshold)})", m.signal) } } }
        if (s.signals.isNotEmpty() || s.regime != null) item {
            SectionCard("Engine") {
                s.regime?.let { KV("Market regime", it) }
                s.signals.forEach { g -> KV(g.strategy, "${g.direction} · conf ${"%.0f".format(g.confidence * 100)}% · ${g.status}", if (g.direction == "LONG") Bull else if (g.direction == "SHORT") Bear else Muted) }
            }
        }
        item {
            SectionCard("Patterns detected") {
                if (s.patterns.isEmpty()) Text("None in the recent bars", color = Muted)
                s.patterns.takeLast(12).reversed().forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(p.name, color = pnlColor(when (p.bias) { com.tradelens.app.domain.Bias.BULLISH -> 1.0; com.tradelens.app.domain.Bias.BEARISH -> -1.0; else -> 0.0 }), modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text("${c.size - 1 - p.index} bars ago", color = Muted, fontSize = 12.sp)
                    }
                    if (p.detail.isNotBlank()) Text(p.detail, color = Muted, fontSize = 11.sp)
                }
            }
        }
        item {
            SectionCard("Levels") {
                s.zones.forEach { z -> KV(if (z.isSupport) "Support zone" else "Resistance zone", "${fmtPrice(z.low)} – ${fmtPrice(z.high)} (${z.touches} touches)", if (z.isSupport) Bull else Bear) }
                s.pivots?.let { p -> KV("Pivot P", fmtPrice(p.p)); KV("R1 / R2 / R3", "${fmtPrice(p.r1)} / ${fmtPrice(p.r2)} / ${fmtPrice(p.r3)}"); KV("S1 / S2 / S3", "${fmtPrice(p.s1)} / ${fmtPrice(p.s2)} / ${fmtPrice(p.s3)}") }
            }
        }
        item { Disclaimer() }
    }
}

@Composable
fun AlertDialogEditor(ltp: Double, onDismiss: () -> Unit, onSave: (String, Double?, String?) -> Unit) {
    var kind by remember { mutableStateOf("PRICE_ABOVE") }
    var value by remember { mutableStateOf("%.2f".format(ltp)) }
    var rule by remember { mutableStateOf("RSI(14) < 30") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New alert") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("PRICE_ABOVE" to "Price ≥", "PRICE_BELOW" to "Price ≤", "RULE" to "Indicator rule", "PATTERN" to "Pattern").forEach { (k, l) ->
                        FilterChip(kind == k, { kind = k; if (k == "PATTERN") rule = "" }, { Text(l) }); Spacer(Modifier.width(4.dp))
                    }
                }
                when (kind) {
                    "PRICE_ABOVE", "PRICE_BELOW" -> Field(value, { value = it }, "Price", number = true)
                    "RULE" -> Field(rule, { rule = it }, "Rule", hint = "e.g. RSI(14) < 30 AND CLOSE > EMA(200), EMA(9) CROSSES_ABOVE EMA(21)")
                    else -> Field(rule, { rule = it }, "Pattern name contains (blank = any)", hint = "e.g. Engulfing, Breakout, Double Bottom")
                }
                Text("Checked every 15 min in the background (Android minimum), 24/7 for crypto.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (kind == "RULE" && runCatching { com.tradelens.app.domain.Rules.parse(rule) }.isFailure) return@TextButton
                onSave(kind, value.toDoubleOrNull().takeIf { kind.startsWith("PRICE") }, rule.takeIf { !kind.startsWith("PRICE") })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
