package com.autotube.agents.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono 16-bit PCM audio. */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    val durationSec: Double get() = samples.size.toDouble() / sampleRate

    fun resample(target: Int): Pcm {
        if (target == sampleRate || samples.isEmpty()) return this
        val ratio = sampleRate.toDouble() / target
        val n = (samples.size / ratio).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val src = i * ratio
            val i0 = src.toInt().coerceAtMost(samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val f = src - i0
            out[i] = (samples[i0] * (1 - f) + samples[i1] * f).toInt().toShort()
        }
        return Pcm(out, target)
    }

    companion object {
        /** Parses a RIFF/WAVE file (8/16-bit PCM, any channel count) into mono 16-bit. */
        fun readWav(file: File): Pcm {
            val bytes = file.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
            var pos = 12
            var channels = 1; var rate = 22050; var bits = 16
            var dataStart = -1; var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                var len = bb.getInt(pos + 4)
                val body = pos + 8
                if (len < 0 || body + len > bytes.size) len = bytes.size - body
                when (id) {
                    "fmt " -> { channels = bb.getShort(body + 2).toInt(); rate = bb.getInt(body + 4); bits = bb.getShort(body + 14).toInt() }
                    "data" -> { dataStart = body; dataLen = len }
                }
                if (dataStart >= 0) break
                pos = body + len + (len and 1)
            }
            require(dataStart >= 0) { "WAV has no data chunk" }
            val bytesPerSample = bits / 8
            val frames = dataLen / (bytesPerSample * channels)
            val out = ShortArray(frames)
            for (f in 0 until frames) {
                var acc = 0
                for (c in 0 until channels) {
                    val o = dataStart + (f * channels + c) * bytesPerSample
                    acc += if (bits == 8) ((bytes[o].toInt() and 0xFF) - 128) shl 8 else bb.getShort(o).toInt()
                }
                out[f] = (acc / channels).toShort()
            }
            return Pcm(out, rate)
        }

        fun silence(seconds: Double, rate: Int) = ShortArray((seconds * rate).toInt())
    }
}
