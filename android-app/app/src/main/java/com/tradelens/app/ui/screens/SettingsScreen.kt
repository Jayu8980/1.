package com.tradelens.app.ui.screens

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.delta.DeltaClient
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.RelayClient
import com.tradelens.app.data.llm.LlmClient
import com.tradelens.app.data.market.BrokerProvider
import com.tradelens.app.data.market.LoginMethod
import com.tradelens.app.data.secure.LlmProvider
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.ui.components.Field
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Warn
import com.tradelens.app.work.Schedulers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: SettingsStore, val secure: SecureStore, val brokers: BrokerRegistry, private val delta: DeltaClient,
    private val engine: EngineClient, private val relay: RelayClient, val llm: LlmClient, private val db: AppDatabase,
) : ViewModel() {
    val msg = MutableStateFlow<String?>(null)
    val version = secure.version

    private fun say(block: suspend () -> String) = viewModelScope.launch { msg.value = runCatching { block() }.getOrElse { "Error: ${it.message}" } }

    fun testDelta() = say { delta.testConnection() }
    fun testEngine() = say { engine.diagnose() }
    fun testRelay() = say { relay.diagnose() }
    fun refreshInstruments(b: BrokerProvider) = say { "${b.displayName}: ${brokers.refreshInstruments(b)} instruments cached" }
    fun login(b: BrokerProvider, params: Map<String, String>) = say {
        b.login(params); db.audit().log(AuditEntity(category = "AUTH", message = "${b.displayName} connected"))
        "${b.displayName} connected – ${brokers.refreshInstruments(b)} instruments cached"
    }
    fun logout(b: BrokerProvider) { b.logout(); viewModelScope.launch { db.audit().log(AuditEntity(category = "AUTH", message = "${b.displayName} logged out")) }; msg.value = "${b.displayName} logged out" }
    fun audit(m: String) = viewModelScope.launch { db.audit().log(AuditEntity(category = "RISK", message = m)) }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.state.collectAsState()
    val msg by vm.msg.collectAsState()
    @Suppress("UNUSED_VARIABLE") val ver by vm.version.collectAsState()
    val ctx = LocalContext.current
    var credLogin by remember { mutableStateOf<BrokerProvider?>(null) }
    var tokenLogin by remember { mutableStateOf<BrokerProvider?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    confirm?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Are you sure?") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    credLogin?.let { b ->
        var pin by remember { mutableStateOf("") }; var totp by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { credLogin = null }, title = { Text("${b.displayName} login") },
            text = { Column { Field(pin, { pin = it }, "PIN (not stored)", secret = true); Field(totp, { totp = it }, "TOTP (6 digits)", number = true); Text("Your PIN is sent once to the broker and never saved.", color = Muted, fontSize = 11.sp) } },
            confirmButton = { TextButton(onClick = { vm.login(b, mapOf("pin" to pin, "totp" to totp)); credLogin = null }) { Text("Login") } })
    }
    tokenLogin?.let { b ->
        var tok by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { tokenLogin = null }, title = { Text("${b.displayName} access token") },
            text = { Column { Field(tok, { tok = it }, "Access token", secret = true); Text("Generate on web.dhan.co → Profile → DhanHQ Trading APIs. Valid ~24 h.", color = Muted, fontSize = 11.sp) } },
            confirmButton = { TextButton(onClick = { vm.login(b, mapOf("access_token" to tok)); tokenLogin = null }) { Text("Save") } })
    }

    LazyColumn(Modifier.statusBarsPadding().imePadding(), contentPadding = ListPadding) {
        item { Text("Settings", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        item { msg?.let { Text(it, color = if (it.startsWith("Error") || it.startsWith("✗")) Bear else Bull, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }

        item {
            SectionCard("Security") {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Biometric lock on open", Modifier.weight(1f)); Switch(s.biometricLock, { v -> vm.settings.update { it.copy(biometricLock = v) } }) }
                Text("All keys and tokens are stored with Android Keystore encryption (EncryptedSharedPreferences). No ads, no tracking.", color = Muted, fontSize = 11.sp)
            }
        }

        item { Text("Indian brokers", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        items(vm.brokers.all, key = { it.id }) { b ->
            SectionCard(b.displayName, trailing = {
                Text(when { b.isLoggedIn() -> "Connected"; b.isExpired() -> "Expired"; b.isConfigured() -> "Not logged in"; else -> "Not configured" },
                    color = when { b.isLoggedIn() -> Bull; b.isExpired() -> Warn; else -> Muted }, fontSize = 12.sp)
            }) {
                b.configFields.forEach { f ->
                    var v by remember(b.id, f.key) { mutableStateOf(vm.secure.get(SecureStore.brokerKey(b.id, f.key)) ?: "") }
                    Field(v, { v = it; vm.secure.put(SecureStore.brokerKey(b.id, f.key), it.trim()) }, f.label, secret = f.secret, hint = f.hint.ifBlank { null })
                }
                val redirect = if (s.engineUrl.isNotBlank()) "${s.engineUrl.trimEnd('/')}/auth/callback/${b.id}" else "tradelens://auth/${b.id}"
                if (b.loginMethod(redirect) is LoginMethod.OAuth) Text("Redirect URL to register: $redirect", color = Muted, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Button(enabled = b.isConfigured(), onClick = {
                        when (val m = b.loginMethod(redirect)) {
                            is LoginMethod.OAuth -> CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(m.url))
                            LoginMethod.Credentials -> credLogin = b
                            LoginMethod.AccessToken -> tokenLogin = b
                        }
                    }) { Text(if (b.isExpired()) "Reconnect" else "Connect") }
                    Spacer(Modifier.width(6.dp))
                    if (b.isLoggedIn()) {
                        OutlinedButton(onClick = { vm.refreshInstruments(b) }) { Text("Refresh instruments") }; Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { vm.logout(b) }) { Text("Logout") }; Spacer(Modifier.width(6.dp))
                    }
                    FilterChip(s.primaryBroker == b.id, { vm.settings.update { it.copy(primaryBroker = b.id) } }, { Text("Primary data") })
                }
            }
        }

        item {
            SectionCard("Delta Exchange India") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(s.deltaTestnet, { vm.settings.update { it.copy(deltaTestnet = true) } }, { Text("Testnet") }); Spacer(Modifier.width(6.dp))
                    FilterChip(!s.deltaTestnet, { confirm = "Switch Delta to PRODUCTION (api.india.delta.exchange)? Data and any enabled live orders will use real money." to { vm.settings.update { it.copy(deltaTestnet = false) } } }, { Text("Production") })
                }
                var key by remember { mutableStateOf(vm.secure.get(SecureStore.DELTA_KEY) ?: "") }
                var sec by remember { mutableStateOf(vm.secure.get(SecureStore.DELTA_SECRET) ?: "") }
                Field(key, { key = it; vm.secure.put(SecureStore.DELTA_KEY, it.trim()) }, "API key")
                Field(sec, { sec = it; vm.secure.put(SecureStore.DELTA_SECRET, it.trim()) }, "API secret", secret = true,
                    hint = "Create keys on ${if (s.deltaTestnet) "the India testnet" else "india.delta.exchange"} (Delta Global keys will not work). Whitelist your IP if required.")
                OutlinedButton(onClick = vm::testDelta) { Text("Test connection") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Live orders"); Text("Off by default. Every order still needs your approval.", color = Muted, fontSize = 11.sp) }
                    Switch(s.deltaLiveOrders, { v -> if (v) confirm = "Enable live Delta orders? (Testnet only until you also unlock Production below.)" to { vm.settings.update { it.copy(deltaLiveOrders = true) }; vm.audit("Delta live orders enabled") }
                        else vm.settings.update { it.copy(deltaLiveOrders = false) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Unlock Production orders", color = Warn); Text("Manual step required before real-money crypto orders.", color = Muted, fontSize = 11.sp) }
                    Switch(s.deltaProductionUnlocked, { v -> if (v) confirm = "Allow REAL-MONEY orders on Delta Production? Leverage can liquidate your margin quickly." to { vm.settings.update { it.copy(deltaProductionUnlocked = true) }; vm.audit("Delta PRODUCTION orders unlocked") }
                        else vm.settings.update { it.copy(deltaProductionUnlocked = false) } })
                }
            }
        }

        item {
            SectionCard("Strategy engine (your laptop / VPS)") {
                var url by remember { mutableStateOf(s.engineUrl) }
                var tok by remember { mutableStateOf(vm.secure.get(SecureStore.ENGINE_TOKEN) ?: "") }
                Field(url, { url = it; vm.settings.update { st -> st.copy(engineUrl = it.trim()) } }, "Engine URL", hint = "Same Wi-Fi: http://<PC IP>:8000 (run_windows.ps1 prints it). Remote: https://… (tunnel or VPS)")
                Field(tok, { tok = it; vm.secure.put(SecureStore.ENGINE_TOKEN, it.trim()) }, "Engine API token", secret = true)
                OutlinedButton(onClick = vm::testEngine) { Text("Test engine") }
            }
        }

        item {
            SectionCard("Order relay (static-IP VPS)") {
                var url by remember { mutableStateOf(s.relayUrl) }
                var tok by remember { mutableStateOf(vm.secure.get(SecureStore.RELAY_TOKEN) ?: "") }
                Field(url, { url = it; vm.settings.update { st -> st.copy(relayUrl = it.trim()) } }, "Relay URL")
                Field(tok, { tok = it; vm.secure.put(SecureStore.RELAY_TOKEN, it.trim()) }, "Relay token", secret = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Indian orders via relay (advanced)"); Text("LIMIT only, tagged with your broker algo ID. Default is 'Open in broker app'.", color = Muted, fontSize = 11.sp) }
                    Switch(s.indianOrdersViaRelay, { v -> if (v) confirm = "Route approved Indian LIMIT orders through your relay server?" to { vm.settings.update { it.copy(indianOrdersViaRelay = true) }; vm.audit("Relay order routing enabled") }
                        else vm.settings.update { it.copy(indianOrdersViaRelay = false) } })
                }
                OutlinedButton(onClick = vm::testRelay) { Text("Test relay") }
            }
        }

        item {
            SectionCard("AI analysis (your own API key)") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    LlmProvider.entries.forEach { p -> FilterChip(s.llmProvider == p, { vm.settings.update { it.copy(llmProvider = p, llmModel = "") } }, { Text(p.label) }); Spacer(Modifier.width(4.dp)) }
                }
                var model by remember(s.llmProvider) { mutableStateOf(s.llmModel) }
                var key by remember(s.llmProvider) { mutableStateOf(vm.secure.get(SecureStore.llmKey(s.llmProvider.name)) ?: "") }
                var base by remember { mutableStateOf(s.llmBaseUrl) }
                Field(model, { model = it; vm.settings.update { st -> st.copy(llmModel = it.trim()) } }, "Model", hint = "Default: ${vm.llm.defaultModel(s.llmProvider)}")
                Field(key, { key = it; vm.secure.put(SecureStore.llmKey(s.llmProvider.name), it.trim()) }, "API key", secret = true)
                if (s.llmProvider == LlmProvider.LOCAL) Field(base, { base = it; vm.settings.update { st -> st.copy(llmBaseUrl = it.trim()) } }, "Base URL", hint = "e.g. https://my-ollama.example.com/v1")
                Text("The AI receives only computed chart data. It never places orders or changes risk limits.", color = Muted, fontSize = 11.sp)
            }
        }

        item {
            SectionCard("Paper trading, alerts & risk") {
                var cap by remember { mutableStateOf(s.paperCapital.toLong().toString()) }
                var fx by remember { mutableStateOf(s.usdInr.toString()) }
                var daily by remember { mutableStateOf(s.riskLimits.dailyLossPct.toString()) }
                var dd by remember { mutableStateOf(s.riskLimits.maxDrawdownPct.toString()) }
                var orders by remember { mutableStateOf(s.riskLimits.maxOrdersPerDay.toString()) }
                Field(cap, { cap = it; it.toDoubleOrNull()?.let { v -> vm.settings.update { st -> st.copy(paperCapital = v) } } }, "Paper starting capital (₹, applies on reset)", number = true)
                Field(fx, { fx = it; it.toDoubleOrNull()?.let { v -> vm.settings.update { st -> st.copy(usdInr = v) } } }, "USD/INR for crypto paper P&L", number = true)
                Field(daily, { daily = it; it.toDoubleOrNull()?.let { v -> vm.settings.update { st -> st.copy(riskLimits = st.riskLimits.copy(dailyLossPct = v)) } } }, "Daily loss limit % (app)", number = true)
                Field(dd, { dd = it; it.toDoubleOrNull()?.let { v -> vm.settings.update { st -> st.copy(riskLimits = st.riskLimits.copy(maxDrawdownPct = v)) } } }, "Max drawdown % circuit breaker (app)", number = true)
                Field(orders, { orders = it; it.toIntOrNull()?.let { v -> vm.settings.update { st -> st.copy(riskLimits = st.riskLimits.copy(maxOrdersPerDay = v)) } } }, "Max live orders per day", number = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Pre/post-market briefs", Modifier.weight(1f)); Switch(s.preMarketBrief, { v -> vm.settings.update { it.copy(preMarketBrief = v) } }) }
                val c = LocalContext.current
                TextButton(onClick = { Schedulers.scheduleAll(c, s.alertIntervalMin) }) { Text("Re-schedule background alerts") }
                KV("Background check interval", "${s.alertIntervalMin} min (Android minimum 15)")
            }
        }
    }
}
