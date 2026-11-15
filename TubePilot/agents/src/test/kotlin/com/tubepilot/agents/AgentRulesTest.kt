package com.tubepilot.agents

import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.CalendarStatus
import com.tubepilot.core.model.Chapter
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.model.ThumbnailSet
import com.tubepilot.core.model.ThumbnailVariant
import com.tubepilot.core.model.TitleTest
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.util.TextUtil
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.media.probe.ImageProbe
import com.tubepilot.media.probe.VideoProbe
import com.tubepilot.media.subtitle.Cue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRulesTest {
    private val base = 1_763_200_000_000L
    private fun entry(id: String, at: Long, topic: String, format: VideoFormat = VideoFormat.SHORT, status: CalendarStatus = CalendarStatus.PLANNED) =
        CalendarEntry(id, at, format, topic, status = status)

    @Test
    fun `conflict resolver enforces strategy, frequency, spacing and duplicates`() {
        val profile = ChannelProfile(niche = "phones", audience = "students", shortsPerWeek = 2, longFormPerWeek = 0, bannedTopics = listOf("crypto"))
        val locked = entry("locked", base, "Battery myths busted", status = CalendarStatus.IN_PRODUCTION)
        val proposed = listOf(
            entry("a", base + TimeUtil.HOUR, "Camera tips for night"),          // too close to locked
            entry("b", base + TimeUtil.DAY, "Crypto apps on phones"),           // banned
            entry("c", base + 2 * TimeUtil.DAY, "Battery myths busted again"),  // duplicate topic
            entry("d", base + 3 * TimeUtil.DAY, "Night photography settings"),
            entry("e", base + 4 * TimeUtil.DAY, "Five gaming phones under budget"), // over the weekly cap
            entry("f", base + 5 * TimeUtil.DAY, "Long one", VideoFormat.LONG),      // long-form cap is 0
        )
        val result = ConflictResolver.resolve(proposed, listOf(locked), profile)
        assertEquals(listOf("locked", "d"), result.kept.map { it.id })
        val reasons = result.removed.associate { it.first.id to it.second }
        assertTrue(reasons["a"]!!.contains("too close"))
        assertTrue(reasons["b"]!!.contains("banned"))
        assertTrue(reasons["c"]!!.contains("duplicate"))
        assertTrue(reasons["e"]!!.contains("frequency"))
        assertTrue(reasons["f"]!!.contains("frequency"))
    }

    @Test
    fun `publish planner uses the slot when far enough ahead, else the next preferred hour`() {
        val profile = ChannelProfile(preferredPublishHours = listOf(18), timezone = "Asia/Kolkata")
        val slot = base + 2 * TimeUtil.DAY
        assertEquals(slot, PublishTimePlanner.publishAt(slot, base, profile))
        val next = PublishTimePlanner.publishAt(base + 10 * TimeUtil.MINUTE, base, profile)!!
        assertTrue(next >= base + PublishTimePlanner.MIN_LEAD_MS)
        assertEquals(18, java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of("Asia/Kolkata")).hour)
        assertNull(PublishTimePlanner.publishAt(null, base, profile.copy(preferredPublishHours = emptyList())))
    }

    @Test
    fun `title test rotates variants every window and keeps the winner`() {
        var test = TitleTest("vid", listOf("A", "B", "C"), activeIndex = 0, switchedAt = base)
        assertIs<TitleTestPlanner.Step.Wait>(TitleTestPlanner.next(test, 100, base + TimeUtil.HOUR))

        val s1 = TitleTestPlanner.next(test, 300, base + TitleTestPlanner.WINDOW_MS)
        assertIs<TitleTestPlanner.Step.Switch>(s1)
        assertEquals(1, s1.toIndex)
        test = s1.test

        val s2 = TitleTestPlanner.next(test, 1_300, test.switchedAt + TitleTestPlanner.WINDOW_MS)
        assertIs<TitleTestPlanner.Step.Switch>(s2)
        test = s2.test

        val done = TitleTestPlanner.next(test, 1_500, test.switchedAt + TitleTestPlanner.WINDOW_MS)
        assertIs<TitleTestPlanner.Step.Finish>(done)
        assertEquals(1, done.winnerIndex) // B gained 1000 views vs A 300 and C 200
        assertEquals(listOf(300L, 1000L, 200L), done.test.viewsPerVariant)
        assertTrue(done.test.finished)
    }

    @Test
    fun `lesson deduper detects near-duplicates`() {
        val existing = listOf("Hooks that start with a number got higher CTR")
        assertTrue(LessonDeduper.isDuplicate("Hooks that start with a number get higher CTR", existing))
        assertFalse(LessonDeduper.isDuplicate("Uploading at 6pm doubled first-hour views", existing))
    }

    @Test
    fun `seo limits clean titles, tags, hashtags, chapters and description size`() {
        val seo = SeoPackage(
            titleVariants = listOf("<Best> phone   camera tips " + "x".repeat(120)),
            description = "d".repeat(6000),
            tags = List(80) { "camera tip number $it" } + "camera tip number 1",
            hashtags = listOf("phone", "#Camera Tips", "phone", "a", "b", "c", "d"),
            chapters = listOf(Chapter(5, "Intro"), Chapter(8, "Too close"), Chapter(60, "Lens"), Chapter(120, "Night")),
        )
        val out = SeoLimits.apply(seo, durationSec = 300, format = VideoFormat.LONG)
        assertTrue(out.title.length <= 100 && '<' !in out.title && "  " !in out.title)
        assertTrue(SeoLimits.tagsLength(out.tags) <= 500)
        assertEquals(out.tags.size, out.tags.distinct().size)
        assertTrue(out.hashtags.size <= 5 && out.hashtags.all { it.startsWith("#") && ' ' !in it })
        assertEquals(listOf(0, 60, 120), out.chapters.map { it.startSec })
        assertTrue(TextUtil.utf8Length(out.fullDescription()) <= 5000)

        val short = SeoLimits.apply(seo.copy(hashtags = listOf("tips")), 40, VideoFormat.SHORT)
        assertEquals("#Shorts", short.hashtags.first())
        assertTrue(short.chapters.isEmpty())
    }

    @Test
    fun `qa rules catch wrong resolution, audio drift, overlapping cues and oversized thumbnails`() {
        val good = VideoProbe(30_000, 1080, 1920, 0, hasAudio = true, hasVideo = true, bitrate = 8_000_000, sizeBytes = 20_000_000)
        assertTrue(QaRules.video(good, VideoFormat.SHORT, 30_400, "Video").all { it.passed })
        val bad = good.copy(width = 1920, height = 1080, hasAudio = false, durationMs = 40_000)
        val failed = QaRules.video(bad, VideoFormat.SHORT, 30_000, "Video").filter { !it.passed }.map { it.name }
        assertEquals(listOf("Video resolution", "Video matches audio", "Video audio track"), failed)
        assertFalse(QaRules.video(null, VideoFormat.SHORT, 1, "Video").single().passed)

        val cues = listOf(Cue(0, 1000, "a"), Cue(900, 2000, "b"))
        assertFalse(QaRules.subtitles(cues, 2000).first { it.name == "Subtitles ordered" }.passed)

        val set = ThumbnailSet(listOf(ThumbnailVariant("/a.jpg", "H", "s", 1), ThumbnailVariant("/b.jpg", "H", "s", 1)))
        val checks = QaRules.thumbnails(set, listOf(ImageProbe(1280, 720, 500_000), ImageProbe(1280, 720, 3_000_000)))
        assertEquals(listOf(true, false), checks.map { it.passed })

        val meta = QaRules.metadata(SeoPackage(titleVariants = listOf("Fine title"), description = "desc", tags = listOf("a"), hashtags = emptyList()))
        assertTrue(meta.all { it.passed })
        assertFalse(QaRules.metadata(null).single().passed)
    }
}
