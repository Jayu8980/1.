package com.tubepilot.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.Dropdown
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.KeyValue
import com.tubepilot.app.ui.components.LabeledSwitch
import com.tubepilot.app.ui.components.SecretField
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.money
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ImageProviderType
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.ModelChoice
import com.tubepilot.core.model.OperatingMode
import com.tubepilot.core.model.TtsProviderType
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.llm.LlmCall
import com.tubepilot.llm.LlmCallContext
import com.tubepilot.llm.LlmGateway
import com.tubepilot.llm.LlmMessage
import com.tubepilot.llm.ModelCatalog
import com.tubepilot.media.library.AssetLibrary
import com.tubepilot.pipeline.PipelineController
import com.tubepilot.youtube.YouTubeClient
import com.tubepilot.youtube.auth.AuthStart
import com.tubepilot.youtube.auth.YouTubeAuthManager
import com.tubepilot.youtube.quota.QuotaCostTable
import com.tubepilot.youtube.quota.QuotaManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val gateway: LlmGateway,
    private val auth: YouTubeAuthManager,
    private val youtube: YouTubeClient,
    private val quota: QuotaManager,
    private val library: AssetLibrary,
    private val controller: PipelineController,
    private val audit: AuditLog,
    usage: UsageRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val connected = auth.connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val spend = usage.observeSummary(TimeUtil.startOfMonthMs(System.currentTimeMillis())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _keys = MutableStateFlow<Map<SecretKey, Boolean>>(emptyMap())
    val keys = _keys.asStateFlow()
    private val _quotaTable = MutableStateFlow<QuotaCostTable?>(null)
    val quotaTable = _quotaTable.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()
    private val _consent = MutableSharedFlow<IntentSender>()
    val consent = _consent.asSharedFlow()

    init {
        viewModelScope.launch { secrets.version.collect { _keys.value = SecretKey.entries.associateWith { secrets.isSet(it) } } }
        viewModelScope.launch { _quotaTable.value = quota.table() }
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = settingsRepo.current()
        val after = settingsRepo.update(transform)
        if (before.mode != after.mode) audit.record("You", "settings.mode", "Operating mode ${before.mode} → ${after.mode}", level = AuditLevel.SECURITY)
        if (before.autoPostReplies != after.autoPostReplies) audit.record("You", "settings.autopost", "Auto-post replies: ${after.autoPostReplies}", level = AuditLevel.SECURITY)
        if (before.monthlyBudgetUsd != after.monthlyBudgetUsd) audit.record("You", "settings.budget", "Budget ${money(before.monthlyBudgetUsd)} → ${money(after.monthlyBudgetUsd)}", level = AuditLevel.INFO)
    }

    fun saveSecret(key: SecretKey, value: String?) = viewModelScope.launch {
        secrets.put(key, value)
        audit.record("You", "secret.${if (value == null) "removed" else "saved"}", key.label, level = AuditLevel.SECURITY)
    }

    fun testModel(choice: ModelChoice) = viewModelScope.launch {
        val result = runCatching {
            gateway.complete(
                LlmCallContext(null, null, "settings:test"),
                LlmCall(system = "You are a connection test. Reply with the single word OK.", messages = listOf(LlmMessage.user("ping")), maxTokens = 64, modelOverride = choice),
            )
        }
        _messages.emit(result.fold({ "${choice.provider.displayName} replied: ${it.text.take(40)}" }, { "Test failed: ${it.message?.take(160)}" }))
    }

    fun connect() = viewModelScope.launch {
        when (val r = auth.authorize()) {
            AuthStart.Authorized -> refreshChannel()
            is AuthStart.NeedsConsent -> _consent.emit(r.intentSender)
            is AuthStart.Failed -> _messages.emit(r.message)
        }
    }

    fun onConsent(data: Intent?) = viewModelScope.launch {
        if (auth.completeConsent(data)) refreshChannel() else _messages.emit("Permission not granted")
    }

    private suspend fun refreshChannel() {
        runCatching { youtube.myChannel() }.onSuccess { _messages.emit("Connected to ${it.title}") }.onFailure { _messages.emit(it.message ?: "Could not read channel") }
    }

    fun disconnect() = viewModelScope.launch { auth.disconnect(); _messages.emit("YouTube disconnected") }

    fun setFolder(music: Boolean, uri: Uri) = viewModelScope.launch {
        library.persistPermission(uri)
        update { if (music) it.copy(musicFolderUri = uri.toString()) else it.copy(brollFolderUri = uri.toString()) }
        val count = library.list(uri.toString(), if (music) "audio/" else "video/").size
        _messages.emit("$count ${if (music) "music tracks" else "B-roll clips"} found")
    }

    /** Copies a MediaPipe .task/.bin model into app storage (MediaPipe needs a file path). */
    fun importModel(uri: Uri) = viewModelScope.launch {
        _messages.emit("Copying model… this can take a minute")
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "model.task"
                val dir = File(context.filesDir, "models").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: error("Cannot read file")
                dest
            }
        }
        result.onSuccess { f -> update { it.copy(onDeviceModelPath = f.absolutePath) }; _messages.emit("On-device model ready (${f.length() / 1_048_576} MB)") }
            .onFailure { _messages.emit("Import failed: ${it.message}") }
    }

    fun saveQuotaLimit(bucketId: String, limit: Int) = viewModelScope.launch {
        val t = quota.table()
        runCatching { quota.saveTable(t.copy(buckets = t.buckets.map { if (it.id == bucketId) it.copy(dailyLimit = limit) else it })) }
            .onFailure { _messages.emit(it.message ?: "Invalid") }
        _quotaTable.value = quota.table()
    }

    fun refreshQuota(url: String) = viewModelScope.launch {
        runCatching { quota.refreshFromUrl(url) }
            .onSuccess { _quotaTable.value = it; update { s -> s.copy(quotaTableUrl = url) }; _messages.emit("Quota table updated (verified ${it.verifiedOn})") }
            .onFailure { _messages.emit("Refresh failed: ${it.message}") }
    }

    fun resetQuota() = viewModelScope.launch { quota.resetTableToBundled(); _quotaTable.value = quota.table(); _messages.emit("Quota table reset to bundled values") }

    fun resumeBudget() = viewModelScope.launch { controller.resumeAfterBudget() }
    fun setKillSwitch(on: Boolean) = viewModelScope.launch { controller.setKillSwitch(on) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAudit: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val spend by vm.spend.collectAsStateWithLifecycle()
    val table by vm.quotaTable.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.onConsent(it.data) }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> vm.setFolder(true, uri) } }
    val brollPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> vm.setFolder(false, uri) } }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.consent.collect { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }

    Scaffold(topBar = { BackTopBar("Settings", onBack) { TextButton(onClick = onOpenAudit) { Text("Audit log") } } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Operating mode") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OperatingMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = s.mode == m, onClick = { vm.update { it.copy(mode = m) } }, shape = SegmentedButtonDefaults.itemShape(i, OperatingMode.entries.size)) {
                            Text(if (m == OperatingMode.AUTO) "Auto" else "Approval")
                        }
                    }
                }
                if (s.mode == OperatingMode.APPROVAL) {
                    LabeledSwitch("Approve scripts", s.approveScripts, { v -> vm.update { it.copy(approveScripts = v) } })
                    LabeledSwitch("Approve before publishing", s.approveBeforePublish, { v -> vm.update { it.copy(approveBeforePublish = v) } })
                } else {
                    InfoNote("Auto mode publishes without asking. Compliance and QA can still block a video.")
                }
            }
            SectionCard("Safety") {
                LabeledSwitch("Kill switch (pause all agents)", s.killSwitch, { v -> vm.setKillSwitch(v) }, "Stops all queued and running work immediately")
                LabeledSwitch("Auto-post comment replies", s.autoPostReplies, { v -> vm.update { it.copy(autoPostReplies = v) } }, "Off: replies are drafts you approve. Sensitive comments are never auto-answered.")
                LabeledSwitch("Auto-hide spam and toxic comments", s.autoHideSpam, { v -> vm.update { it.copy(autoHideSpam = v) } }, "Hidden as 'held for review' — recoverable in YouTube Studio")
                Dropdown("Privacy for unscheduled uploads", listOf("private", "unlisted", "public"), s.defaultPrivacy, { it }, { v -> vm.update { it.copy(defaultPrivacy = v) } })
                LabeledSwitch("Upload on mobile data", s.allowMeteredUploads, { v -> vm.update { it.copy(allowMeteredUploads = v) } })
            }
            SectionCard("API keys") {
                SecretKey.entries.forEach { key ->
                    SecretField(key.label, keys[key] == true, { vm.saveSecret(key, it) }, { vm.saveSecret(key, null) })
                    HorizontalDivider()
                }
            }
            SectionCard("Models") {
                ModelPicker("Default model", s.defaultModel, { c -> vm.update { it.copy(defaultModel = c) } }, vm::testModel)
                LabeledSwitch("Claude refusal fallback", s.claudeRefusalFallback, { v -> vm.update { it.copy(claudeRefusalFallback = v) } }, "Server-side fallback to another Claude model if a request is declined")
                LabeledSwitch("Fall back to on-device model", s.fallbackToOnDevice, { v -> vm.update { it.copy(fallbackToOnDevice = v) } }, "When the cloud provider is unreachable")
                Text("On-device model: ${s.onDeviceModelPath?.substringAfterLast('/') ?: "none"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Choose MediaPipe model file (.task)") }
                Text("Per-agent models", style = MaterialTheme.typography.titleSmall)
                AgentRole.entries.forEach { role ->
                    val choice = s.agentModels[role]
                    ModelPicker(role.displayName, choice ?: s.defaultModel, { c -> vm.update { it.copy(agentModels = it.agentModels + (role to c)) } }, vm::testModel,
                        inherited = choice == null, onReset = { vm.update { it.copy(agentModels = it.agentModels - role) } })
                }
            }
            SectionCard("Budget") {
                var budgetText by remember(s.monthlyBudgetUsd) { mutableStateOf("%.2f".format(s.monthlyBudgetUsd)) }
                OutlinedTextField(budgetText, { budgetText = it }, label = { Text("Monthly cap (USD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { budgetText.toDoubleOrNull()?.takeIf { it > 0 }?.let { b -> vm.update { it.copy(monthlyBudgetUsd = b) } } }) { Text("Save cap") }
                    if (s.budgetPaused) OutlinedButton(onClick = vm::resumeBudget) { Text("Resume agents") }
                }
                Text("Spent this month: ${money(spend?.totalCostUsd ?: 0.0)}. Agents pause automatically at the cap.", style = MaterialTheme.typography.bodySmall)
                spend?.byAgent?.entries?.sortedByDescending { it.value }?.forEach { (role, cost) -> KeyValue(role.displayName, money(cost)) }
                spend?.byModel?.entries?.sortedByDescending { it.value }?.forEach { (model, cost) -> KeyValue(model, money(cost)) }
            }
            SectionCard("YouTube") {
                Text(if (connected) "Channel connected" else "Not connected", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::connect) { Text(if (connected) "Reconnect" else "Connect") }
                    if (connected) OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
                InfoNote("Videos uploaded through an unverified Google Cloud project may be locked to private until the project passes YouTube's API compliance audit.", MaterialTheme.colorScheme.error)
            }
            SectionCard("Voice & visuals") {
                Dropdown("Text-to-speech", TtsProviderType.entries, s.ttsProvider, { it.displayName }, { v -> vm.update { it.copy(ttsProvider = v) } })
                OutlinedTextField(s.ttsVoice, { v -> vm.update { it.copy(ttsVoice = v.trim()) } }, label = { Text("Voice (name/ID, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Image generator", ImageProviderType.entries, s.imageProvider, { it.displayName }, { v -> vm.update { it.copy(imageProvider = v) } })
                if (s.imageProvider == ImageProviderType.OPENAI) {
                    OutlinedTextField(s.imageModel, { v -> vm.update { it.copy(imageModel = v.trim()) } }, label = { Text("OpenAI image model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text("Music library: ${s.musicFolderUri?.let { Uri.parse(it).lastPathSegment } ?: "none"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { musicPicker.launch(null) }) { Text("Choose royalty-free music folder") }
                Text("B-roll library: ${s.brollFolderUri?.let { Uri.parse(it).lastPathSegment } ?: "none"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { brollPicker.launch(null) }) { Text("Choose your own B-roll folder") }
                Text("Name B-roll files with keywords (e.g. city_night_traffic.mp4) so scenes can match them.", style = MaterialTheme.typography.bodySmall)
                var target by remember(s.targetLufs) { mutableStateOf(s.targetLufs.toString()) }
                OutlinedTextField(target, { target = it; it.toDoubleOrNull()?.let { v -> vm.update { st -> st.copy(targetLufs = v.coerceIn(-24.0, -9.0)) } } }, label = { Text("Loudness target (LUFS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            SectionCard("YouTube quota") {
                table?.let { t ->
                    Text("Costs verified ${t.verifiedOn} from ${t.source}", style = MaterialTheme.typography.bodySmall)
                    if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall)
                    t.buckets.forEach { b ->
                        var limit by remember(b) { mutableStateOf(b.dailyLimit.toString()) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text(b.label) }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = { limit.toIntOrNull()?.let { vm.saveQuotaLimit(b.id, it) } }) { Text("Save") }
                        }
                    }
                    t.methods.entries.sortedBy { it.key }.forEach { (m, c) -> KeyValue(m, "${c.cost} (${c.bucket})") }
                }
                var url by remember(s.quotaTableUrl) { mutableStateOf(s.quotaTableUrl.orEmpty()) }
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Quota table URL (JSON)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshQuota(url) }, enabled = url.startsWith("https://")) { Text("Refresh") }
                    OutlinedButton(onClick = vm::resetQuota) { Text("Reset to bundled") }
                }
            }
            SectionCard("Notifications") {
                LabeledSwitch("Approvals", s.notifyApprovals, { v -> vm.update { it.copy(notifyApprovals = v) } })
                LabeledSwitch("Failures and blocks", s.notifyFailures, { v -> vm.update { it.copy(notifyFailures = v) } })
                LabeledSwitch("Weekly report", s.notifyWeeklyReport, { v -> vm.update { it.copy(notifyWeeklyReport = v) } })
                LabeledSwitch("Flagged comments", s.notifyFlaggedComments, { v -> vm.update { it.copy(notifyFlaggedComments = v) } })
            }
        }
    }
}

@Composable
private fun ModelPicker(
    label: String,
    choice: ModelChoice,
    onChange: (ModelChoice) -> Unit,
    onTest: (ModelChoice) -> Unit,
    inherited: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    var model by remember(choice) { mutableStateOf(choice.model) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label + if (inherited) " (default)" else "", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Provider", LlmProviderId.entries, choice.provider, { it.displayName }, { p ->
                onChange(ModelChoice(p, ModelCatalog.forProvider(p).firstOrNull()?.id ?: ""))
            }, Modifier.weight(1f))
            Dropdown("Model", ModelCatalog.forProvider(choice.provider).map { it.id }.ifEmpty { listOf(choice.model) }.let { if (choice.model in it) it else it + choice.model },
                choice.model, { it }, { m -> onChange(choice.copy(model = m)) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(model, { model = it.trim() }, label = { Text("Custom model ID") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(choice.copy(model = model)) }, enabled = model.isNotBlank() && model != choice.model) { Text("Use") }
            TextButton(onClick = { onTest(choice) }) { Text("Test") }
            if (!inherited && onReset != null) TextButton(onClick = onReset) { Text("Reset") }
        }
        HorizontalDivider()
    }
}
