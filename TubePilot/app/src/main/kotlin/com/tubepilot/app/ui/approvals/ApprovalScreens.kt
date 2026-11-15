package com.tubepilot.app.ui.approvals

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.EmptyState
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.KeyValue
import com.tubepilot.app.ui.components.ListField
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.color
import com.tubepilot.app.ui.components.label
import com.tubepilot.app.ui.theme.Danger
import com.tubepilot.app.ui.theme.Success
import com.tubepilot.app.ui.theme.Warning
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.model.Severity
import com.tubepilot.core.model.VideoProject
import com.tubepilot.pipeline.PipelineController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ApprovalInboxViewModel @Inject constructor(projects: ProjectRepository) : ViewModel() {
    val items = projects.observeAll()
        .map { list -> list.filter { it.status == ProjectStatus.WAITING_APPROVAL || it.status == ProjectStatus.BLOCKED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ApprovalInboxScreen(onOpen: (String) -> Unit, vm: ApprovalInboxViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Approval inbox") }) }) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState("All clear", "Scripts and videos that need your decision appear here.") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            p.artifacts.thumbnails?.selected?.let { t ->
                                AsyncImage(model = File(t.path), contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(p.artifacts.seo?.title ?: p.title, style = MaterialTheme.typography.titleSmall)
                                Text(when {
                                    p.status == ProjectStatus.BLOCKED -> "Blocked at ${p.stage.label}"
                                    p.stage == PipelineStage.SCRIPT_APPROVAL -> "Script ready for review"
                                    else -> "Video ready to publish"
                                }, style = MaterialTheme.typography.bodySmall)
                                StatusPill(p.status.label(), p.status.color())
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class ApprovalDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    projects: ProjectRepository,
    private val controller: PipelineController,
) : ViewModel() {
    private val id: String = saved.get<String>("id").orEmpty()
    val project = projects.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun approve(edited: SeoPackage?, thumbnail: Int, done: () -> Unit) = viewModelScope.launch {
        if (edited != null) controller.editPublishDetails(id, edited, thumbnail)
        controller.approve(id)
        done()
    }
    fun requestChanges(notes: String, done: () -> Unit) = viewModelScope.launch { controller.requestChanges(id, notes); done() }
    fun reject(reason: String, done: () -> Unit) = viewModelScope.launch { controller.reject(id, reason); done() }
    fun retry(done: () -> Unit) = viewModelScope.launch { controller.retry(id); done() }
    fun override(justification: String, done: () -> Unit) = viewModelScope.launch { controller.overrideBlock(id, justification); done() }
}

@Composable
fun ApprovalDetailScreen(@Suppress("UNUSED_PARAMETER") projectId: String, onBack: () -> Unit, vm: ApprovalDetailViewModel = hiltViewModel()) {
    val project by vm.project.collectAsStateWithLifecycle()
    val p = project
    Scaffold(topBar = { BackTopBar("Review", onBack) }) { padding ->
        if (p == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                p.status == ProjectStatus.BLOCKED -> BlockedPanel(p, vm, onBack)
                p.stage == PipelineStage.SCRIPT_APPROVAL -> ScriptReview(p, vm, onBack)
                p.stage == PipelineStage.PUBLISH_APPROVAL -> PublishReview(p, vm, onBack)
                else -> Text("This project is not waiting for a decision (${p.stage.label}, ${p.status.label()}).")
            }
        }
    }
}

@Composable
private fun DecisionButtons(onApprove: () -> Unit, onChanges: (String) -> Unit, onReject: (String) -> Unit, approveLabel: String) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(approveLabel) }
        OutlinedButton(onClick = { dialog = "changes" }, modifier = Modifier.weight(1f)) { Text("Changes") }
        OutlinedButton(onClick = { dialog = "reject" }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Reject") }
    }
    dialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (kind == "changes") "What should change?" else "Reject this video?") },
            text = { OutlinedTextField(text, { text = it }, label = { Text(if (kind == "changes") "Notes for the agents" else "Reason (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) },
            confirmButton = {
                Button(onClick = { if (kind == "changes") onChanges(text) else onReject(text); dialog = null }, enabled = kind == "reject" || text.isNotBlank()) {
                    Text(if (kind == "changes") "Send back" else "Reject")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScriptReview(p: VideoProject, vm: ApprovalDetailViewModel, onBack: () -> Unit) {
    val script = p.artifacts.script ?: return Text("Script missing")
    Text(script.title, style = MaterialTheme.typography.headlineSmall)
    Text("${script.format.label} · ${script.language.displayName} · ~${script.estimatedDurationSec}s", style = MaterialTheme.typography.bodySmall)
    SectionCard("Hook") { Text(script.hook) }
    SectionCard("Scenes") {
        script.scenes.forEach { s ->
            Text("${s.index + 1}. ${s.narration}", style = MaterialTheme.typography.bodyMedium)
            Text("Visual: ${s.visualPrompt}" + if (s.onScreenText.isNotBlank()) " · Text: ${s.onScreenText}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionCard("Call to action") { Text(script.cta) }
    p.artifacts.brief?.factsToVerify?.takeIf { it.isNotEmpty() }?.let { facts ->
        InfoNote("Facts to double-check: " + facts.joinToString("; "), Warning)
    }
    DecisionButtons(
        onApprove = { vm.approve(null, 0, onBack) },
        onChanges = { vm.requestChanges(it, onBack) },
        onReject = { vm.reject(it, onBack) },
        approveLabel = "Approve script",
    )
}

@Composable
private fun PublishReview(p: VideoProject, vm: ApprovalDetailViewModel, onBack: () -> Unit) {
    val seo = p.artifacts.seo
    var titleIndex by remember(seo) { mutableIntStateOf(seo?.selectedTitleIndex ?: 0) }
    var title by remember(seo) { mutableStateOf(seo?.title.orEmpty()) }
    var description by remember(seo) { mutableStateOf(seo?.description.orEmpty()) }
    var tags by remember(seo) { mutableStateOf(seo?.tags.orEmpty()) }
    var thumb by remember(p.artifacts.thumbnails) { mutableIntStateOf(p.artifacts.thumbnails?.selectedIndex ?: 0) }

    p.artifacts.edit?.primary?.let { VideoPreview(it.videoPath) }
    p.artifacts.edit?.derivedShorts?.takeIf { it.isNotEmpty() }?.let { Text("+ ${it.size} Short(s) cut from this video will be scheduled a day apart.", style = MaterialTheme.typography.bodySmall) }

    p.artifacts.thumbnails?.let { set ->
        SectionCard("Thumbnail") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                set.variants.forEachIndexed { i, v ->
                    Card(border = if (i == thumb) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.clickable { thumb = i }) {
                        AsyncImage(model = File(v.path), contentDescription = v.headline, contentScale = ContentScale.Crop, modifier = Modifier.width(200.dp).aspectRatio(16f / 9f))
                    }
                }
            }
        }
    }
    if (seo != null) {
        SectionCard("Title (A/B variants)") {
            seo.titleVariants.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().clickable { titleIndex = i; title = t }.padding(vertical = 4.dp)) {
                    Text(if (i == titleIndex) "● " else "○ ")
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(title, { title = it.take(100) }, label = { Text("Title (${title.length}/100)") }, modifier = Modifier.fillMaxWidth())
            Text("Other variants rotate every 48 h after publishing; the best performer is kept.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Description") {
            OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
            ListField("Tags", tags, { tags = it })
            Text("Hashtags: ${seo.hashtags.joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
            if (seo.chapters.isNotEmpty()) Text("Chapters: " + seo.chapters.joinToString { "${SeoPackage.formatTimestamp(it.startSec)} ${it.title}" }, style = MaterialTheme.typography.bodySmall)
            if (seo.pinnedComment.isNotBlank()) Text("Comment to post: ${seo.pinnedComment} (pin it in YouTube Studio; the API cannot pin)", style = MaterialTheme.typography.bodySmall)
        }
    }
    p.artifacts.compliance?.let { c ->
        SectionCard("Compliance: ${c.verdict.name.replace('_', ' ').lowercase()}") {
            Text(c.summary, style = MaterialTheme.typography.bodySmall)
            if (c.requiresAiDisclosure) InfoNote("Will be uploaded with YouTube's altered/synthetic content disclosure.")
            c.issues.forEach { i ->
                val col = when (i.severity) { Severity.BLOCKER -> Danger; Severity.WARNING -> Warning; Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant }
                Text("• [${i.category.name.lowercase()}] ${i.detail}" + if (i.fix.isNotBlank()) " — ${i.fix}" else "", color = col, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    p.artifacts.qa?.let { qa ->
        SectionCard("QA: ${if (qa.passed) "passed" else "failed"}") {
            qa.checks.forEach { c -> Text((if (c.passed) "✓ " else "✗ ") + "${c.name}: ${c.detail}", style = MaterialTheme.typography.bodySmall, color = if (c.passed) Success else Danger) }
        }
    }
    InfoNote("Uploads from an unverified Google Cloud project may stay private until the project passes YouTube's API compliance audit.")
    DecisionButtons(
        onApprove = {
            val edited = seo?.let { s ->
                val variants = s.titleVariants.toMutableList().also { list -> if (titleIndex in list.indices) list[titleIndex] = title else list.add(0, title) }
                s.copy(titleVariants = variants, selectedTitleIndex = titleIndex.coerceIn(0, variants.lastIndex), description = description, tags = tags)
            }
            vm.approve(edited, thumb, onBack)
        },
        onChanges = { vm.requestChanges(it, onBack) },
        onReject = { vm.reject(it, onBack) },
        approveLabel = "Approve & schedule",
    )
}

@Composable
private fun BlockedPanel(p: VideoProject, vm: ApprovalDetailViewModel, onBack: () -> Unit) {
    var justification by remember { mutableStateOf("") }
    Text(p.title, style = MaterialTheme.typography.headlineSmall)
    InfoNote(p.lastError ?: "Blocked", Danger)
    p.artifacts.compliance?.issues?.filter { it.severity == Severity.BLOCKER }?.forEach { Text("• ${it.detail} — ${it.fix}", style = MaterialTheme.typography.bodySmall) }
    SectionCard("What you can do") {
        KeyValue("Stage", p.stage.label)
        Text("Fix the cause (for example, change the strategy, reconnect YouTube or edit the script) and retry. Overriding a compliance or QA block is your responsibility and is recorded in the audit log.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.retry(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Retry this stage") }
        OutlinedButton(onClick = { vm.requestChanges("Blocked at ${p.stage.label}: ${p.lastError}", onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Rewrite script") }
        if (p.stage == PipelineStage.COMPLIANCE || p.stage == PipelineStage.QA) {
            OutlinedTextField(justification, { justification = it }, label = { Text("Justification for override") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.override(justification, onBack) }, enabled = justification.length >= 10, colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger), modifier = Modifier.fillMaxWidth()) {
                Text("Override block")
            }
        }
        OutlinedButton(onClick = { vm.reject("Rejected after block", onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Reject") }
    }
}

/** Inline Media3 player for the rendered video. */
@Composable
fun VideoPreview(path: String) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(path) { if (!File(path).isFile) player.stop() }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
    )
}
