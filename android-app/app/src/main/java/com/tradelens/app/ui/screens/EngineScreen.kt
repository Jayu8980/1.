package com.tradelens.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import com.tradelens.app.TradeLensApp
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.EnginePosition
import com.tradelens.app.data.engine.EngineSignal
import com.tradelens.app.data.engine.EngineStatus
import com.tradelens.app.data.engine.RiskSnapshot
import com.tradelens.app.data.engine.StrategyInfo
import com.tradelens.app.data.engine.ToggleBody
import com.tradelens.app.data.ml.OnnxScorer
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.s
import com.tradelens.app.domain.RiskLimits
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.Pill
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Warn
import com.tradelens.app.work.Notifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EngineUi(
    val status: EngineStatus? = null, val signals: List<EngineSignal> = emptyList(), val strategies: List<StrategyInfo> = emptyList(),
    val risk: RiskSnapshot? = null, val positions: List<EnginePosition> = emptyList(), val error: String? = null, val connected: Boolean = false,
)

@HiltViewModel
class EngineViewModel @Inject constructor(
    val engine: EngineClient, private val db: AppDatabase, private val notifier: Notifier, private val scorer: OnnxScorer,
) : ViewModel() {
    val ui = MutableStateFlow(EngineUi())
    val msg = MutableStateFlow<String?>(null)

    fun refresh() = viewModelScope.launch {
        if (!engine.isConfigured()) { ui.value = EngineUi(error = "Set the engine URL and token in Settings → Engine."); return@launch }
        runCatching {
            val a = engine.api
            EngineUi(a.status(), a.signals(), a.strategies(), a.risk(), a.positions(), null, true)
        }.onSuccess { ui.value = it }.onFailure { ui.value = ui.value.copy(error = "Engine unreachable: ${it.message}", connected = false) }
    }

    /** Live events: every fill and risk breach becomes a push notification. */
    suspend fun listen() {
        if (!engine.isConfigured()) return
        engine.events().collect { e ->
            val d = e.data.obj()
            when (e.type) {
                "fill" -> notifier.notify(TradeLensApp.CH_FILLS, "Fill: ${d.s("symbol")}", "${d.s("side")} ${d.s("qty")} @ ${d.s("price")} (${d.s("strategy")}, ${d.s("venue")})")
                "risk" -> notifier.notify(TradeLensApp.CH_RISK, "Risk: ${d.s("kind")}", d.s("message") ?: "")
                "signal" -> notifier.notify(TradeLensApp.CH_FILLS, "New signal", "${d.s("strategy")}: ${d.s("direction")} ${d.s("symbol")}")
            }
            if (e.type in setOf("fill", "risk", "signal", "status")) refresh()
        }
    }

    private fun act(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { msg.value = label; db.audit().log(AuditEntity(category = "APPROVAL", message = "Engine: $label")) }
            .onFailure { msg.value = "$label failed: ${it.message}" }
        refresh()
    }

    fun approve(s: EngineSignal) = act("Approved ${s.strategy} ${s.direction} ${s.symbol}") { engine.api.approve(s.id) }
    fun reject(s: EngineSignal) = act("Rejected ${s.strategy} ${s.symbol}") { engine.api.reject(s.id) }
    fun enable(n: String, on: Boolean) = act("${if (on) "Enabled" else "Disabled"} $n") { engine.api.setEnabled(n, ToggleBody(on)) }
    fun auto(n: String, on: Boolean) = act("Auto-execution ${if (on) "ON" else "OFF"} for $n") { engine.api.setAuto(n, ToggleBody(on)) }
    fun kill(n: String, on: Boolean) = act("Kill switch ${if (on) "ON" else "OFF"} for $n") { engine.api.kill(n, ToggleBody(on)) }
    fun restart() = act("Engine restarted after circuit breaker") { engine.api.restart() }
    fun flatten() = act("Engine flatten requested") { engine.api.flatten() }
    fun setLimits(l: RiskLimits) = act("Risk limits updated") { engine.api.setLimits(l) }
    fun downloadModel() = act("ML model downloaded for on-device scoring") { scorer.download() }
}

