package com.tubepilot.llm.provider

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.util.JsonText
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmProvider
import com.tubepilot.llm.LlmRequest
import com.tubepilot.llm.LlmResponse
import com.tubepilot.llm.LlmRole
import com.tubepilot.llm.LlmStreamEvent
import com.tubepilot.llm.StopReason
import com.tubepilot.llm.TokenUsage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional on-device fallback using the MediaPipe LLM Inference task with a model file the user
 * downloads (for example a Gemma .task bundle). Small models have no native function calling, so
 * tools are described in the prompt and the model answers with a JSON envelope we parse.
 * The model is loaded lazily and released after use to respect the 8 GB RAM budget.
 */
@Singleton
class OnDeviceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : LlmProvider {

    override val id = LlmProviderId.ON_DEVICE
    private val lock = Mutex()

    override suspend fun isConfigured(): Boolean =
        settings.current().onDeviceModelPath?.let { File(it).isFile } == true

    override suspend fun complete(request: LlmRequest): LlmResponse = lock.withLock {
        val path = settings.current().onDeviceModelPath?.takeIf { File(it).isFile }
            ?: throw MissingConfigurationException("Choose an on-device model file in Settings")
        val prompt = renderPrompt(request)
        val raw = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_CONTEXT_TOKENS)
                .build()
            val engine = try {
                LlmInference.createFromOptions(context, options)
            } catch (e: Exception) {
                throw ProviderException("On-device", "Could not load model: ${e.message}", null, retryable = false, cause = e)
            }
            try {
                engine.generateResponse(prompt)
            } finally {
                engine.close()
            }
        }
        parseResponse(raw, request)
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val response = complete(request)
        if (response.text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(response.text))
        emit(LlmStreamEvent.Completed(response))
    }.flowOn(Dispatchers.IO)

    internal fun renderPrompt(request: LlmRequest): String = buildString {
        append("<start_of_turn>user\n")
        append(request.system).append("\n\n")
        if (request.tools.isNotEmpty()) {
            append("You can call one tool at a time. To call a tool reply ONLY with JSON ")
            append("{\"tool_call\":{\"name\":\"<tool>\",\"input\":{...}}}. Tools:\n")
            request.tools.forEach { append("- ").append(it.name).append(": ").append(it.description)
                .append(" input schema ").append(it.inputSchema.toString()).append('\n') }
            append("When you are finished reply with the final answer instead.\n\n")
        }
        request.messages.forEach { message ->
            message.content.forEach { part ->
                when (part) {
                    is LlmContent.Text -> append(if (message.role == LlmRole.USER) "" else "[assistant] ").append(part.text).append('\n')
                    is LlmContent.ToolUse -> append("[assistant called ${part.name} with ${part.input}]\n")
                    is LlmContent.ToolResult -> append("[result of ${part.toolName}]: ").append(JsonText.truncate(part.content, 3000)).append('\n')
                }
            }
        }
        append("<end_of_turn>\n<start_of_turn>model\n")
    }

    internal fun parseResponse(raw: String, request: LlmRequest): LlmResponse {
        val json = JsonText.extract(raw)
        val toolCall = json?.let { runCatching { TubeJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.get("tool_call") as? JsonObject
        val usage = TokenUsage(request.messages.sumOf { m -> m.content.sumOf { estimateTokens(it) } }, raw.length / 4L)
        if (toolCall != null && request.tools.isNotEmpty()) {
            val name = (toolCall["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val input = toolCall["input"] as? JsonObject ?: JsonObject(emptyMap())
            return LlmResponse(listOf(LlmContent.ToolUse("local_${UUID.randomUUID()}", name, input)), usage, StopReason.TOOL_USE, "on-device", id)
        }
        return LlmResponse(listOf(LlmContent.Text(raw.trim())), usage, StopReason.END_TURN, "on-device", id)
    }

    private fun estimateTokens(content: LlmContent): Long = when (content) {
        is LlmContent.Text -> content.text.length / 4L
        is LlmContent.ToolUse -> content.input.toString().length / 4L
        is LlmContent.ToolResult -> content.content.length / 4L
    }

    private companion object {
        const val MAX_CONTEXT_TOKENS = 4096
    }
}
