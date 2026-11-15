package com.tubepilot.core.model

import kotlinx.serialization.Serializable

/** Spoken/written language of a video. Hinglish is Hindi written in Latin script mixed with English. */
@Serializable
enum class ContentLanguage(val bcp47: String, val youtubeCode: String, val displayName: String) {
    ENGLISH("en-IN", "en", "English"),
    HINDI("hi-IN", "hi", "Hindi"),
    HINGLISH("en-IN", "hi-Latn", "Hinglish"),
}

/** Output format. Shorts are vertical 9:16, long-form is horizontal 16:9. */
@Serializable
enum class VideoFormat(val width: Int, val height: Int, val label: String, val maxDurationSec: Int) {
    SHORT(1080, 1920, "Short (9:16)", 180),
    LONG(1920, 1080, "Long-form (16:9)", 43_200),
}

@Serializable
enum class OperatingMode { AUTO, APPROVAL }

/** The twelve agents that make up the channel team. */
@Serializable
enum class AgentRole(val displayName: String, val summary: String) {
    ORCHESTRATOR("Channel Manager", "Plans the weekly calendar, assigns work and enforces strategy"),
    TREND_RESEARCHER("Trend Researcher", "Finds and scores topics from analytics, search and competitors"),
    SCRIPTWRITER("Scriptwriter", "Writes hooks, scripts and CTAs in the channel voice"),
    SEO_SPECIALIST("SEO Specialist", "Titles, descriptions, tags, chapters and pinned comment"),
    THUMBNAIL_DESIGNER("Thumbnail Designer", "Generates 2-3 thumbnail variants with text overlays"),
    VOICE_AUDIO("Voice & Audio", "Voiceover, background music and loudness normalisation"),
    VIDEO_EDITOR("Video Editor", "Assembles scenes, subtitles, transitions and formats"),
    UPLOAD_SCHEDULER("Upload & Scheduler", "Resumable uploads, scheduling, playlists, captions"),
    COMMUNITY_MANAGER("Community Manager", "Triages comments, drafts replies, hides spam"),
    ANALYTICS("Analytics", "Tracks performance and writes the weekly lessons report"),
    COMPLIANCE("Compliance & Safety", "Reviews everything before publishing and can block it"),
    QA("QA", "Validates files and metadata limits"),
}

@Serializable
enum class AgentState { IDLE, WORKING, CALLING_SKILL, WAITING, ERROR, PAUSED }

/**
 * Stages of the per-video pipeline, in order. The approval stages are gates that only block
 * when the user runs in [OperatingMode.APPROVAL].
 */
@Serializable
enum class PipelineStage(val label: String, val owner: AgentRole?) {
    IDEA("Idea", AgentRole.ORCHESTRATOR),
    RESEARCH("Research", AgentRole.TREND_RESEARCHER),
    SCRIPT("Script", AgentRole.SCRIPTWRITER),
    SCRIPT_APPROVAL("Script approval", null),
    VOICE("Voice", AgentRole.VOICE_AUDIO),
    VISUALS("Visuals", AgentRole.VIDEO_EDITOR),
    EDIT("Edit", AgentRole.VIDEO_EDITOR),
    THUMBNAIL("Thumbnail", AgentRole.THUMBNAIL_DESIGNER),
    SEO("SEO", AgentRole.SEO_SPECIALIST),
    COMPLIANCE("Compliance", AgentRole.COMPLIANCE),
    QA("QA", AgentRole.QA),
    PUBLISH_APPROVAL("Publish approval", null),
    UPLOAD("Upload & schedule", AgentRole.UPLOAD_SCHEDULER),
    COMMENT_MONITORING("Comment monitoring", AgentRole.COMMUNITY_MANAGER),
    ANALYTICS("Analytics", AgentRole.ANALYTICS),
    MEMORY_UPDATE("Memory update", AgentRole.ANALYTICS),
    COMPLETE("Complete", null);

    val isApprovalGate: Boolean get() = this == SCRIPT_APPROVAL || this == PUBLISH_APPROVAL

    fun next(): PipelineStage = entries.getOrElse(ordinal + 1) { COMPLETE }
}

@Serializable
enum class ProjectStatus(val isTerminal: Boolean, val isActive: Boolean) {
    QUEUED(false, true),
    RUNNING(false, true),
    WAITING_APPROVAL(false, false),
    BLOCKED(false, false),
    PAUSED(false, false),
    SCHEDULED(false, true),
    PUBLISHED(false, true),
    FAILED(true, false),
    REJECTED(true, false),
    DONE(true, false),
}

@Serializable
enum class LlmProviderId(val displayName: String) {
    ANTHROPIC("Anthropic Claude"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini"),
    ON_DEVICE("On-device (MediaPipe)"),
}

@Serializable
enum class TtsProviderType(val displayName: String) {
    ANDROID("Android system TTS (offline)"),
    OPENAI("OpenAI TTS"),
    ELEVENLABS("ElevenLabs"),
}

@Serializable
enum class ImageProviderType(val displayName: String) {
    OPENAI("OpenAI Images"),
    GEMINI("Google Imagen (Gemini API)"),
    ON_DEVICE("On-device procedural cards"),
}

@Serializable
enum class LessonCategory { HOOK, TITLE, THUMBNAIL, RETENTION, TOPIC, POSTING_TIME, COMMUNITY, FORMAT, OTHER }

@Serializable
enum class CommentCategory { QUESTION, PRAISE, FEEDBACK, SPAM, TOXIC, SENSITIVE, OTHER }

@Serializable
enum class CommentStatus { NEW, DRAFTED, REPLIED, HIDDEN, FLAGGED, IGNORED }

@Serializable
enum class CalendarStatus { PLANNED, IN_PRODUCTION, PUBLISHED, SKIPPED }

@Serializable
enum class AuditLevel { DEBUG, INFO, WARNING, ERROR, SECURITY }
