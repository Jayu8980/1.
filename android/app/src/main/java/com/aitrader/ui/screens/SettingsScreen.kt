package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.BuildConfig
import com.aitrader.data.local.AppSettings
import com.aitrader.data.local.SettingsStore
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: SettingsStore, private val repo: TradingRepository) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val message = MutableStateFlow<String?>(null)
    fun save(demo: Boolean, url: String, pin: String, user: String) = viewModelScope.launch {
        store.update(demo, url, pin, user); message.value = "Saved"
    }
    fun login(user: String, pass: String) = viewModelScope.launch { message.value = repo.login(user, pass).message }
    fun logout() = viewModelScope.launch { repo.logout(); message.value = "Logged out" }
}

@Composable
fun SettingsScreen(snackbar: SnackbarHostState, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateCompat()
    val msg by vm.message.collectAsStateCompat()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    s?.let { SettingsForm(it, vm) }
}

@Composable
private fun SettingsForm(s: AppSettings, vm: SettingsViewModel) {
    var demo by remember { mutableStateOf(s.demoMode) }
    var url by remember { mutableStateOf(s.engineUrl) }
    var pin by remember { mutableStateOf(s.certPin) }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Data source") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo mode")
                    Text("Synthetic on-device data. Paper only; cannot reach a broker.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(demo, { demo = it })
            }
        }
        SectionCard("Cloud engine") {
            OutlinedTextField(url, { url = it }, label = { Text("Engine URL (https://…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(pin, { pin = it }, label = { Text("TLS pins (sha256/…=, comma separated)") }, singleLine = false,
                modifier = Modifier.fillMaxWidth())
            Text("Use your server key pin plus a backup, e.g. Let's Encrypt ISRG Root X1. See infra/README → 'TLS pins'.",
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.save(demo, url, pin, user) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (!demo && (!url.startsWith("https://") || !pin.startsWith("sha256/")))
                Text("Engine mode needs an https URL and a sha256 pin.", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Engine login") {
            Text(if (s.hasToken) "Logged in as ${s.username}" else "Not logged in",
                color = if (s.hasToken) Profit else MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("Logging in registers this phone's biometric-bound signing key with the engine. " +
                "Broker credentials and the TOTP secret stay on the server.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(demo, url, pin, user); vm.login(user, pass); pass = "" },
                    enabled = user.isNotBlank() && pass.isNotBlank() && !demo) { Text("Log in") }
                if (s.hasToken) OutlinedButton(onClick = vm::logout) { Text("Log out") }
            }
        }
        Text("AI Trader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
