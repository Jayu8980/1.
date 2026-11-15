package com.tubepilot.media.audio

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

data class LoudnessResult(val integratedLufs: Double, val peakDbfs: Double, val durationMs: Long)

/** Biquad in transposed direct form II. */
internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Integrated loudness per ITU-R BS.1770-4 / EBU R128 (K-weighting, 400 ms blocks with 75 % overlap,
 * absolute gate -70 LUFS, relative gate -10 LU) plus sample peak, for 48 kHz mono WAV.
 */
@Singleton
class LoudnessMeter @Inject constructor() {

    fun measure(file: File): LoudnessResult {
        val stage1 = Biquad(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        val stage2 = Biquad(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        val subBlocks = ArrayList<Double>()
        var peak = 0.0
        var frames = 0L
        WavReader(file).use { reader ->
            require(reader.format.sampleRate == CanonicalAudio.SAMPLE_RATE && reader.format.channels == 1) { "Loudness requires canonical audio" }
            val buf = ShortArray(CanonicalAudio.CHUNK)
            var acc = 0.0
            var inBlock = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val x = buf[i] / 32768.0
                    peak = maxOf(peak, abs(x))
                    val y = stage2.process(stage1.process(x))
                    acc += y * y
                    inBlock++
                    if (inBlock == CanonicalAudio.CHUNK) {
                        subBlocks += acc / CanonicalAudio.CHUNK
                        acc = 0.0
                        inBlock = 0
                    }
                }
                frames += n
            }
            if (inBlock > CanonicalAudio.CHUNK / 2) subBlocks += acc / inBlock
        }
        // 400 ms gating blocks = 4 consecutive 100 ms sub-blocks, stepping by 100 ms.
        val blocks = if (subBlocks.size < 4) {
            listOf(subBlocks.average().takeIf { !it.isNaN() } ?: 0.0)
        } else {
            (0..subBlocks.size - 4).map { i -> (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4 }
        }
        fun lufs(z: Double) = -0.691 + 10 * log10(z.coerceAtLeast(1e-12))
        val absGated = blocks.filter { lufs(it) > -70.0 }
        val integrated = if (absGated.isEmpty()) {
            -70.0
        } else {
            val relGate = lufs(absGated.average()) - 10.0
            val relGated = absGated.filter { lufs(it) > relGate }
            lufs((relGated.ifEmpty { absGated }).average())
        }
        val peakDb = if (peak <= 0) -96.0 else 20 * log10(peak)
        return LoudnessResult(integrated, peakDb, frames * 1000 / CanonicalAudio.SAMPLE_RATE)
    }

    /**
     * Applies make-up gain to reach [targetLufs], then a soft-knee limiter that keeps peaks
     * below [ceilingDbfs]. Returns the measured result of the processed file.
     */
    fun normalize(input: File, output: File, targetLufs: Double, ceilingDbfs: Double = -1.0): LoudnessResult {
        val before = measure(input)
        val gainDb = (targetLufs - before.integratedLufs).coerceIn(-30.0, 30.0)
        val gain = 10.0.pow(gainDb / 20)
        val ceiling = 10.0.pow(ceilingDbfs / 20)
        val knee = ceiling * 0.8
        val tmp = File(output.parentFile, output.name + ".part")
        WavReader(input).use { reader ->
            WavWriter(tmp, CanonicalAudio.SAMPLE_RATE, 1).use { writer ->
                val buf = ShortArray(CanonicalAudio.CHUNK)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val x = buf[i] / 32768.0 * gain
                        val ax = abs(x)
                        val limited = if (ax <= knee) ax else knee + (ceiling - knee) * tanh((ax - knee) / (ceiling - knee))
                        buf[i] = (Math.copySign(limited, x) * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    writer.write(buf, n)
                }
            }
        }
        if (!tmp.renameTo(output)) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
        return measure(output)
    }
}
