package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.Metrics
import com.aitrader.data.local.SettingsStore
import com.aitrader.data.model.Dashboard
import com.aitrader.data.model.Position
import com.aitrader.data.model.TradingMode
import com.aitrader.data.repo.MarketFeed
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val LIVE_PHRASE = "I UNDERSTAND THIS USES REAL MONEY"

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: TradingRepository, settings: SettingsStore, val feed: MarketFeed) :
    ResourceViewModel<Dashboard>(pollMs = 3_000) {
    val demo = settings.settings.map { it.demoMode }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val positions = MutableStateFlow<List<Position>>(emptyList())
    override suspend fun fetch() = repo.dashboard().also {
        runCatching { repo.positions() }.onSuccess { p -> positions.value = p.data.filter { it.qty != 0 } }
    }

    fun switchMode(mode: TradingMode, phrase: String) = viewModelScope.launch {
        val r = repo.setMode(mode, phrase); say(r.message.ifBlank { if (r.ok) "Mode: $mode" else "Denied" }); refresh()
    }
    val venue = repo.venue
    fun setVenue(v: String) { repo.venue.value = v; refresh() }
    fun setAuto(on: Boolean) = viewModelScope.launch {
        val r = if (on) repo.autotradeOn() else repo.autotradeOff(); say(r.message); refresh()
    }
    fun kill() = viewModelScope.launch { say(repo.killSwitch(true, "home screen").message); refresh() }
}

/** Everything Home needs, so the three layouts are pure functions of state (and screenshot-testable). */
data class HomeState(
    val d: Dashboard, val demo: Boolean, val venue: String, val positions: List<Position>, val tape: List<TapeItem>,
)

class HomeActions(
    val onLive: (Boolean) -> Unit = {}, val onAuto: (Boolean) -> Unit = {}, val onVenue: (String) -> Unit = {},
    val onKill: () -> Unit = {}, val onNavigate: (String) -> Unit = {},
)

@Composable
fun HomeScreen(snackbar: SnackbarHostState, onNavigate: (String) -> Unit = {}, vm: HomeViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    DisposableEffect(Unit) { vm.feed.acquire(); onDispose { vm.feed.release() } }
    val state by vm.state.collectAsStateCompat()
    val demo by vm.demo.collectAsStateCompat()
    val venue by vm.venue.collectAsStateCompat()
    val positions by vm.positions.collectAsStateCompat()
    val quotes by vm.feed.quotes.collectAsStateCompat()
    var liveDialog by remember { mutableStateOf(false) }
    var paperDialog by remember { mutableStateOf(false) }
    var autoDialog by remember { mutableStateOf(false) }
    var killDialog by remember { mutableStateOf(false) }
    val tape = quotes.values.sortedBy { it.symbol }.map {
        TapeItem(it.symbol, if (it.exchange == "DELTA") "$" + "%,.1f".format(it.ltp) else "%,.2f".format(it.ltp),
            (it.ltp / it.sessionOpen - 1) * 100)
    }
    val actions = HomeActions(
        onLive = { on -> if (on) liveDialog = true else paperDialog = true },
        onAuto = { on -> if (on) autoDialog = true else vm.setAuto(false) },
        onVenue = vm::setVenue, onKill = { killDialog = true }, onNavigate = onNavigate)

    StateContent(state, vm::refresh) { l -> HomeContent(HomeState(l.data, demo, venue, positions, tape), actions) }

    if (liveDialog) LiveDialog(onDismiss = { liveDialog = false }) { vm.switchMode(TradingMode.LIVE, it) }
    if (autoDialog) ConfirmDialog("Turn auto-trading ON?",
        "The engine will place orders by itself, inside your risk limits. Strategies in PAPER stage trade on paper " +
            "even while LIVE is on; only strategies promoted through the gate use real money. Requires biometric.",
        "Turn ON", onConfirm = { vm.setAuto(true) }, onDismiss = { autoDialog = false })
    if (paperDialog) ConfirmDialog("Switch to PAPER?", "New orders will be simulated. Open live positions are not closed.",
        "Switch", onConfirm = { vm.switchMode(TradingMode.PAPER, "") }, onDismiss = { paperDialog = false })
    if (killDialog) ConfirmDialog("Engage kill switch?", "Blocks all new orders and cancels working entries immediately. " +
        "Protective stops stay in place.", "ENGAGE", danger = true, onConfirm = vm::kill, onDismiss = { killDialog = false })
}

