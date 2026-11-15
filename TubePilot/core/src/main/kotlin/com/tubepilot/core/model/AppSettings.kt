package com.tubepilot.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelChoice(val provider: LlmProviderId, val model: String)

/** Price of a model in US dollars per million tokens. */
@Serializable
data class ModelPrice(val inputPerMTok: Double, val outputPerMTok: Double, val cacheReadPerMTok: Double = inputPerMTok * 0.1)

@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    // Operating mode. Approval mode is the safe default.
    val mode: OperatingMode = OperatingMode.APPROVAL,
    val approveScripts: Boolean = true,
    val approveBeforePublish: Boolean = true,
    // Safety.
    val killSwitch: Boolean = false,
    val autoPostReplies: Boolean = false,
    val autoHideSpam: Boolean = false,
    val defaultPrivacy: String = "private",
    // Budget.
    val monthlyBudgetUsd: Double = 25.0,
    val budgetPaused: Boolean = false,
    // Models.
    val defaultModel: ModelChoice = ModelChoice(LlmProviderId.ANTHROPIC, "claude-opus-5-5"),
    val agentModels: Map<AgentRole, ModelChoice> = emptyMap(),
    val fallbackToOnDevice: Boolean = true,
    val claudeRefusalFallback: Boolean = true,
    val onDeviceModelPath: String? = null,
    val priceOverrides: Map<String, ModelPrice> = emptyMap(),
    // Media providers.
    val ttsProvider: TtsProviderType = TtsProviderType.ANDROID,
    val ttsVoice: String = "",
    val imageProvider: ImageProviderType = ImageProviderType.ON_DEVICE,
    val imageModel: String = "gpt-image-1",
    val musicFolderUri: String? = null,
    val brollFolderUri: String? = null,
    val musicVolumeDb: Double = -20.0,
    val targetLufs: Double = -14.0,
    // Uploads.
    val allowMeteredUploads: Boolean = false,
    val quotaTableUrl: String? = null,
    // Notifications.
    val notifyApprovals: Boolean = true,
    val notifyFailures: Boolean = true,
    val notifyWeeklyReport: Boolean = true,
    val notifyFlaggedComments: Boolean = true,
) {
    fun modelFor(role: AgentRole): ModelChoice = agentModels[role] ?: defaultModel

    /** Whether the gate at [stage] stops the pipeline for a human decision. */
    fun requiresApproval(stage: PipelineStage): Boolean = when {
        mode == OperatingMode.AUTO -> false
        stage == PipelineStage.SCRIPT_APPROVAL -> approveScripts
        stage == PipelineStage.PUBLISH_APPROVAL -> approveBeforePublish
        else -> false
    }
}
