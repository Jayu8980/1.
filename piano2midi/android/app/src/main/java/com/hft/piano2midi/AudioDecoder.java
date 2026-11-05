package com.hft.piano2midi;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes any audio format supported by the device (mp3, m4a/aac, wav, flac, ogg, opus...) to mono float PCM. */
final class AudioDecoder {

    static final class Pcm {
        final float[] samples;
        final int sampleRate;

        Pcm(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    interface Progress {
        void onProgress(float fraction);
        boolean isCancelled();
    }

    private AudioDecoder() {}

    /** Growable float array. */
    private static final class FloatList {
        float[] a = new float[1 << 20];
        int n;

        void add(float v) {
            if (n == a.length) {
                float[] b = new float[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = v;
        }

        float[] toArray() {
            float[] b = new float[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }

    static Pcm decode(Context ctx, Uri uri, double maxSeconds, Progress progress) throws IOException, InterruptedException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track found in this file");
            ex.selectTrack(track);
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : -1;
            int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            FloatList out = new FloatList();
            long maxSamples = (long) (maxSeconds * sampleRate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long t = ex.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, size, t, 0);
                            ex.advance();
                            if (progress != null && durationUs > 0) progress.onProgress(Math.min(1f, t / (float) durationUs));
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    maxSamples = (long) (maxSeconds * sampleRate);
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer b = codec.getOutputBuffer(outIdx);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b = b.slice().order(ByteOrder.nativeOrder());
                        appendMono(b, encoding, channels, out);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    if (out.n >= maxSamples) outputDone = true;
                }
            }
            float[] s = out.toArray();
            if (s.length > maxSamples) {
                float[] t = new float[(int) maxSamples];
                System.arraycopy(s, 0, t, 0, t.length);
                s = t;
            }
            return new Pcm(s, sampleRate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    private static void appendMono(ByteBuffer b, int encoding, int channels, FloatList out) {
        if (channels < 1) channels = 1;
        float inv = 1f / channels;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            java.nio.FloatBuffer fb = b.asFloatBuffer();
            int frames = fb.remaining() / channels;
            for (int i = 0; i < frames; i++) {
                float acc = 0;
                for (int c = 0; c < channels; c++) acc += fb.get();
                out.add(acc * inv);
            }
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            int frames = b.remaining() / channels;
            for (int i = 0; i < frames; i++) {
                float acc = 0;
                for (int c = 0; c < channels; c++) acc += ((b.get() & 0xFF) - 128) / 128f;
                out.add(acc * inv);
            }
        } else {
            java.nio.ShortBuffer sb = b.asShortBuffer();
            int frames = sb.remaining() / channels;
            for (int i = 0; i < frames; i++) {
                float acc = 0;
                for (int c = 0; c < channels; c++) acc += sb.get() / 32768f;
                out.add(acc * inv);
            }
        }
    }
}
