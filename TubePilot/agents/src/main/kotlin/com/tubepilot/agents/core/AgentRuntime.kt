package com.tubepilot.agents.core

import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.BudgetExceededException
import com.tubepilot.core.error.ContractViolationException
import com.tubepilot.core.error.PolicyBlockedException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.error.TubePilotException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.LessonCategory
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
import com.tubepilot.skills.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** How the pipeline should retry an agent's stage when it fails with a retryable error. */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialBackoffMs: Long = 30_000,
    val maxBackoffMs: Long = 60 * 60_000,
)

/** Builds the memory section of an agent's system prompt. */
@Singleton
class MemoryContextBuilder @Inject constructor(private val memory: MemoryRepository) {
    suspend fun build(focus: Set<LessonCategory>, includePerformance: Boolean): String {
        val profile = memory.profile()
        val style = memory.styleGuide()
        val focused = if (focus.isEmpty()) emptyList() else memory.activeLessons(8, focus)
        val general = memory.activeLessons(8).filter { g -> focused.none { it.id == g.id } }
        return buildString {
            append("## Channel profile\n").append(PrettyJson.encodeToString(com.tubepilot.core.model.ChannelProfile.serializer(), profile)).append("\n\n")
            append("## Style guide\n").append(PrettyJson.encodeToString(com.tubepilot.core.model.StyleGuide.serializer(), style)).append("\n\n")
            append("## Lessons learned\n")
            val lessons = focused + general
            if (lessons.isEmpty()) append("(none yet)\n") else lessons.forEach { append("- [${it.category}] ${it.text}\n") }
            if (includePerformance) {
                val perf = memory.recentPerformance(10)
                append("\n## Recent video performance\n")
                if (perf.isEmpty()) append("(no data yet)\n")
                perf.forEach {
                    append("- \"${it.title}\" (${it.format}): ${it.views} views, CTR ${it.ctrPercent?.let { c -> "%.1f%%".format(c) } ?: "n/a"}, ")
                    append("avg viewed ${it.avgViewPercentage?.let { p -> "%.0f%%".format(p) } ?: "n/a"}, +${it.subscribersGained} subs\n")
                }
            }
        }
    }
}

/**
 * Shared machinery for all agents: lifecycle (status, audit, kill switch) and the LLM tool loop in
 * which the model picks skills to call. Each tool call is executed by the SkillRegistry, which
 * enforces per-agent enablement and logs it.
 */
