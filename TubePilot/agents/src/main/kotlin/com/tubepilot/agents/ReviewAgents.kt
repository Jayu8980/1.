package com.tubepilot.agents

import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ComplianceReport
import com.tubepilot.core.model.QaCheck
import com.tubepilot.core.model.QaReport
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.model.ThumbnailSet
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.VoiceTrack
import com.tubepilot.core.util.TextUtil
import com.tubepilot.media.probe.ImageProbe
import com.tubepilot.media.probe.MediaProbe
import com.tubepilot.media.probe.VideoProbe
import com.tubepilot.media.subtitle.Cue
import com.tubepilot.media.subtitle.SubtitleBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Reviews every video before publishing; a BLOCK verdict stops the pipeline. */
@Singleton
class ComplianceAgent @Inject constructor(runtime: AgentRuntime) : Agent(AgentRole.COMPLIANCE, runtime) {
    override val rolePrompt = "You are Compliance & Safety: you protect the channel from strikes, copyright claims and demonetisation."
    override val skillIds = listOf("policy_check")
    override val retryPolicy = RetryPolicy(maxAttempts = 3)

    suspend fun review(project: VideoProject): ComplianceReport = task("Compliance review '${project.title}'", project.id) {
        val report = runtime.skill(role, "policy_check", JsonObject(mapOf("projectId" to JsonPrimitive(project.id))), project.id, ComplianceReport.serializer())
        runtime.audit.record(
            role.displayName, "compliance.${report.verdict.name.lowercase()}",
            "${report.summary} — ${report.issues.joinToString("; ") { "${it.severity}/${it.category}: ${it.detail}" }}".take(4000),
            project.id,
            if (report.blocked) AuditLevel.SECURITY else AuditLevel.INFO,
        )
        report
    }
}

/** Pure QA rules so they are unit-testable without media files. */
object QaRules {
    fun video(probe: VideoProbe?, format: VideoFormat, expectedMs: Long, label: String): List<QaCheck> {
        if (probe == null) return listOf(QaCheck("$label file", false, "Render is missing or unreadable"))
        return listOf(
            QaCheck("$label resolution", probe.displayWidth == format.width && probe.displayHeight == format.height,
                "${probe.displayWidth}x${probe.displayHeight}, expected ${format.width}x${format.height}"),
            QaCheck("$label duration", probe.durationMs in 1_000..format.maxDurationSec * 1000L,
                "${probe.durationMs / 1000}s (limit ${format.maxDurationSec}s)"),
            QaCheck("$label matches audio", abs(probe.durationMs - expectedMs) <= 1_500,
                "video ${probe.durationMs} ms vs audio $expectedMs ms"),
            QaCheck("$label audio track", probe.hasAudio, if (probe.hasAudio) "present" else "missing"),
            QaCheck("$label size", probe.sizeBytes in 1..256L * 1024 * 1024 * 1024, "${probe.sizeBytes / 1_048_576} MB"),
        )
    }

    fun audio(voice: VoiceTrack, targetLufs: Double): List<QaCheck> = listOf(
        QaCheck("Loudness", abs(voice.integratedLufs - targetLufs) <= 2.0, "%.1f LUFS (target %.1f)".format(voice.integratedLufs, targetLufs)),
        QaCheck("True peak", voice.peakDbfs <= -0.5, "%.1f dBFS".format(voice.peakDbfs)),
    )

