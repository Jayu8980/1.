package app.sutra.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sutra.R
import app.sutra.data.Rule
import app.sutra.data.SutraStore
import app.sutra.service.SutraService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SutraStore.init(this)
        setContent { SutraTheme { HomeScreen() } }
    }
}

@Composable
fun SutraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFFB68A), secondary = Color(0xFF8FD3C8))
        else -> lightColorScheme(primary = Color(0xFF9A4A12), secondary = Color(0xFF00695F))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val running by SutraStore.running.collectAsStateWithLifecycle()
    val rules by SutraStore.rules.collectAsStateWithLifecycle()
    val log by SutraStore.log.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    running = running,
                    onStart = { SutraService.start(ctx) },
                    onStop = { SutraService.stop(ctx) },
                )
            }
            item { NoPermissionCard() }
            item { SectionTitle(stringResource(R.string.section_rules)) }
            items(Rule.entries, key = { it.id }) { rule ->
                RuleRow(rule, rules[rule] ?: rule.enabledByDefault) { enabled ->
                    SutraStore.setEnabled(rule, enabled)
                    SutraService.refresh(ctx)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.section_log), Modifier.weight(1f))
                    TextButton(onClick = { SutraStore.clearLog() }) { Text(stringResource(R.string.clear)) }
                }
            }
            if (log.isEmpty()) {
                item { Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val container by animateColorAsState(
        if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        label = "status",
    )
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(if (running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (running) R.string.status_running_desc else R.string.status_stopped_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = if (running) onStop else onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (running) R.string.stop_service else R.string.start_service))
            }
        }
    }
}

@Composable
private fun NoPermissionCard() {
    val ctx = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.no_permission_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.no_permission_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                // Opens the system list only; does not request anything. Optional for aggressive OEMs.
                try {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }) {
                Icon(Icons.Rounded.BatterySaver, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.battery_settings))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}

@Composable
private fun RuleRow(rule: Rule, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(rule.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(rule.description), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
