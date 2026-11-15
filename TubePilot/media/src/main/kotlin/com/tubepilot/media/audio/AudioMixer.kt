package com.tubepilot.media.audio

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mixes a canonical voice track with optional looped background music. Music sits at
 * [musicDb] and is side-chain ducked a further [duckDb] while the voice is active, with a fade-in
 * at the start and a fade-out at the end. Output is canonical WAV of the voice length + tail.
 */
@Singleton
class AudioMixer @Inject constructor() {

    fun mix(voice: File, music: File?, output: File, musicDb: Double = -20.0, duckDb: Double = -10.0, tailMs: Long = 600) {
        val voiceFrames = Wav.readFormat(voice)?.frames ?: 0L
        val totalFrames = voiceFrames + tailMs * CanonicalAudio.SAMPLE_RATE / 1000
        val baseGain = 10.0.pow(musicDb / 20)
        val duckGain = 10.0.pow(duckDb / 20)
        val fadeIn = CanonicalAudio.SAMPLE_RATE * 1L
        val fadeOut = CanonicalAudio.SAMPLE_RATE * 2L
        // Envelope follower time constants (attack 10 ms, release 300 ms).
        val attack = exp(-1.0 / (0.010 * CanonicalAudio.SAMPLE_RATE))
        val release = exp(-1.0 / (0.300 * CanonicalAudio.SAMPLE_RATE))
        val threshold = 10.0.pow(-45.0 / 20)

        WavWriter(output, CanonicalAudio.SAMPLE_RATE, 1).use { writer ->
            WavReader(voice).use { voiceReader ->
                val musicReader = music?.let { LoopingReader(it) }
                try {
                    val vBuf = ShortArray(CanonicalAudio.CHUNK)
                    val mBuf = ShortArray(CanonicalAudio.CHUNK)
                    val out = ShortArray(CanonicalAudio.CHUNK)
                    var env = 0.0
                    var duck = 1.0
                    var written = 0L
                    while (written < totalFrames) {
                        val want = minOf(CanonicalAudio.CHUNK.toLong(), totalFrames - written).toInt()
                        val vn = voiceReader.read(vBuf, want).coerceAtLeast(0)
                        for (i in vn until want) vBuf[i] = 0
                        val mn = musicReader?.fill(mBuf, want) ?: 0
                        for (i in mn until want) mBuf[i] = 0
                        for (i in 0 until want) {
                            val v = vBuf[i] / 32768.0
                            val level = abs(v)
                            env = if (level > env) attack * env + (1 - attack) * level else release * env + (1 - release) * level
                            val targetDuck = if (env > threshold) duckGain else 1.0
                            duck += (targetDuck - duck) * 0.0008
                            val pos = written + i
                            val fade = when {
                                pos < fadeIn -> pos.toDouble() / fadeIn
                                pos > totalFrames - fadeOut -> ((totalFrames - pos).toDouble() / fadeOut).coerceAtLeast(0.0)
                                else -> 1.0
                            }
                            val m = mBuf[i] / 32768.0 * baseGain * duck * fade
                            out[i] = ((v + m).coerceIn(-1.0, 1.0) * 32767).roundToInt().toShort()
                        }
                        writer.write(out, want)
                        written += want
                    }
                } finally {
                    musicReader?.close()
                }
            }
        }
    }

    /** Concatenates canonical WAV files with [gapMs] of silence between them. */
    fun concat(parts: List<File>, output: File, gapMs: Long): List<Pair<Long, Long>> {
        val ranges = ArrayList<Pair<Long, Long>>()
        WavWriter(output, CanonicalAudio.SAMPLE_RATE, 1).use { writer ->
            val buf = ShortArray(CanonicalAudio.CHUNK)
            parts.forEachIndexed { index, part ->
                val startMs = writer.framesWritten * 1000 / CanonicalAudio.SAMPLE_RATE
                WavReader(part).use { reader ->
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        writer.write(buf, n)
                    }
                }
                if (index < parts.lastIndex) writer.writeSilence(gapMs * CanonicalAudio.SAMPLE_RATE / 1000)
                val endMs = writer.framesWritten * 1000 / CanonicalAudio.SAMPLE_RATE
                ranges += startMs to endMs
            }
        }
        return ranges
    }

    /** Writes [ms] of silence as canonical WAV (used when a scene has no narration). */
    fun silence(ms: Long, output: File) {
        WavWriter(output, CanonicalAudio.SAMPLE_RATE, 1).use { it.writeSilence(ms * CanonicalAudio.SAMPLE_RATE / 1000) }
    }
}

/** Reads a WAV file in a loop forever (until closed), for background music beds. */
private class LoopingReader(private val file: File) : java.io.Closeable {
    private var reader = WavReader(file)
    private var tmp = ShortArray(CanonicalAudio.CHUNK)

    fun fill(out: ShortArray, n: Int): Int {
        var filled = 0
        var restarts = 0
        while (filled < n) {
            if (tmp.size < n - filled) tmp = ShortArray(n - filled)
            val got = reader.read(tmp, n - filled)
            if (got < 0) {
                reader.close()
                reader = WavReader(file)
                if (++restarts > 1) break // empty file
                continue
            }
            restarts = 0
            System.arraycopy(tmp, 0, out, filled, got)
            filled += got
        }
        return filled
    }

    override fun close() = reader.close()
}
