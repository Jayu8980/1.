package com.tubepilot.app.ui.onboarding

import android.content.Intent
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.components.InfoNote
import com.tubepilot.app.ui.components.SecretField
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.strategy.StrategyForm
import com.tubepilot.app.ui.strategy.StrategyViewModel
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.ModelChoice
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.pipeline.PipelineController
import com.tubepilot.youtube.ChannelInfo
import com.tubepilot.youtube.YouTubeClient
import com.tubepilot.youtube.auth.AuthStart
import com.tubepilot.youtube.auth.YouTubeAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val step: Int = 0,
    val keys: Map<SecretKey, Boolean> = emptyMap(),
    val connecting: Boolean = false,
    val channel: ChannelInfo? = null,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val secrets: SecretStore,
    private val auth: YouTubeAuthManager,
    private val youtube: YouTubeClient,
    private val memory: MemoryRepository,
    private val settings: SettingsRepository,
    private val controller: PipelineController,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state = _state.asStateFlow()
    private val _consent = MutableSharedFlow<IntentSender>()
    val consent = _consent.asSharedFlow()

    init { refreshKeys() }

    private fun refreshKeys() = viewModelScope.launch {
        _state.update { s -> s.copy(keys = SecretKey.entries.associateWith { secrets.isSet(it) }) }
    }

    fun go(step: Int) = _state.update { it.copy(step = step, error = null) }

    fun saveKey(key: SecretKey, value: String?) = viewModelScope.launch {
        secrets.put(key, value)
        refreshKeys().join()
        // Point the default model at whichever provider the user configured first.
        val s = settings.current()
        val keys = _state.value.keys
        if (keys[providerKey(s.defaultModel.provider)] != true) {
            val choice = when {
                keys[SecretKey.ANTHROPIC_API_KEY] == true -> ModelChoice(LlmProviderId.ANTHROPIC, "claude-opus-5-5")
                keys[SecretKey.OPENAI_API_KEY] == true -> ModelChoice(LlmProviderId.OPENAI, "gpt-5-mini")
                keys[SecretKey.GEMINI_API_KEY] == true -> ModelChoice(LlmProviderId.GEMINI, "gemini-2.5-flash")
                else -> null
            }
            choice?.let { c -> settings.update { it.copy(defaultModel = c) } }
        }
    }

    private fun providerKey(p: LlmProviderId) = when (p) {
        LlmProviderId.ANTHROPIC -> SecretKey.ANTHROPIC_API_KEY
        LlmProviderId.OPENAI -> SecretKey.OPENAI_API_KEY
        LlmProviderId.GEMINI -> SecretKey.GEMINI_API_KEY
        LlmProviderId.ON_DEVICE -> null
    }

    fun connect() = viewModelScope.launch {
        _state.update { it.copy(connecting = true, error = null) }
        when (val r = auth.authorize()) {
            AuthStart.Authorized -> loadChannel()
            is AuthStart.NeedsConsent -> _consent.emit(r.intentSender)
            is AuthStart.Failed -> _state.update { it.copy(connecting = false, error = r.message) }
        }
    }

    fun onConsentResult(data: Intent?) = viewModelScope.launch {
        if (auth.completeConsent(data)) loadChannel() else _state.update { it.copy(connecting = false, error = "Permission was not granted") }
    }

    private suspend fun loadChannel() {
        runCatching { youtube.myChannel() }
            .onSuccess { ch ->
                memory.saveProfile(memory.profile().copy(channelId = ch.id, channelTitle = ch.title))
                _state.update { it.copy(connecting = false, channel = ch) }
            }
            .onFailure { e -> _state.update { it.copy(connecting = false, error = e.message) } }
    }

    fun finish() = viewModelScope.launch {
        settings.update { it.copy(onboardingComplete = true) }
        controller.reconcile()
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel(), strategyVm: StrategyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.onConsentResult(result.data) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { vm.consent.collect { sender -> consentLauncher.launch(IntentSenderRequest.Builder(sender).build()) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(progress = { (state.step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
        when (state.step) {
            0 -> {
                Text("TubePilot", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text("A team of AI agents that researches, writes, produces, publishes and learns for your YouTube channel — from your phone.", style = MaterialTheme.typography.bodyLarge)
                SectionCard("How it works") {
                    Text("• 12 agents share one pipeline: idea → script → voice → visuals → edit → thumbnail → SEO → compliance → QA → upload → comments → analytics.")
                    Text("• Approval mode is on: every script and every video waits for your tap before it moves on or goes public.")
                    Text("• A kill switch in the dashboard pauses all agents instantly. Every action is logged.")
                    Text("• Only official YouTube APIs are used. Content is always original or transformed.")
                }
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.go(1)
                }, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
            }
            1 -> {
                Text("AI providers", style = MaterialTheme.typography.headlineSmall)
                Text("Add at least one language-model key. Keys are encrypted with the Android Keystore and only sent to their own provider.")
                SectionCard {
                    SecretField("Anthropic API key (Claude)", state.keys[SecretKey.ANTHROPIC_API_KEY] == true, { vm.saveKey(SecretKey.ANTHROPIC_API_KEY, it) }, { vm.saveKey(SecretKey.ANTHROPIC_API_KEY, null) })
                    SecretField("OpenAI API key (optional: GPT, images, TTS)", state.keys[SecretKey.OPENAI_API_KEY] == true, { vm.saveKey(SecretKey.OPENAI_API_KEY, it) }, { vm.saveKey(SecretKey.OPENAI_API_KEY, null) })
                    SecretField("Google Gemini API key (optional)", state.keys[SecretKey.GEMINI_API_KEY] == true, { vm.saveKey(SecretKey.GEMINI_API_KEY, it) }, { vm.saveKey(SecretKey.GEMINI_API_KEY, null) })
                }
                InfoNote("Without an image or TTS key the app still works: it uses the phone's own text-to-speech and generates original background visuals on the device.")
                val hasLlm = state.keys[SecretKey.ANTHROPIC_API_KEY] == true || state.keys[SecretKey.OPENAI_API_KEY] == true || state.keys[SecretKey.GEMINI_API_KEY] == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.go(0) }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.go(2) }, enabled = hasLlm) { Text("Continue") }
                }
            }
            2 -> {
                Text("Connect your channel", style = MaterialTheme.typography.headlineSmall)
                Text("TubePilot asks Google for three permissions: upload videos (youtube.upload), manage your videos and comments (youtube.force-ssl), and read analytics (yt-analytics.readonly).")
                InfoNote(
                    "Important: videos uploaded through an unverified Google Cloud project may be locked to private until the project passes YouTube's API compliance audit. " +
                        "See docs/GOOGLE_CLOUD_SETUP.md for the audit steps.",
                    MaterialTheme.colorScheme.error,
                )
                state.channel?.let { ch ->
                    SectionCard("Connected") {
                        Text(ch.title, style = MaterialTheme.typography.titleMedium)
                        Text("${ch.subscribers} subscribers · ${ch.videoCount} videos")
                    }
                }
                state.error?.let { InfoNote(it, MaterialTheme.colorScheme.error) }
                if (state.connecting) CircularProgressIndicator()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.go(1) }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    if (state.channel == null) {
                        OutlinedButton(onClick = { vm.go(3) }) { Text("Skip for now") }
                        Button(onClick = { vm.connect() }, enabled = !state.connecting) { Text("Connect YouTube") }
                    } else {
                        Button(onClick = { vm.go(3) }) { Text("Continue") }
                    }
                }
            }
            else -> {
                Text("Channel strategy", style = MaterialTheme.typography.headlineSmall)
                Text("The agents read this before every task. You can change it any time.")
                StrategyForm(strategyVm)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.go(2) }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { strategyVm.save { vm.finish() } }) { Text("Finish setup") }
                }
            }
        }
    }
}
