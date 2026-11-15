package com.tubepilot.llm

import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.BudgetExceededException
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.error.TubePilotException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.LlmUsage
import com.tubepilot.core.model.ModelChoice
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Who is calling and why; used for model routing, cost attribution and the audit log. */
data class LlmCallContext(val agent: AgentRole?, val projectId: String? = null, val purpose: String)

/** A provider-agnostic request; the gateway fills in provider and model from Settings. */
data class LlmCall(
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 16_000,
    val jsonOutput: Boolean = false,
    val effort: Effort = Effort.MEDIUM,
    /** Forces a specific model (used by tests and the Settings "test connection" button). */
    val modelOverride: ModelChoice? = null,
)

data class BudgetStatus(val spentUsd: Double, val capUsd: Double, val paused: Boolean) {
    val remainingUsd: Double get() = (capUsd - spentUsd).coerceAtLeast(0.0)
}

/**
 * Single entry point for every LLM call. Enforces the kill switch and monthly budget, routes each
 * agent to its configured model, retries transient failures with jittered exponential backoff,
 * optionally falls back to the on-device model, and records token usage and cost.
 */
@Singleton
class LlmGateway @Inject constructor(
    providers: Set<@JvmSuppressWildcards LlmProvider>,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val audit: AuditLog,
    private val time: TimeSource,
) {
    private val providers: Map<LlmProviderId, LlmProvider> = providers.associateBy { it.id }

    /** Visible for tests: base delay of the first retry. */
    internal var retryBaseDelayMs = 2_000L

    suspend fun budgetStatus(): BudgetStatus {
        val s = settings.current()
        val spent = usage.spentSince(TimeUtil.startOfMonthMs(time.now()))
        return BudgetStatus(spent, s.monthlyBudgetUsd, s.budgetPaused || spent >= s.monthlyBudgetUsd)
    }

    suspend fun complete(context: LlmCallContext, call: LlmCall): LlmResponse {
        val choice = preflight(context, call)
        return executeWithPolicy(context, call, choice) { provider, request -> provider.complete(request) }
    }

    /**
     * Streams a response. Retries are only attempted before the first token arrives; a failure
     * mid-stream surfaces to the caller, which can retry the whole call.
     */
    fun stream(context: LlmCallContext, call: LlmCall): Flow<LlmStreamEvent> = flow {
        val choice = preflight(context, call)
        val provider = providerFor(choice.provider)
        val request = call.toRequest(choice.model)
        var completed: LlmResponse? = null
        provider.stream(request).collect { event ->
            if (event is LlmStreamEvent.Completed) completed = event.response
            emit(event)
        }
        completed?.let { recordUsage(context, it) }
    }

    private suspend fun preflight(context: LlmCallContext, call: LlmCall): ModelChoice {
        val s = settings.current()
        if (s.killSwitch) throw AgentsPausedException()
        val choice = call.modelOverride ?: context.agent?.let { s.modelFor(it) } ?: s.defaultModel
        if (choice.provider != LlmProviderId.ON_DEVICE) {
            val spent = usage.spentSince(TimeUtil.startOfMonthMs(time.now()))
            if (s.budgetPaused || spent >= s.monthlyBudgetUsd) {
                if (!s.budgetPaused) {
                    settings.update { it.copy(budgetPaused = true) }
                    audit.record(
                        "LlmGateway", "budget.paused",
                        "Monthly budget of \$%.2f reached (spent \$%.2f). Agents paused.".format(s.monthlyBudgetUsd, spent),
                        context.projectId, AuditLevel.WARNING,
                    )
                }
                throw BudgetExceededException(spent, s.monthlyBudgetUsd)
            }
        }
        return choice
    }

    private suspend fun executeWithPolicy(
        context: LlmCallContext,
        call: LlmCall,
        choice: ModelChoice,
        block: suspend (LlmProvider, LlmRequest) -> LlmResponse,
    ): LlmResponse {
        val provider = providerFor(choice.provider)
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                val response = block(provider, call.toRequest(choice.model))
                recordUsage(context, response)
                return response
            } catch (e: TubePilotException) {
                lastError = e
                // Pauses and configuration problems are never retried here; the pipeline decides.
                if (!e.retryable || e is AgentsPausedException || e is BudgetExceededException) break
                if (attempt < MAX_ATTEMPTS - 1) {
                    val wait = retryBaseDelayMs * (1L shl attempt) + Random.nextLong(0, retryBaseDelayMs / 2 + 1)
                    audit.record(
                        "LlmGateway", "llm.retry",
                        "${choice.provider}/${choice.model} attempt ${attempt + 1} failed: ${e.message}; retrying in ${wait}ms",
                        context.projectId, AuditLevel.WARNING,
                    )
                    delay(wait)
                }
            }
        }
        val failure = lastError ?: IllegalStateException("LLM call failed")
        // Last resort: the optional on-device model, when the cloud provider is unreachable.
        val s = settings.current()
        val onDevice = providers[LlmProviderId.ON_DEVICE]
        if (choice.provider != LlmProviderId.ON_DEVICE && s.fallbackToOnDevice && onDevice != null && onDevice.isConfigured() &&
            failure is ProviderException
        ) {
            audit.record("LlmGateway", "llm.fallback", "Falling back to on-device model after: ${failure.message}", context.projectId, AuditLevel.WARNING)
            val response = onDevice.complete(call.toRequest("on-device"))
            recordUsage(context, response)
            return response
        }
        throw failure
    }

    private fun providerFor(id: LlmProviderId): LlmProvider =
        providers[id] ?: throw MissingConfigurationException("LLM provider $id is not available in this build")

    private suspend fun recordUsage(context: LlmCallContext, response: LlmResponse) {
        val s = settings.current()
        val price = ModelCatalog.price(response.provider, response.model, s.priceOverrides)
        val cost = ModelCatalog.cost(price, response.usage)
        usage.record(
            LlmUsage(
                timestamp = time.now(),
                provider = response.provider,
                model = response.model,
                agent = context.agent,
                inputTokens = response.usage.inputTokens,
                outputTokens = response.usage.outputTokens,
                cacheReadTokens = response.usage.cacheReadTokens,
                costUsd = cost,
                projectId = context.projectId,
            ),
        )
        audit.record(
            context.agent?.displayName ?: "LlmGateway",
            "llm.call",
            "${context.purpose}: ${response.provider.displayName}/${response.model} in=${response.usage.inputTokens} " +
                "out=${response.usage.outputTokens} cached=${response.usage.cacheReadTokens} cost=\$%.4f stop=${response.stopReason}".format(cost),
            context.projectId,
            AuditLevel.DEBUG,
        )
    }

    private fun LlmCall.toRequest(model: String) = LlmRequest(
        model = model,
        system = system,
        messages = messages,
        tools = tools,
        maxTokens = maxTokens,
        jsonOutput = jsonOutput,
        effort = effort,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
