package com.tubepilot.app.ui.strategy

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.Dropdown
import com.tubepilot.app.ui.components.LabeledSwitch
import com.tubepilot.app.ui.components.ListField
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.StyleGuide
import com.tubepilot.youtube.YouTubeClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StrategyViewModel @Inject constructor(
    private val memory: MemoryRepository,
    private val youtube: YouTubeClient,
    private val audit: AuditLog,
) : ViewModel() {
    private val _profile = MutableStateFlow(ChannelProfile())
    val profile = _profile.asStateFlow()
    private val _style = MutableStateFlow(StyleGuide())
    val style = _style.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            _profile.value = memory.observeProfile().first()
            _style.value = memory.observeStyleGuide().first()
        }
    }

    fun edit(transform: (ChannelProfile) -> ChannelProfile) { _profile.value = transform(_profile.value) }
    fun editStyle(transform: (StyleGuide) -> StyleGuide) { _style.value = transform(_style.value) }

    fun save(onSaved: () -> Unit = {}) = viewModelScope.launch {
        val p = _profile.value.copy(
            shortsPerWeek = _profile.value.shortsPerWeek.coerceIn(0, 21),
            longFormPerWeek = _profile.value.longFormPerWeek.coerceIn(0, 7),
            languages = (_profile.value.languages + _profile.value.primaryLanguage).distinct(),
        )
        memory.saveProfile(p)
        memory.saveStyleGuide(_style.value)
        audit.record("You", "strategy.saved", "Niche '${p.niche}', ${p.shortsPerWeek} Shorts + ${p.longFormPerWeek} long-form/week")
        _messages.emit("Strategy saved")
        onSaved()
    }

    /** Checks competitor channel IDs exist (1 quota unit each). */
    fun verifyCompetitors() = viewModelScope.launch {
        val results = _profile.value.competitorChannelIds.map { id -> id to runCatching { youtube.channel(id)?.title }.getOrNull() }
        _messages.emit(results.joinToString("\n") { (id, title) -> if (title != null) "✓ $title" else "✗ $id not found" }.ifEmpty { "No competitors listed" })
    }
}

@Composable
fun StrategyScreen(onBack: () -> Unit, vm: StrategyViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(topBar = { BackTopBar("Channel strategy", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StrategyForm(vm)
            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save strategy") }
        }
    }
}

/** The strategy form, shared with onboarding. */
@Composable
fun StrategyForm(vm: StrategyViewModel) {
    val p by vm.profile.collectAsStateWithLifecycle()
    val s by vm.style.collectAsStateWithLifecycle()
    SectionCard("Niche & audience") {
        OutlinedTextField(p.niche, { v -> vm.edit { it.copy(niche = v) } }, label = { Text("Niche") }, placeholder = { Text("e.g. Budget smartphone tips for students") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p.audience, { v -> vm.edit { it.copy(audience = v) } }, label = { Text("Audience") }, placeholder = { Text("e.g. 16-25, India, first smartphone") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p.tone, { v -> vm.edit { it.copy(tone = v) } }, label = { Text("Tone of voice") }, modifier = Modifier.fillMaxWidth())
        ListField("Content pillars", p.contentPillars, { v -> vm.edit { it.copy(contentPillars = v) } }, "Comma separated, e.g. Camera tips, Battery, Reviews")
        ListField("Banned topics", p.bannedTopics, { v -> vm.edit { it.copy(bannedTopics = v) } }, "Agents never cover these; Compliance blocks them")
    }
    SectionCard("Language") {
        Dropdown("Primary language", ContentLanguage.entries, p.primaryLanguage, { it.displayName }, { v -> vm.edit { it.copy(primaryLanguage = v) } })
        Text("Also publish captions in", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentLanguage.entries.forEach { lang ->
                FilterChip(selected = lang in p.languages, onClick = {
                    vm.edit { it.copy(languages = if (lang in it.languages) it.languages - lang else it.languages + lang) }
                }, label = { Text(lang.displayName) })
            }
        }
    }
    SectionCard("Posting schedule") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Shorts / week", p.shortsPerWeek, Modifier.weight(1f)) { v -> vm.edit { it.copy(shortsPerWeek = v) } }
            NumberField("Long-form / week", p.longFormPerWeek, Modifier.weight(1f)) { v -> vm.edit { it.copy(longFormPerWeek = v) } }
        }
        ListField("Publish hours (0-23)", p.preferredPublishHours.map { it.toString() }, { v ->
            vm.edit { it.copy(preferredPublishHours = v.mapNotNull { h -> h.toIntOrNull()?.takeIf { x -> x in 0..23 } }) }
        }, "In your timezone, e.g. 12, 18")
        OutlinedTextField(p.timezone, { v -> vm.edit { it.copy(timezone = v.trim()) } }, label = { Text("Timezone (IANA)") }, placeholder = { Text("Asia/Kolkata") }, modifier = Modifier.fillMaxWidth())
        LabeledSwitch("Made for kids", p.madeForKids, { v -> vm.edit { it.copy(madeForKids = v) } }, "Sets YouTube's COPPA flag; disables comments and personalised ads")
    }
    SectionCard("Competitors & playlist") {
        ListField("Competitor channel IDs", p.competitorChannelIds, { v -> vm.edit { it.copy(competitorChannelIds = v) } }, "UC… IDs from channel URLs; used as demand signals only")
        androidx.compose.material3.OutlinedButton(onClick = { vm.verifyCompetitors() }) { Text("Verify competitors") }
        OutlinedTextField(p.defaultPlaylistId.orEmpty(), { v -> vm.edit { it.copy(defaultPlaylistId = v.trim().ifEmpty { null }) } }, label = { Text("Default playlist ID (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p.ctaStyle, { v -> vm.edit { it.copy(ctaStyle = v) } }, label = { Text("Call-to-action style") }, modifier = Modifier.fillMaxWidth())
    }
    SectionCard("Style guide") {
        ListField("Voice rules", s.voiceRules, { v -> vm.editStyle { it.copy(voiceRules = v) } })
        ListField("Do", s.dos, { v -> vm.editStyle { it.copy(dos = v) } })
        ListField("Don't", s.donts, { v -> vm.editStyle { it.copy(donts = v) } })
        OutlinedTextField(s.thumbnailStyle, { v -> vm.editStyle { it.copy(thumbnailStyle = v) } }, label = { Text("Thumbnail style") }, modifier = Modifier.fillMaxWidth())
        ListField("Brand colours (hex)", s.brandColors, { v -> vm.editStyle { it.copy(brandColors = v) } }, "First colour is the accent, e.g. #FFD400, #111111")
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { t -> t.filter(Char::isDigit).take(2).toIntOrNull()?.let(onChange) ?: if (t.isEmpty()) onChange(0) else Unit },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
