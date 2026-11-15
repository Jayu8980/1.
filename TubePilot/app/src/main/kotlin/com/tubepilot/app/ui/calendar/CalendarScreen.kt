package com.tubepilot.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.agents.AgentTeam
import com.tubepilot.agents.contracts.PlanRequest
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.formatDate
import com.tubepilot.app.ui.components.formatTime
import com.tubepilot.app.ui.theme.Success
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.CalendarStatus
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.pipeline.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendar: CalendarRepository,
    private val memory: MemoryRepository,
    private val projects: ProjectRepository,
    private val team: AgentTeam,
    private val scheduler: PipelineScheduler,
) : ViewModel() {
    private val _weekStart = MutableStateFlow(0L)
    val weekStart = _weekStart.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    val entries = _weekStart.flatMapLatest { start -> calendar.observeRange(start, start + 7 * TimeUtil.DAY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { _weekStart.value = TimeUtil.startOfWeekMs(System.currentTimeMillis(), TimeUtil.zoneOrDefault(memory.profile().timezone)) }
    }

    fun shiftWeek(weeks: Int) { _weekStart.value += weeks * 7 * TimeUtil.DAY }

    fun plan(replace: Boolean) = viewModelScope.launch {
        _busy.value = true
        runCatching { team.orchestrator.planWeek(PlanRequest(_weekStart.value, replaceUnstarted = replace)) }
            .onSuccess { plan -> _messages.emit("Planned ${plan.entries.size} slot(s)" + if (plan.removed.isNotEmpty()) "; dropped ${plan.removed.size} conflicting idea(s)" else "") }
            .onFailure { _messages.emit("Planning failed: ${it.message}") }
        _busy.value = false
    }

    /** Starts production of a planned slot right away instead of waiting for its lead time. */
    fun startNow(entry: CalendarEntry) = viewModelScope.launch {
        val profile = memory.profile()
        val project = projects.create(entry.topic, entry.format, profile.primaryLanguage, entry.id, entry.slotStart.takeIf { it > System.currentTimeMillis() + TimeUtil.HOUR })
        projects.update(project.id) { it.copy(artifacts = ProjectArtifacts(idea = TopicIdea(entry.topic, entry.angle, entry.keywords, entry.format, entry.pillar))) }
        calendar.upsert(listOf(entry.copy(status = CalendarStatus.IN_PRODUCTION, projectId = project.id)))
        scheduler.enqueue(project.id, project.stage, append = false)
        _messages.emit("Production started")
    }

    fun skip(entry: CalendarEntry) = viewModelScope.launch { calendar.upsert(listOf(entry.copy(status = CalendarStatus.SKIPPED))) }
}

@Composable
fun CalendarScreen(onOpenProject: (String) -> Unit, vm: CalendarViewModel = hiltViewModel()) {
    val week by vm.weekStart.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (week > 0) "Week of ${formatDate(week)}" else "Calendar") },
                actions = {
                    IconButton(onClick = { vm.shiftWeek(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
                    IconButton(onClick = { vm.shiftWeek(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.plan(replace = false) }, enabled = !busy) { Text(if (entries.isEmpty()) "Plan this week" else "Fill gaps") }
                    OutlinedButton(onClick = { vm.plan(replace = true) }, enabled = !busy && entries.isNotEmpty()) { Text("Re-plan") }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
                }
            }
            if (entries.isEmpty() && !busy) item {
                Text("No slots planned. The Channel Manager plans automatically twice a day, or tap “Plan this week”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(e.slotStart), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            StatusPill(e.status.name.lowercase().replace('_', ' '), if (e.status == CalendarStatus.PUBLISHED) Success else MaterialTheme.colorScheme.primary)
                        }
                        Text(e.topic, style = MaterialTheme.typography.titleSmall)
                        if (e.angle.isNotBlank()) Text(e.angle, style = MaterialTheme.typography.bodySmall)
                        Text("${e.format.label}${if (e.pillar.isNotBlank()) " · ${e.pillar}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                e.projectId != null -> TextButton(onClick = { onOpenProject(e.projectId!!) }) { Text("Open project") }
                                e.status == CalendarStatus.PLANNED -> {
                                    TextButton(onClick = { vm.startNow(e) }) { Text("Start now") }
                                    TextButton(onClick = { vm.skip(e) }) { Text("Skip") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
