package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.RiskConfig
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RiskViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<RiskConfig>(pollMs = 5_000) {
    override suspend fun fetch() = repo.risk()
    fun kill(engaged: Boolean) = viewModelScope.launch {
        val r = repo.killSwitch(engaged, if (engaged) "manual (app)" else "manual release (app)"); say(r.message); refresh()
    }
    fun save(r: RiskConfig) = viewModelScope.launch { val res = repo.updateRisk(r); say(res.message); refresh() }
}

@Composable
fun RiskScreen(snackbar: SnackbarHostState, vm: RiskViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    var confirmKill by remember { mutableStateOf(false) }
    var confirmRelease by remember { mutableStateOf(false) }

    StateContent(state, vm::refresh) { l ->
        val r = l.data
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!r.killSwitch) {
                    Button(onClick = { confirmKill = true }, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White),
                        modifier = Modifier.size(180.dp).testTag("killSwitch")) {
                        Text("KILL\nSWITCH", fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KillBanner()
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { confirmRelease = true }) { Text("Release kill switch") }
                    }
                }
            }
            Text("Stops all new orders, cancels working orders and drops to PAPER. Positions stay open — exit them from Positions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionCard("Today") {
                val used = (r.dayLossUsed / r.dailyLossLimit).coerceIn(0.0, 1.0)
                Text("Daily loss used: ${Fmt.money(r.dayLossUsed)} / ${Fmt.money(r.dailyLossLimit)}")
                LinearProgressIndicator({ used.toFloat() }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = if (used > 0.8) Loss else if (used > 0.5) Warn else Profit)
                Text("Drawdown: %.1f%% / max %.1f%%".format(r.currentDrawdownPct, r.maxDrawdownPct))
                LinearProgressIndicator({ (r.currentDrawdownPct / r.maxDrawdownPct).coerceIn(0.0, 1.0).toFloat() },
                    Modifier.fillMaxWidth().padding(vertical = 6.dp))
                r.breaches.forEach { Text("⚠ $it", color = Loss) }
            }
            LimitsEditor(r, vm::save)
        }
    }
    if (confirmKill) ConfirmDialog("Engage kill switch?", "All new orders blocked and working orders cancelled immediately.",
        "ENGAGE", danger = true, onConfirm = { vm.kill(true) }, onDismiss = { confirmKill = false })
    if (confirmRelease) ConfirmDialog("Release kill switch?", "Strategies may start placing orders again (in PAPER).",
        "Release", onConfirm = { vm.kill(false) }, onDismiss = { confirmRelease = false })
}

@Composable
private fun LimitsEditor(r: RiskConfig, onSave: (RiskConfig) -> Unit) {
    var loss by remember(r.dailyLossLimit) { mutableStateOf(r.dailyLossLimit.toLong().toString()) }
    var dd by remember(r.maxDrawdownPct) { mutableStateOf(r.maxDrawdownPct.toString()) }
    var perTrade by remember(r.maxRiskPerTradePct) { mutableStateOf(r.maxRiskPerTradePct.toString()) }
    var pos by remember(r.maxPositionPct) { mutableStateOf(r.maxPositionPct.toString()) }
    var sector by remember(r.maxSectorPct) { mutableStateOf(r.maxSectorPct.toString()) }
    var kelly by remember(r.kellyFraction) { mutableStateOf(r.kellyFraction.toString()) }
    SectionCard("Limits (server enforces; biometric to change)") {
        @Composable fun field(label: String, v: String, set: (String) -> Unit) =
            OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        field("Daily loss limit (₹)", loss) { loss = it }
        field("Max drawdown (%)", dd) { dd = it }
        field("Max risk per trade (%, ≤ 2)", perTrade) { perTrade = it }
        field("Max position size (% of equity)", pos) { pos = it }
        field("Max sector exposure (%)", sector) { sector = it }
        field("Kelly fraction (≤ 0.25)", kelly) { kelly = it }
        Spacer(Modifier.height(8.dp))
        val parsed = runCatching {
            r.copy(dailyLossLimit = loss.toDouble(), maxDrawdownPct = dd.toDouble(), maxRiskPerTradePct = perTrade.toDouble(),
                maxPositionPct = pos.toDouble(), maxSectorPct = sector.toDouble(), kellyFraction = kelly.toDouble())
        }.getOrNull()
        Button(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed != r, modifier = Modifier.fillMaxWidth()) {
            Text("Save limits")
        }
    }
}
