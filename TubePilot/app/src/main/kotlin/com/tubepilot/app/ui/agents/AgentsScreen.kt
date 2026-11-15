package com.tubepilot.app.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.agents.AgentTeam
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.KeyValue
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.StatusDot
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.color
import com.tubepilot.app.ui.components.formatTime
import com.tubepilot.app.ui.theme.Danger
import com.tubepilot.app.ui.theme.Success
import com.tubepilot.app.ui.theme.Warning
import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.SkillStateRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.AuditEvent
import com.tubepilot.core.model.AuditLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AgentsViewModel @Inject constructor(status: AgentStatusRepository, settings: SettingsRepository, val team: AgentTeam) : ViewModel() {
    val statuses = status.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
}

@Composable
fun AgentsScreen(onOpen: (String) -> Unit, onAudit: () -> Unit, vm: AgentsViewModel = hiltViewModel()) {
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Agent team") }, actions = { TextButton(onClick = onAudit) { Text("Audit log") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AgentRole.entries) { role ->
                val s = statuses[role]
                val model = settings.modelFor(role)
                Card(Modifier.fillMaxWidth().clickable { onOpen(role.name) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot((s?.state ?: com.tubepilot.core.model.AgentState.IDLE).color())
                            Spacer(Modifier.width(8.dp))
                            Text(role.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text((s?.state?.name ?: "IDLE").lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(role.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s?.currentTask?.let { Text("Now: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        s?.lastError?.takeIf { s.state == com.tubepilot.core.model.AgentState.ERROR }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        }
                        Text("Model: ${model.provider.displayName} · ${model.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    audit: AuditLog,
    skills: SkillStateRepository,
    status: AgentStatusRepository,
    team: AgentTeam,
) : ViewModel() {
    val role: AgentRole = AgentRole.entries.firstOrNull { it.name == saved.get<String>("role") } ?: AgentRole.ORCHESTRATOR
    val agent = team.byRole(role)
    val log = audit.observeActor(role.displayName, 300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calls = skills.observeCalls(300).map { list -> list.filter { it.agent == role } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val status = status.observe().map { it[role] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AgentDetailScreen(@Suppress("UNUSED_PARAMETER") roleName: String, onBack: () -> Unit, vm: AgentDetailViewModel = hiltViewModel()) {
    val log by vm.log.collectAsStateWithLifecycle()
    val calls by vm.calls.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { BackTopBar(vm.role.displayName, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SectionCard(modifier = Modifier.padding(16.dp)) {
                Text(vm.role.summary)
                KeyValue("State", status?.state?.name ?: "IDLE")
                KeyValue("Task", status?.currentTask ?: "—")
                KeyValue("Updated", formatTime(status?.updatedAt ?: 0))
                KeyValue("Skills", vm.agent.skillIds.ifEmpty { listOf("(deterministic agent)") }.joinToString(", "))
                KeyValue("Retries", "${vm.agent.retryPolicy.maxAttempts} attempts, backoff from ${vm.agent.retryPolicy.initialBackoffMs / 1000}s")
                Text("Role prompt", style = MaterialTheme.typography.labelMedium)
                Text(vm.agent.rolePrompt, style = MaterialTheme.typography.bodySmall)
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Activity (${log.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Skill calls (${calls.size})") })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                if (tab == 0) {
                    items(log, key = { it.id }) { AuditRow(it) }
                } else {
                    items(calls, key = { it.id }) { c ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.skillId, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusPill(if (c.success) "ok · ${c.durationMs} ms" else "failed", if (c.success) Success else Danger)
                            }
                            Text(formatTime(c.timestamp), style = MaterialTheme.typography.labelSmall)
                            Text("in: ${c.input}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            (c.output ?: c.error)?.let { Text("out: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                            HorizontalDivider(Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuditRow(e: AuditEvent) {
    val color = when (e.level) {
        AuditLevel.ERROR -> Danger
        AuditLevel.WARNING -> Warning
        AuditLevel.SECURITY -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.action, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.weight(1f))
            Text(formatTime(e.timestamp), style = MaterialTheme.typography.labelSmall)
        }
        Text("${e.actor}: ${e.detail}", style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.padding(top = 5.dp))
    }
}

@HiltViewModel
class AuditViewModel @Inject constructor(audit: AuditLog) : ViewModel() {
    val events = audit.observe(1000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AuditLogScreen(onBack: () -> Unit, vm: AuditViewModel = hiltViewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()
    var levels by remember { mutableStateOf(setOf(AuditLevel.INFO, AuditLevel.WARNING, AuditLevel.ERROR, AuditLevel.SECURITY)) }
    Scaffold(topBar = { BackTopBar("Audit log", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuditLevel.entries.forEach { l ->
                    FilterChip(selected = l in levels, onClick = { levels = if (l in levels) levels - l else levels + l }, label = { Text(l.name.lowercase()) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(events.filter { it.level in levels }, key = { it.id }) { AuditRow(it) }
            }
        }
    }
}
