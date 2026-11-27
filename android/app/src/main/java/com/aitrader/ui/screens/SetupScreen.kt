package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.local.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun finish(demo: Boolean) = viewModelScope.launch { store.finishOnboarding(demo) }
}

/** First launch: real trading through your engine is the default path; demo is opt-in. */
@Composable
fun SetupScreen(onEngine: () -> Unit, onDemo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AI Trader", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Trades through your own engine on a static-IP server, using your broker account.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onEngine, modifier = Modifier.fillMaxWidth().testTag("setupEngine")) {
            Text("Connect my engine (real trading)")
        }
        Text("Enter your engine URL, TLS pins and login. Then connect a broker and switch LIVE on.",
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().testTag("setupDemo")) {
            Text("Explore demo (simulated data)")
        }
        Text("You can switch any time in Settings.", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp))
    }
}
