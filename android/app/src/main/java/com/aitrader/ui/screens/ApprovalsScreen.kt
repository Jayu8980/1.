package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.OrderIntent
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApprovalsViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<List<OrderIntent>>(pollMs = 3_000) {
    override suspend fun fetch() = repo.intents()
    fun approve(i: OrderIntent) = viewModelScope.launch { say(repo.approveIntent(i).message); refresh() }
    fun reject(i: OrderIntent) = viewModelScope.launch { say(repo.rejectIntent(i.id).message); refresh() }
}

@Composable
fun ApprovalsScreen(snackbar: SnackbarHostState, vm: ApprovalsViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    var confirm by remember { mutableStateOf<OrderIntent?>(null) }
    StateContent(state, vm::refresh) { l ->
        val pending = l.data.filter { it.status == "PENDING" }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Orders requested by AI assistants or API keys while the engine is LIVE. Nothing reaches your broker " +
                    "until you approve with your fingerprint/face. Requests expire after 5 minutes.",
                    style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
            }
            if (pending.isEmpty()) item { GlassCard { Text("No pending requests", fontWeight = FontWeight.SemiBold) } }
            items(pending, key = { it.id }) { i -> IntentCard(i, onApprove = { confirm = i }, onReject = { vm.reject(i) }) }
            val done = l.data.filter { it.status != "PENDING" }
            if (done.isNotEmpty()) item { SectionHeader("History") }
            items(done, key = { it.id }) { i -> IntentCard(i, null, null) }
        }
    }
    confirm?.let { i ->
        ConfirmDialog("Send this REAL order?", "${i.summary}\n\nRequested by ${i.requestedBy}\nReason: ${i.reason}",
            "Approve with fingerprint", danger = true, onConfirm = { vm.approve(i) }, onDismiss = { confirm = null })
    }
}

@Composable
private fun IntentCard(i: OrderIntent, onApprove: (() -> Unit)?, onReject: (() -> Unit)?) {
    GlassCard(Modifier.testTag("intent_${i.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.summary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            StatusPill(if (i.status == "PENDING") "${i.expiresInSec}s" else i.status, when (i.status) {
                "PENDING" -> Warn; "APPROVED" -> Profit; else -> Nifty }, pulse = i.status == "PENDING")
        }
        Spacer(Modifier.height(6.dp))
        Text("From ${i.requestedBy}", style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
        Text("“${i.reason}”", style = MaterialTheme.typography.bodySmall)
        if (i.result.isNotBlank()) Text(i.result, style = MaterialTheme.typography.labelSmall,
            color = if (i.status == "APPROVED") Profit else LocalTokens.current.muted)
        if (onApprove != null && onReject != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
                    modifier = Modifier.testTag("approve_${i.id}")) { Text("Approve (REAL)") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}
