package com.hft.piano2midi.core;

/**
 * Log-mel front end matching the hFT-Transformer training pipeline
 * (torchaudio Resample + MelSpectrogram, see corpus/conv_wav2fe.py):
 *
 *   sr=16000, n_fft=2048, win_length=2048, hop=256, hann window (periodic),
 *   center=True, pad_mode="constant", power=2, n_mels=256, f_min=0, f_max=8000,
 *   mel_scale="htk", norm="slaney", feature = log(mel + 1e-8)
 *
 * Pure Java (no Android dependencies) so it can be unit-tested on a desktop JVM.
 */
public final class FeatureExtractor {

    public static final int SAMPLE_RATE = 16000;
    public static final int N_FFT = 2048;
    public static final int HOP = 256;
    public static final int N_MELS = 256;
    public static final double LOG_OFFSET = 1e-8;
    /** Value used to pad the feature matrix: log(1e-8) as float32. */
    public static final float MIN_VALUE = (float) Math.log(LOG_OFFSET);

    private static final int N_FREQS = N_FFT / 2 + 1;

    private final float[] window = new float[N_FFT];
    /** Sparse mel filterbank: for each mel band the first bin and its weights. */
    private final int[] melStart = new int[N_MELS];
    private final float[][] melWeights = new float[N_MELS][];
    private final Fft fft = new Fft(N_FFT);

    public interface Progress {
        /** @param fraction 0..1 */
        void onProgress(float fraction);
    }

    public FeatureExtractor() {
        for (int i = 0; i < N_FFT; i++) {
            // torch.hann_window(N, periodic=True)
            double v = Math.sin(Math.PI * i / N_FFT);
            window[i] = (float) (v * v);
        }
        buildMelFilterbank();
    }

    private static double hzToMel(double f) {
        return 2595.0 * Math.log10(1.0 + f / 700.0);
    }

    private static double melToHz(double m) {
        return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0);
    }

    /** torchaudio.functional.melscale_fbanks(..., norm='slaney', mel_scale='htk'), float32 maths. */
    private void buildMelFilterbank() {
        float[] allFreqs = new float[N_FREQS];
        for (int i = 0; i < N_FREQS; i++) {
            allFreqs[i] = (float) (i * (double) (SAMPLE_RATE / 2) / (N_FREQS - 1));
        }
        float mMin = (float) hzToMel(0.0);
        float mMax = (float) hzToMel(SAMPLE_RATE / 2.0);
        float[] fPts = new float[N_MELS + 2];
        for (int i = 0; i < N_MELS + 2; i++) {
            float m = mMin + (mMax - mMin) * i / (N_MELS + 1);
            fPts[i] = (float) melToHz(m);
        }
        for (int m = 0; m < N_MELS; m++) {
            float lower = fPts[m], center = fPts[m + 1], upper = fPts[m + 2];
            float dDown = center - lower, dUp = upper - center;
            float enorm = 2.0f / (upper - lower);
            float[] w = new float[N_FREQS];
            int first = -1, last = -1;
            for (int k = 0; k < N_FREQS; k++) {
                float down = (allFreqs[k] - lower) / dDown;
                float up = (upper - allFreqs[k]) / dUp;
                float v = Math.max(0f, Math.min(down, up)) * enorm;
                w[k] = v;
                if (v > 0f) {
                    if (first < 0) first = k;
                    last = k;
                }
            }
            if (first < 0) {
                melStart[m] = 0;
                melWeights[m] = new float[0];
            } else {
                melStart[m] = first;
                float[] sub = new float[last - first + 1];
                System.arraycopy(w, first, sub, 0, sub.length);
                melWeights[m] = sub;
            }
        }
    }

    /** Number of feature frames produced for a 16 kHz signal of the given length. */
    public static int numFrames(int numSamples) {
        return 1 + numSamples / HOP;
    }

    /**
     * @param wav16k mono signal at 16 kHz in [-1, 1]
     * @return feature matrix [numFrames][N_MELS]
     */
    public float[][] logMel(float[] wav16k, Progress progress) {
        int n = wav16k.length;
        int frames = numFrames(n);
        float[][] out = new float[frames][];
        for (int t = 0; t < frames; t++) {
            out[t] = frame(wav16k, n, t);
            if (progress != null && (t & 255) == 0) {
                progress.onProgress(t / (float) frames);
            }
        }
        if (progress != null) progress.onProgress(1f);
        return out;
    }

    /** Frames whose analysis window lies completely inside the first n samples (for streaming). */
    public static int framesReady(int n) {
        return n < N_FFT / 2 ? 0 : (n - N_FFT / 2) / HOP + 1;
    }

    private final double[] re = new double[N_FFT];
    private final double[] im = new double[N_FFT];
    private final double[] power = new double[N_FREQS];

    /** Log-mel frame t of a signal whose first n samples are wav[0..n) (zero beyond). Not thread-safe. */
    public float[] frame(float[] wav, int n, int t) {
        int start = t * HOP - N_FFT / 2;
        for (int i = 0; i < N_FFT; i++) {
            int idx = start + i;
            re[i] = (idx >= 0 && idx < n) ? wav[idx] * window[i] : 0.0;
            im[i] = 0.0;
        }
        fft.transform(re, im);
        for (int k = 0; k < N_FREQS; k++) {
            power[k] = re[k] * re[k] + im[k] * im[k];
        }
        float[] row = new float[N_MELS];
        for (int m = 0; m < N_MELS; m++) {
            float[] w = melWeights[m];
            int s0 = melStart[m];
            double acc = 0.0;
            for (int k = 0; k < w.length; k++) {
                acc += w[k] * power[s0 + k];
            }
            row[m] = (float) Math.log(acc + LOG_OFFSET);
        }
        return row;
    }

    /** Iterative radix-2 complex FFT. */
    public static final class Fft {
        private final int n;
        private final int[] rev;
        private final double[] cos;
        private final double[] sin;

        public Fft(int n) {
            this.n = n;
            int bits = Integer.numberOfTrailingZeros(n);
            rev = new int[n];
            for (int i = 0; i < n; i++) {
                rev[i] = Integer.reverse(i) >>> (32 - bits);
            }
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(-2 * Math.PI * i / n);
                sin[i] = Math.sin(-2 * Math.PI * i / n);
            }
        }

        public void transform(double[] re, double[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (j > i) {
                    double tr = re[i]; re[i] = re[j]; re[j] = tr;
                    double ti = im[i]; im[i] = im[j]; im[j] = ti;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1;
                int step = n / size;
                for (int i = 0; i < n; i += size) {
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        int a = i + j, b = a + half;
                        double xr = re[b] * cos[k] - im[b] * sin[k];
                        double xi = re[b] * sin[k] + im[b] * cos[k];
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }
}
