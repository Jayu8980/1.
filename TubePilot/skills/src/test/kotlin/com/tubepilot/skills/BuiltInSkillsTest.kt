package com.tubepilot.skills

import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.CommentCategory
import com.tubepilot.core.model.ComplianceCategory
import com.tubepilot.core.model.ComplianceVerdict
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.SceneVisual
import com.tubepilot.core.model.ScriptScene
import com.tubepilot.core.model.Severity
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.VisualKind
import com.tubepilot.core.model.VisualSet
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import com.tubepilot.skills.builtin.CalendarPlanner
import com.tubepilot.skills.builtin.CommentHeuristics
import com.tubepilot.skills.builtin.PolicyRules
import com.tubepilot.skills.builtin.ReplyDrafterExecutor
import com.tubepilot.skills.builtin.ScriptWriterExecutor
import com.tubepilot.skills.builtin.ShortsSegmenter
import com.tubepilot.skills.builtin.TitleHeuristics
import com.tubepilot.skills.builtin.scoreIdea
import com.tubepilot.testing.FakeMemoryRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltInSkillsTest {

    @Test
    fun `all 14 built-in manifests parse and map to registered executors`() {
        val dir = File("src/main/assets/skills")
        val manifests = dir.listFiles()!!.filter { it.isDirectory }.map { d ->
            val m = SkillManifestParser.parseManifest(File(d, "skill.json").readText())
            assertTrue(File(d, m.promptFile).readText().isNotBlank(), "${m.id} has an empty prompt")
            assertEquals(d.name, m.id)
            m
        }
        val expected = setOf(
            "trend_scan", "keyword_research", "hook_generator", "script_writer", "shorts_cutter", "subtitle_generator", "thumbnail_maker",
            "title_ab_test", "comment_triage", "reply_drafter", "analytics_report", "repurpose_content", "content_calendar", "policy_check",
        )
        assertEquals(expected, manifests.map { it.id }.toSet())
        val executorKeys = Regex("@StringKey\\(\"([a-z_]+)\"\\)").findAll(File("src/main/kotlin/com/tubepilot/skills/di/SkillsModule.kt").readText()).map { it.groupValues[1] }.toSet()
        manifests.forEach { m -> assertTrue((m.executor ?: Skill.PROMPT_EXECUTOR) in executorKeys, "no executor bound for ${m.id}") }
        assertTrue(manifests.single { it.id == "policy_check" }.agents == listOf(AgentRole.COMPLIANCE))
    }

    @Test
    fun `prompt template renders variables and input paths`() {
        val out = PromptTemplate.render("Hi {{ name }} about {{input.topic.title}} {{unknown}}!", mapOf("name" to "Asha"), TubeJson.parseToJsonElement("""{"topic":{"title":"phones"}}""").jsonObject)
        assertEquals("Hi Asha about phones !", out)
    }

    @Test
    fun `idea scoring weights demand, low competition and niche fit`() {
        val idea = scoreIdea(TopicIdea("t", demandScore = 8.0, competitionScore = 2.0, nicheFitScore = 10.0))
        assertEquals(0.4 * 8 + 0.25 * 8 + 0.35 * 10, idea.totalScore, 0.01)
        assertEquals(10.0, scoreIdea(TopicIdea("t", demandScore = 99.0)).demandScore)
    }

    @Test
    fun `script normaliser puts the hook first, adds the CTA and caps Shorts length`() {
        val longNarration = (1..40).joinToString(" ") { "word" }
        val raw = TubeJson.parseToJsonElement(
            """{"title":"T","hook":"Stop scrolling now","cta":"Subscribe for more",
               "scenes":[${(1..8).joinToString(",") { """{"narration":"$longNarration","visualPrompt":"v$it"}""" }}]}""",
        ).jsonObject
        val script = ScriptWriterExecutor.normalize(raw, VideoFormat.SHORT, ContentLanguage.ENGLISH, "fallback")
        assertEquals("Stop scrolling now", script.scenes.first().narration)
        assertEquals("Subscribe for more", script.scenes.last().narration)
        assertTrue(script.estimatedDurationSec <= 60, "Short is ${script.estimatedDurationSec}s")
        assertEquals((0 until script.scenes.size).toList(), script.scenes.map { it.index })
    }

    @Test
    fun `title heuristics reward length and early keywords, punish clickbait and brackets`() {
        val good = TitleHeuristics.score("Phone camera tricks that make cheap phones look pro", "phone camera", emptyList()).first
        val bait = TitleHeuristics.score("YOU WON'T BELIEVE THIS SHOCKING PHONE TRICK", "phone camera", emptyList()).first
        val brackets = TitleHeuristics.score("Phone camera <tips>", "phone camera", emptyList()).first
        assertTrue(good > bait)
        assertTrue(good > brackets)
    }

    @Test
    fun `comment heuristics catch spam and sensitive comments without the model`() {
        assertEquals(CommentCategory.SPAM, CommentHeuristics.prefilter("Check my channel!! www.free-subs.xyz")?.category)
        assertEquals(CommentCategory.SENSITIVE, CommentHeuristics.prefilter("I feel like I want to kill myself")?.category)
        assertNull(CommentHeuristics.prefilter("Which phone did you use for the night shots?"))
    }

    @Test
    fun `reply sanitiser strips links and contact details and asks for review`() {
        val (reply, review) = ReplyDrafterExecutor.sanitize("Thanks! Mail me at a@b.com or see https://x.y/z")
        assertTrue("@" !in reply && "http" !in reply)
        assertTrue(review)
    }

    @Test
    fun `calendar planner spreads uploads and keeps them apart`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val weekStart = TimeUtil.startOfWeekMs(1_763_200_000_000L, zone)
        val slots = CalendarPlanner.slots(weekStart, zone, shorts = 4, longs = 1, hours = listOf(18))
        assertEquals(5, slots.size)
        assertEquals(1, slots.count { it.second == VideoFormat.LONG })
        slots.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 3 * TimeUtil.HOUR) }
        assertTrue(slots.all { it.first in weekStart until weekStart + 8 * TimeUtil.DAY })

        val ideas = listOf(TopicIdea("Short A", format = VideoFormat.SHORT, totalScore = 5.0), TopicIdea("Long B", format = VideoFormat.LONG, totalScore = 9.0))
        val entries = CalendarPlanner.assign(slots, ideas, emptyMap())
        assertEquals("Long B", entries.first { it.format == VideoFormat.LONG }.topic)
        assertEquals(1, entries.count { it.topic == "Short A" })
        assertEquals(3, entries.count { it.topic.startsWith("Open slot") })
    }

    @Test
    fun `shorts segmenter keeps valid model picks and fills with greedy windows`() {
        val durations = List(10) { 10_000L }
        val segments = ShortsSegmenter.validate(listOf(0 to 2, 1 to 3, 5 to 5), durations, max = 2)
        assertEquals(0 to 2, segments.first())
        assertEquals(2, segments.size)
        segments.forEach { (a, b) -> assertTrue((a..b).sumOf { durations[it] } in ShortsSegmenter.MIN_MS..ShortsSegmenter.MAX_MS) }
    }

    @Test
    fun `policy rules block banned topics, repetitive scripts and unknown media`() {
        val memory = FakeMemoryRepository()
        val script = VideoScript("Gambling tips", ContentLanguage.ENGLISH, VideoFormat.SHORT, "hook",
            listOf(ScriptScene(0, "today we talk about gambling and how phones help with gambling apps every day", "v")), "cta", 30)
        val artifacts = ProjectArtifacts(
            script = script,
            visuals = VisualSet(VideoFormat.SHORT, listOf(SceneVisual(0, "/x.mp4", VisualKind.VIDEO, "downloaded:someone"))),
        )
        val issues = PolicyRules.check(artifacts, memory.profileValue, recentScripts = listOf(script.copy(title = "Older")))
        assertTrue(issues.any { it.category == ComplianceCategory.BANNED_TOPIC && it.severity == Severity.BLOCKER })
        assertTrue(issues.any { it.category == ComplianceCategory.REPETITIVE_CONTENT && it.severity == Severity.BLOCKER })
        assertTrue(issues.any { it.category == ComplianceCategory.COPYRIGHT && it.severity == Severity.BLOCKER })
        assertEquals(ComplianceVerdict.BLOCK, PolicyRules.verdict(issues))
    }

    @Test
    fun `policy rules pass original content and flag realistic AI visuals for disclosure`() {
        val script = VideoScript("Camera tips", ContentLanguage.ENGLISH, VideoFormat.SHORT, "hook",
            listOf(ScriptScene(0, "Clean your lens before every shot", "photorealistic close-up of a phone lens")), "cta", 20)
        val artifacts = ProjectArtifacts(script = script, visuals = VisualSet(VideoFormat.SHORT, listOf(SceneVisual(0, "/a.jpg", VisualKind.IMAGE, "generated:OpenAI Images"))))
        val issues = PolicyRules.check(artifacts, FakeMemoryRepository().profileValue, emptyList())
        assertTrue(issues.none { it.severity == Severity.BLOCKER })
        assertTrue(PolicyRules.requiresAiDisclosure(artifacts))
    }

    @Test
    fun `input validation reports type mismatches`() {
        val schema = TubeJson.parseToJsonElement("""{"type":"object","properties":{"n":{"type":"integer"},"list":{"type":"array"}},"required":["n"]}""").jsonObject
        assertTrue(SkillManifestParser.validateInput(schema, TubeJson.parseToJsonElement("""{"n":3,"list":[]}""").jsonObject).isEmpty())
        assertEquals(2, SkillManifestParser.validateInput(schema, TubeJson.parseToJsonElement("""{"list":"x"}""").jsonObject).size)
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
