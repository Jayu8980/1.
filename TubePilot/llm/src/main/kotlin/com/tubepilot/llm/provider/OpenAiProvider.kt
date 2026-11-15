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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** OpenAI Chat Completions with function calling and SSE streaming. */
@Singleton
class OpenAiProvider @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) : LlmProvider {

    override val id = LlmProviderId.OPENAI
    internal var baseUrl = "https://api.openai.com/v1"

    override suspend fun isConfigured() = secrets.isSet(SecretKey.OPENAI_API_KEY)

    override suspend fun complete(request: LlmRequest): LlmResponse =
        stream(request).filterIsInstance<LlmStreamEvent.Completed>().first().response

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val key = secrets.get(SecretKey.OPENAI_API_KEY)
            ?: throw MissingConfigurationException("Add an OpenAI API key in Settings")
        val body = buildBody(request).toString().toRequestBody(JSON)
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        val text = StringBuilder()
        val toolCalls = sortedMapOf<Int, PendingToolCall>()
        var finish: String? = null
        var usage = TokenUsage(0, 0)
        var model = request.model

        http.executeChecked("OpenAI", httpRequest).use { response ->
            val source = response.body?.source() ?: throw ProviderException("OpenAI", "empty body", null, retryable = true)
            Sse.read(source) { data ->
                val chunk = TubeJson.parseToJsonElement(data).jsonObject
                chunk.str("model")?.let { model = it }
                (chunk["usage"] as? JsonObject)?.let { u ->
                    val cached = (u["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0
                    usage = TokenUsage(u.long("prompt_tokens") ?: 0, u.long("completion_tokens") ?: 0, cached)
                }
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@read
                choice.str("finish_reason")?.let { finish = it }
                val delta = choice["delta"] as? JsonObject ?: return@read
                delta.str("content")?.let {
                    text.append(it)
                    emit(LlmStreamEvent.TextDelta(it))
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                    val tc = el as? JsonObject ?: return@forEach
                    val index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    val pending = toolCalls.getOrPut(index) { PendingToolCall() }
                    tc.str("id")?.let { pending.id = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        fn.str("name")?.let { pending.name = it }
                        fn.str("arguments")?.let { pending.arguments.append(it) }
                    }
                }
            }
        }

        val content = buildList {
            if (text.isNotEmpty()) add(LlmContent.Text(text.toString()))
            toolCalls.values.forEach { tc ->
                val args = runCatching { TubeJson.parseToJsonElement(tc.arguments.toString()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                add(LlmContent.ToolUse(tc.id ?: "call_${tc.hashCode()}", tc.name.orEmpty(), args))
            }
        }
        val stop = when (finish) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            "stop" -> StopReason.END_TURN
            else -> if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.OTHER
        }
        emit(LlmStreamEvent.Completed(LlmResponse(content, usage, stop, model, id)))
    }.flowOn(Dispatchers.IO)

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
        put("max_completion_tokens", request.maxTokens)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.system)
            })
            request.messages.forEach { message ->
                if (message.role == LlmRole.ASSISTANT) {
                    val texts = message.content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }
                    val calls = message.content.filterIsInstance<LlmContent.ToolUse>()
                    add(buildJsonObject {
                        put("role", "assistant")
                        if (texts.isNotEmpty()) put("content", texts) else put("content", JsonNull)
                        if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                            calls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.input.toString())
                                    })
                                })
                            }
                        })
                    })
                } else {
                    message.content.filterIsInstance<LlmContent.ToolResult>().forEach { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.toolUseId)
                            put("content", result.content)
                        })
                    }
                    val texts = message.content.filterIsInstance<LlmContent.Text>().joinToString("\n") { it.text }
                    if (texts.isNotEmpty()) add(buildJsonObject {
                        put("role", "user")
                        put("content", texts)
                    })
                }
            }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            })
        } else if (request.jsonOutput) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
    }

    private class PendingToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
