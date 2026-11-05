package com.hft.piano2midi.core.music;

/**
 * Fast "was this note just played?" detector for wait mode with an acoustic piano.
 *
 * Open-ended transcription is far too slow for wait mode (the model needs seconds of
 * context), but here we already know which notes to expect. For each watched pitch the
 * listener looks, every 32 ms, only at spectral energy that has just appeared (so ringing
 * notes and steady noise drop out), estimates the newly struck pitches iteratively (best
 * harmonic sum, remove its partials, repeat) and reports those that are being watched.
 * Pitches the app itself is playing can be excluded to limit loudspeaker echo.
 *
 * Input is 16 kHz mono. Not thread-safe: feed from one thread.
 */
public final class NoteListener {

    public interface Callback {
        void onNote(int pitch);
    }

    public static final int RATE = 16000;
    private static final int N = 4096, HOP = 512, HIST = 32, HARMONICS = 6;
    private static final double FRAME_SEC = HOP / (double) RATE;

    private final Callback callback;
    private final double[] window = new double[N];
    private final double[] re = new double[N], im = new double[N], mag = new double[N / 2 + 1];
    private int frame;
    private long processed; // samples consumed (frame starts)
    private final boolean[] watch = new boolean[128];
    private final boolean[] exclude = new boolean[128];
    private final int[] lastFire = new int[128];


    private final int[] rev;
    private final double[] cs, sn;

