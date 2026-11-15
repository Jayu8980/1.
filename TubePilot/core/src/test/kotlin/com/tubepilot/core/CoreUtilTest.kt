package com.tubepilot.core

import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.Chapter
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.util.JsonText
import com.tubepilot.core.util.TextUtil
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreUtilTest {
    @Test
    fun `extracts JSON from fenced or chatty model output`() {
        assertEquals("""{"a":{"b":"}"}}""", JsonText.extract("Sure!\n```json\n{\"a\":{\"b\":\"}\"}}\n```\nDone"))
        assertEquals("[1,[2]]", JsonText.extract("x [1,[2]] y"))
        assertNull(JsonText.extract("no json here"))
        assertNull(JsonText.extract("{ unterminated"))
    }

    @Test
    fun `quota day rolls over at midnight Pacific time`() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val beforeMidnight = Instant.parse("2026-11-15T23:59:00-08:00").toEpochMilli()
        assertEquals("2026-11-15", TimeUtil.quotaDayKey(beforeMidnight))
        assertEquals("2026-11-16", TimeUtil.quotaDayKey(beforeMidnight + 2 * 60_000))
        val reset = TimeUtil.nextQuotaResetMs(beforeMidnight)
        assertEquals(0, Instant.ofEpochMilli(reset).atZone(pacific).hour)
        assertEquals(60_000, reset - beforeMidnight)
    }

    @Test
    fun `jaccard similarity detects near duplicate scripts`() {
        val a = TextUtil.shingles("clean your phone lens before every photo for sharper results")
        val b = TextUtil.shingles("clean your phone lens before every photo for much sharper results")
        val c = TextUtil.shingles("battery saver mode explained in sixty seconds")
        assertTrue(TextUtil.jaccard(a, b) > 0.5)
        assertEquals(0.0, TextUtil.jaccard(a, c))
    }

    @Test
    fun `utf8 truncation never splits a multibyte character`() {
        val hindi = "नमस्ते दुनिया"
        val cut = TextUtil.truncateUtf8(hindi, 10)
        assertTrue(TextUtil.utf8Length(cut) <= 10)
        assertTrue(hindi.startsWith(cut))
    }

    @Test
    fun `description includes chapters only when YouTube would accept them`() {
        val seo = SeoPackage(listOf("t"), description = "Body", tags = emptyList(), hashtags = listOf("tips"), chapters = listOf(Chapter(0, "Intro"), Chapter(65, "Lens"), Chapter(3725, "Night")))
        val text = seo.fullDescription()
        assertTrue(text.contains("0:00 Intro") && text.contains("1:05 Lens") && text.contains("1:02:05 Night"))
        assertTrue(text.endsWith("#tips"))
        assertTrue("Chapters" !in seo.copy(chapters = seo.chapters.take(2)).fullDescription())
    }

    @Test
    fun `settings decode old documents with safe defaults`() {
        val s = TubeJson.decodeFromString(AppSettings.serializer(), """{"monthlyBudgetUsd": 5.0, "unknownFutureField": true}""")
        assertEquals(5.0, s.monthlyBudgetUsd)
        assertTrue(s.approveBeforePublish)
        assertTrue(!s.autoPostReplies && !s.killSwitch)
    }
}
