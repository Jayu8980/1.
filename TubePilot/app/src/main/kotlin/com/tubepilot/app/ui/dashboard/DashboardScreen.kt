package com.tubepilot.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tubepilot.app.ui.components.Dropdown
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.UsageBar
import com.tubepilot.app.ui.components.color
import com.tubepilot.app.ui.components.formatTime
import com.tubepilot.app.ui.components.label
import com.tubepilot.app.ui.components.money
import com.tubepilot.app.ui.navigation.Routes
import com.tubepilot.app.ui.theme.Danger
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.CommentFilter
import com.tubepilot.core.domain.CommentRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.OperatingMode
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.UsageSummary
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.pipeline.PipelineController
import com.tubepilot.pipeline.PipelineScheduler
import com.tubepilot.youtube.auth.YouTubeAuthManager
import com.tubepilot.youtube.quota.BucketStatus
import com.tubepilot.youtube.quota.QuotaManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodayTask(val title: String, val detail: String, val route: String?)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    projects: ProjectRepository,
    calendar: CalendarRepository,
    comments: CommentRepository,
    usage: UsageRepository,
    quota: QuotaManager,
    auth: YouTubeAuthManager,
    private val settingsRepo: SettingsRepository,
    private val controller: PipelineController,
    private val scheduler: PipelineScheduler,
) : ViewModel() {
    private val now = System.currentTimeMillis()

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val projects = projects.observeAll()
        .map { list -> list.filter { !it.status.isTerminal || it.updatedAt > now - 3 * TimeUtil.DAY } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val running = scheduler.observeRunning().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val quota = quota.observeStatus().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val spend = usage.observeSummary(TimeUtil.startOfMonthMs(now)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val connected = auth.connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val today = combine(
        projects.observeAll(),
        calendar.observeRange(now - TimeUtil.HOUR, now + 2 * TimeUtil.DAY),
        comments.observe(CommentFilter(statuses = setOf(CommentStatus.FLAGGED, CommentStatus.DRAFTED))),
    ) { list, entries: List<CalendarEntry>, flagged ->
        buildList {
            list.filter { it.status == ProjectStatus.WAITING_APPROVAL }.forEach { add(TodayTask("Review: ${it.title}", it.stage.label, Routes.approval(it.id))) }
            list.filter { it.status == ProjectStatus.BLOCKED || it.status == ProjectStatus.FAILED }.forEach { add(TodayTask("Fix: ${it.title}", it.lastError.orEmpty().take(120), Routes.project(it.id))) }
            if (flagged.isNotEmpty()) add(TodayTask("${flagged.size} comment(s) to review", "Drafted replies and flagged comments", Routes.COMMENTS))
            entries.forEach { add(TodayTask("${it.format.label}: ${it.topic}", "Publishes ${formatTime(it.slotStart)} · ${it.status.name.lowercase()}", it.projectId?.let(Routes::project))) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setKillSwitch(on: Boolean) = viewModelScope.launch { controller.setKillSwitch(on) }
    fun resumeBudget() = viewModelScope.launch { controller.resumeAfterBudget() }
    fun runNow(kind: PipelineScheduler.PeriodicKind) = scheduler.runNow(kind)
    fun create(title: String, angle: String, format: VideoFormat, language: ContentLanguage) = viewModelScope.launch {
        controller.createManualProject(title, angle, format, language)
    }
}

@Composable
fun DashboardScreen(nav: NavController, vm: DashboardViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val spend by vm.spend.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TubePilot") }, actions = { TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text(if (settings.mode == OperatingMode.AUTO) "AUTO" else "APPROVAL") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New video") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { KillSwitchCard(settings, vm::setKillSwitch, vm::resumeBudget) }
            if (!connected) item {
                InfoNote("YouTube is not connected, so research, uploads and comments are limited. Connect your channel in Settings.", MaterialTheme.colorScheme.error)
            }
            item {
                SectionCard("Today") {
                    if (today.isEmpty()) Text("Nothing needs you right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    today.take(8).forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = t.route != null) { t.route?.let(nav::navigate) }.padding(vertical = 4.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
            item { SpendCard(settings, spend) }
            item { QuotaCard(quota) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.runNow(PipelineScheduler.PeriodicKind.PLAN) }, modifier = Modifier.weight(1f)) { Text("Plan now") }
                    OutlinedButton(onClick = { vm.runNow(PipelineScheduler.PeriodicKind.COMMENTS) }, modifier = Modifier.weight(1f)) { Text("Comments") }
                    OutlinedButton(onClick = { vm.runNow(PipelineScheduler.PeriodicKind.ANALYTICS) }, modifier = Modifier.weight(1f)) { Text("Report") }
                }
            }
            item { Text("Pipeline", style = MaterialTheme.typography.titleMedium) }
            if (projects.isEmpty()) item {
                Text("No videos in production. Tap “New video”, or “Plan now” to let the Channel Manager plan the week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(projects, key = { it.id }) { p -> ProjectRow(p, p.id in running) { nav.navigate(Routes.project(p.id)) } }
            item { Spacer(Modifier.padding(32.dp)) }
        }
    }
    if (showNew) NewProjectDialog(onDismiss = { showNew = false }) { t, a, f, l -> vm.create(t, a, f, l); showNew = false }
}

@Composable
private fun KillSwitchCard(settings: AppSettings, onToggle: (Boolean) -> Unit, onResumeBudget: () -> Unit) {
    val paused = settings.killSwitch
    Card(colors = CardDefaults.cardColors(containerColor = if (paused) Danger.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (paused) "All agents paused" else "Agents running", style = MaterialTheme.typography.titleMedium)
                    Text(if (paused) "Nothing will be generated, uploaded or posted." else "Kill switch: pause every agent instantly.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = !paused, onCheckedChange = { onToggle(!it) })
            }
            if (settings.budgetPaused) {
                Spacer(Modifier.padding(4.dp))
                InfoNote("Monthly LLM budget reached; agents are auto-paused. Raise the cap in Settings, then resume.", Danger)
                Button(onClick = onResumeBudget, colors = ButtonDefaults.buttonColors()) { Text("Resume after budget change") }
            }
        }
    }
}

@Composable
private fun SpendCard(settings: AppSettings, spend: UsageSummary?) {
    SectionCard("Spending this month") {
        val spent = spend?.totalCostUsd ?: 0.0
        UsageBar("LLM cost", (spent / settings.monthlyBudgetUsd.coerceAtLeast(0.01)).toFloat(), "${money(spent)} of ${money(settings.monthlyBudgetUsd)}")
        Text("${spend?.calls ?: 0} calls · ${(spend?.inputTokens ?: 0) / 1000}k in / ${(spend?.outputTokens ?: 0) / 1000}k out tokens", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QuotaCard(buckets: List<BucketStatus>) {
    SectionCard("YouTube API quota today") {
        buckets.forEach { b -> UsageBar(b.bucket.label, b.fractionUsed, "${b.used} / ${b.bucket.dailyLimit}") }
        buckets.firstOrNull()?.let { Text("Resets ${formatTime(it.resetAtMs)} (midnight Pacific). Uploads queue automatically when quota is low.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ProjectRow(p: VideoProject, running: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                StatusPill(if (running) "Working" else p.status.label(), p.status.color())
            }
            Text("${p.format.label} · ${p.stage.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(3.dp))
            LinearProgressIndicator(progress = { p.stage.ordinal / PipelineStage.COMPLETE.ordinal.toFloat() }, modifier = Modifier.fillMaxWidth())
            p.lastError?.takeIf { p.status != ProjectStatus.DONE }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String, VideoFormat, ContentLanguage) -> Unit) {
    var title by remember { mutableStateOf("") }
    var angle by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(VideoFormat.SHORT) }
    var language by remember { mutableStateOf(ContentLanguage.ENGLISH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(angle, { angle = it }, label = { Text("Angle (optional)") }, modifier = Modifier.fillMaxWidth())
                Dropdown("Format", VideoFormat.entries, format, { it.label }, { format = it })
                Dropdown("Language", ContentLanguage.entries, language, { it.displayName }, { language = it })
            }
        },
        confirmButton = { Button(onClick = { onCreate(title, angle, format, language) }, enabled = title.isNotBlank()) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hub for the secondary screens. */
@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    data class Entry(val title: String, val subtitle: String, val icon: ImageVector, val route: String)
    val entries = listOf(
        Entry("Comments inbox", "Drafted replies, flagged and hidden comments", Icons.AutoMirrored.Filled.Chat, Routes.COMMENTS),
        Entry("Analytics & weekly report", "CTR, retention, lessons learned", Icons.AutoMirrored.Filled.ShowChart, Routes.ANALYTICS),
        Entry("Skills store", "Enable, disable and import skills", Icons.Filled.Widgets, Routes.SKILLS),
        Entry("Channel strategy", "Niche, audience, schedule, style guide", Icons.Filled.Tune, Routes.STRATEGY),
        Entry("Settings", "API keys, models, budget, mode, quota", Icons.Filled.Settings, Routes.SETTINGS),
        Entry("Audit log", "Every action, by every agent", Icons.AutoMirrored.Filled.ListAlt, Routes.AUDIT),
    )
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(entries) { e ->
                ListItem(
                    headlineContent = { Text(e.title) },
                    supportingContent = { Text(e.subtitle) },
                    leadingContent = { Icon(e.icon, null) },
                    modifier = Modifier.clickable { onNavigate(e.route) },
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoNote("TubePilot uses only the official YouTube Data and Analytics APIs. Every video is original or transformed content; it never re-uploads other people's work.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.VerifiedUser, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Secrets are encrypted with the Android Keystore.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("AI disclosure is set automatically for realistic synthetic content.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Compliance can block publishing; overrides are logged.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
