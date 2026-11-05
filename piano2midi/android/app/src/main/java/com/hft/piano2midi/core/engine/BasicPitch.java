package com.hft.piano2midi.core.engine;

import com.hft.piano2midi.core.Note;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Spotify Basic Pitch (ICASSP 2022, "nmp" model) on ONNX Runtime: 22.05 kHz audio in 2 s
 * windows (30 frames overlap), note / onset activations at 86 fps, and a port of
 * basic_pitch.note_creation.output_to_notes_polyphonic (inferred onsets, melodia trick).
 */
public final class BasicPitch implements AutoCloseable {

    public static final int SAMPLE_RATE = 22050;
    private static final int FFT_HOP = 256;
    private static final int AUDIO_N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP; // 43844
    private static final int ANNOT_N_FRAMES = (SAMPLE_RATE / FFT_HOP) * 2; // 172
    private static final int OVERLAP_FRAMES = 30;
    private static final int OVERLAP_LEN = OVERLAP_FRAMES * FFT_HOP;        // 7680
    private static final int HOP_SIZE = AUDIO_N_SAMPLES - OVERLAP_LEN;      // 36164
    private static final int N_NOTES = 88;
    private static final double MAGIC_ALIGNMENT_OFFSET = 0.0018;
    private static final int ENERGY_TOL = 11;

    public static final double DEFAULT_ONSET = 0.5, DEFAULT_FRAME = 0.3, DEFAULT_MIN_NOTE_MS = 127.7;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    public interface Progress {
        void onChunk(int done, int total);

        boolean isCancelled();
    }

    public BasicPitch(String modelPath, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        session = env.createSession(modelPath, o);
        inputName = session.getInputNames().iterator().next();
    }

    /** Frame activations for a whole recording (22.05 kHz mono). */
    public static final class Output implements EngineOutput {
        public final float[][] note, onset;
        public final double seconds;

        Output(float[][] note, float[][] onset, double seconds) {
            this.note = note;
            this.onset = onset;
            this.seconds = seconds;
        }

        /** p1 = onset threshold, p2 = frame threshold, p3 = minimum note length (ms). */
        @Override
        public List<Note> decode(double p1, double p2, double p3, int mode) {
            int minLen = (int) Math.round(p3 / 1000.0 * (SAMPLE_RATE / (double) FFT_HOP));
            return toNotes(note, onset, p1, p2, minLen);
        }

        @Override
        public double seconds() {
            return seconds;
        }
    }

    public Output transcribe(float[] audio22k, Progress progress) throws Exception {
        int originalLength = audio22k.length;
        // pad half the overlap at the start, then window with hop HOP_SIZE
        int padded = OVERLAP_LEN / 2 + originalLength;
        int windows = Math.max(1, (padded + HOP_SIZE - 1) / HOP_SIZE);
        int keep = ANNOT_N_FRAMES - OVERLAP_FRAMES; // 142 frames kept per window
        int nOlap = OVERLAP_FRAMES / 2;
        double expectedWindows = originalLength / (double) HOP_SIZE;
        int totalFrames = Math.min(windows * keep, (int) (expectedWindows * keep));
        float[][] note = new float[totalFrames][N_NOTES], onset = new float[totalFrames][N_NOTES];
        float[] win = new float[AUDIO_N_SAMPLES];
        long[] shape = {1, AUDIO_N_SAMPLES, 1};
        float[] nb = new float[ANNOT_N_FRAMES * N_NOTES], ob = new float[ANNOT_N_FRAMES * N_NOTES];
        for (int w = 0; w < windows; w++) {
            if (progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
            int start = w * HOP_SIZE - OVERLAP_LEN / 2; // index into the unpadded audio
            for (int i = 0; i < AUDIO_N_SAMPLES; i++) {
                int k = start + i;
                win[i] = k >= 0 && k < originalLength ? audio22k[k] : 0f;
            }
            OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(win), shape);
            try {
                Map<String, OnnxTensor> feed = Collections.singletonMap(inputName, in);
                OrtSession.Result r = session.run(feed);
                try {
                    ((OnnxTensor) r.get("StatefulPartitionedCall:1").get()).getFloatBuffer().get(nb);
                    ((OnnxTensor) r.get("StatefulPartitionedCall:2").get()).getFloatBuffer().get(ob);
                } finally {
                    r.close();
                }
            } finally {
                in.close();
            }
            for (int f = 0; f < keep; f++) {
                int dst = w * keep + f;
                if (dst >= totalFrames) break;
                System.arraycopy(nb, (f + nOlap) * N_NOTES, note[dst], 0, N_NOTES);
                System.arraycopy(ob, (f + nOlap) * N_NOTES, onset[dst], 0, N_NOTES);
            }
            if (progress != null) progress.onChunk(w + 1, windows);
        }
        return new Output(note, onset, originalLength / (double) SAMPLE_RATE);
    }

    /** basic_pitch.note_creation.model_frames_to_time */
    static double frameTime(int i) {
        double windowOffset = (FFT_HOP / (double) SAMPLE_RATE) * (ANNOT_N_FRAMES - (AUDIO_N_SAMPLES / (double) FFT_HOP))
                + MAGIC_ALIGNMENT_OFFSET;
        return i * FFT_HOP / (double) SAMPLE_RATE - windowOffset * Math.floor(i / (double) ANNOT_N_FRAMES);
    }

