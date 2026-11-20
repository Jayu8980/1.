package com.tradelens.app.data.llm

import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.a
import com.tradelens.app.data.net.o
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.data.net.s
import com.tradelens.app.data.secure.LlmProvider
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class KeyLevels(val support: List<Double> = emptyList(), val resistance: List<Double> = emptyList())
@Serializable data class Setup(
    val direction: String = "none", val entry: Double? = null, val stopLoss: Double? = null,
    val targets: List<Double> = emptyList(), val riskReward: Double? = null, val trigger: String = "",
)
@Serializable data class AiAnalysis(
    val trendBias: String = "neutral",
    val summary: String = "",
    val keyLevels: KeyLevels = KeyLevels(),
    val bullishCase: String = "",
    val bearishCase: String = "",
    val setup: Setup = Setup(),
    val confidence: Int = 0,
    val risks: List<String> = emptyList(),
    val uncertainty: String = "",
)

data class ChatMsg(val role: String, val text: String)

/**
 * Bring-your-own-key LLM client. The model only ever receives a compact JSON summary of data that the app
 * computed; it has no tools and cannot place orders or change risk limits.
 */
@Singleton
class LlmClient @Inject constructor(private val settings: SettingsStore, private val secure: SecureStore) {
    private val client = Http.client.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    fun defaultModel(p: LlmProvider) = when (p) {
        LlmProvider.CLAUDE -> "claude-opus-5-5"
        LlmProvider.OPENAI -> "gpt-5"
        LlmProvider.GEMINI -> "gemini-2.5-pro"
        LlmProvider.LOCAL -> "llama3.1"
    }

    private val provider get() = settings.value.llmProvider
    private val model get() = settings.value.llmModel.ifBlank { defaultModel(provider) }
    fun isConfigured() = provider == LlmProvider.LOCAL && settings.value.llmBaseUrl.isNotBlank() || secure.get(SecureStore.llmKey(provider.name)) != null

    suspend fun analyse(summaryJson: String): AiAnalysis {
        val user = "Analyse this instrument using ONLY the data below. Data JSON:\n$summaryJson"
        val text = complete(ANALYSIS_SYSTEM, listOf(ChatMsg("user", user)), structured = true)
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return AppJson.decodeFromString(AiAnalysis.serializer(), cleaned.substring(cleaned.indexOf('{').coerceAtLeast(0)))
    }

    suspend fun chat(context: String, history: List<ChatMsg>): String =
        complete("$CHAT_SYSTEM\n\nChart data (JSON):\n$context", history, structured = false)

    suspend fun brief(context: String, kind: String): String = complete(
        "You write concise $kind market notes for an Indian retail trader. Use only the supplied data, flag anomalies, state uncertainty, " +
            "never give guaranteed outcomes, end with 'Not financial advice.'", listOf(ChatMsg("user", context)), structured = false,
    )

