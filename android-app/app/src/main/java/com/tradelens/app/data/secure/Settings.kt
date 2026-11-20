package com.tradelens.app.data.secure

import android.content.Context
import androidx.core.content.edit
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.domain.RiskLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

enum class LlmProvider(val label: String) { CLAUDE("Claude"), OPENAI("OpenAI"), GEMINI("Gemini"), LOCAL("Local (OpenAI-compatible)") }

@Serializable
data class AppSettings(
    val disclaimerAccepted: Boolean = false,
    val biometricLock: Boolean = true,
    val deltaTestnet: Boolean = true,
    val deltaLiveOrders: Boolean = false,
    val deltaProductionUnlocked: Boolean = false,
    val engineUrl: String = "",
    val relayUrl: String = "",
    val indianOrdersViaRelay: Boolean = false,
    val primaryBroker: String = "",
    val llmProvider: LlmProvider = LlmProvider.CLAUDE,
    val llmModel: String = "",
    val llmBaseUrl: String = "",
    val riskLimits: RiskLimits = RiskLimits(),
    val globalKill: Boolean = false,
    val paperCapital: Double = 1_000_000.0,
    val alertIntervalMin: Int = 15,
    val preMarketBrief: Boolean = true,
    val usdInr: Double = 88.0,
)

/** Non-secret preferences (plain SharedPreferences, JSON-encoded). */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tradelens_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = prefs.getString("json", null)
        ?.let { runCatching { AppJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        _state.value = n
        prefs.edit { putString("json", AppJson.encodeToString(AppSettings.serializer(), n)) }
    }
}
