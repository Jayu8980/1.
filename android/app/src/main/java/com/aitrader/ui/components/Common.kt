package com.aitrader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.TradingMode
import com.aitrader.data.repo.Loaded
import com.aitrader.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val loaded: Loaded<T>) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

/** Base VM: loads (optionally polls) one resource and exposes a one-shot message channel. */
abstract class ResourceViewModel<T>(private val pollMs: Long? = null) : ViewModel() {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _msg.asStateFlow()
    private var job: Job? = null

    protected abstract suspend fun fetch(): Loaded<T>

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            do {
                refreshOnce()
                pollMs?.let { delay(it) }
            } while (pollMs != null && isActive)
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun refresh() { viewModelScope.launch { refreshOnce() } }

    private suspend fun refreshOnce() {
        _state.value = try { UiState.Ready(fetch()) } catch (e: Exception) {
            if (_state.value is UiState.Ready) _state.value.also { _msg.value = e.message } else UiState.Failed(e.message ?: "error")
        }
    }

    protected fun say(m: String) { _msg.value = m }
    fun consumeMessage() { _msg.value = null }
}

/** Starts polling while the screen is visible. */
@Composable
fun <T> Bind(vm: ResourceViewModel<T>) {
    DisposableEffect(vm) { vm.start(); onDispose { vm.stop() } }
}

@Composable
fun <T> StateContent(state: UiState<T>, onRetry: () -> Unit, content: @Composable (Loaded<T>) -> Unit) {
    when (state) {
        UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is UiState.Failed -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
        is UiState.Ready -> Column {
            if (state.loaded.fromCache) StaleBanner(state.loaded)
            content(state.loaded)
        }
    }
}

@Composable
fun StaleBanner(l: Loaded<*>) {
    Surface(color = Warn.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Text("Offline — cached ${Fmt.ago(l.updatedAt)}${l.error?.let { " · $it" } ?: ""}",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = Warn)
    }
}

@Composable
fun MessageSnackbar(vm: ResourceViewModel<*>, host: SnackbarHostState) {
    val msg by vm.message.collectAsStateCompat()
    LaunchedEffect(msg) { msg?.let { host.showSnackbar(it); vm.consumeMessage() } }
}

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsStateWithLifecycle()

@Composable
fun ModeBadge(mode: TradingMode, demo: Boolean, modifier: Modifier = Modifier) {
    val (label, color) = when {
        demo -> "DEMO · PAPER" to PaperBlue
        mode == TradingMode.LIVE -> "LIVE" to LiveRed
        else -> "PAPER" to PaperBlue
    }
    Text(label, modifier.testTag("modeBadge").background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
fun pnlColor(v: Double): Color = when { v > 0 -> Profit; v < 0 -> Loss; else -> MaterialTheme.colorScheme.onSurface }

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(text, Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false,
                  onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = {
            Button(onClick = { onDismiss(); onConfirm() },
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = Loss) else ButtonDefaults.buttonColors()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
