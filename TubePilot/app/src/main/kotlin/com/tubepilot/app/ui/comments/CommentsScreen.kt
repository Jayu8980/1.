package com.tubepilot.app.ui.comments

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.agents.AgentTeam
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.EmptyState
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.components.formatTime
import com.tubepilot.app.ui.theme.Danger
import com.tubepilot.app.ui.theme.Success
import com.tubepilot.app.ui.theme.Warning
import com.tubepilot.core.domain.CommentFilter
import com.tubepilot.core.domain.CommentRepository
import com.tubepilot.core.model.CommentCategory
import com.tubepilot.core.model.CommentItem
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.pipeline.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommentsViewModel @Inject constructor(
    private val comments: CommentRepository,
    private val team: AgentTeam,
    private val scheduler: PipelineScheduler,
) : ViewModel() {
    val filter = MutableStateFlow(setOf(CommentStatus.DRAFTED, CommentStatus.FLAGGED))
    val items = filter.flatMapLatest { comments.observe(CommentFilter(statuses = it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    fun toggle(status: CommentStatus) { filter.value = if (status in filter.value) filter.value - status else filter.value + status }

    fun send(c: CommentItem, text: String) = viewModelScope.launch {
        runCatching { team.community.sendReply(c.commentId, text) }
            .onSuccess { _messages.emit("Reply posted") }
            .onFailure { _messages.emit("Could not post: ${it.message}") }
    }

    fun hide(c: CommentItem) = viewModelScope.launch {
        runCatching { team.community.hide(c.commentId) }.onFailure { _messages.emit("Could not hide: ${it.message}") }
    }

    fun ignore(c: CommentItem) = viewModelScope.launch { team.community.ignore(c.commentId) }
    fun checkNow() = scheduler.runNow(PipelineScheduler.PeriodicKind.COMMENTS)
}

@Composable
fun CommentsScreen(onBack: () -> Unit, vm: CommentsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = { BackTopBar("Comments inbox", onBack) { TextButton(onClick = vm::checkNow) { Text("Check now") } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CommentStatus.entries.forEach { s ->
                    FilterChip(selected = s in filter, onClick = { vm.toggle(s) }, label = { Text(s.name.lowercase()) })
                }
            }
            if (items.isEmpty()) {
                EmptyState("Nothing here", "Drafted replies and flagged comments appear here. The Community Manager checks every 6 hours.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.commentId }) { c -> CommentCard(c, vm) }
                }
            }
        }
    }
}

@Composable
private fun CommentCard(c: CommentItem, vm: CommentsViewModel) {
    var reply by remember(c.commentId, c.draftReply) { mutableStateOf(c.draftReply.orEmpty()) }
    val categoryColor = when (c.category) {
        CommentCategory.SPAM, CommentCategory.TOXIC -> Danger
        CommentCategory.SENSITIVE -> Warning
        CommentCategory.QUESTION -> MaterialTheme.colorScheme.tertiary
        CommentCategory.PRAISE -> Success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill((c.category?.name ?: "new").lowercase(), categoryColor)
            }
            Text("on “${c.videoTitle.ifBlank { c.videoId }}” · ${formatTime(c.publishedAt)}", style = MaterialTheme.typography.labelSmall)
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
            c.flagReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warning) }
            if (c.status != CommentStatus.HIDDEN && c.status != CommentStatus.REPLIED && c.category != CommentCategory.SPAM) {
                OutlinedTextField(reply, { reply = it }, label = { Text("Reply") }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.status != CommentStatus.REPLIED && c.status != CommentStatus.HIDDEN) {
                    Button(onClick = { vm.send(c, reply) }, enabled = reply.isNotBlank()) { Text("Send") }
                    OutlinedButton(onClick = { vm.hide(c) }) { Text("Hide") }
                    TextButton(onClick = { vm.ignore(c) }) { Text("Ignore") }
                } else {
                    Text(c.status.name.lowercase(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
