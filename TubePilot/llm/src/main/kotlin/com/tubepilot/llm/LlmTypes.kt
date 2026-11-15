package com.tubepilot.llm

import com.tubepilot.core.model.LlmProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** A tool (skill) the model may call. [inputSchema] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

sealed interface LlmContent {
    data class Text(val text: String) : LlmContent
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : LlmContent
    data class ToolResult(val toolUseId: String, val toolName: String, val content: String, val isError: Boolean = false) : LlmContent
}

enum class LlmRole { USER, ASSISTANT }

/**
 * One conversation turn. [native] carries the provider's own representation of an assistant turn
 * (for example Claude thinking blocks or Gemini thought signatures) so it can be replayed verbatim
 * on the next request of a tool loop; providers ignore native payloads that are not theirs.
 */
data class LlmMessage(
    val role: LlmRole,
    val content: List<LlmContent>,
    val native: NativeContent? = null,
) {
    companion object {
        fun user(text: String) = LlmMessage(LlmRole.USER, listOf(LlmContent.Text(text)))
    }
}

data class NativeContent(val provider: LlmProviderId, val payload: Any)

enum class Effort { LOW, MEDIUM, HIGH }

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 16_000,
    /** Ask for a JSON-only answer where the provider supports a native JSON mode. */
    val jsonOutput: Boolean = false,
    val effort: Effort = Effort.MEDIUM,
)

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

data class LlmResponse(
    val content: List<LlmContent>,
    val usage: TokenUsage,
    val stopReason: StopReason,
    val model: String,
    val provider: LlmProviderId,
    val native: NativeContent? = null,
) {
    val text: String get() = content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }
    val toolCalls: List<LlmContent.ToolUse> get() = content.filterIsInstance<LlmContent.ToolUse>()

    /** The assistant turn to append to the conversation, preserving provider-native blocks. */
    fun asAssistantMessage(): LlmMessage = LlmMessage(LlmRole.ASSISTANT, content, native)
}

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class Completed(val response: LlmResponse) : LlmStreamEvent
}

interface LlmProvider {
    val id: LlmProviderId

    /** True when credentials (or the on-device model file) are available. */
    suspend fun isConfigured(): Boolean

    suspend fun complete(request: LlmRequest): LlmResponse

    /** Streams text deltas and always ends with exactly one [LlmStreamEvent.Completed]. */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>
}
