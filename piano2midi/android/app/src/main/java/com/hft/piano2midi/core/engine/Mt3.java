package com.hft.piano2midi.core.engine;

import com.hft.piano2midi.core.FeatureExtractor;
import com.hft.piano2midi.core.Note;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Google MT3 (Gardner et al., ICLR 2022) on ONNX Runtime: TF-compatible log-mel front end,
 * T5 encoder + greedy step decoding with a growing KV cache (output tensors are fed back
 * as inputs, no copies through Java), and the event-codec -> notes decoding of
 * mt3.metrics_utils.event_predictions_to_ns. Two checkpoints: "ismir2021" (piano with
 * velocities, 512-frame segments) and "mt3" (multi-instrument, ties, 256-frame segments).
 */
public final class Mt3 implements AutoCloseable {

    public static final int SAMPLE_RATE = 16000;
    private static final int HOP = 128, NFFT = 2048, NMEL = 512, MAX_TOKENS = 1024;
    private static final int LAYERS = 8, HEADS = 6, HEAD_DIM = 64;

    private final OrtEnvironment env;
    private final OrtSession enc, dec;
    public final boolean multi;
    private final int segFrames, velocityBins;

    public interface Progress {
        void onChunk(int done, int total);

        boolean isCancelled();
    }

    public Mt3(String encoderPath, String decoderPath, boolean multiInstrument, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        enc = env.createSession(encoderPath, o);
        dec = env.createSession(decoderPath, o);
        multi = multiInstrument;
        segFrames = multi ? 256 : 512;
        velocityBins = multi ? 1 : 127;
    }

    /** Notes are final (MT3 has no decoding thresholds). */
    public static final class Output implements EngineOutput {
        private final List<Note> notes;
        public final int[] programs; // GM program per note (parallel to notes)
        private final double seconds;

        Output(List<Note> notes, int[] programs, double seconds) {
            this.notes = notes;
            this.programs = programs;
            this.seconds = seconds;
        }

        @Override
        public List<Note> decode(double p1, double p2, double p3, int mode) {
            List<Note> copy = new ArrayList<Note>(notes.size());
            for (Note n : notes) copy.add(new Note(n.pitch, n.onset, n.offset, n.velocity));
            return copy;
        }

        @Override
        public double seconds() {
            return seconds;
        }
    }

    // ------------------------------------------------------------ front end

    private static float[] melMatrix; // [1025][512] row-major
    /** Sparse view of the mel matrix: per band, first bin and weights (bins 1..1024 only). */
    private static int[] melLo;
    private static float[][] melW;

    private static synchronized float[] mel() {
        if (melMatrix != null) return melMatrix;
        int nb = NFFT / 2 + 1;
        float[] m = new float[nb * NMEL];
        double lo = 1127.0 * Math.log1p(20.0 / 700.0), hi = 1127.0 * Math.log1p(7600.0 / 700.0);
        double[] edges = new double[NMEL + 2];
        for (int i = 0; i < NMEL + 2; i++) edges[i] = lo + (hi - lo) * i / (NMEL + 1);
        for (int k = 1; k < nb; k++) {
            double f = k * (SAMPLE_RATE / 2.0) / (nb - 1);
            double sm = 1127.0 * Math.log1p(f / 700.0);
            for (int j = 0; j < NMEL; j++) {
                double lower = (sm - edges[j]) / (edges[j + 1] - edges[j]);
                double upper = (edges[j + 2] - sm) / (edges[j + 2] - edges[j + 1]);
                m[k * NMEL + j] = (float) Math.max(0, Math.min(lower, upper));
            }
        }
        melLo = new int[NMEL];
        melW = new float[NMEL][];
        for (int j = 0; j < NMEL; j++) {
            int a = -1, b = -1;
            for (int k = 1; k < nb; k++) {
                if (m[k * NMEL + j] != 0f) {
                    if (a < 0) a = k;
                    b = k;
                }
            }
            if (a < 0) {
                melW[j] = new float[0];
                continue;
            }
            melLo[j] = a;
            melW[j] = new float[b - a + 1];
            for (int k = a; k <= b; k++) melW[j][k - a] = m[k * NMEL + j];
        }
        melMatrix = m;
        return m;
    }

