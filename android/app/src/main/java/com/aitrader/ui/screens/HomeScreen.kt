package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.local.SettingsStore
import com.aitrader.data.model.Dashboard
import com.aitrader.data.model.TradingMode
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val LIVE_PHRASE = "I UNDERSTAND THIS USES REAL MONEY"

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: TradingRepository, settings: SettingsStore) :
    ResourceViewModel<Dashboard>(pollMs = 3_000) {
    val demo = settings.settings.map { it.demoMode }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    override suspend fun fetch() = repo.dashboard()

    fun switchMode(mode: TradingMode, phrase: String) = viewModelScope.launch {
        val r = repo.setMode(mode, phrase); say(r.message.ifBlank { if (r.ok) "Mode: $mode" else "Denied" }); refresh()
    }
}

@Composable
fun HomeScreen(snackbar: SnackbarHostState, vm: HomeViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    val demo by vm.demo.collectAsStateCompat()
    var liveDialog by remember { mutableStateOf(false) }
    var paperDialog by remember { mutableStateOf(false) }

    StateContent(state, vm::refresh) { l ->
        val d = l.data
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeBadge(d.mode, demo)
                Spacer(Modifier.weight(1f))
                if (d.mode == TradingMode.PAPER)
                    OutlinedButton(onClick = { liveDialog = true }, modifier = Modifier.testTag("goLive")) { Text("Go LIVE…") }
                else Button(onClick = { paperDialog = true }) { Text("Back to PAPER") }
            }
            if (d.killSwitch) KillBanner()
            SectionCard {
                Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.signedMoney(d.dayPnl), Modifier.testTag("dayPnl"), style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold, color = pnlColor(d.dayPnl))
                Text(Fmt.pct(d.dayPnlPct), color = pnlColor(d.dayPnl))
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Equity", Fmt.compact(d.equity), Modifier.weight(1f))
                    Stat("Total P&L", Fmt.compact(d.totalPnl), Modifier.weight(1f), pnlColor(d.totalPnl))
                    Stat("Open", d.openPositions.toString(), Modifier.weight(1f))
                }
            }
            SectionCard("Equity vs NIFTY 50 (rebased)") {
                if (!d.benchmarkLive) Text("No NIFTY feed yet — log in a broker on the engine; NIFTY shown flat.",
                    style = MaterialTheme.typography.labelSmall, color = Warn)
                LineChart(listOf(
                    Series("Strategy", d.curve.map { it.equity }, MaterialTheme.colorScheme.primary),
                    Series("NIFTY 50", d.curve.map { it.nifty }, Nifty, dashed = true),
                ), description = "equity curve versus NIFTY")
                val (alpha, beta) = com.aitrader.data.Metrics.alphaBeta(
                    com.aitrader.data.Metrics.returns(d.curve.map { it.equity }),
                    com.aitrader.data.Metrics.returns(d.curve.map { it.nifty }))
                val sharpe = com.aitrader.data.Metrics.sharpe(com.aitrader.data.Metrics.returns(d.curve.map { it.equity }))
                Spacer(Modifier.height(8.dp))
                Row {
                    Stat("Sharpe", Fmt.num(sharpe), Modifier.weight(1f))
                    Stat("Alpha (ann.)", Fmt.pct(alpha * 100, 1), Modifier.weight(1f), pnlColor(alpha))
                    Stat("Beta", Fmt.num(beta), Modifier.weight(1f))
                    Stat("Max DD", Fmt.plainPct(com.aitrader.data.Metrics.maxDrawdown(d.curve.map { it.equity }) * 100),
                        Modifier.weight(1f))
                }
            }
            Text("Updated ${Fmt.time(d.updatedAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (liveDialog) LiveDialog(onDismiss = { liveDialog = false }) { vm.switchMode(TradingMode.LIVE, it) }
    if (paperDialog) ConfirmDialog("Switch to PAPER?", "New orders will be simulated. Open live positions are not closed.",
        "Switch", onConfirm = { vm.switchMode(TradingMode.PAPER, "") }, onDismiss = { paperDialog = false })
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
                Text("Real orders will be sent to Angel One from the engine's static IP. The risk engine keeps its veto.")
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
