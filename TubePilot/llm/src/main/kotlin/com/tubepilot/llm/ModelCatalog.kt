package com.tubepilot.llm

import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.ModelPrice

data class ModelInfo(val provider: LlmProviderId, val id: String, val label: String, val price: ModelPrice)

/**
 * Known models and default list prices (USD per million tokens). Anthropic prices are the
 * published first-party rates; OpenAI and Gemini prices change often, so every price can be
 * overridden in Settings and the catalog is only a starting point. Unknown model IDs are allowed:
 * they are priced with the provider's most expensive known rate so the budget cap stays safe.
 */
object ModelCatalog {
    val models: List<ModelInfo> = listOf(
        ModelInfo(LlmProviderId.ANTHROPIC, "claude-opus-5-5", "Claude Opus 5.5", ModelPrice(4.0, 20.0, 0.20)),
        ModelInfo(LlmProviderId.ANTHROPIC, "claude-sonnet-5-5", "Claude Sonnet 5.5", ModelPrice(2.0, 10.0, 0.10)),
        ModelInfo(LlmProviderId.ANTHROPIC, "claude-haiku-5-5", "Claude Haiku 5.5", ModelPrice(0.10, 0.50, 0.01)),
        ModelInfo(LlmProviderId.ANTHROPIC, "claude-fable-5-2", "Claude Fable 5.2", ModelPrice(8.0, 40.0, 0.20)),
        ModelInfo(LlmProviderId.OPENAI, "gpt-5", "GPT-5", ModelPrice(1.25, 10.0, 0.125)),
        ModelInfo(LlmProviderId.OPENAI, "gpt-5-mini", "GPT-5 mini", ModelPrice(0.25, 2.0, 0.025)),
        ModelInfo(LlmProviderId.GEMINI, "gemini-2.5-pro", "Gemini 2.5 Pro", ModelPrice(1.25, 10.0, 0.31)),
        ModelInfo(LlmProviderId.GEMINI, "gemini-2.5-flash", "Gemini 2.5 Flash", ModelPrice(0.30, 2.50, 0.075)),
        ModelInfo(LlmProviderId.ON_DEVICE, "on-device", "On-device model file", ModelPrice(0.0, 0.0, 0.0)),
    )

    fun forProvider(provider: LlmProviderId): List<ModelInfo> = models.filter { it.provider == provider }

    fun price(provider: LlmProviderId, model: String, overrides: Map<String, ModelPrice>): ModelPrice {
        overrides[model]?.let { return it }
        models.firstOrNull { it.id == model }?.let { return it.price }
        if (provider == LlmProviderId.ON_DEVICE) return ModelPrice(0.0, 0.0, 0.0)
        return forProvider(provider).maxByOrNull { it.price.outputPerMTok }?.price ?: ModelPrice(10.0, 50.0)
    }

    fun cost(price: ModelPrice, usage: TokenUsage): Double {
        val uncachedInput = (usage.inputTokens - usage.cacheReadTokens).coerceAtLeast(0)
        return (uncachedInput * price.inputPerMTok +
            usage.cacheReadTokens * price.cacheReadPerMTok +
            // Cache writes are billed at a premium over base input (1.25x for the 5-minute TTL).
            usage.cacheWriteTokens * price.inputPerMTok * 1.25 +
            usage.outputTokens * price.outputPerMTok) / 1_000_000.0
    }
}