    /** spectral_ops.compute_logmel on one segment (pad_end framing, periodic Hann, |STFT|). */
    static float[] logmel(float[] x, int from, int len, int frames) {
        float[] out = new float[frames * NMEL];
        mel();
        double[] re = new double[NFFT], im = new double[NFFT];
        double[] win = new double[NFFT];
        for (int i = 0; i < NFFT; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT);
        FeatureExtractor.Fft fft = new FeatureExtractor.Fft(NFFT);
        int nf = (len + HOP - 1) / HOP;
        double[] mag = new double[NFFT / 2 + 1];
        for (int t = 0; t < frames; t++) {
            if (t >= nf) break; // remaining frames stay 0 (segment padding, like the feature converter)
            for (int i = 0; i < NFFT; i++) {
                int k = t * HOP + i;
                re[i] = k < len ? x[from + k] * win[i] : 0.0;
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int k = 0; k <= NFFT / 2; k++) mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            for (int j = 0; j < NMEL; j++) {
                double acc = 0;
                float[] w = melW[j];
                int lo = melLo[j];
                for (int k = 0; k < w.length; k++) acc += w[k] * mag[lo + k];
                out[t * NMEL + j] = (float) Math.log(acc <= 0 ? 1e-5 : acc);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ transcription

    public Output transcribe(float[] audio16k, Progress progress) throws Exception {
        int seg = segFrames * HOP;
        int segments = Math.max(1, (audio16k.length + seg - 1) / seg);
        List<double[]> starts = new ArrayList<double[]>();
        List<int[]> tokens = new ArrayList<int[]>();
        int nValid = 1001 + 128 + (velocityBins + 1) + 1 + 128 + 128;
        for (int s = 0; s < segments; s++) {
            if (progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
            int from = s * seg, len = Math.min(seg, audio16k.length - from);
            float[] spec = logmel(audio16k, from, len, segFrames);
            int[] toks = decodeSegment(spec, nValid, progress);
            double start = from / (double) SAMPLE_RATE;
            start -= start % 0.01;
            starts.add(new double[]{start});
            tokens.add(toks);
            if (progress != null) progress.onChunk(s + 1, segments);
        }
        return toNotes(starts, tokens, audio16k.length / (double) SAMPLE_RATE);
    }

    private int[] decodeSegment(float[] spec, int nValid, Progress progress) throws Exception {
        OnnxTensor specT = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), new long[]{1, segFrames, NMEL});
        OrtSession.Result er = enc.run(Collections.singletonMap("spec", specT));
        specT.close();
        OnnxTensor crossK = (OnnxTensor) er.get("cross_k").get(), crossV = (OnnxTensor) er.get("cross_v").get();
        long[] empty = {LAYERS, 1, HEADS, 0, HEAD_DIM};
        OnnxTensor pastK = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), empty);
        OnnxTensor pastV = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), empty);
        OrtSession.Result prev = null;
        List<Integer> out = new ArrayList<Integer>();
        long[] tokBuf = new long[1];
        float[] logits = null;
        try {
            int tok = 0;
            for (int pos = 0; pos < MAX_TOKENS; pos++) {
                if ((pos & 31) == 0 && progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
                tokBuf[0] = tok;
                OnnxTensor tokT = OnnxTensor.createTensor(env, LongBuffer.wrap(tokBuf), new long[]{1, 1});
                Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
                feed.put("token", tokT);
                feed.put("past_k", pastK);
                feed.put("past_v", pastV);
                feed.put("cross_k", crossK);
                feed.put("cross_v", crossV);
                OrtSession.Result r = dec.run(feed);
                tokT.close();
                if (prev != null) prev.close(); // the old cache tensors are no longer needed
                else {
                    pastK.close();
                    pastV.close();
                }
                prev = r;
                FloatBuffer lb = ((OnnxTensor) r.get("logits").get()).getFloatBuffer();
                if (logits == null) logits = new float[lb.remaining()];
                lb.get(logits);
                int best = 0;
                for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
                tok = best;
                if (tok == 1) break; // EOS
                out.add(tok >= 3 && tok < 3 + nValid ? tok - 3 : -2);
                pastK = (OnnxTensor) r.get("new_k").get();
                pastV = (OnnxTensor) r.get("new_v").get();
            }
        } finally {
            if (prev != null) prev.close();
            er.close();
        }
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) res[i] = out.get(i);
        return res;
    }

    // ------------------------------------------------------------ tokens -> notes

    private Output toNotes(List<double[]> starts, List<int[]> segTokens, double seconds) {
        // event ranges: shift 0..1000, pitch 0..127, velocity 0..bins, tie, program 0..127, drum 0..127
        int shiftEnd = 1001, pitchEnd = shiftEnd + 128, velEnd = pitchEnd + velocityBins + 1, tieEnd = velEnd + 1,
                progEnd = tieEnd + 128, drumEnd = progEnd + 128;
        List<double[]> notes = new ArrayList<double[]>(); // onset, offset, pitch, velocity, program
        Map<Long, double[]> active = new LinkedHashMap<Long, double[]>(); // key(pitch,program) -> {onset, vel}
        double curTime = 0;
        for (int si = 0; si < segTokens.size(); si++) {
            double start = starts.get(si)[0];
            Double maxTime = si + 1 < segTokens.size() ? starts.get(si + 1)[0] : null;
            Set<Long> tied = new HashSet<Long>();
            boolean tieSection = multi;
            int vel = 100, program = 0, steps = 0;
            double t = start;
            for (int tok : segTokens.get(si)) {
                if (tok < 0 || tok >= drumEnd) continue;
                if (tok < shiftEnd) {
                    steps += tok;
                    t = start + steps / 100.0;
                    if (maxTime != null && t > maxTime) break;
                    continue;
                }
                steps = 0;
                if (t < curTime) continue;
                curTime = t;
                if (tok < pitchEnd) {
                    int pitch = tok - shiftEnd;
                    long key = ((long) pitch << 8) | program;
                    if (tieSection) {
                        if (active.containsKey(key) && !tied.contains(key)) tied.add(key);
                    } else if (vel == 0) {
                        double[] a = active.remove(key);
                        if (a != null) notes.add(new double[]{a[0], Math.max(t, a[0] + 0.01), pitch, a[1], program});
                    } else {
                        double[] a = active.remove(key);
                        if (a != null) notes.add(new double[]{a[0], Math.max(t, a[0] + 0.01), pitch, a[1], program});
                        active.put(key, new double[]{t, vel});
                    }
                } else if (tok < velEnd) {
                    int bin = tok - pitchEnd;
                    vel = bin == 0 ? 0 : (int) (127.0 * bin / velocityBins);
                } else if (tok < tieEnd) {
                    if (tieSection) {
                        for (Long key : new ArrayList<Long>(active.keySet())) {
                            if (!tied.contains(key)) {
                                double[] a = active.remove(key);
                                notes.add(new double[]{a[0], Math.max(curTime, a[0] + 0.01), (int) (key >> 8), a[1], (int) (key & 0xFF)});
                            }
                        }
                        tieSection = false;
                    }
                } else if (tok < progEnd) {
                    program = tok - tieEnd;
                }
                // drum events are ignored (piano-roll app)
            }
        }
        for (double[] a : active.values()) curTime = Math.max(curTime, a[0] + 0.01);
        for (Map.Entry<Long, double[]> e : active.entrySet()) {
            double[] a = e.getValue();
            notes.add(new double[]{a[0], Math.max(curTime, a[0] + 0.01), (int) (e.getKey() >> 8), a[1], (int) (e.getKey() & 0xFF)});
        }
        Collections.sort(notes, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                int c = Double.compare(a[0], b[0]);
                return c != 0 ? c : Double.compare(a[2], b[2]);
            }
        });
        List<Note> res = new ArrayList<Note>();
        int[] progs = new int[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            double[] a = notes.get(i);
            int p = (int) a[2];
            if (p < 21 || p > 108) continue;
            // the multi-instrument model has no velocities (all 127): use a natural default
            int v = multi ? 96 : Math.max(1, Math.min(127, (int) a[3]));
            progs[res.size()] = (int) a[4];
            res.add(new Note(p, a[0], a[1], v));
        }
        int[] trimmed = new int[res.size()];
        System.arraycopy(progs, 0, trimmed, 0, trimmed.length);
        return new Output(res, trimmed, seconds);
    }

    @Override
    public void close() throws OrtException {
        enc.close();
        dec.close();
    }
}
