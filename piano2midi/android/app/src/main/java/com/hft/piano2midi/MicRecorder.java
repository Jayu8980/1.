package com.hft.piano2midi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Captures mono microphone audio into a growing float buffer on its own thread.
 * Prefers the UNPROCESSED source (no AGC / noise suppression, which smear piano
 * notes) and 16 kHz (the model rate); falls back to MIC and 48/44.1 kHz.
 */
final class MicRecorder {

    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;
    private final int rate;
    private final Object lock = new Object();
    private float[] buf;
    private int n;
    private final int maxSamples;
    private volatile float level, peak;

    private boolean released;

    /**
     * @param unprocessed whether the device reports support for the UNPROCESSED source
     * @throws IllegalStateException if no microphone configuration works
     */
    MicRecorder(double maxSeconds, boolean unprocessed) {
        int[] rates = {16000, 48000, 44100};
        int[] sources = unprocessed
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        AudioRecord ok = null;
        int okRate = 0;
        outer:
        for (int src : sources) {
            for (int r : rates) {
                int min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                try {
                    AudioRecord a = new AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, r / 2 * 2)); // ~0.5 s buffer
                    if (a.getState() == AudioRecord.STATE_INITIALIZED) {
                        ok = a;
                        okRate = r;
                        break outer;
                    }
                    a.release();
                } catch (Exception ignored) {
                    // try the next configuration
                }
            }
        }
        if (ok == null) throw new IllegalStateException("No usable microphone configuration");
        rec = ok;
        rate = okRate;
        maxSamples = (int) (maxSeconds * rate);
        buf = new float[rate * 30];
    }

    int sampleRate() {
        return rate;
    }

    void start() {
        running = true;
        rec.startRecording();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mic");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void loop() {
        short[] s = new short[rate / 50]; // 20 ms reads
        while (running) {
            int r = rec.read(s, 0, s.length);
            if (r <= 0) {
                if (r < 0) break;
                continue;
            }
            float sum = 0, pk = 0;
            synchronized (lock) {
                if (n + r > buf.length) {
                    float[] b = new float[Math.max(buf.length * 2, n + r)];
                    System.arraycopy(buf, 0, b, 0, n);
                    buf = b;
                }
                for (int i = 0; i < r; i++) {
                    float v = s[i] / 32768f;
                    buf[n + i] = v;
                    sum += v * v;
                    pk = Math.max(pk, Math.abs(v));
                }
                n += r;
            }
            level = (float) Math.sqrt(sum / r);
            peak = pk;
            if (n >= maxSamples) running = false;
        }
    }

    /** True while capturing (false once stopped or the length limit was reached). */
    boolean isRunning() {
        return running;
    }

    synchronized void stop() {
        if (released) return;
        released = true;
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) { }
        }
        try {
            rec.stop();
        } catch (Exception ignored) { }
        rec.release();
    }

    /** Snapshot of the buffer: samples [0, length) of the returned array are valid and final. */
    float[] buffer() {
        synchronized (lock) {
            return buf;
        }
    }

    int length() {
        synchronized (lock) {
            return n;
        }
    }

    /** Consistent (array, length) pair. */
    Object[] snapshot() {
        synchronized (lock) {
            return new Object[]{buf, n};
        }
    }

    float rms() {
        return level;
    }

    float peak() {
        return peak;
    }

    double seconds() {
        return length() / (double) rate;
    }
}
