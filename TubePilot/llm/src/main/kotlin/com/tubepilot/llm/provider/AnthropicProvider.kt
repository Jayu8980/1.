package com.tubepilot.llm.provider

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.ServiceUnavailableException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.toJsonElement
import com.tubepilot.core.util.toPlain
import com.tubepilot.llm.Effort
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmMessage
import com.tubepilot.llm.LlmProvider
import com.tubepilot.llm.LlmRequest
import com.tubepilot.llm.LlmResponse
import com.tubepilot.llm.LlmRole
import com.tubepilot.llm.LlmStreamEvent
import com.tubepilot.llm.NativeContent
import com.tubepilot.llm.StopReason
import com.tubepilot.llm.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claude via the official Anthropic Java SDK. Requests always stream (avoids HTTP timeouts on
 * long generations) and are accumulated into a final message with the SDK's MessageAccumulator.
 */
@Singleton
class AnthropicProvider @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) : LlmProvider {

    override val id = LlmProviderId.ANTHROPIC

    private val lock = Mutex()
    /** Test hook: points the SDK at a mock server. Null uses the SDK's default endpoint. */
    internal var baseUrl: String? = null
    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    override suspend fun isConfigured(): Boolean = secrets.isSet(SecretKey.ANTHROPIC_API_KEY)

    private suspend fun client(): AnthropicClient = lock.withLock {
        val key = secrets.get(SecretKey.ANTHROPIC_API_KEY)
            ?: throw MissingConfigurationException("Add an Anthropic API key in Settings to use Claude")
        cachedClient?.takeIf { cachedKey == key }?.let { return it }
        cachedClient?.close()
        AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { baseUrl?.let { baseUrl(it) } }
            // The gateway owns retries with backoff and budget checks, so keep SDK retries low.
            .maxRetries(1)
            .timeout(Duration.ofMinutes(10))
            .build()
            .also {
                cachedClient = it
                cachedKey = key
            }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse =
        stream(request).filterIsInstance<LlmStreamEvent.Completed>().first().response

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val client = client()
        val params = buildParams(request, settings.current().claudeRefusalFallback)
        val accumulator = MessageAccumulator.create()
        try {
            client.messages().createStreaming(params).use { response ->
                for (event in response) {
                    currentCoroutineContext().ensureActive()
                    accumulator.accumulate(event)
                    val delta = event.contentBlockDelta().flatMap { it.delta().text() }.orElse(null)
                    if (delta != null) emit(LlmStreamEvent.TextDelta(delta.text()))
                }
            }
        } catch (e: AnthropicServiceException) {
            throw mapError(e)
        } catch (e: AnthropicRetryableException) {
            throw ProviderException("Anthropic", e.message ?: "retryable error", null, retryable = true, cause = e)
        } catch (e: AnthropicIoException) {
            throw ProviderException("Anthropic", e.message ?: "network error", null, retryable = true, cause = e)
        }
        emit(LlmStreamEvent.Completed(toResponse(accumulator.message())))
    }.flowOn(Dispatchers.IO)

