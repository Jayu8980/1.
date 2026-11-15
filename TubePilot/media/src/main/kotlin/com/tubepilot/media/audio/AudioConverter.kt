package com.tubepilot.media.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Streaming linear-interpolation resampler for mono float samples. */
class LinearResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var t = 0.0
    private var prev = 0f
    private var out = FloatArray(0)

    /** Resamples [n] samples of [input] and returns (buffer, count). The buffer is reused. */
    fun process(input: FloatArray, n: Int): Pair<FloatArray, Int> {
        if (inRate == outRate) return input to n
        val capacity = ((n + 2) / step).toInt() + 4
        if (out.size < capacity) out = FloatArray(capacity)
        var count = 0
        // Virtual array: index -1 is the last sample of the previous block.
        while (t <= n - 1) {
            val i = kotlin.math.floor(t).toInt()
            val frac = (t - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = if (i + 1 < 0) prev else input[minOf(i + 1, n - 1)]
            out[count++] = a + (b - a) * frac
            t += step
        }
        t -= n
        if (n > 0) prev = input[n - 1]
        return out to count
    }
}

/**
 * Converts any audio file Android can decode (WAV, MP3, AAC/M4A, OGG, FLAC, Opus) into the
 * canonical 48 kHz mono 16-bit WAV, streaming so long files never sit in memory.
 */
@Singleton
class AudioConverter @Inject constructor() {

    fun toCanonical(input: File, output: File) {
        val tmp = File(output.parentFile, output.name + ".part")
        val wav = Wav.readFormat(input)
        if (wav != null && wav.isPcm16) convertWav(input, tmp) else decodeWithMediaCodec(input, tmp)
        if (!tmp.renameTo(output)) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
    }

    private fun convertWav(input: File, output: File) {
        WavReader(input).use { reader ->
            Sink(reader.format.sampleRate, reader.format.channels, output).use { sink ->
                val buf = ShortArray(8192 * reader.format.channels)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sink.acceptShorts(buf, n)
                }
            }
        }
    }

    private fun decodeWithMediaCodec(input: File, output: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${input.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            codec.configure(format, null, null, 0)
            codec.start()
            var sink: Sink? = null
            try {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var shorts = ShortArray(0)
                var floats = FloatArray(0)
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (sink == null) sink = Sink(sampleRate, channels, output)
                            if (floatPcm) {
                                val count = info.size / 4
                                if (floats.size < count) floats = FloatArray(count)
                                buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats, 0, count)
                                sink.acceptFloats(floats, count)
                            } else {
                                val count = info.size / 2
                                if (shorts.size < count) shorts = ShortArray(count)
                                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts, 0, count)
                                sink.acceptShorts(shorts, count)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                sink?.close() ?: WavWriter(output, CanonicalAudio.SAMPLE_RATE, 1).close()
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Downmixes to mono, resamples to 48 kHz and writes 16-bit PCM. */
    private class Sink(inRate: Int, private val channels: Int, output: File) : AutoCloseable {
        private val writer = WavWriter(output, CanonicalAudio.SAMPLE_RATE, 1)
        private val resampler = LinearResampler(inRate, CanonicalAudio.SAMPLE_RATE)
        private var mono = FloatArray(0)
        private var outShorts = ShortArray(0)

        fun acceptShorts(data: ShortArray, count: Int) {
            val frames = count / channels
            ensure(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += data[f * channels + c]
                mono[f] = sum / channels / 32768f
            }
            emit(frames)
        }

        fun acceptFloats(data: FloatArray, count: Int) {
            val frames = count / channels
            ensure(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += data[f * channels + c]
                mono[f] = sum / channels
            }
            emit(frames)
        }

        private fun ensure(frames: Int) {
            if (mono.size < frames) mono = FloatArray(frames)
        }

        private fun emit(frames: Int) {
            val (out, n) = resampler.process(mono, frames)
            if (outShorts.size < n) outShorts = ShortArray(n)
            for (i in 0 until n) outShorts[i] = (out[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            writer.write(outShorts, n)
        }

        override fun close() = writer.close()
    }
}
