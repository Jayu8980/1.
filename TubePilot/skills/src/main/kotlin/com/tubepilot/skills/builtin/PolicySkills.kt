package com.tubepilot.skills.builtin

import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.ComplianceCategory
import com.tubepilot.core.model.ComplianceIssue
import com.tubepilot.core.model.ComplianceReport
import com.tubepilot.core.model.ComplianceVerdict
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.Severity
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.VisualKind
import com.tubepilot.core.util.TextUtil
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic policy rules. These never depend on the model, so a misbehaving or unavailable
 * LLM can only add issues, never remove a hard block.
 */
object PolicyRules {
    /** Similarity above which a script is treated as mass-produced/repetitive (monetisation risk). */
    const val REPETITION_BLOCK = 0.55
    const val REPETITION_WARN = 0.35
    private val MISLEADING = listOf("free iphone", "guaranteed money", "100% guaranteed", "get rich quick", "miracle cure", "cures cancer", "earn lakhs daily")
    private val REALISTIC_HINTS = listOf("photorealistic", "realistic photo", "news footage", "real person", "cctv", "documentary footage", "breaking news")

    fun check(
        artifacts: ProjectArtifacts,
        profile: ChannelProfile,
        recentScripts: List<VideoScript>,
    ): List<ComplianceIssue> {
        val issues = ArrayList<ComplianceIssue>()
        val script = artifacts.script
        val seo = artifacts.seo
        val text = buildString {
            script?.let { append(it.title).append(' ').append(it.fullNarration()).append(' ') }
            seo?.let { append(it.titleVariants.joinToString(" ")).append(' ').append(it.description).append(' ').append(it.tags.joinToString(" ")) }
        }.lowercase(Locale.ROOT)

        profile.bannedTopics.filter { it.isNotBlank() && text.contains(it.lowercase(Locale.ROOT).trim()) }.forEach {
            issues += ComplianceIssue(ComplianceCategory.BANNED_TOPIC, Severity.BLOCKER, "Mentions banned topic '$it' from the channel strategy", "Remove the topic or update the strategy")
        }

        if (script != null) {
            val shingles = TextUtil.shingles(script.fullNarration())
            val maxSim = recentScripts.filter { it.title != script.title || it.fullNarration() != script.fullNarration() }
                .maxOfOrNull { TextUtil.jaccard(shingles, TextUtil.shingles(it.fullNarration())) } ?: 0.0
            when {
                maxSim >= REPETITION_BLOCK -> issues += ComplianceIssue(
                    ComplianceCategory.REPETITIVE_CONTENT, Severity.BLOCKER,
                    "Script is %.0f%% similar to a recent video. Near-duplicate uploads risk YouTube's inauthentic/repetitive content policy.".format(maxSim * 100),
                    "Rewrite with a different angle, structure and examples",
                )
                maxSim >= REPETITION_WARN -> issues += ComplianceIssue(
                    ComplianceCategory.REPETITIVE_CONTENT, Severity.WARNING,
                    "Script is %.0f%% similar to a recent video".format(maxSim * 100), "Vary the structure and examples",
                )
            }
            if (script.scenes.size < 2 && script.format.name == "LONG") {
                issues += ComplianceIssue(ComplianceCategory.REPETITIVE_CONTENT, Severity.WARNING, "Very little original narration for a long-form video")
            }
        }

        MISLEADING.firstOrNull { text.contains(it) }?.let {
            issues += ComplianceIssue(ComplianceCategory.MISLEADING_METADATA, Severity.BLOCKER, "Contains deceptive or misleading claim '$it'", "Remove the claim")
        }
        seo?.let { s ->
            if (s.titleVariants.any { it.length > 100 }) issues += ComplianceIssue(ComplianceCategory.MISLEADING_METADATA, Severity.WARNING, "A title exceeds 100 characters")
            if (s.tags.size > 30) issues += ComplianceIssue(ComplianceCategory.MISLEADING_METADATA, Severity.WARNING, "Excessive tags can be treated as tag spam")
        }

        artifacts.visuals?.visuals?.forEach { v ->
            val owned = v.source.startsWith("generated") || v.source.startsWith("user_library") || v.source.startsWith("On-device") ||
                v.source.contains("OpenAI") || v.source.contains("Imagen")
            if (!owned) {
                issues += ComplianceIssue(ComplianceCategory.COPYRIGHT, Severity.BLOCKER, "Scene ${v.sceneIndex} uses media of unknown origin (${v.source})", "Use generated visuals or your own library")
            }
            if (v.kind == VisualKind.VIDEO && !v.source.startsWith("user_library")) {
                issues += ComplianceIssue(ComplianceCategory.COPYRIGHT, Severity.BLOCKER, "Scene ${v.sceneIndex} video clip is not from your own library")
            }
        }
        artifacts.voice?.musicTrack?.let {
            issues += ComplianceIssue(
                ComplianceCategory.COPYRIGHT, Severity.INFO,
                "Background music '$it' comes from your library. Make sure its licence allows monetised YouTube use.",
            )
        }

        if (requiresAiDisclosure(artifacts)) {
            issues += ComplianceIssue(
                ComplianceCategory.AI_DISCLOSURE, Severity.INFO,
                "Contains realistic AI-generated or altered content. The upload will set YouTube's altered or synthetic content disclosure.",
            )
        }
        if (profile.madeForKids) {
            issues += ComplianceIssue(ComplianceCategory.MADE_FOR_KIDS, Severity.INFO, "Channel is marked made-for-kids: comments and personalised ads will be disabled by YouTube")
        }
        return issues
    }