    internal fun buildParams(request: LlmRequest, refusalFallback: Boolean): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens.toLong())
            // The system prompt (role prompt + memory) is stable across a tool loop: cache it.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(request.system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
        request.messages.forEach { builder.addMessage(toParam(it)) }
        request.tools.forEach { tool -> builder.addTool(toTool(tool.name, tool.description, tool.inputSchema)) }
        if (supportsEffort(request.model)) {
            val effort = when (request.effort) {
                Effort.LOW -> OutputConfig.Effort.LOW
                Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
                Effort.HIGH -> OutputConfig.Effort.HIGH
            }
            builder.outputConfig(OutputConfig.builder().effort(effort).build())
        }
        if (refusalFallback && supportsServerFallback(request.model)) {
            // Server-side refusal fallback: on a policy decline the API re-runs the request on a
            // fallback model chosen by refusal category, inside the same call.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun toParam(message: LlmMessage): MessageParam {
        val native = message.native
        if (message.role == LlmRole.ASSISTANT && native?.provider == LlmProviderId.ANTHROPIC) {
            @Suppress("UNCHECKED_CAST")
            return MessageParam.ofAssistantOfContentBlockParams(native.payload as List<ContentBlockParam>)
        }
        val blocks = message.content.map { part ->
            when (part) {
                is LlmContent.Text -> ContentBlockParam.ofText(TextBlockParam.builder().text(part.text.ifEmpty { " " }).build())
                is LlmContent.ToolUse -> ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder()
                        .id(part.id)
                        .name(part.name)
                        .input(
                            ToolUseBlockParam.Input.builder()
                                .putAllAdditionalProperties(part.input.mapValues { (_, v) -> JsonValue.from(v.toPlain()) })
                                .build(),
                        )
                        .build(),
                )
                is LlmContent.ToolResult -> ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(part.toolUseId)
                        .content(part.content)
                        .isError(part.isError)
                        .build(),
                )
            }
        }
        return if (message.role == LlmRole.USER) {
            MessageParam.ofUserOfContentBlockParams(blocks)
        } else {
            MessageParam.ofAssistantOfContentBlockParams(blocks)
        }
    }

    private fun toTool(name: String, description: String, schema: JsonObject): Tool {
        val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val propsBuilder = Tool.InputSchema.Properties.builder()
        properties.forEach { (key, value) -> propsBuilder.putAdditionalProperty(key, JsonValue.from(value.toPlain())) }
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(propsBuilder.build())
                    .required(required)
                    .build(),
            )
            .build()
    }

    private fun toResponse(message: Message): LlmResponse {
        val content = message.content().mapNotNull { block ->
            val text = block.text().orElse(null)
            val toolUse = block.toolUse().orElse(null)
            when {
                text != null -> LlmContent.Text(text.text())
                toolUse != null -> {
                    val input = toolUse._input().convert(Any::class.java).toJsonElement() as? JsonObject ?: JsonObject(emptyMap())
                    LlmContent.ToolUse(toolUse.id(), toolUse.name(), input)
                }
                else -> null // thinking/fallback blocks are kept in the native payload only
            }
        }
        val usage = message.usage()
        val stop = when (message.stopReason().orElse(null)) {
            com.anthropic.models.messages.StopReason.END_TURN,
            com.anthropic.models.messages.StopReason.STOP_SEQUENCE -> StopReason.END_TURN
            com.anthropic.models.messages.StopReason.TOOL_USE -> StopReason.TOOL_USE
            com.anthropic.models.messages.StopReason.MAX_TOKENS -> StopReason.MAX_TOKENS
            com.anthropic.models.messages.StopReason.REFUSAL -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
        return LlmResponse(
            content = content,
            usage = TokenUsage(
                inputTokens = usage.inputTokens() + usage.cacheReadInputTokens().orElse(0L) + usage.cacheCreationInputTokens().orElse(0L),
                outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
                cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
            ),
            stopReason = stop,
            model = message.model().asString(),
            provider = id,
            // Replaying the exact blocks keeps thinking blocks valid across tool-loop turns.
            native = NativeContent(id, message.content().map { it.toParam() }),
        )
    }

    private fun mapError(e: AnthropicServiceException): ProviderException {
        val retryable = e is RateLimitException || e is OverloadedException || e is InternalServerException ||
            e is ServiceUnavailableException || e.statusCode() >= 500 || e.statusCode() == 408 || e.statusCode() == 409
        return ProviderException("Anthropic", e.message ?: "HTTP ${e.statusCode()}", e.statusCode(), retryable, e)
    }

    private companion object {
        val EFFORT_MODELS = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6")
        val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        fun supportsEffort(model: String) = EFFORT_MODELS.any { model.startsWith(it) }
        fun supportsServerFallback(model: String) = model in FALLBACK_MODELS
    }
}