    private suspend fun complete(system: String, msgs: List<ChatMsg>, structured: Boolean): String = when (provider) {
        LlmProvider.CLAUDE -> claude(system, msgs, structured)
        LlmProvider.GEMINI -> gemini(system, msgs, structured)
        LlmProvider.OPENAI -> openAiCompatible("https://api.openai.com/v1", system, msgs, structured, key())
        LlmProvider.LOCAL -> openAiCompatible(settings.value.llmBaseUrl.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }, system, msgs, structured,
            secure.get(SecureStore.llmKey(provider.name)))
    }

    private fun key() = secure.get(SecureStore.llmKey(provider.name)) ?: throw IllegalStateException("Add your ${provider.label} API key in Settings → AI")

    private suspend fun post(url: String, body: String, headers: Map<String, String>): JsonObject {
        val rb = Request.Builder().url(url).post(body.toRequestBody(json))
        headers.forEach { (k, v) -> rb.header(k, v) }
        val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.newCall(rb.build()).execute() }
        resp.use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("${provider.label} error ${r.code}: ${txt.take(300)}")
            return parseJson(txt).obj() ?: JsonObject(emptyMap())
        }
    }

    /** Anthropic Messages API (raw HTTP; no Android SDK). Structured output via output_config.format json_schema. */
    private suspend fun claude(system: String, msgs: List<ChatMsg>, structured: Boolean): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("system", system)
            putJsonObject("output_config") {
                put("effort", "medium")
                if (structured) putJsonObject("format") { put("type", "json_schema"); put("schema", ANALYSIS_SCHEMA) }
            }
            put("fallbacks", "default")
            putJsonArray("messages") { msgs.forEach { m -> addJsonObject { put("role", m.role); put("content", m.text) } } }
        }
        val r = post("https://api.anthropic.com/v1/messages", body.toString(), mapOf(
            "x-api-key" to key(), "anthropic-version" to "2026-10-21", "anthropic-beta" to "server-side-fallback-2026-07-01",
        ))
        if (r.s("stop_reason") == "refusal") throw IllegalStateException("Claude declined this request (${r.o("stop_details").s("category") ?: "policy"})")
        return r.a("content").orEmpty().mapNotNull { it.obj() }.filter { it.s("type") == "text" }.joinToString("") { it.s("text").orEmpty() }
    }

    private suspend fun openAiCompatible(base: String, system: String, msgs: List<ChatMsg>, structured: Boolean, key: String?): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", if (structured) "$system\nRespond with JSON matching: $ANALYSIS_SCHEMA" else system) }
                msgs.forEach { m -> addJsonObject { put("role", m.role); put("content", m.text) } }
            }
            if (structured) putJsonObject("response_format") { put("type", "json_object") }
        }
        val headers = key?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        val r = post("$base/chat/completions", body.toString(), headers)
        return r.a("choices")?.firstOrNull().obj().o("message").s("content").orEmpty()
    }

    private suspend fun gemini(system: String, msgs: List<ChatMsg>, structured: Boolean): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", if (structured) "$system\nJSON schema: $ANALYSIS_SCHEMA" else system) } } }
            putJsonArray("contents") {
                msgs.forEach { m -> addJsonObject { put("role", if (m.role == "assistant") "model" else "user"); putJsonArray("parts") { addJsonObject { put("text", m.text) } } } }
            }
            if (structured) putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        val r = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body.toString(), mapOf("x-goog-api-key" to key()))
        return r.a("candidates")?.firstOrNull().obj().o("content").a("parts").orEmpty().joinToString("") { it.obj().s("text").orEmpty() }
    }

    companion object {
        val ANALYSIS_SYSTEM = """
            You are a disciplined technical analyst for Indian equities, F&O and crypto derivatives.
            Rules: use ONLY the numbers in the supplied JSON (prices, indicators, patterns, levels, confluence, regime, strategy signals).
            Do not invent news, fundamentals or prices. If the data is insufficient or conflicting, say so in "uncertainty" and lower "confidence".
            Levels must come from the supplied data. A setup must have stop below entry for longs (above for shorts); set direction "none" if no clean setup.
            confidence is 0-100. Keep each text field under 80 words. This is educational analysis, not financial advice.
        """.trimIndent()

        val CHAT_SYSTEM = """
            You answer follow-up questions about the chart data supplied below. Use only that data, state uncertainty plainly,
            and never instruct the user to place an order or change risk limits. Not financial advice.
        """.trimIndent()

        val ANALYSIS_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("trendBias") { put("type", "string"); putJsonArray("enum") { add("bullish"); add("bearish"); add("neutral") } }
                putJsonObject("summary") { put("type", "string") }
                putJsonObject("keyLevels") {
                    put("type", "object"); put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("support") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                        putJsonObject("resistance") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                    }
                    putJsonArray("required") { add("support"); add("resistance") }
                }
                putJsonObject("bullishCase") { put("type", "string") }
                putJsonObject("bearishCase") { put("type", "string") }
                putJsonObject("setup") {
                    put("type", "object"); put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("direction") { put("type", "string"); putJsonArray("enum") { add("long"); add("short"); add("none") } }
                        putJsonObject("entry") { put("type", "number") }
                        putJsonObject("stopLoss") { put("type", "number") }
                        putJsonObject("targets") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                        putJsonObject("riskReward") { put("type", "number") }
                        putJsonObject("trigger") { put("type", "string") }
                    }
                    putJsonArray("required") { add("direction"); add("entry"); add("stopLoss"); add("targets"); add("riskReward"); add("trigger") }
                }
                putJsonObject("confidence") { put("type", "integer") }
                putJsonObject("risks") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("uncertainty") { put("type", "string") }
            }
            putJsonArray("required") {
                listOf("trendBias", "summary", "keyLevels", "bullishCase", "bearishCase", "setup", "confidence", "risks", "uncertainty").forEach { add(it) }
            }
        }
    }
}