@Composable
fun HomeContent(s: HomeState, a: HomeActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        when (LocalAppearance.current.layout) {
            LayoutStyle.PRO -> ProHome(s, a, wide)
            LayoutStyle.CARDS -> CardsHome(s, a, wide)
            LayoutStyle.MINIMAL -> FocusHome(s, a)
        }
    }
}

// ------------------------------------------------------------------------------ shared pieces
private data class Stats(val sharpe: Double, val alpha: Double, val beta: Double, val maxDd: Double)

private fun stats(d: Dashboard): Stats {
    val r = Metrics.returns(d.curve.map { it.equity }); val b = Metrics.returns(d.curve.map { it.nifty })
    val (al, be) = Metrics.alphaBeta(r, b)
    return Stats(Metrics.sharpe(r), al, be, Metrics.maxDrawdown(d.curve.map { it.equity }) * 100)
}

@Composable
private fun AccountLabel(d: Dashboard, onHero: Boolean = false) {
    val live = d.account == "live"
    Text(if (live) "REAL ACCOUNT${if (d.broker.isNotBlank()) " · ${d.broker.uppercase()}" else ""}" else "PAPER ACCOUNT",
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = when { onHero -> Color.White.copy(alpha = .85f); live -> LiveRed; else -> LocalTokens.current.muted })
}

/** On the gradient hero every pill is white-on-glass so it stays legible on any accent/theme. */
@Composable
private fun StatusRow(s: HomeState, onHero: Boolean = false) {
    @Composable fun pill(text: String, c: Color, pulse: Boolean = false, m: Modifier = Modifier) =
        StatusPill(text, if (onHero) Color.White else c, pulse, m)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s.demo -> pill("DEMO", PaperBlue)
            s.d.mode == TradingMode.LIVE -> pill("LIVE", LiveRed, pulse = true, m = Modifier.testTag("modeBadge"))
            else -> pill("PAPER", PaperBlue, m = Modifier.testTag("modeBadge"))
        }
        if (s.d.autoTrade) pill("AUTO · ${s.d.autoOpenTrades}", Profit, pulse = true)
        if (s.d.killSwitch) pill("KILL SWITCH", Loss)
    }
}

@Composable
private fun Controls(s: HomeState, a: HomeActions, compact: Boolean = false) {
    TradingControls(s.d, s.demo, a.onLive, a.onAuto, compact)
}

