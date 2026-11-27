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
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.StrategyStats
import com.aitrader.data.model.StrategyUpdate
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StrategiesViewModel @Inject constructor(private val repo: TradingRepository) :
    ResourceViewModel<List<StrategyStats>>(pollMs = 15_000) {
    override suspend fun fetch() = repo.strategies()
    fun update(id: String, u: StrategyUpdate) = viewModelScope.launch {
        val r = repo.updateStrategy(id, u); say(r.message); refresh()
    }
}

@Composable
fun StrategiesScreen(snackbar: SnackbarHostState, vm: StrategiesViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    StateContent(state, vm::refresh) { l ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val total = l.data.filter { it.enabled }.sumOf { it.allocationPct }
                Text("Allocated: %.0f%% of capital".format(total), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(l.data, key = { it.id }) { s -> StrategyCard(s, vm::update) }
        }
    }
}

@Composable
private fun StrategyCard(s: StrategyStats, onUpdate: (String, StrategyUpdate) -> Unit) {
    var alloc by remember(s.allocationPct) { mutableFloatStateOf(s.allocationPct.toFloat()) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.Bold)
                Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.enabled, { onUpdate(s.id, StrategyUpdate(enabled = it)) })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(s.stage.replace('_', ' '), when (s.stage) { "LIVE" -> LiveRed; "LIVE_10" -> Warn; "PAPER" -> PaperBlue; else -> Nifty })
            s.regimes.forEach { Chip(it, MaterialTheme.colorScheme.secondary) }
        }
        s.autoDisabledReason?.let {
            Spacer(Modifier.height(6.dp)); Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Stat("Live Sharpe", Fmt.num(s.liveSharpe), Modifier.weight(1f),
                if (s.liveSharpe < s.backtestSharpe * 0.5 && s.trades > 0) Warn else MaterialTheme.colorScheme.onSurface)
            Stat("BT Sharpe", Fmt.num(s.backtestSharpe), Modifier.weight(1f))
            Stat("Trades", s.trades.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Stat("Win rate", Fmt.plainPct(s.winRate * 100, 0), Modifier.weight(1f))
            Stat("P&L", Fmt.compact(s.pnl), Modifier.weight(1f), pnlColor(s.pnl))
            Stat("DD / BT max", "%.1f / %.1f%%".format(s.drawdownPct, s.backtestMaxDdPct), Modifier.weight(1f),
                if (s.drawdownPct > 1.5 * s.backtestMaxDdPct) Loss else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        Text("Allocation %.0f%%".format(alloc), style = MaterialTheme.typography.labelMedium)
        Slider(alloc, { alloc = it }, valueRange = 0f..50f, steps = 9)
        if (alloc.toDouble() != s.allocationPct)
            Button(onClick = { onUpdate(s.id, StrategyUpdate(allocationPct = alloc.toDouble())) }) { Text("Save allocation") }
    }
}
