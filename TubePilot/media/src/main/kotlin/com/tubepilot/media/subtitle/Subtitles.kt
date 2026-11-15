package com.tubepilot.media.subtitle

import com.tubepilot.core.model.SceneTiming
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoScript
import java.io.File

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Builds subtitle cues from per-scene narration and exact scene timings. Within a scene, time is
 * split across chunks in proportion to their character count, which tracks speech closely
 * because each scene was synthesised on its own.
 */
object SubtitleBuilder {

    fun cues(script: VideoScript, timings: List<SceneTiming>, format: VideoFormat): List<Cue> {
        val maxChars = if (format == VideoFormat.SHORT) 26 else 42
        val byScene = timings.associateBy { it.sceneIndex }
        return script.scenes.flatMap { scene ->
            val timing = byScene[scene.index] ?: return@flatMap emptyList()
            cuesForSpan(scene.narration, timing.startMs, timing.endMs - TRAILING_PAUSE_MS, maxChars)
        }
    }

    fun cuesForSpan(text: String, startMs: Long, endMs: Long, maxChars: Int): List<Cue> {
        val chunks = chunk(text, maxChars)
        if (chunks.isEmpty() || endMs <= startMs) return emptyList()
        val total = chunks.sumOf { it.length }.coerceAtLeast(1)
        var t = startMs.toDouble()
        val span = (endMs - startMs).toDouble()
        return chunks.map { c ->
            val d = span * c.length / total
            val cue = Cue(t.toLong(), (t + d).toLong().coerceAtMost(endMs), c)
            t += d
            cue
        }
    }

    /** Greedy word wrap into caption-sized chunks. */
    fun chunk(text: String, maxChars: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        val line = StringBuilder()
        for (w in words) {
            if (line.isNotEmpty() && line.length + 1 + w.length > maxChars) {
                chunks += line.toString()
                line.clear()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(w)
            if (w.endsWith('.') || w.endsWith('?') || w.endsWith('!') || w.endsWith('।')) {
                chunks += line.toString()
                line.clear()
            }
        }
        if (line.isNotEmpty()) chunks += line.toString()
        return chunks
    }

    fun shift(cues: List<Cue>, fromMs: Long, toMs: Long): List<Cue> =
        cues.filter { it.endMs > fromMs && it.startMs < toMs }
            .map { Cue((it.startMs - fromMs).coerceAtLeast(0), (it.endMs - fromMs).coerceAtMost(toMs - fromMs), it.text) }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(ts(cue.startMs)).append(" --> ").append(ts(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    fun writeSrt(cues: List<Cue>, file: File): File = file.apply { writeText(toSrt(cues)) }

    fun parseSrt(text: String): List<Cue> = text.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim().lines()
        val timeLine = lines.firstOrNull { it.contains("-->") } ?: return@mapNotNull null
        val (a, b) = timeLine.split("-->").map { parseTs(it.trim()) }
        val body = lines.dropWhile { !it.contains("-->") }.drop(1).joinToString("\n")
        if (a == null || b == null) null else Cue(a, b, body)
    }

    private fun ts(ms: Long): String = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun parseTs(s: String): Long? {
        val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(s) ?: return null
        val (h, mi, se, ms) = m.destructured
        return h.toLong() * 3_600_000 + mi.toLong() * 60_000 + se.toLong() * 1000 + ms.padEnd(3, '0').take(3).toLong()
    }

    private const val TRAILING_PAUSE_MS = 200L
}
