package com.tubepilot.llm

import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.BudgetExceededException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.ModelChoice
import com.tubepilot.testing.FakeAuditLog
import com.tubepilot.testing.FakeSettingsRepository
import com.tubepilot.testing.FakeTime
import com.tubepilot.testing.FakeUsageRepository
import com.tubepilot.testing.ScriptedLlmProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmGatewayTest {
    private val anthropic = ScriptedLlmProvider(LlmProviderId.ANTHROPIC)
    private val openAi = ScriptedLlmProvider(LlmProviderId.OPENAI)
    private val onDevice = ScriptedLlmProvider(LlmProviderId.ON_DEVICE)
    private val usage = FakeUsageRepository()
    private val audit = FakeAuditLog()
    private val time = FakeTime()

    private fun gateway(settings: AppSettings = AppSettings(onboardingComplete = true)): Pair<LlmGateway, FakeSettingsRepository> {
        val repo = FakeSettingsRepository(settings)
        return LlmGateway(setOf(anthropic, openAi, onDevice), repo, usage, audit, time).also { it.retryBaseDelayMs = 1 } to repo
    }

    private val call = LlmCall(system = "sys", messages = listOf(LlmMessage.user("hi")))
    private val ctx = LlmCallContext(AgentRole.SCRIPTWRITER, "p1", "test")

    @Test
    fun `routes each agent to its configured model`() = runTest {
        val (gw, _) = gateway(AppSettings(agentModels = mapOf(AgentRole.SCRIPTWRITER to ModelChoice(LlmProviderId.OPENAI, "gpt-5-mini"))))
        openAi.enqueueText("ok")
        gw.complete(ctx, call)
        assertEquals("gpt-5-mini", openAi.requests.single().model)
        assertTrue(anthropic.requests.isEmpty())
    }

    @Test
    fun `records token usage and cost from the catalog price`() = runTest {
        val (gw, _) = gateway()
        anthropic.enqueueText("ok", TokenUsage(1_000_000, 1_000_000))
        gw.complete(ctx, call)
        val record = usage.records.single()
        assertEquals("claude-opus-5-5", anthropic.requests.single().model)
        // test-model is unknown, so it is priced at the provider's most expensive known model (Fable 5.2: $8 + $40).
        assertEquals(48.0, record.costUsd, 0.0001)
        assertEquals(AgentRole.SCRIPTWRITER, record.agent)
    }

    @Test
    fun `retries retryable failures then succeeds`() = runTest {
        val (gw, _) = gateway()
        anthropic.enqueueError(ProviderException("Anthropic", "overloaded", 529, retryable = true)).enqueueText("ok")
        val response = gw.complete(ctx, call)
        assertEquals("ok", response.text)
        assertEquals(2, anthropic.requests.size)
        assertTrue("llm.retry" in audit.actions())
    }

    @Test
    fun `does not retry non-retryable failures`() = runTest {
        val (gw, _) = gateway(AppSettings(fallbackToOnDevice = false))
        anthropic.enqueueError(ProviderException("Anthropic", "bad request", 400, retryable = false))
        assertFailsWith<ProviderException> { gw.complete(ctx, call) }
        assertEquals(1, anthropic.requests.size)
    }

    @Test
    fun `falls back to the on-device model when the cloud keeps failing`() = runTest {
        val (gw, _) = gateway(AppSettings(fallbackToOnDevice = true))
        repeat(3) { anthropic.enqueueError(ProviderException("Anthropic", "down", 503, retryable = true)) }
        onDevice.enqueueText("local answer")
        assertEquals("local answer", gw.complete(ctx, call).text)
        assertEquals(3, anthropic.requests.size)
        assertTrue("llm.fallback" in audit.actions())
    }

    @Test
    fun `budget cap auto-pauses agents before calling the provider`() = runTest {
        val (gw, repo) = gateway(AppSettings(monthlyBudgetUsd = 10.0))
        usage.extraSpend = 10.5
        assertFailsWith<BudgetExceededException> { gw.complete(ctx, call) }
        assertTrue(repo.current().budgetPaused)
        assertTrue(anthropic.requests.isEmpty())
        assertTrue("budget.paused" in audit.actions())
    }

    @Test
    fun `kill switch stops every call`() = runTest {
        val (gw, _) = gateway(AppSettings(killSwitch = true))
        assertFailsWith<AgentsPausedException> { gw.complete(ctx, call) }
        assertTrue(anthropic.requests.isEmpty())
    }

    @Test
    fun `cost accounting discounts cached input tokens`() {
        val price = ModelCatalog.price(LlmProviderId.ANTHROPIC, "claude-opus-5-5", emptyMap())
        val cost = ModelCatalog.cost(price, TokenUsage(inputTokens = 1_000_000, outputTokens = 0, cacheReadTokens = 1_000_000))
        assertEquals(0.20, cost, 0.0001)
    }
}
