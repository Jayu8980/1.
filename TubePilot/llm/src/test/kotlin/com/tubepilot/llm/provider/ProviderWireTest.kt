package com.tubepilot.llm.provider

import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.Effort
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmMessage
import com.tubepilot.llm.LlmRequest
import com.tubepilot.llm.LlmRole
import com.tubepilot.llm.StopReason
import com.tubepilot.llm.ToolSpec
import com.tubepilot.testing.FakeSecretStore
import com.tubepilot.testing.FakeSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the real provider code (Anthropic SDK, OpenAI SSE parsing) against a mock HTTP server. */
class ProviderWireTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private val toolSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("maxIdeas", buildJsonObject { put("type", "integer") }) })
        put("required", JsonArray(listOf(JsonPrimitive("maxIdeas"))))
    }

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" })

    @Test
    fun `anthropic provider streams text and tool use through the official SDK`() = runBlocking {
        server.enqueue(
            sse(
                "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":8}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Scanning trends"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"trend_scan","input":{}}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"maxIdeas\": 3}"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )
        val provider = AnthropicProvider(FakeSecretStore(mapOf(SecretKey.ANTHROPIC_API_KEY to "sk-test")), FakeSettingsRepository(AppSettings()))
        provider.baseUrl = server.url("/").toString().trimEnd('/')

        val response = provider.complete(
            LlmRequest(
                model = "claude-opus-5-5",
                system = "You are the Trend Researcher.",
                messages = listOf(LlmMessage.user("Find ideas")),
                tools = listOf(ToolSpec("trend_scan", "Scan trends", toolSchema)),
                effort = Effort.HIGH,
            ),
        )

        assertEquals("Scanning trends", response.text)
        val call = response.toolCalls.single()
        assertEquals("trend_scan", call.name)
        assertEquals(3, call.input["maxIdeas"]!!.jsonPrimitive.int)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(20, response.usage.outputTokens)
        assertEquals(8, response.usage.cacheReadTokens)
        assertNotNull(response.native)

        val recorded = server.takeRequest()
        // The SDK sends the key either as x-api-key or (SDK 3.x) as a bearer credential.
        val auth = recorded.getHeader("x-api-key") ?: recorded.getHeader("Authorization")?.removePrefix("Bearer ")
        assertEquals("sk-test", auth)
        assertTrue(recorded.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        val body = TubeJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("trend_scan", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

        // The next turn replays the provider-native assistant blocks and sends the tool result.
        server.enqueue(
            sse(
                "message_start" to """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":30,"output_tokens":1}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\"ideas\":[]}"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )
        val second = provider.complete(
            LlmRequest(
                model = "claude-opus-5-5",
                system = "You are the Trend Researcher.",
                messages = listOf(
                    LlmMessage.user("Find ideas"),
                    response.asAssistantMessage(),
                    LlmMessage(LlmRole.USER, listOf(LlmContent.ToolResult("toolu_1", "trend_scan", "{\"ideas\":[]}"))),
                ),
                tools = listOf(ToolSpec("trend_scan", "Scan trends", toolSchema)),
            ),
        )
        assertEquals(StopReason.END_TURN, second.stopReason)
        val secondBody = TubeJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = secondBody["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(blocks.any { it["type"]!!.jsonPrimitive.content == "tool_use" && it["id"]!!.jsonPrimitive.content == "toolu_1" })
        val result = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai provider assembles tool call arguments split across chunks`() = runBlocking {
        val chunks = listOf(
            """{"model":"gpt-5-mini","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"keyword_research","arguments":"{\"se"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ed\":\"phone camera\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":10}}}""",
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"))
        val provider = OpenAiProvider(OkHttpClient(), FakeSecretStore(mapOf(SecretKey.OPENAI_API_KEY to "sk-openai")))
        provider.baseUrl = server.url("/v1").toString()

        val response = provider.complete(LlmRequest("gpt-5-mini", "sys", listOf(LlmMessage.user("go")), tools = listOf(ToolSpec("keyword_research", "kw", toolSchema))))

        val call = response.toolCalls.single()
        assertEquals("call_9", call.id)
        assertEquals("phone camera", call.input["seed"]!!.jsonPrimitive.content)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(10, response.usage.cacheReadTokens)
        val body = TubeJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("function", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini schema sanitiser removes unsupported keywords recursively`() {
        val provider = GeminiProvider(OkHttpClient(), FakeSecretStore())
        val schema = TubeJson.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{"a":{"type":"string","default":"x"},"b":{"type":"object","additionalProperties":false,"properties":{}}}}""",
        )
        val clean = provider.sanitizeSchema(schema) as JsonObject
        assertTrue("additionalProperties" !in clean)
        val props = clean["properties"]!!.jsonObject
        assertTrue("default" !in props["a"]!!.jsonObject)
        assertTrue("additionalProperties" !in props["b"]!!.jsonObject)
    }
}
