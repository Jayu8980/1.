package com.tubepilot.skills

import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.SkillException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.LlmGateway
import com.tubepilot.testing.FakeAuditLog
import com.tubepilot.testing.FakeMemoryRepository
import com.tubepilot.testing.FakeSettingsRepository
import com.tubepilot.testing.FakeSkillStateRepository
import com.tubepilot.testing.FakeTime
import com.tubepilot.testing.FakeUsageRepository
import com.tubepilot.testing.ScriptedLlmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun schema(required: List<String> = emptyList(), vararg props: Pair<String, String>) = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(props.associate { (k, t) -> k to buildJsonObject { put("type", t) } }))
    if (required.isNotEmpty()) put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
}

private fun manifest(id: String, agents: List<AgentRole>, defaultEnabled: Boolean = true, executor: String? = id) = SkillManifest(
    id = id, name = id.replace('_', ' '), description = "Test skill $id",
    inputs = schema(listOf("topic"), "topic" to "string", "count" to "integer"),
    outputs = schema(emptyList(), "result" to "string"),
    agents = agents, defaultEnabled = defaultEnabled, executor = executor,
)

private class EchoExecutor(override val id: String) : SkillExecutor {
    var calls = 0
    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        calls++
        return buildJsonObject { put("result", "echo:${(input["topic"] as JsonPrimitive).content}") }
    }
}

class SkillRegistryTest {
    private val state = FakeSkillStateRepository()
    private val audit = FakeAuditLog()
    private val settings = FakeSettingsRepository(AppSettings(onboardingComplete = true))
    private val echo = EchoExecutor("echo")
    private val provider = ScriptedLlmProvider()
    private val time = FakeTime()
    private val runtime = SkillRuntime(LlmGateway(setOf(provider), settings, FakeUsageRepository(), audit, time), FakeMemoryRepository())
    private val builtIns = object : BuiltInSkillSource {
        override suspend fun load() = listOf(
            Skill(manifest("echo", listOf(AgentRole.TREND_RESEARCHER, AgentRole.SEO_SPECIALIST)), "Echo {{input.topic}}", builtIn = true),
            Skill(manifest("quiet", listOf(AgentRole.TREND_RESEARCHER), defaultEnabled = false, executor = "echo"), "", builtIn = true),
        )
    }
    private val registry = SkillRegistry(builtIns, mapOf("echo" to echo, Skill.PROMPT_EXECUTOR to PromptSkillExecutor()), state, settings, audit, runtime, time)
    private val input = buildJsonObject { put("topic", "phones") }

    @Test
    fun `invokes an enabled skill and logs the call`() = runTest {
        val outcome = registry.invoke(AgentRole.TREND_RESEARCHER, "echo", input, "p1")
        assertEquals("echo:phones", (outcome.output["result"] as JsonPrimitive).content)
        val record = state.calls.single()
        assertTrue(record.success)
        assertEquals("echo", record.skillId)
        assertEquals(AgentRole.TREND_RESEARCHER, record.agent)
        assertTrue("skill.call" in audit.actions())
    }

    @Test
    fun `exposes only enabled skills as tools, respecting defaults and per-agent overrides`() = runTest {
        assertEquals(listOf("echo"), registry.toolsFor(AgentRole.TREND_RESEARCHER).map { it.name })
        registry.setEnabled("quiet", AgentRole.TREND_RESEARCHER, true)
        registry.setEnabled("echo", AgentRole.TREND_RESEARCHER, false)
        assertEquals(listOf("quiet"), registry.toolsFor(AgentRole.TREND_RESEARCHER).map { it.name })
        // The override is per agent: SEO still has echo.
        assertEquals(listOf("echo"), registry.toolsFor(AgentRole.SEO_SPECIALIST).map { it.name })
    }

    @Test
    fun `rejects skills the agent may not use or that are disabled`() = runTest {
        assertFailsWith<SkillException> { registry.invoke(AgentRole.SCRIPTWRITER, "echo", input, null) }
        assertFailsWith<SkillException> { registry.invoke(AgentRole.TREND_RESEARCHER, "quiet", input, null) }
        assertFailsWith<SkillException> { registry.invoke(AgentRole.TREND_RESEARCHER, "missing", input, null) }
        assertEquals(0, echo.calls)
    }

    @Test
    fun `validates input against the manifest schema`() = runTest {
        val e = assertFailsWith<SkillException> { registry.invoke(AgentRole.TREND_RESEARCHER, "echo", buildJsonObject { put("count", "three") }, null) }
        assertTrue(e.message!!.contains("missing required field 'topic'"))
        assertTrue(e.message!!.contains("'count' should be integer"))
    }

    @Test
    fun `kill switch blocks skill calls`() = runTest {
        settings.update { it.copy(killSwitch = true) }
        assertFailsWith<AgentsPausedException> { registry.invoke(AgentRole.TREND_RESEARCHER, "echo", input, null) }
    }

    @Test
    fun `failed executions are logged as failures`() = runTest {
        val failing = SkillRegistry(
            builtIns, mapOf("echo" to object : SkillExecutor {
                override val id = "echo"
                override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject = throw IllegalStateException("boom")
            }), state, settings, audit, runtime, time,
        )
        assertFailsWith<SkillException> { failing.invoke(AgentRole.TREND_RESEARCHER, "echo", input, null) }
        assertFalse(state.calls.single().success)
        assertEquals("boom", state.calls.single().error)
    }

    @Test
    fun `imports a prompt skill from a zip and runs it through the LLM`() = runTest {
        val m = manifest("my_hooks", listOf(AgentRole.SCRIPTWRITER), executor = null)
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("my_hooks/skill.json")); z.write(TubeJson.encodeToString(SkillManifest.serializer(), m).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("my_hooks/prompt.md")); z.write("Write hooks about {{input.topic}} for {{niche}}".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val skill = registry.import(zip, "my_hooks.zip")
        assertEquals("my_hooks", skill.id)
        assertTrue(state.importedMap.containsKey("my_hooks"))

        provider.enqueueText("""Here you go: {"result":"3 hooks"}""")
        val out = registry.invoke(AgentRole.SCRIPTWRITER, "my_hooks", input, null).output
        assertEquals("3 hooks", (out["result"] as JsonPrimitive).content)
        val prompt = (provider.requests.single().messages.single().content.single() as com.tubepilot.llm.LlmContent.Text).text
        assertEquals("Write hooks about phones for Budget phones", prompt)
    }

    @Test
    fun `imported skills are sandboxed`() = runTest {
        fun pkg(m: SkillManifest) = (TubeJson.encodeToJsonElement(SkillManifest.serializer(), m).jsonObject + ("prompt" to JsonPrimitive("p"))).let { JsonObject(it).toString().toByteArray() }
        assertFailsWith<SkillValidationException> { registry.import(pkg(manifest("echo", listOf(AgentRole.SCRIPTWRITER), executor = null)), "dup.json") }
        assertFailsWith<SkillValidationException> { registry.import(pkg(manifest("native_one", listOf(AgentRole.SCRIPTWRITER), executor = "echo")), "x.json") }
        assertFailsWith<SkillValidationException> {
            registry.import(pkg(manifest("uploader", listOf(AgentRole.SCRIPTWRITER), executor = null).copy(permissions = listOf(SkillPermission.YOUTUBE_WRITE))), "x.json")
        }
        assertFailsWith<SkillValidationException> { registry.import("not a skill".toByteArray(), "x.txt") }
        assertTrue(state.importedMap.isEmpty())
    }
}
