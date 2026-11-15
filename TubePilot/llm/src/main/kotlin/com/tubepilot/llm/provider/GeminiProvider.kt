package com.tubepilot.llm.provider

import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmProvider
import com.tubepilot.llm.LlmRequest
import com.tubepilot.llm.LlmResponse
import com.tubepilot.llm.LlmRole
import com.tubepilot.llm.LlmStreamEvent
import com.tubepilot.llm.NativeContent
import com.tubepilot.llm.StopReason
import com.tubepilot.llm.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Google Gemini via the Generative Language REST API (streamGenerateContent over SSE). */
@Singleton
class GeminiProvider @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) : LlmProvider {

    override val id = LlmProviderId.GEMINI
    internal var baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun isConfigured() = secrets.isSet(SecretKey.GEMINI_API_KEY)

    override suspend fun complete(request: LlmRequest): LlmResponse =
        stream(request).filterIsInstance<LlmStreamEvent.Completed>().first().response

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val key = secrets.get(SecretKey.GEMINI_API_KEY)
            ?: throw MissingConfigurationException("Add a Google Gemini API key in Settings")
        val httpRequest = Request.Builder()
            .url("$baseUrl/models/${request.model}:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(buildBody(request).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val text = StringBuilder()
        val calls = mutableListOf<LlmContent.ToolUse>()
        val rawParts = mutableListOf<JsonElement>()
        var finish: String? = null
        var usage = TokenUsage(0, 0)

        http.executeChecked("Gemini", httpRequest).use { response ->
            val source = response.body?.source() ?: throw ProviderException("Gemini", "empty body", null, retryable = true)
            Sse.read(source) { data ->
                val chunk = TubeJson.parseToJsonElement(data).jsonObject
                (chunk["usageMetadata"] as? JsonObject)?.let { u ->
                    usage = TokenUsage(
                        u.long("promptTokenCount") ?: 0,
                        (u.long("candidatesTokenCount") ?: 0) + (u.long("thoughtsTokenCount") ?: 0),
                        u.long("cachedContentTokenCount") ?: 0,
                    )
                }
                val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@read
                candidate.str("finishReason")?.let { finish = it }
                val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
                parts.forEach { el ->
                    val part = el as? JsonObject ?: return@forEach
                    rawParts += part
                    val thought = (part["thought"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                    part.str("text")?.takeIf { !thought }?.let {
                        text.append(it)
                        emit(LlmStreamEvent.TextDelta(it))
                    }
                    (part["functionCall"] as? JsonObject)?.let { fc ->
                        calls += LlmContent.ToolUse(
                            id = fc.str("id") ?: "fc_${UUID.randomUUID()}",
                            name = fc.str("name").orEmpty(),
                            input = (fc["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                        )
                    }
                }
            }
        }

        val content = buildList<LlmContent> {
            if (text.isNotEmpty()) add(LlmContent.Text(text.toString()))
            addAll(calls)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.TOOL_USE
            finish == "MAX_TOKENS" -> StopReason.MAX_TOKENS
            finish == "SAFETY" || finish == "PROHIBITED_CONTENT" || finish == "BLOCKLIST" -> StopReason.REFUSAL
            finish == "STOP" -> StopReason.END_TURN
            else -> StopReason.OTHER
        }
        // Raw parts carry thought signatures that must be echoed back during function calling.
        emit(LlmStreamEvent.Completed(LlmResponse(content, usage, stop, request.model, id, NativeContent(id, JsonArray(rawParts)))))
    }.flowOn(Dispatchers.IO)

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", request.system) }) })
        })
        put("contents", buildJsonArray {
            request.messages.forEach { message ->
                val native = message.native
                if (message.role == LlmRole.ASSISTANT && native?.provider == LlmProviderId.GEMINI) {
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", native.payload as JsonArray)
                    })
                    return@forEach
                }
                add(buildJsonObject {
                    put("role", if (message.role == LlmRole.USER) "user" else "model")
                    put("parts", buildJsonArray {
                        message.content.forEach { part ->
                            when (part) {
                                is LlmContent.Text -> add(buildJsonObject { put("text", part.text) })
                                is LlmContent.ToolUse -> add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", part.name)
                                        put("args", part.input)
                                    })
                                })
                                is LlmContent.ToolResult -> add(buildJsonObject {
                                    put("functionResponse", buildJsonObject {
                                        put("name", part.toolName)
                                        put("response", buildJsonObject {
                                            put(if (part.isError) "error" else "result", part.content)
                                        })
                                    })
                                })
                            }
                        }
                    })
                })
            }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        request.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", sanitizeSchema(tool.inputSchema))
                            })
                        }
                    })
                })
            })
        }
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", request.maxTokens)
            if (request.jsonOutput && request.tools.isEmpty()) put("responseMimeType", "application/json")
        })
    }

    /** Gemini function schemas accept an OpenAPI subset; drop keywords it rejects. */
    internal fun sanitizeSchema(schema: JsonElement): JsonElement = when (schema) {
        is JsonObject -> JsonObject(
            schema.filterKeys { it !in UNSUPPORTED_KEYS }.mapValues { (k, v) ->
                if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { (_, p) -> sanitizeSchema(p) }) else sanitizeSchema(v)
            },
        )
        is JsonArray -> JsonArray(schema.map { sanitizeSchema(it) })
        else -> schema
    }

    private companion object {
        val UNSUPPORTED_KEYS = setOf("additionalProperties", "\$schema", "\$id", "default", "examples", "const")
    }
}
