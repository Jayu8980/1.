package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.ApiKeyInfo
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

val SCOPE_HELP = linkedMapOf(
    "read" to "Brokers, quotes, positions, orders, funds, risk, signals",
    "trade" to "Place orders — PAPER executes; LIVE needs your approval on this phone",
    "control" to "Cancel orders, kill switch, auto-trade OFF, start broker logins",
)

@HiltViewModel
class ApiViewModel @Inject constructor(private val repo: TradingRepository) : ResourceViewModel<List<ApiKeyInfo>>() {
    val created = MutableStateFlow<String?>(null)
    val engineUrl = MutableStateFlow("")
    override suspend fun fetch() = repo.apiKeys().also { engineUrl.value = repo.engineUrl() }
    fun create(name: String, scopes: List<String>) = viewModelScope.launch {
        val r = repo.createKey(name, scopes)
        if (r.ok && r.key != null) created.value = r.key else say(r.message)
        refresh()
    }
    fun revoke(id: String) = viewModelScope.launch { say(repo.revokeKey(id).message); refresh() }
}

/** MCP client configs the user can paste. Pure, unit-tested. */
fun mcpConfigs(engineUrl: String, key: String): Pair<String, String> {
    val url = engineUrl.trimEnd('/').ifBlank { "https://trader.example.com" }
    val remote = """{
  "mcpServers": {
    "aitrader": {
      "type": "http",
      "url": "$url/mcp/",
      "headers": { "X-API-Key": "$key" }
    }
  }
}"""
    val stdio = """{
  "mcpServers": {
    "aitrader": {
      "command": "python",
      "args": ["-m", "app.mcp_server", "--engine", "$url"],
      "cwd": "/path/to/repo/engine",
      "env": { "AITRADER_API_KEY": "$key" }
    }
  }
}"""
    return remote to stdio
}

@Composable
fun ApiScreen(snackbar: SnackbarHostState, vm: ApiViewModel = hiltViewModel()) {
    Bind(vm); MessageSnackbar(vm, snackbar)
    val state by vm.state.collectAsStateCompat()
    val created by vm.created.collectAsStateCompat()
    val url by vm.engineUrl.collectAsStateCompat()
    var creating by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<ApiKeyInfo?>(null) }

    StateContent(state, vm::refresh) { l ->
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(brush = heroBrush()) {
                Text("Connect AI assistants & your own code", color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("MCP lets Claude and other AI assistants read your brokers, quotes and positions and request orders. " +
                    "The REST API does the same for scripts. LIVE orders always come back here for your fingerprint.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { creating = true }, modifier = Modifier.testTag("newKey")) {
                    Icon(Icons.Filled.Key, null); Spacer(Modifier.width(8.dp)); Text("New API key")
                }
            }
            SectionHeader("Endpoints")
            GlassCard {
                Endpoint("MCP (remote, streamable HTTP)", "${url.trimEnd('/').ifBlank { "https://<engine>" }}/mcp/")
                Endpoint("REST API", "${url.trimEnd('/').ifBlank { "https://<engine>" }}/api/v1/ext/…")
                Endpoint("API docs", "${url.trimEnd('/').ifBlank { "https://<engine>" }}/docs")
                Text("Auth header: X-API-Key: atk_…", style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
            }
            SectionHeader("Keys (${l.data.count { !it.revoked }} active)")
            if (l.data.isEmpty()) Text("No keys yet.", color = LocalTokens.current.muted)
            l.data.forEach { k ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.name, fontWeight = FontWeight.SemiBold)
                            Text("${k.scopes.joinToString(" · ")} · id ${k.id}", style = MaterialTheme.typography.labelSmall,
                                color = LocalTokens.current.muted)
                            Text(if (k.revoked) "Revoked" else "Last used: ${k.lastUsed?.take(16)?.replace('T', ' ') ?: "never"}",
                                style = MaterialTheme.typography.labelSmall, color = if (k.revoked) Loss else LocalTokens.current.muted)
                        }
                        if (!k.revoked) TextButton(onClick = { revoke = k }) { Text("Revoke", color = Loss) }
                    }
                }
            }
            SectionHeader("What keys can never do")
            Text("Switch LIVE on, turn auto-trade on, change risk limits, promote strategies, release the kill switch, " +
                "or approve their own live orders. Those stay biometric-only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
        }
    }
    if (creating) CreateKeyDialog(onDismiss = { creating = false }) { n, s -> creating = false; vm.create(n, s) }
    created?.let { key -> KeyCreatedDialog(key, url) { vm.created.value = null } }
    revoke?.let { k -> ConfirmDialog("Revoke ${k.name}?", "Anything using this key stops working immediately.", "Revoke",
        danger = true, onConfirm = { vm.revoke(k.id) }, onDismiss = { revoke = null }) }
}

@Composable
private fun Endpoint(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
    SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("Claude") }
    val picked = remember { mutableStateMapOf("read" to true, "trade" to false, "control" to false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (who uses it)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                SCOPE_HELP.forEach { (scope, help) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(picked[scope] == true, { picked[scope] = it }, modifier = Modifier.testTag("scope_$scope"))
                        Column {
                            Text(scope, fontWeight = FontWeight.SemiBold)
                            Text(help, style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
                        }
                    }
                }
                Text("Requires fingerprint/face. Grant the fewest scopes needed.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, picked.filterValues { it }.keys.toList()) },
                enabled = picked.values.any { it } && name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun KeyCreatedDialog(key: String, engineUrl: String, onDone: () -> Unit) {
    val clip = LocalClipboardManager.current
    val (remote, stdio) = mcpConfigs(engineUrl, key)
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = {}, title = { Text("Copy your key now") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It will not be shown again. Treat it like a password.", color = Warn, style = MaterialTheme.typography.bodySmall)
                GlassCard(padding = 10.dp) { SelectionContainer { Text(key, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(key)) }) {
                    Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy key")
                }
                Text("MCP client config", fontWeight = FontWeight.SemiBold)
                Segmented(listOf(0 to "Remote (HTTPS)", 1 to "Local (stdio)"), tab, { tab = it })
                val cfg = if (tab == 0) remote else stdio
                GlassCard(padding = 10.dp) { SelectionContainer { Text(cfg, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(cfg)) }) {
                    Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy config")
                }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("I've saved it") } })
}
