package com.hft.piano2midi.core;

/**
 * Runs the hFT-Transformer over a whole log-mel feature matrix, 128 frames at a
 * time with 32 frames of context on each side (port of AMT.transcript()).
 */
public final class Transcriber {

    public static final int MARGIN_B = 32;
    public static final int MARGIN_F = 32;
    public static final int NUM_FRAME = 128;
    public static final int WINDOW = MARGIN_B + NUM_FRAME + MARGIN_F; // 192
    public static final int NUM_NOTE = 88;
    public static final int NOTE_MIN = 21;

    /** One forward pass: input [N_MELS][WINDOW] row-major, outputs [NUM_FRAME][NUM_NOTE] row-major. */
    public interface Model {
        void run(float[] spec, float[] onset, float[] offset, float[] mpe, int[] velocity) throws Exception;
    }

    public interface Progress {
        void onChunk(int done, int total);
        boolean isCancelled();
    }

    /** Frame-level model outputs for a whole recording. */
    public static final class Result {
        public final float[][] onset, offset, mpe;
        public final int[][] velocity;
        /** Number of frames that correspond to real audio (the rest is padding). */
        public final int validFrames;

        public Result(float[][] onset, float[][] offset, float[][] mpe, int[][] velocity, int validFrames) {
            this.onset = onset;
            this.offset = offset;
            this.mpe = mpe;
            this.velocity = velocity;
            this.validFrames = validFrames;
        }

        Result(int frames, int validFrames) {
            onset = new float[frames][NUM_NOTE];
            offset = new float[frames][NUM_NOTE];
            mpe = new float[frames][NUM_NOTE];
            velocity = new int[frames][NUM_NOTE];
            this.validFrames = validFrames;
        }
    }

    private Transcriber() {}

    public static int numChunks(int numFrames) {
        return (numFrames + NUM_FRAME - 1) / NUM_FRAME;
    }

    /**
     * Model input for chunk c: frames [c*128 - 32, c*128 + 160) transposed to [N_MELS][WINDOW],
     * padded with log(1e-8) outside [0, nFrames).
     */
    static void fillWindow(java.util.List<float[]> feature, int nFrames, int c, float[] spec) {
        int nBins = FeatureExtractor.N_MELS;
        int i = c * NUM_FRAME;
        for (int w = 0; w < WINDOW; w++) {
            int fr = i + w - MARGIN_B;
            float[] row = (fr >= 0 && fr < nFrames) ? feature.get(fr) : null;
            for (int b = 0; b < nBins; b++) {
                spec[b * WINDOW + w] = row != null ? row[b] : FeatureExtractor.MIN_VALUE;
            }
        }
    }

    public static Result transcribe(float[][] feature, Model model, Progress progress) throws Exception {
        final int nFrames = feature.length;
        final int nBins = FeatureExtractor.N_MELS;
        final int chunks = numChunks(nFrames);
        final int total = chunks * NUM_FRAME; // nFrames + len_s
        Result res = new Result(total, nFrames);

        float[] spec = new float[nBins * WINDOW];
        float[] on = new float[NUM_FRAME * NUM_NOTE];
        float[] off = new float[NUM_FRAME * NUM_NOTE];
        float[] mp = new float[NUM_FRAME * NUM_NOTE];
        int[] vel = new int[NUM_FRAME * NUM_NOTE];

        for (int c = 0; c < chunks; c++) {
            if (progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
            int i = c * NUM_FRAME;
            fillWindow(java.util.Arrays.asList(feature), nFrames, c, spec);
            model.run(spec, on, off, mp, vel);
            for (int f = 0; f < NUM_FRAME; f++) {
                System.arraycopy(on, f * NUM_NOTE, res.onset[i + f], 0, NUM_NOTE);
                System.arraycopy(off, f * NUM_NOTE, res.offset[i + f], 0, NUM_NOTE);
                System.arraycopy(mp, f * NUM_NOTE, res.mpe[i + f], 0, NUM_NOTE);
                System.arraycopy(vel, f * NUM_NOTE, res.velocity[i + f], 0, NUM_NOTE);
            }
            if (progress != null) progress.onChunk(c + 1, chunks);
        }
        return res;
    }
}