    /** Port of output_to_notes_polyphonic(infer_onsets=True, melodia_trick=True). */
    static List<Note> toNotes(float[][] frames, float[][] onsetsIn, double onsetThresh, double frameThresh, int minNoteLen) {
        int n = frames.length;
        List<Note> out = new ArrayList<Note>();
        if (n == 0) return out;
        // inferred onsets: max(onsets, rescaled positive frame differences over 1 and 2 frames)
        float[][] onsets = new float[n][N_NOTES];
        float maxOnset = 0, maxDiff = 0;
        float[][] diff = new float[n][N_NOTES];
        for (int t = 0; t < n; t++) {
            for (int f = 0; f < N_NOTES; f++) {
                maxOnset = Math.max(maxOnset, onsetsIn[t][f]);
                if (t < 2) continue;
                float d1 = frames[t][f] - frames[t - 1][f];
                float d2 = frames[t][f] - frames[t - 2][f];
                float d = Math.max(0f, Math.min(d1, d2));
                diff[t][f] = d;
                maxDiff = Math.max(maxDiff, d);
            }
        }
        float scale = maxDiff > 0 ? maxOnset / maxDiff : 0f;
        for (int t = 0; t < n; t++) {
            for (int f = 0; f < N_NOTES; f++) onsets[t][f] = Math.max(onsetsIn[t][f], diff[t][f] * scale);
        }
        // onset peaks (strict local maxima in time) above threshold, processed backwards in time
        List<int[]> peaks = new ArrayList<int[]>();
        for (int t = 1; t < n - 1; t++) {
            for (int f = 0; f < N_NOTES; f++) {
                float v = onsets[t][f];
                if (v >= onsetThresh && v > onsets[t - 1][f] && v > onsets[t + 1][f]) peaks.add(new int[]{t, f});
            }
        }
        float[][] energy = new float[n][];
        for (int t = 0; t < n; t++) energy[t] = frames[t].clone();
        List<int[]> events = new ArrayList<int[]>(); // {start, end, freq}
        for (int k = peaks.size() - 1; k >= 0; k--) {
            int start = peaks.get(k)[0], f = peaks.get(k)[1];
            if (start >= n - 1) continue;
            int i = start + 1, c = 0;
            while (i < n - 1 && c < ENERGY_TOL) {
                if (energy[i][f] < frameThresh) c++;
                else c = 0;
                i++;
            }
            i -= c;
            if (i - start <= minNoteLen) continue;
            for (int t = start; t < i; t++) {
                energy[t][f] = 0;
                if (f < N_NOTES - 1) energy[t][f + 1] = 0;
                if (f > 0) energy[t][f - 1] = 0;
            }
            events.add(new int[]{start, i, f});
        }
        // melodia trick: grow notes from the strongest remaining energy (lazy max-heap)
        PriorityQueue<long[]> heap = new PriorityQueue<long[]>(1024, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                int c = Float.compare(Float.intBitsToFloat((int) b[0]), Float.intBitsToFloat((int) a[0]));
                return c != 0 ? c : Long.compare(a[1], b[1]); // ties: first in row-major order (np.argmax)
            }
        });
        for (int t = 0; t < n; t++) {
            for (int f = 0; f < N_NOTES; f++) {
                if (energy[t][f] > frameThresh) heap.add(new long[]{Float.floatToIntBits(energy[t][f]), (long) t * N_NOTES + f});
            }
        }
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int tMid = (int) (top[1] / N_NOTES), f = (int) (top[1] % N_NOTES);
            float cur = energy[tMid][f];
            if (cur != Float.intBitsToFloat((int) top[0]) || cur <= frameThresh) continue; // stale entry
            energy[tMid][f] = 0;
            int i = tMid + 1, c = 0;
            while (i < n - 1 && c < ENERGY_TOL) {
                if (energy[i][f] < frameThresh) c++;
                else c = 0;
                energy[i][f] = 0;
                if (f < N_NOTES - 1) energy[i][f + 1] = 0;
                if (f > 0) energy[i][f - 1] = 0;
                i++;
            }
            int iEnd = i - 1 - c;
            i = tMid - 1;
            c = 0;
            while (i > 0 && c < ENERGY_TOL) {
                if (energy[i][f] < frameThresh) c++;
                else c = 0;
                energy[i][f] = 0;
                if (f < N_NOTES - 1) energy[i][f + 1] = 0;
                if (f > 0) energy[i][f - 1] = 0;
                i--;
            }
            int iStart = i + 1 + c;
            if (iEnd - iStart <= minNoteLen) continue;
            events.add(new int[]{iStart, iEnd, f});
        }
        for (int[] e : events) {
            double sum = 0;
            for (int t = e[0]; t < e[1]; t++) sum += frames[t][e[2]];
            double amp = e[1] > e[0] ? sum / (e[1] - e[0]) : 0;
            int vel = (int) Math.round(127 * amp);
            out.add(new Note(e[2] + 21, frameTime(e[0]), frameTime(e[1]), Math.max(1, Math.min(127, vel))));
        }
        Collections.sort(out, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                int c = Double.compare(a.onset, b.onset);
                return c != 0 ? c : Integer.compare(a.pitch, b.pitch);
            }
        });
        return out;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
