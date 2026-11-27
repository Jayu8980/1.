package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aitrader.data.model.Signal
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SignalsViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<List<Signal>>(pollMs = 10_000) {
    override suspend fun fetch() = repo.signals()
}

fun statusLabel(s: String) = when (s) {
    "TAKEN" -> "Taken"; "SKIPPED_COST" -> "Skipped · costs > edge"; "SKIPPED_CONFIDENCE" -> "Skipped · low confidence"
    "SKIPPED_REGIME" -> "Skipped · regime"; "VETOED" -> "Vetoed by risk"; else -> s
}

@Composable
fun SignalsScreen(snackbar: SnackbarHostState, vm: SignalsViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    var onlyTaken by remember { mutableStateOf(false) }
    StateContent(state, vm::refresh) { l ->
        val list = if (onlyTaken) l.data.filter { it.status == "TAKEN" } else l.data
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.data.count { it.status == "TAKEN" }} of ${l.data.size} taken", Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(onlyTaken, { onlyTaken = !onlyTaken }, label = { Text("Taken only") })
                }
            }
            items(list, key = { it.id }) { SignalCard(it) }
        }
    }
}

@Composable
private fun SignalCard(s: Signal) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(s.side, if (s.side == "BUY") Profit else Loss)
            Spacer(Modifier.width(8.dp))
            Text(s.symbol, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(s.regime, when (s.regime) { "TREND" -> PaperBlue; "RANGE" -> MaterialTheme.colorScheme.secondary
                "HIGH_VOL" -> Warn; else -> Nifty })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Confidence", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(76.dp))
            LinearProgressIndicator({ s.confidence.toFloat() }, Modifier.weight(1f).height(6.dp))
            Spacer(Modifier.width(8.dp))
            Text("%.0f%%".format(s.confidence * 100), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(s.reason, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row {
            Stat("Entry", Fmt.price(s.entry), Modifier.weight(1f))
            Stat("Stop", Fmt.price(s.stop), Modifier.weight(1f), Loss)
            Stat("Target", Fmt.price(s.target), Modifier.weight(1f), Profit)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Stat("R:R", "1:%.1f".format(s.rewardRisk), Modifier.weight(1f))
            Stat("Edge after costs", "%.0f bps".format(s.expectedEdgeBps), Modifier.weight(2f))
        }
        Spacer(Modifier.height(6.dp))
        Text("${statusLabel(s.status)} · ${s.strategy} · ${Fmt.time(s.ts)}", style = MaterialTheme.typography.labelSmall,
            color = if (s.status == "TAKEN") Profit else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
