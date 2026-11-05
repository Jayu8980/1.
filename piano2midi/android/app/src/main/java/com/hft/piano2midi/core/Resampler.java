package com.hft.piano2midi.core;

/**
 * Band-limited resampler equivalent to torchaudio.transforms.Resample with the
 * default settings (sinc_interp_hann, lowpass_filter_width=6, rolloff=0.99),
 * which is what the hFT-Transformer feature pipeline uses.
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
        int g = gcd(origFreq, newFreq);
        int orig = origFreq / g;
        int neu = newFreq / g;
        final int lowpassWidth = 6;
        final double rolloff = 0.99;
        double baseFreq = Math.min(orig, neu) * rolloff;
        int width = (int) Math.ceil(lowpassWidth * orig / baseFreq);
        int klen = 2 * width + orig;

        // Kernel per output phase, trimmed to its non-zero span.
        float[][] kernels = new float[neu][];
        int[] kOffset = new int[neu];
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

        int length = x.length;
        // Signal is conceptually zero-padded by `width` on the left and `width + orig` on the right.
        int numBlocks = (length + 2 * width + orig - klen) / orig + 1;
        long target = (long) Math.ceil((double) neu * length / orig);
        int outLen = (int) Math.min(target, (long) numBlocks * neu);
        float[] y = new float[outLen];
        for (int j = 0; j < numBlocks; j++) {
            int base = j * orig - width; // index into x of padded position j*orig
            for (int p = 0; p < neu; p++) {
                int o = j * neu + p;
                if (o >= outLen) break;
                float[] k = kernels[p];
                int s = base + kOffset[p];
                double acc = 0.0;
                if (s >= 0 && s + k.length <= length) {
                    for (int i = 0; i < k.length; i++) acc += k[i] * x[s + i];
                } else {
                    for (int i = 0; i < k.length; i++) {
                        int xi = s + i;
                        if (xi >= 0 && xi < length) acc += k[i] * x[xi];
                    }
                }
                y[o] = (float) acc;
            }
            if (progress != null && (j & 1023) == 0) progress.onProgress(j / (float) numBlocks);
        }
        if (progress != null) progress.onProgress(1f);
        return y;
    }
}