@Composable
private fun PositionsPreview(ps: List<Position>, a: HomeActions, dense: Boolean) {
    SectionHeader("Open positions (${ps.size})") { TextButton(onClick = { a.onNavigate("positions") }) { Text("All") } }
    if (ps.isEmpty()) {
        Text("No open positions", color = LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    GlassCard(padding = if (dense) 10.dp else 14.dp) {
        if (dense) Row {
            listOf("SYMBOL" to 1.6f, "QTY" to .8f, "LTP" to 1.1f, "P&L" to 1.1f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted,
                    textAlign = if (h == "SYMBOL") TextAlign.Start else TextAlign.End)
            }
        }
        ps.take(6).forEachIndexed { i, p ->
            if (i > 0 || dense) HorizontalDivider(Modifier.padding(vertical = if (dense) 6.dp else 8.dp), color = LocalTokens.current.glassBorder)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.6f)) {
                    Text(p.symbol.removeSuffix("-EQ"), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (!dense) Text("${p.qty} @ ${Fmt.price(p.avgPrice)}", style = MaterialTheme.typography.labelSmall,
                        color = LocalTokens.current.muted)
                }
                if (dense) {
                    Text("${p.qty}", Modifier.weight(.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                    Text("%,.2f".format(p.ltp), Modifier.weight(1.1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                }
                Text(Fmt.signedMoney(p.unrealizedPnl), Modifier.weight(1.1f), textAlign = TextAlign.End,
                    color = pnlColor(p.unrealizedPnl), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ------------------------------------------------------------------------------ PRO terminal
@Composable
private fun ProHome(s: HomeState, a: HomeActions, wide: Boolean) {
    val d = s.d; val st = stats(d); val g = LocalTokens.current.gap
    Column(Modifier.fillMaxSize()) {
        TickerTape(s.tape)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(g)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusRow(s); Spacer(Modifier.weight(1f))
                if (!s.demo) Segmented(listOf("current" to "Engine", "live" to "Broker"), s.venue, a.onVenue)
            }
            val tiles: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(g)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(g)) {
                        MetricTile("Day P&L", Fmt.signedMoney(d.dayPnl), Modifier.weight(1f), pnlColor(d.dayPnl), Fmt.pct(d.dayPnlPct))
                        MetricTile("Equity", Fmt.compact(d.equity), Modifier.weight(1f), sub = d.account.uppercase())
                        MetricTile("Total P&L", Fmt.compact(d.totalPnl), Modifier.weight(1f), pnlColor(d.totalPnl))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(g)) {
                        MetricTile("Sharpe", Fmt.num(st.sharpe), Modifier.weight(1f))
                        MetricTile("Alpha", Fmt.pct(st.alpha * 100, 1), Modifier.weight(1f), pnlColor(st.alpha), "β ${Fmt.num(st.beta)}")
                        MetricTile("Max DD", Fmt.plainPct(st.maxDd), Modifier.weight(1f), Loss.copy(alpha = .9f), "${d.openPositions} open")
                    }
                }
            }
            val chart: @Composable (Modifier) -> Unit = { m ->
                GlassCard(m) {
                    if (!d.benchmarkLive) Text("No NIFTY feed yet — benchmark shown flat", style = MaterialTheme.typography.labelSmall, color = Warn)
                    InteractiveEquityChart(d.curve, height = if (wide) 300.dp else 170.dp)
                }
            }
            if (d.killSwitch) KillBanner()
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(g)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(g)) {
                        tiles(Modifier); Controls(s, a, compact = true); PositionsPreview(s.positions, a, dense = true)
                    }
                    chart(Modifier.weight(1.2f))
                }
            } else {
                tiles(Modifier); chart(Modifier); Controls(s, a, compact = true); PositionsPreview(s.positions, a, dense = true)
            }
            KillLink(a)
        }
    }
}

// ------------------------------------------------------------------------------ CARDS
@Composable
private fun CardsHome(s: HomeState, a: HomeActions, wide: Boolean) {
    val d = s.d; val st = stats(d); val g = LocalTokens.current.gap + 4.dp
    val hero: @Composable () -> Unit = {
        GlassCard(brush = heroBrush(), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountLabel(d, onHero = true); Spacer(Modifier.weight(1f)); StatusRow(s, onHero = true)
            }
            Spacer(Modifier.height(10.dp))
            Text("Today's P&L", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .75f))
            AnimatedMoney(d.dayPnl, MaterialTheme.typography.displayMedium, Color.White, modifier = Modifier.testTag("dayPnl"))
            Text((if (d.dayPnl >= 0) "▲ " else "▼ ") + Fmt.pct(d.dayPnlPct) + " today", color = Color.White,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Sparkline(d.curve.takeLast(120).map { it.equity }, Color.White, Modifier.fillMaxWidth().height(56.dp))
            Spacer(Modifier.height(12.dp))
            Row {
                listOf("Equity" to Fmt.compact(d.equity), "Total" to Fmt.compact(d.totalPnl), "Open" to "${d.openPositions}").forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .7f))
                        Text(v, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickAction(Icons.Filled.SwapVert, "Trade", MaterialTheme.colorScheme.primary) { a.onNavigate("markets") }
            QuickAction(Icons.AutoMirrored.Filled.ShowChart, "Markets", MaterialTheme.colorScheme.secondary) { a.onNavigate("markets") }
            QuickAction(Icons.Filled.Bolt, "Signals", Warn) { a.onNavigate("signals") }
            QuickAction(Icons.Filled.AccountBalance, "Brokers", Profit) { a.onNavigate("brokers") }
            QuickAction(Icons.Filled.PowerSettingsNew, "Kill", Loss) { a.onKill() }
        }
    }
    val perf: @Composable () -> Unit = {
        GlassCard {
            SectionHeader("Performance")
            InteractiveEquityChart(d.curve, height = 190.dp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Sharpe", Fmt.num(st.sharpe), Modifier.weight(1f))
                MetricTile("Alpha", Fmt.pct(st.alpha * 100, 1), Modifier.weight(1f), pnlColor(st.alpha))
                MetricTile("Max DD", Fmt.plainPct(st.maxDd), Modifier.weight(1f))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(g)) {
        if (!s.demo) Segmented(listOf("current" to "Engine account", "live" to "Real broker"), s.venue, a.onVenue)
        if (d.killSwitch) KillBanner()
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(g)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(g)) { hero(); actions(); Controls(s, a) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(g)) { perf(); PositionsPreview(s.positions, a, false) }
        } else {
            hero(); actions(); Controls(s, a); perf(); PositionsPreview(s.positions, a, false)
        }
        Text("Updated ${Fmt.time(d.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
    }
}

