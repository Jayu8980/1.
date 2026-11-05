package com.hft.piano2midi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.hft.piano2midi.core.music.NoteListener;

/**
 * Streams the microphone into a {@link NoteListener} (for wait mode with an acoustic piano).
 * Captures at 16 kHz when possible; otherwise low-pass filters and resamples to 16 kHz.
 * Constant memory: nothing is recorded.
 */
final class MicListener {

    private final NoteListener listener;
    private final AudioRecord rec;
    private final int rate;
    private volatile boolean running;
    private Thread thread;
    private volatile int[] watch = new int[0];
    private volatile boolean[] exclude = new boolean[128];

    /** @throws IllegalStateException if the microphone cannot be opened */
    MicListener(boolean unprocessed, NoteListener.Callback cb) {
        listener = new NoteListener(cb);
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
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, r / 5 * 2));
                    if (a.getState() == AudioRecord.STATE_INITIALIZED) {
                        ok = a;
                        okRate = r;
                        break outer;
                    }
                    a.release();
                } catch (Exception ignored) {
                    // next configuration
                }
            }
        }
        if (ok == null) throw new IllegalStateException("No usable microphone configuration");
        rec = ok;
        rate = okRate;
    }

    /** Expected pitches (from the wait track) and pitches the app itself is playing. */
    void update(int[] expected, boolean[] sounding) {
        watch = expected;
        exclude = sounding;
    }

    void start() {
        running = true;
        rec.startRecording();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mic-listen");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void loop() {
        short[] s = new short[rate / 100]; // 10 ms reads
        float[] f = new float[s.length];
        float[] out = new float[s.length];
        // resampling state (only used when rate != 16 kHz)
        final int taps = 31;
        float[] fir = new float[taps];
        float[] hist = new float[taps];
        int hpos = 0;
        double step = rate / (double) NoteListener.RATE, phase = 0;
        float last = 0;
        if (rate != NoteListener.RATE) {
            double fc = 7200.0 / rate; // low-pass below the 8 kHz Nyquist of the target rate
            double sum = 0;
            for (int i = 0; i < taps; i++) {
                double m = i - (taps - 1) / 2.0;
                double sinc = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
                double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1));
                fir[i] = (float) (sinc * w);
                sum += fir[i];
            }
            for (int i = 0; i < taps; i++) fir[i] /= sum;
        }
        int[] lastWatch = null;
        while (running) {
            int n = rec.read(s, 0, s.length);
            if (n <= 0) {
                if (n < 0) break;
                continue;
            }
            int[] w = watch;
            if (w != lastWatch) {
                listener.watch(w);
                lastWatch = w;
            }
            listener.exclude(exclude);
            if (rate == NoteListener.RATE) {
                for (int i = 0; i < n; i++) f[i] = s[i] / 32768f;
                listener.push(f, 0, n);
            } else {
                int m = 0;
                for (int i = 0; i < n; i++) {
                    hist[hpos] = s[i] / 32768f;
                    hpos = (hpos + 1) % taps;
                    float y = 0;
                    for (int k = 0; k < taps; k++) y += fir[k] * hist[(hpos + k) % taps];
                    // emit output samples that fall between the previous and this input sample
                    while (phase <= 1.0) {
                        if (m < out.length) out[m++] = (float) (last + (y - last) * phase);
                        phase += step;
                    }
                    phase -= 1.0;
                    last = y;
                }
                listener.push(out, 0, m);
            }
        }
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) { }
        }
        try {
            rec.stop();
        } catch (Exception ignored) { }
        rec.release();
    }
}