@Singleton
class AgentRuntime @Inject constructor(
    val registry: SkillRegistry,
    val gateway: LlmGateway,
    val memoryContext: MemoryContextBuilder,
    private val status: AgentStatusRepository,
    val audit: AuditLog,
    val settings: SettingsRepository,
) {
    /** Runs [block] as a unit of agent work with status tracking and auditing. */
    suspend fun <T> task(role: AgentRole, taskName: String, projectId: String?, block: suspend () -> T): T {
        if (settings.current().killSwitch) {
            status.set(role, AgentState.PAUSED, null, projectId)
            throw AgentsPausedException()
        }
        status.set(role, AgentState.WORKING, taskName, projectId)
        audit.record(role.displayName, "agent.start", taskName, projectId, AuditLevel.INFO)
        return try {
            val result = block()
            status.set(role, AgentState.IDLE, null, null)
            audit.record(role.displayName, "agent.done", taskName, projectId, AuditLevel.INFO)
            result
        } catch (e: CancellationException) {
            status.set(role, AgentState.IDLE, null, null)
            throw e
        } catch (e: Throwable) {
            val state = when (e) {
                is AgentsPausedException, is BudgetExceededException -> AgentState.PAUSED
                is QuotaExhaustedException -> AgentState.WAITING
                else -> AgentState.ERROR
            }
            status.set(role, state, taskName, projectId, e.message)
            audit.record(role.displayName, "agent.error", "$taskName: ${e.message}", projectId, if (state == AgentState.ERROR) AuditLevel.ERROR else AuditLevel.WARNING)
            throw e
        }
    }

    suspend fun setCallingSkill(role: AgentRole, skillId: String, projectId: String?) =
        status.set(role, AgentState.CALLING_SKILL, "calling $skillId", projectId)

    /**
     * The agent loop. The model sees the role prompt, memory, the task and the agent's enabled
     * skills as tools. It may call skills for up to [maxSteps] turns, then must answer with JSON
     * matching [outputExample]'s shape. If [deliverableSkill] was called successfully its output is
     * decoded as the result, so large artefacts (scripts, reports) are not re-typed by the model.
     */
    suspend fun <O> reason(
        role: AgentRole,
        rolePrompt: String,
        focus: Set<LessonCategory>,
        task: String,
        input: JsonObject,
        output: KSerializer<O>,
        outputExample: O,
        projectId: String?,
        deliverableSkill: String? = null,
        maxSteps: Int = 6,
        effort: Effort = Effort.MEDIUM,
        includePerformance: Boolean = false,
    ): O {
        val tools = registry.toolsFor(role)
        val system = buildString {
            append(rolePrompt.trim()).append("\n\n")
            append("# Ground rules\n")
            append("- You work for one creator on their own YouTube channel through official APIs only.\n")
            append("- Produce original or genuinely transformed content. Never plan re-uploads of other people's videos, music or art.\n")
            append("- Follow YouTube's Community Guidelines and the channel's banned topics.\n")
            append("- Use the tools (skills) when they help; call them with valid JSON matching their schema.\n\n")
            append("# Memory\n").append(memoryContext.build(focus, includePerformance)).append('\n')
            append("# Final answer\nWhen finished, reply with ONLY a JSON object shaped like this example:\n")
            append(PrettyJson.encodeToString(output, outputExample))
        }
        val messages = mutableListOf(
            LlmMessage.user("## Task\n$task\n\n## Input\n" + PrettyJson.encodeToString(JsonObject.serializer(), input)),
        )
        val context = LlmCallContext(role, projectId, "agent:${role.name.lowercase()}")
        var deliverable: JsonObject? = null

        for (step in 0..maxSteps) {
            val forceFinish = step == maxSteps
            if (forceFinish) {
                messages += LlmMessage.user("You have used all tool turns. Do not call any more tools; reply now with the final JSON object.")
            }
            val response = gateway.complete(context, LlmCall(system, messages.toList(), tools, maxTokens = 16_000, effort = effort))
            if (response.stopReason == StopReason.REFUSAL) throw PolicyBlockedException("${role.displayName}: the model declined this task")

            val calls = response.toolCalls
            if (calls.isEmpty() || forceFinish) {
                deliverable?.let { return decode(output, it, it.toString()) }
                return parseFinal(context, system, messages + response.asAssistantMessage(), tools, response.text, output)
            }

            messages += response.asAssistantMessage()
            val results = calls.map { call ->
                setCallingSkill(role, call.name, projectId)
                try {
                    val outcome = registry.invoke(role, call.name, call.input, projectId)
                    if (call.name == deliverableSkill) deliverable = outcome.output
                    LlmContent.ToolResult(call.id, call.name, JsonText.truncate(outcome.output.toString(), 30_000))
                } catch (e: AgentsPausedException) {
                    throw e
                } catch (e: BudgetExceededException) {
                    throw e
                } catch (e: QuotaExhaustedException) {
                    LlmContent.ToolResult(call.id, call.name, "Quota for ${e.bucket} is exhausted today. Continue without this tool.", isError = true)
                } catch (e: TubePilotException) {
                    LlmContent.ToolResult(call.id, call.name, "Skill failed: ${e.message}", isError = true)
                } catch (e: IllegalArgumentException) {
                    LlmContent.ToolResult(call.id, call.name, "Invalid input: ${e.message}", isError = true)
                }
            }
            status.set(role, AgentState.WORKING, "reasoning", projectId)
            messages += LlmMessage(LlmRole.USER, results)
        }
        throw ContractViolationException("${role.displayName} did not finish", "")
    }

    private suspend fun <O> parseFinal(
        context: LlmCallContext,
        system: String,
        history: List<LlmMessage>,
        tools: List<com.tubepilot.llm.ToolSpec>,
        text: String,
        output: KSerializer<O>,
    ): O {
        tryDecode(output, text)?.let { return it }
        val repair = gateway.complete(
            context.copy(purpose = context.purpose + ":repair"),
            LlmCall(
                system, history + LlmMessage.user("Your reply was not valid JSON for the required shape. Reply with only the corrected JSON object, no tools."),
                tools, maxTokens = 16_000, effort = Effort.LOW,
            ),
        )
        return tryDecode(output, repair.text) ?: throw ContractViolationException("${context.agent?.displayName} returned invalid JSON", JsonText.truncate(repair.text, 2000))
    }

    private fun <O> tryDecode(output: KSerializer<O>, text: String): O? {
        val json = JsonText.extract(text) ?: return null
        return runCatching { TubeJson.decodeFromString(output, json) }.getOrNull()
    }

    private fun <O> decode(output: KSerializer<O>, obj: JsonObject, raw: String): O =
        runCatching { TubeJson.decodeFromJsonElement(output, obj) }.getOrElse {
            throw ContractViolationException("Skill output does not match ${output.descriptor.serialName}: ${it.message}", JsonText.truncate(raw, 2000))
        }

    /** Invokes a skill directly (no LLM decision) while keeping agent status and audit in sync. */
    suspend fun <O> skill(role: AgentRole, skillId: String, input: JsonObject, projectId: String?, output: KSerializer<O>): O {
        setCallingSkill(role, skillId, projectId)
        val result = registry.invoke(role, skillId, input, projectId).output
        status.set(role, AgentState.WORKING, "processing $skillId result", projectId)
        return decode(output, result, result.toString())
    }

    suspend fun skillRaw(role: AgentRole, skillId: String, input: JsonObject, projectId: String?): JsonObject {
        setCallingSkill(role, skillId, projectId)
        return registry.invoke(role, skillId, input, projectId).output.also {
            status.set(role, AgentState.WORKING, "processing $skillId result", projectId)
        }
    }

    /** True when [skillId] is enabled for [role] (agents adapt when the user disables a skill). */
    suspend fun hasSkill(role: AgentRole, skillId: String): Boolean = registry.enabledFor(role).any { it.id == skillId }
}

/** Base class: every agent has a role, a role prompt, memory focus and a retry policy. */
abstract class Agent(val role: AgentRole, protected val runtime: AgentRuntime) {
    abstract val rolePrompt: String
    open val memoryFocus: Set<LessonCategory> = emptySet()
    open val retryPolicy: RetryPolicy = RetryPolicy()

    /** Ids of the skills this agent is designed to use (the Skills Store shows the live state). */
    abstract val skillIds: List<String>

    protected suspend fun <T> task(name: String, projectId: String?, block: suspend () -> T): T = runtime.task(role, name, projectId, block)
}