    public NoteListener(Callback cb) {
        callback = cb;
        for (int i = 0; i < N; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
        int bits = Integer.numberOfTrailingZeros(N);
        rev = new int[N];
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        cs = new double[N / 2];
        sn = new double[N / 2];
        for (int i = 0; i < N / 2; i++) {
            cs[i] = Math.cos(-2 * Math.PI * i / N);
            sn[i] = Math.sin(-2 * Math.PI * i / N);
        }
        for (int p = 0; p < 128; p++) lastFire[p] = -1000;
    }

    /** Pitches to listen for (replaces the previous set). */
    public void watch(int[] pitches) {
        for (int p = 0; p < 128; p++) watch[p] = false;
        for (int p : pitches) if (p >= 0 && p < 128) watch[p] = true;
    }

    /** Pitches currently played by the app's own synth (ignored to limit echo). */
    public void exclude(boolean[] sounding) {
        System.arraycopy(sounding, 0, exclude, 0, 128);
    }

    /** Processes all complete frames in x[0, n) not seen yet (x is the whole growing signal). */
    public void feed(float[] x, int n) {
        while (processed + N <= n) {
            analyse(x, (int) processed);
            processed += HOP;
        }
    }

    // streaming input: ring buffer of the last N samples, unrolled once per hop
    private final float[] ring = new float[N], lin = new float[N];
    private int ringPos, filled, sinceHop;

    /** Streaming alternative to {@link #feed}: appends samples and analyses every 32 ms. */
    public void push(float[] s, int off, int len) {
        for (int i = 0; i < len; i++) {
            ring[ringPos] = s[off + i];
            ringPos = (ringPos + 1) & (N - 1);
            if (filled < N) filled++;
            if (filled == N && ++sinceHop >= HOP) {
                sinceHop = 0;
                System.arraycopy(ring, ringPos, lin, 0, N - ringPos);
                System.arraycopy(ring, 0, lin, N - ringPos, ringPos);
                analyse(lin, 0);
                processed += HOP;
            }
        }
    }

    private void fft() {
        for (int i = 0; i < N; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1, step = N / size;
            for (int i = 0; i < N; i += size) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    int a = i + j, b = a + half;
                    double xr = re[b] * cs[k] - im[b] * sn[k];
                    double xi = re[b] * sn[k] + im[b] * cs[k];
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    private static double hz(int pitch) {
        return 440.0 * Math.pow(2, (pitch - 69) / 12.0);
    }

    private final double[] prev = new double[N / 2 + 1];   // recent spectrum (decaying max)
    private final double[] diff = new double[N / 2 + 1];   // energy that just appeared
    private final double[] level = new double[N / 2 + 1];  // slow average spectrum (noise floor)
    private final double[] sal = new double[128];
    private final double[] base = new double[N / 2 + 1];  // spectrum just before the last onset
    private int onsetAt = -100;
    private static final int MAX_NOTES = 8;
    /** Tuning: prev-spectrum factor, noise-floor factor, partial threshold (x mean), onset fraction, rel. */
    public static double K_PREV = 1.25, K_LEVEL = 2.0, K_THR = 2.5, K_ONSET = 0.02, K_REL = 0.15;

    /** Weighted harmonic sum of the new-energy spectrum for pitch p (and whether f0 itself is there). */
    private double harmonicSum(int p, boolean[] f0Present, double thr) {
        double df = RATE / (double) N, acc = 0;
        double f0 = hz(p);
        int count = 0;
        f0Present[0] = false;
        for (int h = 1; h <= HARMONICS; h++) {
            double f = f0 * h;
            if (f > 7000) break;
            if (f < 45) continue;
            int k0 = Math.max(1, (int) Math.floor(f * 0.98 / df)), k1 = Math.min(diff.length - 1, Math.max(k0 + 1, (int) Math.ceil(f * 1.02 / df)));
            double pk = 0;
            for (int k = k0; k <= k1; k++) pk = Math.max(pk, diff[k]);
            if (pk > thr) {
                count++;
                if (h == 1 || (p < 45 && h <= 2)) f0Present[0] = true;
            }
            acc += (p < 48 ? 1.0 : 1.0 / Math.sqrt(h)) * pk;
        }
        return count >= (p > 88 ? 1 : 2) ? acc : 0;
    }

    private void removePartials(int p) {
        double df = RATE / (double) N, f0 = hz(p);
        for (int h = 1; h <= HARMONICS; h++) {
            double f = f0 * h;
            if (f > 7000) break;
            int k0 = Math.max(1, (int) Math.floor(f * 0.975 / df)), k1 = Math.min(diff.length - 1, (int) Math.ceil(f * 1.025 / df));
            // keep a share so a partial common with another new note is not lost entirely
            for (int k = k0; k <= k1; k++) diff[k] *= 0.25;
        }
    }

    private final boolean[] f0Flag = new boolean[1];

    private void analyse(float[] x, int start) {
        for (int i = 0; i < N; i++) {
            re[i] = x[start + i] * window[i];
            im[i] = 0;
        }
        fft();
        double total = 0, newTotal = 0;
        for (int k = 0; k <= N / 2; k++) {
            mag[k] = Math.hypot(re[k], im[k]);
            double d = mag[k] - K_PREV * prev[k];
            // only energy that also stands out against this bin's slow background level counts
            diff[k] = d > 0 && mag[k] > K_LEVEL * level[k] ? d : 0;
            total += mag[k];
            newTotal += diff[k];
        }
        boolean fresh = newTotal > K_ONSET * total && frame >= 3;
        if (fresh && frame - onsetAt > 2) {
            onsetAt = frame;
            System.arraycopy(prev, 0, base, 0, base.length);
        }
        boolean inWindow = frame - onsetAt <= 2;
        if (inWindow && !fresh) {
            // later frames of the onset window: new energy relative to the pre-onset spectrum
            newTotal = 0;
            for (int k = 0; k <= N / 2; k++) {
                double d = mag[k] - K_PREV * base[k];
                diff[k] = d > 0 && mag[k] > K_LEVEL * level[k] ? d : 0;
                newTotal += diff[k];
            }
        }
        // threshold for "a partial is there": well above the typical new-energy bin
        double mean = newTotal / diff.length;
        double thr = Math.max(K_THR * mean, 1e-6);
        if (inWindow && newTotal > 0) {
            // iterative estimation of the newly struck pitches
            double first = 0;
            for (int it = 0; it < MAX_NOTES; it++) {
                int best = -1;
                double bestS = 0;
                for (int p = 21; p <= 108; p++) {
                    double h = harmonicSum(p, f0Flag, thr);
                    if (!f0Flag[0]) h = 0; // sub-octave / sub-fifth candidates lack their own fundamental
                    sal[p] = h;
                    if (h > bestS) {
                        bestS = h;
                        best = p;
                    }
                }
                if (best < 0 || bestS <= 0) break;
                if (it == 0) first = bestS;
                else if (bestS < K_REL * first) break;
                if (watch[best] && !exclude[best] && frame - lastFire[best] >= 5) {
                    lastFire[best] = frame;
                    callback.onNote(best);
                }
                removePartials(best);
            }
        }
        for (int k = 0; k <= N / 2; k++) {
            prev[k] = Math.max(mag[k], prev[k] * 0.8);
            level[k] = level[k] * 0.97 + mag[k] * 0.03;
        }
        frame++;
    }

    /** Seconds of audio analysed so far (for latency measurements). */
    public double seconds() {
        return (processed + N) / (double) RATE;
    }

    public static double frameSeconds() {
        return FRAME_SEC;
    }
}
