package com.tubepilot.app.ui.skills

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.LabeledSwitch
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.StatusPill
import com.tubepilot.app.ui.theme.Success
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.util.PrettyJson
import com.tubepilot.skills.Skill
import com.tubepilot.skills.SkillAssignment
import com.tubepilot.skills.SkillManifest
import com.tubepilot.skills.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val registry: SkillRegistry,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val assignments = registry.observeAssignments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init { viewModelScope.launch { registry.ensureLoaded() } }

    fun toggle(skillId: String, role: AgentRole, enabled: Boolean) = viewModelScope.launch { registry.setEnabled(skillId, role, enabled) }

    fun import(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val (name, bytes) = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "skill"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
                name to bytes
            }
            registry.import(bytes, name)
        }
        _messages.emit(result.fold({ "Imported ${it.manifest.name} (disabled agents can be enabled below)" }, { "Import failed: ${it.message}" }))
    }

    fun remove(skillId: String) = viewModelScope.launch {
        runCatching { registry.remove(skillId) }.onFailure { _messages.emit(it.message ?: "Cannot remove") }
    }
}

@Composable
fun SkillsScreen(onBack: () -> Unit, vm: SkillsViewModel = hiltViewModel()) {
    val assignments by vm.assignments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var details by remember { mutableStateOf<Skill?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val bySkill = assignments.groupBy { it.skill.id }

    Scaffold(
        topBar = {
            BackTopBar("Skills store", onBack) {
                IconButton(onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Import skill")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                InfoNote("Import a .zip (skill.json + prompt.md) or a single JSON skill. Imported skills are sandboxed: prompt-only, with LLM and memory-read permissions, so they can never upload, post or touch files.")
            }
            items(bySkill.values.toList(), key = { it.first().skill.id }) { list -> SkillCard(list, vm, onDetails = { details = it }) }
        }
    }
    details?.let { skill -> SkillDetailsDialog(skill) { details = null } }
}

@Composable
private fun SkillCard(list: List<SkillAssignment>, vm: SkillsViewModel, onDetails: (Skill) -> Unit) {
    val skill = list.first().skill
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.manifest.name, style = MaterialTheme.typography.titleMedium)
                Text("${skill.id} · v${skill.manifest.version} · ${skill.manifest.author}", style = MaterialTheme.typography.labelSmall)
            }
            StatusPill(if (skill.builtIn) "Built-in" else "Imported", if (skill.builtIn) MaterialTheme.colorScheme.primary else Success)
        }
        Text(skill.manifest.description, style = MaterialTheme.typography.bodySmall)
        Text("Permissions: " + skill.manifest.permissions.joinToString { it.label }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { a -> LabeledSwitch(a.role.displayName, a.enabled, { vm.toggle(skill.id, a.role, it) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDetails(skill) }) { Text("View prompt") }
            if (!skill.builtIn) OutlinedButton(onClick = { vm.remove(skill.id) }) { Text("Remove") }
        }
    }
}

@Composable
private fun SkillDetailsDialog(skill: Skill, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(skill.manifest.name) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Prompt template", style = MaterialTheme.typography.labelLarge)
                Text(skill.promptTemplate, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("skill.json", style = MaterialTheme.typography.labelLarge)
                Text(PrettyJson.encodeToString(SkillManifest.serializer(), skill.manifest), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
    )
}