    fun subtitles(cues: List<Cue>, durationMs: Long): List<QaCheck> {
        val ordered = cues.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 }
        val shortest = cues.minOfOrNull { it.endMs - it.startMs } ?: 0
        val lastEnd = cues.maxOfOrNull { it.endMs } ?: 0
        return listOf(
            QaCheck("Subtitles present", cues.isNotEmpty(), "${cues.size} cues"),
            QaCheck("Subtitles ordered", ordered, if (ordered) "no overlaps" else "overlapping cues"),
            QaCheck("Subtitle timing", cues.isEmpty() || shortest >= 300, "shortest cue $shortest ms", blocking = false),
            QaCheck("Subtitles within video", lastEnd <= durationMs + 500, "last cue ends at $lastEnd ms of $durationMs ms"),
        )
    }

    fun thumbnails(set: ThumbnailSet?, probes: List<ImageProbe?>): List<QaCheck> {
        if (set == null || set.variants.isEmpty()) return listOf(QaCheck("Thumbnails", false, "none produced"))
        return set.variants.mapIndexed { i, v ->
            val p = probes.getOrNull(i)
            QaCheck("Thumbnail ${i + 1}", p != null && p.sizeBytes <= 2L * 1024 * 1024 && p.width >= 640 && p.width * 9 == p.height * 16,
                p?.let { "${it.width}x${it.height}, ${it.sizeBytes / 1024} KB (max 2 MB, 16:9)" } ?: "missing (${v.path})")
        }
    }

    fun metadata(seo: SeoPackage?): List<QaCheck> {
        if (seo == null) return listOf(QaCheck("Metadata", false, "missing"))
        val title = seo.title
        val description = seo.fullDescription()
        val tagChars = SeoLimits.tagsLength(seo.tags)
        return listOf(
            QaCheck("Title length", title.length in 1..100, "${title.length}/100 characters"),
            QaCheck("Title characters", '<' !in title && '>' !in title, "no angle brackets"),
            QaCheck("Description length", TextUtil.utf8Length(description) <= 5000, "${TextUtil.utf8Length(description)}/5000 bytes"),
            QaCheck("Description characters", '<' !in description && '>' !in description, "no angle brackets"),
            QaCheck("Tags length", tagChars <= 500, "$tagChars/500 characters"),
            QaCheck("Hashtags", seo.hashtags.size <= 15, "${seo.hashtags.size} (YouTube ignores all above 60; keep 3-5)", blocking = false),
            QaCheck("Chapters", seo.chapters.isEmpty() || (seo.chapters.first().startSec == 0 && seo.chapters.size >= 3), "${seo.chapters.size} chapters", blocking = false),
        )
    }
}

/** Validates files and metadata before anything is uploaded. */
@Singleton
class QaAgent @Inject constructor(
    runtime: AgentRuntime,
    private val probe: MediaProbe,
) : Agent(AgentRole.QA, runtime) {
    override val rolePrompt = "You are QA: nothing broken or out of spec reaches YouTube."
    override val skillIds = emptyList<String>()

    suspend fun check(project: VideoProject): QaReport = task("QA '${project.title}'", project.id) {
        val a = project.artifacts
        val checks = ArrayList<QaCheck>()
        val voice = a.voice
        val edit = a.edit
        if (voice == null || edit == null) {
            checks += QaCheck("Pipeline artefacts", false, "voice or edit missing")
        } else {
            checks += QaRules.video(probe.video(File(edit.primary.videoPath)), project.format, voice.durationMs, "Video")
            checks += QaRules.audio(voice, runtime.settings.current().targetLufs)
            val srt = File(edit.primary.srtPath).takeIf { it.isFile }?.readText()
            checks += QaRules.subtitles(srt?.let { SubtitleBuilder.parseSrt(it) }.orEmpty(), edit.primary.durationMs)
            edit.derivedShorts.forEachIndexed { i, s ->
                checks += QaRules.video(probe.video(File(s.videoPath)), VideoFormat.SHORT, s.durationMs, "Short ${i + 1}")
            }
        }
        checks += QaRules.thumbnails(a.thumbnails, a.thumbnails?.variants.orEmpty().map { probe.image(File(it.path)) })
        checks += QaRules.metadata(a.seo)
        val report = QaReport(checks)
        runtime.audit.record(role.displayName, if (report.passed) "qa.passed" else "qa.failed",
            checks.filter { !it.passed }.joinToString("; ") { "${it.name}: ${it.detail}" }.ifEmpty { "${checks.size} checks passed" },
            project.id, if (report.passed) AuditLevel.INFO else AuditLevel.WARNING)
        report
    }
}