// ------------------------------------------------------------------------------ FOCUS
@Composable
private fun FocusHome(s: HomeState, a: HomeActions) {
    val d = s.d
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        StatusRow(s)
        AccountLabel(d)
        AnimatedMoney(d.dayPnl, MaterialTheme.typography.displayLarge, pnlColor(d.dayPnl), modifier = Modifier.testTag("dayPnl"))
        Text("${Fmt.pct(d.dayPnlPct)} today", style = MaterialTheme.typography.titleMedium, color = pnlColor(d.dayPnl))
        Sparkline(d.curve.map { it.equity }, if (d.totalPnl >= 0) Profit else Loss, Modifier.fillMaxWidth().height(90.dp), stroke = 4f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Equity" to Fmt.compact(d.equity), "All-time" to Fmt.compact(d.totalPnl), "Open" to "${d.openPositions}").forEach { (k, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(k, style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
                }
            }
        }
        if (d.killSwitch) KillBanner()
        Controls(s, a)
        if (!s.demo) Segmented(listOf("current" to "Engine", "live" to "Real broker"), s.venue, a.onVenue)
        KillLink(a)
    }
}

@Composable
private fun KillLink(a: HomeActions) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = a.onKill, colors = ButtonDefaults.outlinedButtonColors(contentColor = Loss),
            border = androidx.compose.foundation.BorderStroke(1.dp, Loss.copy(alpha = .5f))) {
            Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text("Kill switch")
        }
    }
}

// ------------------------------------------------------------------------------ controls & dialogs
@Composable
fun TradingControls(d: Dashboard, demo: Boolean, onLive: (Boolean) -> Unit, onAuto: (Boolean) -> Unit, compact: Boolean = false) {
    val live = d.mode == TradingMode.LIVE
    GlassCard(padding = if (compact) 12.dp else 16.dp) {
        @Composable fun row(title: String, sub: String, checked: Boolean, tag: String, color: Color, enabled: Boolean = true,
                            onChange: (Boolean) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = if (checked && tag == "liveSwitch") LiveRed else Color.Unspecified)
                    if (!compact || checked) Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
                }
                Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag),
                    colors = SwitchDefaults.colors(checkedTrackColor = color))
            }
        }
        row("LIVE trading", if (live) "Real orders via your active broker" else "OFF — all orders are simulated (PAPER)",
            live, "liveSwitch", LiveRed, onChange = onLive)
        HorizontalDivider(Modifier.padding(vertical = if (compact) 4.dp else 8.dp), color = LocalTokens.current.glassBorder)
        row("Auto-trade", when {
            d.killSwitch -> "Blocked by kill switch"
            d.autoTrade && live -> "ON — promoted strategies trade real money, others paper"
            d.autoTrade -> "ON — engine places paper orders by itself"
            else -> "OFF — no automatic entries"
        }, d.autoTrade, "autoSwitch", Profit, enabled = !d.killSwitch || d.autoTrade, onChange = onAuto)
    }
}

@Composable
fun KillBanner() {
    Surface(color = Loss.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = Loss)
            Spacer(Modifier.width(8.dp))
            Text("KILL SWITCH ENGAGED — no new orders", color = Loss, fontWeight = FontWeight.Bold)
        }
    }
}

/** LIVE needs: this typed phrase + biometric signature (in the request) + server-side allow flag. */
@Composable
fun LiveDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = text == LIVE_PHRASE
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Switch to LIVE trading", color = LiveRed) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real orders will be sent from the engine's static IP. The risk engine keeps its veto.")
                Text("Type exactly:", style = MaterialTheme.typography.labelMedium)
                Text(LIVE_PHRASE, fontWeight = FontWeight.Bold)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("livePhrase"), singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Text("You will then be asked for your fingerprint/face.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(); onConfirm(text) }, enabled = ok, modifier = Modifier.testTag("confirmLive"),
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed)) { Text("Go LIVE") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
