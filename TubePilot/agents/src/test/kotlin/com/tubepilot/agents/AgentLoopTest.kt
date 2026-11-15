package com.tubepilot.agents

import com.tubepilot.agents.contracts.IdeaRequest
import com.tubepilot.agents.contracts.ScriptRequest
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.agents.core.MemoryContextBuilder
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ScriptScene
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmGateway
import com.tubepilot.skills.BuiltInSkillSource
import com.tubepilot.skills.Skill
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import com.tubepilot.skills.SkillManifest
import com.tubepilot.skills.SkillRegistry
import com.tubepilot.skills.SkillRuntime
import com.tubepilot.testing.FakeAgentStatusRepository
import com.tubepilot.testing.FakeAuditLog
import com.tubepilot.testing.FakeMemoryRepository
import com.tubepilot.testing.FakeSettingsRepository
import com.tubepilot.testing.FakeSkillStateRepository
import com.tubepilot.testing.FakeTime
import com.tubepilot.testing.FakeUsageRepository
import com.tubepilot.testing.ScriptedLlmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentLoopTest {
    private val provider = ScriptedLlmProvider()
    private val settings = FakeSettingsRepository(AppSettings(onboardingComplete = true))
    private val audit = FakeAuditLog()
    private val status = FakeAgentStatusRepository()
    private val skillState = FakeSkillStateRepository()
    private val memory = FakeMemoryRepository()
    private val time = FakeTime()
    private val gateway = LlmGateway(setOf(provider), settings, FakeUsageRepository(), audit, time)

    private val script = VideoScript(
        "5 camera tricks", ContentLanguage.HINGLISH, VideoFormat.SHORT, "Ruko! Ye trick try karo",
        listOf(ScriptScene(0, "Ruko! Ye trick try karo", "phone on a desk"), ScriptScene(1, "Lens saaf karo", "cloth wiping a lens")), "Subscribe karo", 30,
    )
    private val scriptJson = TubeJson.encodeToJsonElement(VideoScript.serializer(), script).jsonObject
    private var scriptWriterCalls = 0

    private fun skill(id: String, role: AgentRole) = Skill(
        SkillManifest(
            id = id, name = id, description = "fake $id",
            inputs = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) },
            outputs = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) },
            agents = listOf(role), executor = id,
        ),
        "", builtIn = true,
    )

    private val registry = SkillRegistry(
        object : BuiltInSkillSource {
            override suspend fun load() = listOf(skill("hook_generator", AgentRole.SCRIPTWRITER), skill("script_writer", AgentRole.SCRIPTWRITER), skill("trend_scan", AgentRole.TREND_RESEARCHER))
        },
        mapOf(
            "hook_generator" to executor("hook_generator") { buildJsonObject { put("hooks", TubeJson.parseToJsonElement("""[{"text":"Ruko!","pattern":"curiosity_gap"}]""")) } },
            "script_writer" to executor("script_writer") { scriptWriterCalls++; scriptJson },
            "trend_scan" to executor("trend_scan") { buildJsonObject { put("ideas", TubeJson.parseToJsonElement("[]")) } },
        ),
        skillState, settings, audit, SkillRuntime(gateway, memory), time,
    )
    private val runtime = AgentRuntime(registry, gateway, MemoryContextBuilder(memory), status, audit, settings)

    private fun executor(id: String, block: () -> JsonObject) = object : SkillExecutor {
        override val id = id
        override suspend fun execute(context: SkillContext, input: JsonObject) = block()
    }

    private val request = ScriptRequest("5 camera tricks", VideoFormat.SHORT, ContentLanguage.HINGLISH, "{}")

    @Test
    fun `scriptwriter picks skills by tool calling and returns the deliverable`() = runTest {
        provider.enqueueToolCall("hook_generator", buildJsonObject { put("topic", "camera") })
            .enqueueToolCall("script_writer", buildJsonObject { put("title", "5 camera tricks") })
            .enqueueText("""{"done":true}""")

        val result = ScriptwriterAgent(runtime).write(request, "p1")

        assertEquals(script.scenes, result.scenes)
        assertEquals(3, provider.requests.size)
        // The model was offered exactly the skills enabled for the Scriptwriter.
        assertEquals(setOf("hook_generator", "script_writer"), provider.requests.first().tools.map { it.name }.toSet())
        // Tool results are sent back to the model in the next turn.
        val toolResult = provider.requests[1].messages.last().content.single() as LlmContent.ToolResult
        assertEquals("hook_generator", toolResult.toolName)
        assertEquals(listOf("hook_generator", "script_writer"), skillState.calls.map { it.skillId })
        assertEquals(AgentState.IDLE, status.states[AgentRole.SCRIPTWRITER]?.state)
        assertTrue(status.history.any { it.second == AgentState.CALLING_SKILL })
    }

    @Test
    fun `scriptwriter falls back to a direct skill call when the model never delivers`() = runTest {
        provider.enqueueText("I think the script should be about lenses.").enqueueText("still not json")
        val result = ScriptwriterAgent(runtime).write(request, "p1")
        assertEquals(script.title, result.title)
        assertEquals(1, scriptWriterCalls)
    }

    @Test
    fun `disabled skills are not offered and failing tools are reported back to the model`() = runTest {
        registry.setEnabled("hook_generator", AgentRole.SCRIPTWRITER, false)
        provider.enqueueToolCall("hook_generator", JsonObject(emptyMap()))
            .enqueueToolCall("script_writer", JsonObject(emptyMap()))
            .enqueueText("""{"done":true}""")
        ScriptwriterAgent(runtime).write(request, "p1")
        assertEquals(listOf("script_writer"), provider.requests.first().tools.map { it.name })
        val error = provider.requests[1].messages.last().content.single() as LlmContent.ToolResult
        assertTrue(error.isError)
        assertTrue(error.content.contains("disabled"))
    }

    @Test
    fun `trend researcher re-scores and ranks the model's ideas`() = runTest {
        provider.enqueueToolCall("trend_scan", JsonObject(emptyMap())).enqueueText(
            """{"ideas":[
                {"title":"Low","format":"SHORT","demandScore":2,"competitionScore":9,"nicheFitScore":3,"totalScore":9.9},
                {"title":"High","format":"SHORT","demandScore":9,"competitionScore":2,"nicheFitScore":9,"totalScore":1.0}
            ]}""",
        )
        val ideas = TrendResearcherAgent(runtime).findIdeas(IdeaRequest(count = 2)).ideas
        assertEquals(listOf("High", "Low"), ideas.map { it.title })
        assertEquals(0.4 * 9 + 0.25 * 8 + 0.35 * 9, ideas.first().totalScore, 0.01)
    }

    @Test
    fun `kill switch pauses the agent before any model call`() = runTest {
        settings.update { it.copy(killSwitch = true) }
        assertFailsWith<AgentsPausedException> { ScriptwriterAgent(runtime).write(request, "p1") }
        assertTrue(provider.requests.isEmpty())
        assertEquals(AgentState.PAUSED, status.states[AgentRole.SCRIPTWRITER]?.state)
    }

    @Test
    fun `memory context includes channel profile and lessons`() = runTest {
        memory.addLesson(com.tubepilot.core.model.Lesson(text = "Hooks with numbers got higher CTR", category = com.tubepilot.core.model.LessonCategory.HOOK, createdAt = 0))
        provider.enqueueToolCall("script_writer", JsonObject(emptyMap())).enqueueText("""{"done":true}""")
        ScriptwriterAgent(runtime).write(request, "p1")
        val system = provider.requests.first().system
        assertTrue(system.contains("Budget phones"))
        assertTrue(system.contains("Hooks with numbers got higher CTR"))
    }
}
