package com.tubepilot.media.audio

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical internal audio format: 48 kHz mono 16-bit PCM WAV (matches the K-weighting filters). */
object CanonicalAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val CHUNK = 4_800 // 100 ms
}

data class WavFormat(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val audioFormat: Int, val dataOffset: Long, val dataBytes: Long) {
    val isPcm16: Boolean get() = audioFormat == 1 && bitsPerSample == 16
    val frames: Long get() = dataBytes / (channels * (bitsPerSample / 8)).coerceAtLeast(1)
    val durationMs: Long get() = frames * 1000 / sampleRate.coerceAtLeast(1)
}

object Wav {
    /** Parses RIFF/WAVE headers, skipping unknown chunks (LIST, fact, ...). Returns null if not a WAV. */
    fun readFormat(file: File): WavFormat? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
            var offset = 12L
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var fmt = 0
            while (true) {
                val id = ByteArray(4).also { input.readFully(it) }
                val size = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
                offset += 8
                val name = String(id)
                if (name == "fmt ") {
                    val body = ByteArray(size.toInt()).also { input.readFully(it) }
                    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    fmt = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bits = bb.short.toInt()
                    if (fmt == 0xFFFE && body.size >= 26) fmt = ByteBuffer.wrap(body, 24, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                } else if (name == "data") {
                    // Some encoders write 0 or 0xFFFFFFFF for streamed data; fall back to file size.
                    val actual = if (size == 0L || size == 0xFFFFFFFFL) file.length() - offset else minOf(size, file.length() - offset)
                    return WavFormat(sampleRate, channels, bits, fmt, offset, actual)
                } else {
                    input.skipFully(size)
                }
                offset += size + (size and 1)
                if (name == "fmt " && size and 1L == 1L) input.skipFully(1)
                if (name != "fmt " && size and 1L == 1L) input.skipFully(1)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    /** Writes raw little-endian 16-bit PCM as a WAV file. */
    fun fromRawPcm16(raw: File, sampleRate: Int, channels: Int, out: File) {
        WavWriter(out, sampleRate, channels).use { writer ->
            FileInputStream(raw).buffered().use { input ->
                val bytes = ByteArray(8192)
                val shorts = ShortArray(4096)
                var carry = -1
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    var i = 0
                    var count = 0
                    if (carry >= 0 && n > 0) {
                        shorts[count++] = ((bytes[0].toInt() shl 8) or carry).toShort()
                        i = 1
                        carry = -1
                    }
                    while (i + 1 < n) {
                        shorts[count++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                        i += 2
                    }
                    if (i < n) carry = bytes[i].toInt() and 0xFF
                    writer.write(shorts, count)
                }
            }
        }
    }
}

/** Streams 16-bit PCM samples out of a WAV file. */
class WavReader(file: File) : Closeable {
    val format: WavFormat = Wav.readFormat(file) ?: throw IOException("Not a WAV file: ${file.name}")
    private val raf = RandomAccessFile(file, "r")
    private var remaining = format.dataBytes
    private var bytes = ByteArray(0)

    init {
        require(format.isPcm16) { "Only 16-bit PCM WAV is read directly (got fmt=${format.audioFormat} bits=${format.bitsPerSample})" }
        raf.seek(format.dataOffset)
    }

    /** Reads up to [out].size interleaved samples. Returns the count, or -1 at end of data. */
    fun read(out: ShortArray, max: Int = out.size): Int {
        if (remaining <= 0) return -1
        val wantBytes = minOf((max * 2).toLong(), remaining).toInt() and 1.inv()
        if (wantBytes == 0) return -1
        if (bytes.size < wantBytes) bytes = ByteArray(wantBytes)
        val n = raf.read(bytes, 0, wantBytes)
        if (n <= 0) return -1
        remaining -= n
        val samples = n / 2
        ByteBuffer.wrap(bytes, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, samples)
        return samples
    }

    override fun close() = raf.close()
}

/** Streams 16-bit PCM into a WAV file; the header sizes are patched on close. */
class WavWriter(private val file: File, val sampleRate: Int, val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var buffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    val framesWritten: Long get() = dataBytes / (2 * channels)

    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        if (buffer.capacity() < count * 2) buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        buffer.asShortBuffer().put(samples, 0, count)
        raf.write(buffer.array(), 0, count * 2)
        dataBytes += count * 2
    }

    fun writeSilence(frames: Long) {
        val chunk = ShortArray(4096)
        var left = frames * channels
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            write(chunk, n)
            left -= n
        }
    }

    override fun close() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