@Composable
fun EngineScreen(nav: NavController, vm: EngineViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val msg by vm.msg.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var approve by remember { mutableStateOf<EngineSignal?>(null) }
    var autoConfirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.listen() }

    approve?.let { s ->
        AlertDialog(
            onDismissRequest = { approve = null }, title = { Text("Approve ${s.direction} ${s.symbol}?") },
            text = {
                Column {
                    KV("Strategy", s.strategy); KV("Venue", s.venue.uppercase()); KV("Size", "${s.qty}"); KV("Entry", fmtPrice(s.entry))
                    KV("Stop", fmtPrice(s.stop)); KV("Target", fmtPrice(s.target)); KV("Leverage", "${s.leverage}x")
                    KV("Margin required", fmtMoney(s.marginRequired)); KV("Est. charges", fmtMoney(s.charges))
                    s.liquidationPrice?.let { KV("Est. liquidation", fmtPrice(it), Bear) }
                    KV("Confidence", "%.0f%%".format(s.confidence * 100)); Text(s.reason, fontSize = 12.sp, color = Muted)
                    if (s.venue != "paper") Text("This is a LIVE order.", color = Warn, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { TextButton(onClick = { vm.approve(s); approve = null }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { approve = null }) { Text("Cancel") } },
        )
    }
    autoConfirm?.let { n ->
        AlertDialog(onDismissRequest = { autoConfirm = null }, title = { Text("Enable auto-execution for $n?") },
            text = { Text("Signals from this strategy will be executed without asking you each time (risk limits and kill switches still apply).") },
            confirmButton = { TextButton(onClick = { vm.auto(n, true); autoConfirm = null }) { Text("Enable") } },
            dismissButton = { TextButton(onClick = { autoConfirm = null }) { Text("Cancel") } })
    }

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Alpha Engine", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill(if (ui.connected) "CONNECTED" else "OFFLINE", if (ui.connected) Bull else Bear)
                TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
            }
        }
        item { ErrorText(ui.error); msg?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }
        ui.status?.let { s ->
            item {
                SectionCard("Status") {
                    KV("Mode", s.mode.uppercase()); KV("Regime", s.regime)
                    if (s.regimeProbs.isNotEmpty()) Text(s.regimeProbs.entries.joinToString(" · ") { "${it.key} ${"%.0f".format(it.value * 100)}%" }, color = Muted, fontSize = 11.sp)
                    KV("Equity", fmtMoney(s.equity)); KV("Day P&L", "${fmtMoney(s.dayPnl)} (${fmtPct(s.dayPnlPct)})", pnlColor(s.dayPnl))
                    KV("Drawdown", fmtPct(-s.drawdownPct), pnlColor(-s.drawdownPct)); KV("Orders today", "${s.ordersToday}")
                    if (s.circuitBroken) { Text("Max-drawdown circuit breaker tripped – all strategies halted.", color = Bear); Button(onClick = vm::restart) { Text("Manual restart") } }
                    else if (s.halted) Text("Daily loss limit hit – flattened and halted for today.", color = Bear)
                    Row { OutlinedButton(onClick = vm::flatten) { Text("Flatten all") }; TextButton(onClick = { vm.downloadModel() }) { Text("Get ML model") }
                        TextButton(onClick = { nav.navigate(Routes.REPORTS) }) { Text("Reports") } }
                }
            }
        }
        item { ScrollableTabRow(tab, edgePadding = 8.dp) { listOf("Signals", "Strategies", "Risk", "Positions").forEachIndexed { k, l -> Tab(tab == k, { tab = k }, text = { Text(l) }) } } }
        when (tab) {
            0 -> {
                if (ui.signals.isEmpty()) item { Text("No signals", color = Muted, modifier = Modifier.padding(16.dp)) }
                items(ui.signals, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(s.direction, if (s.direction == "LONG") Bull else if (s.direction == "SHORT") Bear else Muted)
                            Text("  ${s.symbol}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(s.status, color = Muted, fontSize = 12.sp)
                        }
                        Text("${s.strategy} · strength ${"%.2f".format(s.strength)} · conf ${"%.0f".format(s.confidence * 100)}% · ${s.horizon} · ${fmtTime(s.createdAt)}", color = Muted, fontSize = 12.sp)
                        Text("Entry ${fmtPrice(s.entry)} · SL ${fmtPrice(s.stop)} · TG ${fmtPrice(s.target)} · qty ${s.qty} · ${s.venue}", fontSize = 12.sp)
                        if (s.reason.isNotBlank()) Text(s.reason, color = Muted, fontSize = 11.sp)
                        if (s.status == "PENDING") Row { TextButton(onClick = { approve = s }) { Text("Approve…") }; TextButton(onClick = { vm.reject(s) }) { Text("Reject") } }
                    }
                    HorizontalDivider()
                }
                item { Disclaimer() }
            }
            1 -> items(ui.strategies, key = { it.name }) { st ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill(st.stage, when (st.stage) { "LIVE", "LIVE_SMALL" -> Bull; "DEMOTED", "REJECTED" -> Bear; else -> Warn })
                    }
                    Text(st.description, color = Muted, fontSize = 12.sp)
                    Text("OOS Sharpe ${"%.2f".format(st.oosSharpe)} · DSR ${st.deflatedSharpe?.let { "%.2f".format(it) } ?: "—"} · PBO ${st.pbo?.let { "%.2f".format(it) } ?: "—"} · weight ${"%.0f".format(st.weight * 100)}% · trades ${st.trades}" +
                        (st.drift?.let { " · drift ${"%.1f".format(it)}σ" } ?: "") + if (!st.regimeActive) " · off in current regime" else "", fontSize = 12.sp)
                    if (st.notes.isNotBlank()) Text(st.notes, color = Muted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On", fontSize = 12.sp); Switch(st.enabled, { vm.enable(st.name, it) })
                        Text("  Auto", fontSize = 12.sp); Switch(st.auto, { if (it) autoConfirm = st.name else vm.auto(st.name, false) })
                        Text("  Kill", fontSize = 12.sp, color = Bear); Switch(st.killed, { vm.kill(st.name, it) })
                    }
                }
                HorizontalDivider()
            }
            2 -> item { RiskPanel(ui.risk, vm::setLimits) }
            else -> items(ui.positions) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) { Text("${p.symbol} ${p.qty}", fontWeight = FontWeight.SemiBold); Text("${p.strategy} · ${p.venue} · avg ${fmtPrice(p.avgPrice)} · ltp ${fmtPrice(p.ltp)}", color = Muted, fontSize = 12.sp) }
                    Text(fmtMoney(p.pnl), color = pnlColor(p.pnl))
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RiskPanel(r: RiskSnapshot?, onSave: (RiskLimits) -> Unit) {
    if (r == null) { Text("No risk data", color = Muted, modifier = Modifier.padding(16.dp)); return }
    var daily by remember(r) { mutableStateOf(r.limits.dailyLossPct.toString()) }
    var dd by remember(r) { mutableStateOf(r.limits.maxDrawdownPct.toString()) }
    var orders by remember(r) { mutableStateOf(r.limits.maxOrdersPerDay.toString()) }
    var pos by remember(r) { mutableStateOf(r.limits.maxPositionPct.toString()) }
    var sector by remember(r) { mutableStateOf(r.limits.maxSectorPct.toString()) }
    var margin by remember(r) { mutableStateOf(r.limits.maxMarginUtilPct.toString()) }
    SectionCard("Live risk") {
        KV("Gross / net exposure", "${"%.1f".format(r.grossExposurePct)}% / ${"%.1f".format(r.netExposurePct)}%")
        KV("Portfolio vol (ann.)", "${"%.1f".format(r.portfolioVolPct)}%")
        KV("Margin utilisation", "${"%.1f".format(r.marginUtilPct)}%", if (r.marginUtilPct > r.limits.maxMarginUtilPct) Bear else Bull)
        KV("Greeks Δ/Γ/Θ/V", "%.1f / %.3f / %.0f / %.0f".format(r.greeks.delta, r.greeks.gamma, r.greeks.theta, r.greeks.vega))
        KV("Avg slippage vs expected", "${"%.1f".format(r.avgSlippageBps)} bps"); KV("Latency", "${"%.0f".format(r.latencyMs)} ms"); KV("API errors today", "${r.apiErrors}")
        r.sectorExposure.forEach { (k, v) -> KV("  $k", "${"%.1f".format(v)}%", if (v > r.limits.maxSectorPct) Bear else Muted) }
    }
    SectionCard("Risk limits (engine)") {
        Field(daily, { daily = it }, "Daily loss limit % (auto-flatten & halt)", number = true)
        Field(dd, { dd = it }, "Max drawdown % (circuit breaker)", number = true)
        Field(orders, { orders = it }, "Max orders per day", number = true)
        Field(pos, { pos = it }, "Max % per stock", number = true)
        Field(sector, { sector = it }, "Max % per sector", number = true)
        Field(margin, { margin = it }, "Max F&O margin utilisation %", number = true)
        Button(onClick = {
            onSave(r.limits.copy(dailyLossPct = daily.toDoubleOrNull() ?: r.limits.dailyLossPct, maxDrawdownPct = dd.toDoubleOrNull() ?: r.limits.maxDrawdownPct,
                maxOrdersPerDay = orders.toIntOrNull() ?: r.limits.maxOrdersPerDay, maxPositionPct = pos.toDoubleOrNull() ?: r.limits.maxPositionPct,
                maxSectorPct = sector.toDoubleOrNull() ?: r.limits.maxSectorPct, maxMarginUtilPct = margin.toDoubleOrNull() ?: r.limits.maxMarginUtilPct))
        }) { Text("Save limits") }
        Text("Only you can change limits; the AI never does.", color = Muted, fontSize = 11.sp)
    }
}
