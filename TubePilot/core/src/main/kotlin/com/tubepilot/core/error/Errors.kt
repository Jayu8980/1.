package com.tubepilot.core.error

/** Base class for failures the pipeline understands and reacts to. */
sealed class TubePilotException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Whether retrying the same operation later can succeed. */
    abstract val retryable: Boolean
}

/** The kill switch is on. Work must stop and resume later without counting as a failure. */
class AgentsPausedException : TubePilotException("All agents are paused by the kill switch") {
    override val retryable = true
}

class BudgetExceededException(val spentUsd: Double, val capUsd: Double) :
    TubePilotException("Monthly LLM budget reached: \$%.2f of \$%.2f".format(spentUsd, capUsd)) {
    override val retryable = true
}

class QuotaExhaustedException(val bucket: String, val resetAtMs: Long) :
    TubePilotException("YouTube API quota bucket '$bucket' exhausted until reset") {
    override val retryable = true
}

class AuthRequiredException(message: String = "YouTube authorisation is required. Open the app to reconnect.") :
    TubePilotException(message) {
    override val retryable = false
}

class MissingConfigurationException(message: String) : TubePilotException(message) {
    override val retryable = false
}

/** A remote provider failed. [retryable] reflects HTTP semantics (429/5xx/network vs 4xx). */
class ProviderException(
    val provider: String,
    message: String,
    val statusCode: Int? = null,
    override val retryable: Boolean,
    cause: Throwable? = null,
) : TubePilotException("$provider: $message", cause)

/** The model returned output that does not match the expected contract, even after repair. */
class ContractViolationException(message: String, val raw: String) : TubePilotException(message) {
    override val retryable = true
}

/** A safety policy stopped the operation. Never retried automatically. */
class PolicyBlockedException(message: String) : TubePilotException(message) {
    override val retryable = false
}

class SkillException(val skillId: String, message: String, override val retryable: Boolean = false, cause: Throwable? = null) :
    TubePilotException("Skill $skillId: $message", cause)
