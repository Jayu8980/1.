package com.autotube.agents.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.autotube.agents.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/**
 * The shared "brain" every agent thinks with: Claude via the official Anthropic Java SDK.
 * Each agent supplies its own system prompt (its role), so one client serves the whole team.
 */
class ClaudeClient(private val settings: Settings) {

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    private fun client(): AnthropicClient {
        val key = settings.anthropicApiKey
        require(key.isNotBlank()) { "Anthropic API key is not set (Settings → AI brain)." }
        cached?.let { if (it.first == key) return it.second }
        val c = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofMinutes(5))
            .maxRetries(3)
            .build()
        cached = key to c
        return c
    }

    /** Ask Claude and return the plain-text answer. */
    suspend fun ask(system: String, user: String, maxTokens: Long = 16000, effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM): String =
        withContext(Dispatchers.IO) {
            val model = settings.model
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system)
                .addUserMessage(user)
            if (supportsEffort(model)) {
                b.outputConfig(BetaOutputConfig.builder().effort(effort).build())
            }
            if (model in SERVER_FALLBACK_MODELS) {
                // If a safety classifier declines, the API retries on a suitable model automatically.
                b.addBeta("server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val response = client().beta().messages().create(b.build())
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                throw IllegalStateException("Claude declined this request (refusal).")
            }
            response.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }.trim()
        }

    /** Ask Claude for a JSON object. Retries once if the reply isn't valid JSON. */
    suspend fun askJson(system: String, user: String, maxTokens: Long = 16000): JSONObject {
        val sys = "$system\n\nRespond with ONE valid JSON object only. No markdown fences, no commentary."
        var last = ""
        repeat(2) { attempt ->
            val prompt = if (attempt == 0) user else
                "$user\n\nYour previous reply was not valid JSON. Reply again with only the JSON object."
            last = ask(sys, prompt, maxTokens)
            extractJson(last)?.let { return it }
        }
        throw IllegalStateException("Model did not return JSON: ${last.take(200)}")
    }

    companion object {
        private val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        private fun supportsEffort(model: String) =
            model.startsWith("claude-") && !model.startsWith("claude-haiku-4") && !model.contains("-4-5") && !model.startsWith("claude-3")

        fun extractJson(text: String): JSONObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try { JSONObject(text.substring(start, end + 1)) } catch (e: Exception) { null }
        }

        fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
