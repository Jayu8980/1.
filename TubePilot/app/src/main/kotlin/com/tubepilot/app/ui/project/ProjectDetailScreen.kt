package com.tubepilot.app.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.agents.AuditRow
import com.tubepilot.app.ui.approvals.VideoPreview
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.KeyValue
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.color
import com.tubepilot.app.ui.components.formatTime
import com.tubepilot.app.ui.components.label
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.pipeline.PipelineController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    projects: ProjectRepository,
    audit: AuditLog,
    private val controller: PipelineController,
) : ViewModel() {
    val id: String = saved.get<String>("id").orEmpty()
    val project = projects.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val log = audit.observeProject(id, 200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry() = viewModelScope.launch { controller.retry(id) }
    fun delete(done: () -> Unit) = viewModelScope.launch { controller.delete(id); done() }
}

@Composable
fun ProjectDetailScreen(@Suppress("UNUSED_PARAMETER") projectId: String, onBack: () -> Unit, onReview: (String) -> Unit, vm: ProjectDetailViewModel = hiltViewModel()) {
    val project by vm.project.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val p = project
    Scaffold(topBar = { BackTopBar(p?.title ?: "Project", onBack) }) { padding ->
        if (p == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Row { StatusPill(p.status.label(), p.status.color()) }
                KeyValue("Format", p.format.label)
                KeyValue("Language", p.language.displayName)
                KeyValue("Stage", "${p.stage.label} (${p.stage.ordinal + 1}/${PipelineStage.entries.size})")
                KeyValue("Created", formatTime(p.createdAt))
                KeyValue("Slot", p.scheduledPublishAt?.let(::formatTime) ?: "next preferred hour")
                p.lastError?.let { InfoNote(it, MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.status == ProjectStatus.WAITING_APPROVAL || p.status == ProjectStatus.BLOCKED) Button(onClick = { onReview(p.id) }) { Text("Review") }
                    if (p.status == ProjectStatus.FAILED || p.status == ProjectStatus.BLOCKED) OutlinedButton(onClick = vm::retry) { Text("Retry") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
            SectionCard("Pipeline") {
                PipelineStage.entries.filter { it != PipelineStage.COMPLETE }.forEach { st ->
                    val mark = when {
                        st.ordinal < p.stage.ordinal || p.stage == PipelineStage.COMPLETE -> "✓"
                        st == p.stage -> "▶"
                        else -> "·"
                    }
                    Text("$mark ${st.label}" + (st.owner?.let { " — ${it.displayName}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            val a = p.artifacts
            a.edit?.primary?.let { VideoPreview(it.videoPath) }
            a.idea?.let { SectionCard("Idea") { Text(it.title); if (it.angle.isNotBlank()) Text(it.angle, style = MaterialTheme.typography.bodySmall); Text("Score %.1f".format(it.totalScore), style = MaterialTheme.typography.labelSmall) } }
            a.brief?.let { b -> SectionCard("Research brief") { Text("Angle: ${b.uniqueAngle}"); b.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
            a.script?.let { sc -> SectionCard("Script (${sc.scenes.size} scenes, ~${sc.estimatedDurationSec}s)") { Text(sc.hook, style = MaterialTheme.typography.bodyMedium); Text(sc.cta, style = MaterialTheme.typography.bodySmall) } }
            a.voice?.let { v -> SectionCard("Audio") { KeyValue("Engine", v.engine); KeyValue("Duration", "${v.durationMs / 1000}s"); KeyValue("Loudness", "%.1f LUFS, peak %.1f dBFS".format(v.integratedLufs, v.peakDbfs)); KeyValue("Music", v.musicTrack ?: "none") } }
            a.uploads.takeIf { it.isNotEmpty() }?.let { ups ->
                SectionCard("Uploads") {
                    ups.forEach { u ->
                        Text("${u.title} — youtu.be/${u.videoId}", style = MaterialTheme.typography.bodyMedium)
                        Text("${u.privacyStatus}${u.publishAtIso?.let { " · publishes $it" } ?: ""} · thumbnail ${if (u.thumbnailSet) "set" else "not set"} · captions ${if (u.captionsUploaded) "yes" else "no"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            a.uploadSessions.forEach { s -> InfoNote("Upload in progress: ${s.confirmedBytes / 1_048_576}/${s.totalBytes / 1_048_576} MB confirmed; resumes automatically.") }
            a.titleTest?.let { t -> SectionCard("Title A/B test") { t.variants.forEachIndexed { i, v -> Text("${if (i == t.activeIndex) "▶" else "·"} $v — ${t.viewsPerVariant.getOrNull(i) ?: 0} views", style = MaterialTheme.typography.bodySmall) } } }
            SectionCard("Activity") { log.take(60).forEach { AuditRow(it) } }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("Local files and history are removed. Videos already on YouTube are not affected.") },
            confirmButton = { Button(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