    fun requiresAiDisclosure(artifacts: ProjectArtifacts): Boolean {
        if (artifacts.script?.realisticSyntheticContent == true) return true
        val prompts = artifacts.script?.scenes?.joinToString(" ") { it.visualPrompt }.orEmpty().lowercase(Locale.ROOT)
        val aiVisuals = artifacts.visuals?.visuals?.any { it.source.contains("OpenAI") || it.source.contains("Imagen") } == true
        return aiVisuals && REALISTIC_HINTS.any { prompts.contains(it) }
    }

    fun verdict(issues: List<ComplianceIssue>): ComplianceVerdict = when {
        issues.any { it.severity == Severity.BLOCKER } -> ComplianceVerdict.BLOCK
        issues.any { it.severity == Severity.WARNING } -> ComplianceVerdict.PASS_WITH_WARNINGS
        else -> ComplianceVerdict.PASS
    }
}

/** policy_check: deterministic rules plus a model review against YouTube's Community Guidelines. */
@Singleton
class PolicyCheckExecutor @Inject constructor(
    private val projects: ProjectRepository,
    private val memory: MemoryRepository,
) : SkillExecutor {
    override val id = "policy_check"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val project = projects.require(context, input)
        val profile = memory.profile()
        val recent = projects.recentScripts(20).filter { it != project.artifacts.script }
        val issues = PolicyRules.check(project.artifacts, profile, recent).toMutableList()

        val script = project.artifacts.script
        val seo = project.artifacts.seo
        val review = context.runtime.runPrompt(
            context, input,
            mapOf(
                "script" to (script?.let { "${it.title}\n" + it.scenes.joinToString("\n") { s -> "[${s.index}] ${s.narration} | visual: ${s.visualPrompt}" } } ?: "(none)"),
                "metadata" to (seo?.let { "Titles: ${it.titleVariants}\nDescription: ${it.fullDescription()}\nTags: ${it.tags}" } ?: "(none)"),
            ),
        )
        review.objects("issues").forEach { o ->
            val category = runCatching { ComplianceCategory.valueOf(o.str("category").uppercase(Locale.ROOT)) }.getOrDefault(ComplianceCategory.OTHER)
            val severity = runCatching { Severity.valueOf(o.str("severity").uppercase(Locale.ROOT)) }.getOrDefault(Severity.WARNING)
            if (o.str("detail").isNotBlank()) issues += ComplianceIssue(category, severity, o.str("detail"), o.str("fix"))
        }
        val disclosure = PolicyRules.requiresAiDisclosure(project.artifacts) || review.bool("requiresAiDisclosure", false)
        val report = ComplianceReport(
            verdict = PolicyRules.verdict(issues),
            issues = issues,
            requiresAiDisclosure = disclosure,
            madeForKids = profile.madeForKids,
            summary = review.str("summary").ifBlank { "${issues.size} item(s) reviewed" },
        )
        return report.toJsonObject(ComplianceReport.serializer())
    }
}
