package com.hft.piano2midi.core;

/**
 * Band-limited resampler equivalent to torchaudio.transforms.Resample with the
 * default settings (sinc_interp_hann, lowpass_filter_width=6, rolloff=0.99),
 * which is what the hFT-Transformer feature pipeline uses.
 *
 * {@link Stream} computes the same output incrementally from a growing input
 * buffer (for live recording); {@link #resample} runs it over a whole signal.
 */
public final class Resampler {

    private Resampler() {}

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public static float[] resample(float[] x, int origFreq, int newFreq, FeatureExtractor.Progress progress) {
        if (origFreq == newFreq) return x;
        Stream s = new Stream(origFreq, newFreq);
        int outLen = s.totalOutput(x.length);
        float[] y = new float[outLen];
        int step = 1 << 16;
        for (int o = 0; o < outLen; o += step) {
            s.process(x, x.length, y, o, Math.min(outLen, o + step));
            if (progress != null) progress.onProgress(o / (float) outLen);
        }
        if (progress != null) progress.onProgress(1f);
        return y;
    }

    /** Stateless incremental resampler: output sample o only depends on a fixed input span. */
    public static final class Stream {
        private final int orig, neu, width;
        private final float[][] kernels;
        private final int[] kOffset;

        public Stream(int origFreq, int newFreq) {
            int g = gcd(origFreq, newFreq);
            orig = origFreq / g;
            neu = newFreq / g;
            final int lowpassWidth = 6;
            final double rolloff = 0.99;
            double baseFreq = Math.min(orig, neu) * rolloff;
            width = (int) Math.ceil(lowpassWidth * orig / baseFreq);
            int klen = 2 * width + orig;
            kernels = new float[neu][];
            kOffset = new int[neu];
            double scale = baseFreq / orig;
            for (int p = 0; p < neu; p++) {
                float[] full = new float[klen];
                int first = -1, last = -1;
                for (int k = 0; k < klen; k++) {
                    // float32 like torch: idx/orig - p/new, then * base_freq
                    float idx = (float) (k - width) / orig;
                    float t = ((float) -p / neu + idx) * (float) baseFreq;
                    t = Math.max(-lowpassWidth, Math.min(lowpassWidth, t));
                    double w = Math.cos(t * Math.PI / lowpassWidth / 2);
                    w = w * w;
                    double tp = t * Math.PI;
                    double s = (tp == 0.0) ? 1.0 : Math.sin(tp) / tp;
                    float v = (float) (s * w * scale);
                    full[k] = v;
                    if (v != 0f) {
                        if (first < 0) first = k;
                        last = k;
                    }
                }
                if (first < 0) {
                    kernels[p] = new float[0];
                    kOffset[p] = 0;
                } else {
                    float[] sub = new float[last - first + 1];
                    System.arraycopy(full, first, sub, 0, sub.length);
                    kernels[p] = sub;
                    kOffset[p] = first;
                }
            }
        }

        /** Number of output samples for a complete input of the given length. */
        public int totalOutput(int inLen) {
            int klen = 2 * width + orig;
            long numBlocks = (inLen + 2L * width + orig - klen) / orig + 1;
            long target = (long) Math.ceil((double) neu * inLen / orig);
            return (int) Math.min(target, numBlocks * neu);
        }

        /** Output samples that can already be computed exactly from `inLen` input samples. */
        public int ready(int inLen) {
            // output o of block j uses input up to j*orig - width + kOffset + klen_p <= j*orig + width + orig
            long j = (long) (inLen - width - orig) / orig;
            if (inLen - width - orig < 0) return 0;
            return (int) Math.min((j + 1) * neu, totalOutput(inLen));
        }

        /** Computes outputs [o0, o1) into y (indexed by o) from x[0, inLen); input past inLen is zero. */
        public void process(float[] x, int inLen, float[] y, int o0, int o1) {
            for (int o = o0; o < o1; o++) {
                int j = o / neu, p = o - j * neu;
                float[] k = kernels[p];
                int s = j * orig - width + kOffset[p];
                double acc = 0.0;
                if (s >= 0 && s + k.length <= inLen) {
                    for (int i = 0; i < k.length; i++) acc += k[i] * x[s + i];
                } else {
                    for (int i = 0; i < k.length; i++) {
                        int xi = s + i;
                        if (xi >= 0 && xi < inLen) acc += k[i] * x[xi];
                    }
                }
                y[o] = (float) acc;
            }
        }
    }
}
