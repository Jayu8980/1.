package com.tubepilot.skills

import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.error.ContractViolationException
import com.tubepilot.core.error.PolicyBlockedException
import com.tubepilot.core.util.JsonText
import com.tubepilot.core.util.PrettyJson
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.Effort
import com.tubepilot.llm.LlmCall
import com.tubepilot.llm.LlmCallContext
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmGateway
import com.tubepilot.llm.LlmMessage
import com.tubepilot.llm.LlmRole
import com.tubepilot.llm.StopReason
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** `{{name}}` / `{{input.field}}` substitution. Unknown variables render as empty strings. */
object PromptTemplate {
    private val VAR = Regex("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}")

    fun render(template: String, vars: Map<String, String>, input: JsonObject? = null): String =
        VAR.replace(template) { m ->
            val key = m.groupValues[1]
            vars[key] ?: if (key.startsWith("input.") && input != null) lookup(input, key.removePrefix("input.")) else ""
        }

    private fun lookup(root: JsonObject, path: String): String {
        var node: JsonElement? = root
        for (part in path.split('.')) node = (node as? JsonObject)?.get(part)
        return when (node) {
            null -> ""
            is JsonPrimitive -> node.content
            else -> node.toString()
        }
    }

    fun variables(template: String): Set<String> = VAR.findAll(template).map { it.groupValues[1] }.toSet()
}

/** Services shared by skill executors: prompt execution with JSON contracts and memory context. */
@Singleton
class SkillRuntime @Inject constructor(
    private val gateway: LlmGateway,
    private val memory: MemoryRepository,
) {
    /** Memory variables every prompt template can use. */
    suspend fun memoryVariables(): Map<String, String> {
        val profile = memory.profile()
        val style = memory.styleGuide()
        val lessons = memory.activeLessons(limit = 12)
        return mapOf(
            "channel" to PrettyJson.encodeToString(com.tubepilot.core.model.ChannelProfile.serializer(), profile),
            "style" to PrettyJson.encodeToString(com.tubepilot.core.model.StyleGuide.serializer(), style),
            "lessons" to if (lessons.isEmpty()) "(no lessons recorded yet)" else lessons.joinToString("\n") {
                "- [${it.category}] ${it.text} (confidence ${"%.0f".format(it.confidence * 100)}%)"
            },
            "niche" to profile.niche,
            "audience" to profile.audience,
            "tone" to profile.tone,
            "banned_topics" to profile.bannedTopics.joinToString(", ").ifEmpty { "(none)" },
        )
    }

    /**
     * Renders the skill's prompt template with [vars] (plus memory and the input), asks the model
     * for JSON that matches the skill's output schema, and returns the parsed object. One repair
     * round is attempted if the first answer is not valid JSON.
     */
    suspend fun runPrompt(
        context: SkillContext,
        input: JsonObject,
        vars: Map<String, String> = emptyMap(),
        effort: Effort = Effort.MEDIUM,
        maxTokens: Int = 12_000,
    ): JsonObject {
        val manifest = context.skill.manifest
        val allVars = memoryVariables() + vars + mapOf("input" to PrettyJson.encodeToString(JsonObject.serializer(), input))
        val userPrompt = PromptTemplate.render(context.skill.promptTemplate, allVars, input)
        val system = buildString {
            append("You are the \"${manifest.name}\" skill inside TubePilot, an assistant that helps one creator run their own YouTube channel. ")
            append("Only produce original content. Never suggest re-uploading or copying other creators' videos, music or artwork, never impersonate real people, ")
            append("and follow YouTube's Community Guidelines.\n\n")
            append("Respond with a single JSON object and nothing else. It must conform to this JSON Schema:\n")
            append(PrettyJson.encodeToString(JsonObject.serializer(), manifest.outputs))
        }
        val call = LlmCall(
            system = system,
            messages = listOf(LlmMessage.user(userPrompt)),
            maxTokens = maxTokens,
            jsonOutput = true,
            effort = effort,
        )
        val llmContext = LlmCallContext(context.agent, context.projectId, "skill:${manifest.id}")
        val first = gateway.complete(llmContext, call)
        if (first.stopReason == StopReason.REFUSAL) {
            throw PolicyBlockedException("The model declined the ${manifest.name} request. Review the topic for policy issues.")
        }
        parseObject(first.text)?.let { return it }

        // Repair round: show the model its answer and ask for valid JSON only.
        val repair = gateway.complete(
            llmContext.copy(purpose = "skill:${manifest.id}:repair"),
            call.copy(
                messages = call.messages + first.asAssistantMessage() +
                    LlmMessage(LlmRole.USER, listOf(LlmContent.Text("That was not a single valid JSON object matching the schema. Reply again with only the corrected JSON object."))),
                effort = Effort.LOW,
            ),
        )
        return parseObject(repair.text)
            ?: throw ContractViolationException("${manifest.id} returned invalid JSON", JsonText.truncate(repair.text, 2000))
    }

    fun parseObject(text: String): JsonObject? {
        val json = JsonText.extract(text) ?: return null
        return runCatching { TubeJson.parseToJsonElement(json).jsonObject }.getOrNull()
    }
}

/** Generic executor for prompt-only skills (all imported skills and several built-ins). */
@Singleton
class PromptSkillExecutor @Inject constructor() : SkillExecutor {
    override val id = Skill.PROMPT_EXECUTOR
    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject = context.runtime.runPrompt(context, input)
}
