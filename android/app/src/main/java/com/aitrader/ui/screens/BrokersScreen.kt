package com.aitrader.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.BrokerInfo
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrokersViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<List<BrokerInfo>>() {
    /** One-shot: a broker login URL the screen must open in the system browser. */
    val openUrl = MutableStateFlow<String?>(null)
    override suspend fun fetch() = repo.brokers()

    fun connect(id: String) = viewModelScope.launch {
        val r = repo.connectBroker(id)
        if (r.ok && r.loginUrl != null) openUrl.value = r.loginUrl else say(r.message)
        refresh()
    }
    fun activate(id: String) = viewModelScope.launch { say(repo.activateBroker(id).message); refresh() }
    fun disconnect(id: String) = viewModelScope.launch { say(repo.disconnectBroker(id).message); refresh() }
}

private fun authLabel(a: String) = when (a) {
    "OAUTH" -> "Browser login (daily)"; "TOTP" -> "Server TOTP login (daily)"; "TOKEN" -> "Access token on server"
    "API_KEY" -> "API key + secret on server (signed)"; else -> a
}

@Composable
fun BrokersScreen(snackbar: SnackbarHostState, vm: BrokersViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    val url by vm.openUrl.collectAsStateCompat()
    val ctx = LocalContext.current
    var activate by remember { mutableStateOf<BrokerInfo?>(null) }
    var disconnect by remember { mutableStateOf<BrokerInfo?>(null) }

    // Returning from the broker's login page (aitrader://brokers or back button) -> refresh status.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(url) {
        url?.let {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                .onFailure { snackbar.showSnackbar("No browser available to open the broker login") }
            vm.openUrl.value = null
        }
    }

    StateContent(state, vm::refresh) { l ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Broker API keys and session tokens live only on the engine (static IP). " +
                    "The active broker receives LIVE orders; PAPER never touches a broker.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(l.data, key = { it.id }) { b ->
                BrokerCard(b, onConnect = { vm.connect(b.id) }, onActivate = { activate = b }, onDisconnect = { disconnect = b })
            }
        }
    }
    activate?.let { b ->
        ConfirmDialog("Use ${b.name} for LIVE?", "Only allowed while in PAPER mode. Requires biometric.", "Use ${b.name}",
            onConfirm = { vm.activate(b.id) }, onDismiss = { activate = null })
    }
    disconnect?.let { b ->
        ConfirmDialog("Disconnect ${b.name}?", "The engine deletes its session token. Reconnect any time.", "Disconnect",
            danger = true, onConfirm = { vm.disconnect(b.id) }, onDismiss = { disconnect = null })
    }
}

@Composable
private fun BrokerCard(b: BrokerInfo, onConnect: () -> Unit, onActivate: () -> Unit, onDisconnect: () -> Unit) {
    SectionCard(modifier = Modifier.testTag("broker_${b.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, fontWeight = FontWeight.Bold)
                    if (b.active) { Spacer(Modifier.width(8.dp)); Chip("ACTIVE", PaperBlue) }
                    if (b.segments.any { it.startsWith("CRYPTO") }) { Spacer(Modifier.width(6.dp)); Chip("CRYPTO 24×7", Warn) }
                }
                Text(authLabel(b.auth) + " · " + b.segments.joinToString(" "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (b.connected) Icons.Filled.CheckCircle else Icons.Filled.LinkOff, null,
                tint = if (b.connected) Profit else if (b.configured) Warn else Nifty)
        }
        Spacer(Modifier.height(6.dp))
        val status = buildString {
            append(b.message)
            if (b.account.isNotBlank()) append(" · ${b.account}")
            b.expiresAt?.let { if (b.connected && b.auth != "TOTP") append(" · until ${it.take(16).replace('T', ' ')}") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (b.connected) Profit else MaterialTheme.colorScheme.onSurfaceVariant)
        if (b.segments.any { it.startsWith("CRYPTO") })
            Text("Crypto derivatives: not SEBI-regulated, trades 24×7, separate fees and tax treatment.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!b.configured && b.requiredSettings.isNotEmpty())
            Text("Set on server: ${b.requiredSettings.joinToString()}", style = MaterialTheme.typography.labelSmall, color = Warn)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!b.connected) Button(onClick = onConnect, enabled = b.configured, modifier = Modifier.testTag("connect_${b.id}")) {
                Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (b.auth == "OAUTH") "Log in" else "Connect")
            } else {
                if (!b.active) Button(onClick = onActivate) { Text("Use for LIVE") }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                if (b.auth == "OAUTH") TextButton(onClick = onConnect) { Text("Re-login") }
            }
        }
    }
}
