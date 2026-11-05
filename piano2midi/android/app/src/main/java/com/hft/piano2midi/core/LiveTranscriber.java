package com.hft.piano2midi.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental version of the file pipeline for live microphone input:
 * capture-rate PCM → (streaming resample) → 16 kHz → log-mel frames → 128-frame model chunks.
 *
 * A chunk runs as soon as its 32 frames of look-ahead context exist, so the output is
 * identical to transcribing the finished recording as a file; the remaining chunks are
 * padded exactly like the batch path once {@link #finish} is called.
 *
 * Not thread-safe: call everything from one processing thread.
 */
public final class LiveTranscriber {

    private final Transcriber.Model model;
    private final Resampler.Stream resampler; // null when capturing at 16 kHz
    private final FeatureExtractor fe = new FeatureExtractor();

    private float[] wav16 = new float[1 << 18];
    private int n16;
    private final List<float[]> feat = new ArrayList<float[]>();
    private final List<float[]> on = new ArrayList<float[]>(), off = new ArrayList<float[]>(), mpe = new ArrayList<float[]>();
    private final List<int[]> vel = new ArrayList<int[]>();
    private int chunksDone;
    private boolean finished;
    private int totalFrames = -1;

    private final float[] spec = new float[FeatureExtractor.N_MELS * Transcriber.WINDOW];
    private final float[] bOn = new float[Transcriber.NUM_FRAME * Transcriber.NUM_NOTE];
    private final float[] bOff = new float[bOn.length], bMpe = new float[bOn.length];
    private final int[] bVel = new int[bOn.length];

    public LiveTranscriber(Transcriber.Model model, int captureRate) {
        this.model = model;
        this.resampler = captureRate == FeatureExtractor.SAMPLE_RATE ? null
                : new Resampler.Stream(captureRate, FeatureExtractor.SAMPLE_RATE);
    }

    /** The 16 kHz signal so far (array may be longer than {@link #samples16k()}). */
    public float[] wav16k() {
        return wav16;
    }

    public int samples16k() {
        return n16;
    }

    private void ensure16(int n) {
        if (n <= wav16.length) return;
        int cap = wav16.length;
        while (cap < n) cap *= 2;
        float[] b = new float[cap];
        System.arraycopy(wav16, 0, b, 0, n16);
        wav16 = b;
    }

    /** Brings the 16 kHz signal and feature frames up to date with `inLen` captured samples. */
    private void ingest(float[] in, int inLen, boolean last) {
        if (resampler == null) {
            ensure16(inLen);
            System.arraycopy(in, n16, wav16, n16, inLen - n16);
            n16 = inLen;
        } else {
            int target = last ? resampler.totalOutput(inLen) : resampler.ready(inLen);
            if (target > n16) {
                ensure16(target);
                resampler.process(in, inLen, wav16, n16, target);
                n16 = target;
            }
        }
        int frames = last ? FeatureExtractor.numFrames(n16) : FeatureExtractor.framesReady(n16);
        for (int t = feat.size(); t < frames; t++) feat.add(fe.frame(wav16, n16, t));
        if (last) totalFrames = frames;
    }

    /**
     * Processes new input and runs at most one model chunk.
     * @param in captured mono samples (capture rate), valid up to inLen
     * @return true if a chunk was transcribed
     */
    public boolean step(float[] in, int inLen) throws Exception {
        if (finished) return false;
        ingest(in, inLen, false);
        int needed = chunksDone * Transcriber.NUM_FRAME + Transcriber.NUM_FRAME + Transcriber.MARGIN_F;
        if (feat.size() < needed) return false;
        runChunk(Integer.MAX_VALUE);
        return true;
    }

    /** Marks the input complete; afterwards call {@link #finishStep} until it returns false. */
    public void finish(float[] in, int inLen) {
        if (finished) return;
        ingest(in, inLen, true);
        finished = true;
    }

    /** Runs one of the remaining (padded) chunks after {@link #finish}; false when all are done. */
    public boolean finishStep() throws Exception {
        if (!finished || chunksDone >= Transcriber.numChunks(totalFrames)) return false;
        runChunk(totalFrames);
        return true;
    }

    public int remainingChunks() {
        return finished ? Math.max(0, Transcriber.numChunks(totalFrames) - chunksDone) : -1;
    }

    private void runChunk(int nFrames) throws Exception {
        int c = chunksDone;
        Transcriber.fillWindow(feat, Math.min(nFrames, feat.size()), c, spec);
        model.run(spec, bOn, bOff, bMpe, bVel);
        int nn = Transcriber.NUM_NOTE;
        for (int f = 0; f < Transcriber.NUM_FRAME; f++) {
            float[] a = new float[nn], b = new float[nn], d = new float[nn];
            int[] v = new int[nn];
            System.arraycopy(bOn, f * nn, a, 0, nn);
            System.arraycopy(bOff, f * nn, b, 0, nn);
            System.arraycopy(bMpe, f * nn, d, 0, nn);
            System.arraycopy(bVel, f * nn, v, 0, nn);
            on.add(a);
            off.add(b);
            mpe.add(d);
            vel.add(v);
        }
        chunksDone++;
    }

    /** Seconds of audio covered by transcribed chunks. */
    public double transcribedSeconds() {
        double s = chunksDone * Transcriber.NUM_FRAME * NoteDecoder.HOP_SEC;
        return Math.min(s, n16 / (double) FeatureExtractor.SAMPLE_RATE);
    }

    /** Frame outputs so far (a complete result once all finish steps have run). */
    public Transcriber.Result result() {
        int valid = totalFrames >= 0 ? totalFrames : on.size();
        return new Transcriber.Result(on.toArray(new float[0][]), off.toArray(new float[0][]),
                mpe.toArray(new float[0][]), vel.toArray(new int[0][]), Math.min(valid, on.size()));
    }
}
