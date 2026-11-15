package com.tubepilot.core.model

import kotlinx.serialization.Serializable

/*
 * Data contracts exchanged between agents. Every contract is serialised as JSON: agents that use
 * an LLM receive the JSON shape in their prompt and their answer is decoded back into these types.
 */

@Serializable
data class TopicIdea(
    val title: String,
    val angle: String = "",
    val keywords: List<String> = emptyList(),
    val format: VideoFormat = VideoFormat.SHORT,
    val pillar: String = "",
    /** 0..10, how many people are looking for this. */
    val demandScore: Double = 0.0,
    /** 0..10, higher means MORE competition (harder). */
    val competitionScore: Double = 0.0,
    /** 0..10, how well it matches the channel niche and audience. */
    val nicheFitScore: Double = 0.0,
    val totalScore: Double = 0.0,
    val rationale: String = "",
    val sources: List<String> = emptyList(),
)

@Serializable
data class ResearchBrief(
    val idea: TopicIdea,
    val targetViewer: String = "",
    val audienceQuestions: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val competitorNotes: List<String> = emptyList(),
    val uniqueAngle: String = "",
    val factsToVerify: List<String> = emptyList(),
)

@Serializable
data class ScriptScene(
    val index: Int,
    val narration: String,
    /** Prompt for an image generator or a B-roll search; never names real people or brands. */
    val visualPrompt: String,
    val onScreenText: String = "",
    val brollQuery: String = "",
    val durationHintSec: Double = 0.0,
)

@Serializable
data class VideoScript(
    val title: String,
    val language: ContentLanguage,
    val format: VideoFormat,
    val hook: String,
    val scenes: List<ScriptScene>,
    val cta: String,
    val estimatedDurationSec: Int,
    /** True when the script describes realistic synthetic people, places or events. */
    val realisticSyntheticContent: Boolean = false,
) {
    /** Full narration in speaking order (hook is scene 0 when the writer put it there). */
    fun fullNarration(): String = scenes.joinToString(" ") { it.narration }
}

@Serializable
data class SceneTiming(val sceneIndex: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class VoiceTrack(
    val voicePath: String,
    val mixPath: String,
    val durationMs: Long,
    val sceneTimings: List<SceneTiming>,
    val integratedLufs: Double,
    val peakDbfs: Double,
    val engine: String,
    val musicTrack: String? = null,
)

@Serializable
enum class VisualKind { IMAGE, VIDEO }

@Serializable
data class SceneVisual(val sceneIndex: Int, val path: String, val kind: VisualKind, val source: String)

@Serializable
data class VisualSet(val format: VideoFormat, val visuals: List<SceneVisual>)

@Serializable
data class RenderOutput(
    val format: VideoFormat,
    val videoPath: String,
    val srtPath: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** Set when this render was cut out of a long-form video. */
    val derivedFromSec: Double? = null,
    val label: String = format.label,
)

@Serializable
data class EditResult(val primary: RenderOutput, val derivedShorts: List<RenderOutput> = emptyList())

@Serializable
data class ThumbnailVariant(val path: String, val headline: String, val style: String, val sizeBytes: Long)

@Serializable
data class ThumbnailSet(val variants: List<ThumbnailVariant>, val selectedIndex: Int = 0) {
    val selected: ThumbnailVariant? get() = variants.getOrNull(selectedIndex) ?: variants.firstOrNull()
}

@Serializable
data class Chapter(val startSec: Int, val title: String)

@Serializable
data class SeoPackage(
    val titleVariants: List<String>,
    val selectedTitleIndex: Int = 0,
    val description: String,
    val tags: List<String>,
    val hashtags: List<String>,
    val chapters: List<Chapter> = emptyList(),
    val pinnedComment: String = "",
    val categoryId: String = "22",
) {
    val title: String get() = titleVariants.getOrElse(selectedTitleIndex) { titleVariants.firstOrNull().orEmpty() }

    /** Description as uploaded: body, chapters block, hashtags. */
    fun fullDescription(): String = buildString {
        append(description.trim())
        if (chapters.size >= 3 && chapters.first().startSec == 0) {
            append("\n\nChapters\n")
            chapters.forEach { append(formatTimestamp(it.startSec)).append(' ').append(it.title).append('\n') }
        }
        if (hashtags.isNotEmpty()) {
            append("\n")
            append(hashtags.joinToString(" ") { if (it.startsWith("#")) it else "#$it" })
        }
    }.trim()

    companion object {
        fun formatTimestamp(totalSec: Int): String {
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

@Serializable
enum class ComplianceCategory {
    COPYRIGHT, COMMUNITY_GUIDELINES, REPETITIVE_CONTENT, AI_DISCLOSURE, BANNED_TOPIC,
    MISLEADING_METADATA, MADE_FOR_KIDS, OTHER,
}

@Serializable
enum class Severity { INFO, WARNING, BLOCKER }

@Serializable
data class ComplianceIssue(
    val category: ComplianceCategory,
    val severity: Severity,
    val detail: String,
    val fix: String = "",
)

@Serializable
enum class ComplianceVerdict { PASS, PASS_WITH_WARNINGS, BLOCK }

@Serializable
data class ComplianceReport(
    val verdict: ComplianceVerdict,
    val issues: List<ComplianceIssue>,
    val requiresAiDisclosure: Boolean,
    val madeForKids: Boolean = false,
    val summary: String,
) {
    val blocked: Boolean get() = verdict == ComplianceVerdict.BLOCK
}

@Serializable
data class QaCheck(val name: String, val passed: Boolean, val detail: String, val blocking: Boolean = true)

@Serializable
data class QaReport(val checks: List<QaCheck>) {
    val passed: Boolean get() = checks.none { it.blocking && !it.passed }
}

/** Persisted state of an in-flight resumable upload so a reboot can continue where it stopped. */
@Serializable
data class UploadSession(
    val renderPath: String,
    val sessionUri: String,
    val totalBytes: Long,
    val confirmedBytes: Long = 0,
    val createdAt: Long,
)

@Serializable
data class UploadResult(
    val videoId: String,
    val renderPath: String,
    val format: VideoFormat,
    val title: String,
    val privacyStatus: String,
    val publishAtIso: String?,
    val thumbnailSet: Boolean,
    val captionsUploaded: Boolean,
    val playlistId: String?,
    val pinnedCommentPosted: Boolean = false,
    val uploadedAt: Long,
)

@Serializable
data class TitleTest(
    val videoId: String,
    val variants: List<String>,
    val activeIndex: Int = 0,
    val switchedAt: Long,
    /** views gained while each variant was live, index-aligned with [variants]. */
    val viewsPerVariant: List<Long> = emptyList(),
    val baselineViews: Long = 0,
    val finished: Boolean = false,
    val winnerIndex: Int? = null,
)

/** Everything the pipeline has produced for one video, stored as a single JSON document. */
@Serializable
data class ProjectArtifacts(
    val idea: TopicIdea? = null,
    val brief: ResearchBrief? = null,
    val script: VideoScript? = null,
    val voice: VoiceTrack? = null,
    val visuals: VisualSet? = null,
    val edit: EditResult? = null,
    val thumbnails: ThumbnailSet? = null,
    val seo: SeoPackage? = null,
    val compliance: ComplianceReport? = null,
    val qa: QaReport? = null,
    val uploadSessions: List<UploadSession> = emptyList(),
    val uploads: List<UploadResult> = emptyList(),
    val titleTest: TitleTest? = null,
    val reviewerNotes: String? = null,
    val lessonsRecorded: Boolean = false,
)
