package com.tubepilot.skills

import com.tubepilot.core.model.AgentRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a skill is allowed to touch. Shown to the user when enabling or importing a skill. */
@Serializable
enum class SkillPermission(val label: String) {
    LLM("Call the language model"),
    MEMORY_READ("Read channel memory"),
    MEMORY_WRITE("Write channel memory"),
    YOUTUBE_READ("Read YouTube data"),
    YOUTUBE_WRITE("Change YouTube data"),
    MEDIA_RENDER("Render audio/video on device"),
    STORAGE("Read and write project files"),
    NETWORK("Call third-party media APIs"),
}

/** Contents of skill.json. */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String,
    /** JSON Schema (type object) of the input; also used as the LLM tool input schema. */
    val inputs: JsonObject,
    /** JSON Schema of the output the skill returns. */
    val outputs: JsonObject,
    val permissions: List<SkillPermission> = listOf(SkillPermission.LLM),
    /** Agents allowed to use the skill. */
    val agents: List<AgentRole>,
    val defaultEnabled: Boolean = true,
    /** Built-in Kotlin executor id; null means the generic prompt executor. */
    val executor: String? = null,
    val promptFile: String = "prompt.md",
    val author: String = "TubePilot",
    val tags: List<String> = emptyList(),
)

data class Skill(val manifest: SkillManifest, val promptTemplate: String, val builtIn: Boolean) {
    val id: String get() = manifest.id
    val executorId: String get() = manifest.executor ?: PROMPT_EXECUTOR

    companion object {
        const val PROMPT_EXECUTOR = "prompt"
    }
}

/** Effective state of one skill for one agent, for the Skills Store. */
data class SkillAssignment(val skill: Skill, val role: AgentRole, val enabled: Boolean)

/** Runs a skill. Built-in executors are Kotlin classes; imported skills use the prompt executor. */
interface SkillExecutor {
    val id: String
    suspend fun execute(context: SkillContext, input: JsonObject): JsonObject
}

data class SkillContext(
    val skill: Skill,
    val agent: AgentRole,
    val projectId: String?,
    val runtime: SkillRuntime,
)
