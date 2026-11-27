package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.local.AlertEntity
import com.aitrader.data.model.AuditEntry
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<List<AuditEntry>>(pollMs = 10_000) {
    val alerts = repo.alerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override suspend fun fetch() = repo.audit()
}

@Composable
fun LogsScreen(snackbar: SnackbarHostState, vm: LogsViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    val alerts by vm.alerts.collectAsStateCompat()
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Audit trail") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Alerts (${alerts.size})") })
        }
        if (tab == 0) StateContent(state, vm::refresh) { l ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(l.data) { AuditRow(it) }
            }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alerts.isEmpty()) item { Text("No alerts yet. Fills, stops, errors and risk breaches appear here and as notifications.") }
            items(alerts, key = { it.id }) { AlertRow(it) }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEntry) {
    val color = when {
        e.event.startsWith("risk") || e.event.contains("kill") || e.event.contains("blocked") || e.event.contains("denied") -> Loss
        e.event.startsWith("order") -> Profit
        e.event.startsWith("auth") || e.event.startsWith("mode") -> PaperBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row {
            Text(e.event, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(e.ts.take(19).replace('T', ' '), style = MaterialTheme.typography.labelSmall)
        }
        Text(e.data, style = MaterialTheme.typography.bodySmall)
        Text(e.hash.take(16) + "…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AlertRow(a: AlertEntity) {
    SectionCard {
        Row { Chip(a.kind.uppercase(), if (a.kind in setOf("risk", "kill", "error")) Loss else PaperBlue)
            Spacer(Modifier.weight(1f)); Text(Fmt.dateTime(a.ts), style = MaterialTheme.typography.labelSmall) }
        Text(a.title, fontWeight = FontWeight.SemiBold)
        Text(a.body, style = MaterialTheme.typography.bodySmall)
    }
}
