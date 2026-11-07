package com.hft.piano2midi.core.engine;

import com.hft.piano2midi.core.FeatureExtractor;
import com.hft.piano2midi.core.Note;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * MuScriptor (Kyutai x Mirelo, 2026) on ONNX Runtime: a decoder-only transformer that reads a
 * 5 s log-mel spectrogram prefix and writes MT3-style note tokens. This is a port of
 * muscriptor.transcription_model.TranscriptionModel.transcribe with its defaults: greedy
 * decoding, prelude forcing of each chunk's tie prologue, and the OpenNoteTracker /
 * decode_model_tokens state machine, followed by validate_notes and trim_overlapping_notes.
 */
public final class Muscriptor implements AutoCloseable {

    public static final int SAMPLE_RATE = 16000;
    private static final int SEG = 80000, HOP = 160, NFFT = 2048, NMEL = 512, FRAMES = SEG / HOP + 1;
    private static final int CARD = 1395, VALID = 1393, EOS = 1, MAX_GEN = 2000;
    private static final int LAYERS = 24, HEADS = 16, HEAD_DIM = 64;
    // vocabulary layout (tokenizer.notes.build_event_vocab, max_shift_steps = 1001)
    private static final int SHIFT0 = 3, PITCH0 = SHIFT0 + 1001, VEL0 = PITCH0 + 128, TIE = VEL0 + 2,
            PROG0 = TIE + 1, DRUM0 = PROG0 + 130, END = DRUM0 + 128;
    private static final double MIN_DUR = 0.01;
    /** The model has on/off velocities only. */
    public static final int VELOCITY = 96;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final float[] window = new float[NFFT];
    private final int[] melLo = new int[NMEL];
    private final float[][] melW = new float[NMEL][];

    public interface Progress {
        void onChunk(int done, int total);

        boolean isCancelled();
    }

    public Muscriptor(String modelPath, String frontendPath, int threads) throws OrtException, IOException {
        loadFrontend(frontendPath);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        session = env.createSession(modelPath, o);
    }

    /** Window [2048] then dense mel filterbank [1025][512], float32 little-endian. */
    private void loadFrontend(String path) throws IOException {
        int nb = NFFT / 2 + 1;
        byte[] raw = new byte[(NFFT + nb * NMEL) * 4];
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)));
        try {
            in.readFully(raw);
        } finally {
            in.close();
        }
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        fb.get(window);
        float[] dense = new float[nb * NMEL];
        fb.get(dense);
        for (int j = 0; j < NMEL; j++) {
            int a = -1, b = -1;
            for (int k = 0; k < nb; k++) {
                if (dense[k * NMEL + j] != 0f) {
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
            for (int k = a; k <= b; k++) melW[j][k - a] = dense[k * NMEL + j];
        }
    }

    /** Notes are final (greedy decoding has no thresholds). */
    public static final class Output implements EngineOutput {
        private final List<Note> notes;
        public final int[] programs;
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

    /** torch.stft(center=True, reflect pad) magnitude -> mel -> log(x + 1e-6), [501][512] row-major. */
    float[] logmel(float[] x, int from) {
        int len = Math.max(0, Math.min(SEG, x.length - from)); // the chunk is zero-padded to 5 s
        float[] out = new float[FRAMES * NMEL];
        double[] re = new double[NFFT], im = new double[NFFT], mag = new double[NFFT / 2 + 1];
        FeatureExtractor.Fft fft = new FeatureExtractor.Fft(NFFT);
        for (int t = 0; t < FRAMES; t++) {
            for (int i = 0; i < NFFT; i++) {
                int k = t * HOP + i - NFFT / 2;
                if (k < 0) k = -k;                       // reflect
                else if (k >= SEG) k = 2 * (SEG - 1) - k;
                re[i] = (k < len ? x[from + k] : 0f) * (double) window[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int k = 0; k <= NFFT / 2; k++) mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            for (int j = 0; j < NMEL; j++) {
                float[] w = melW[j];
                int lo = melLo[j];
                double acc = 0;
                for (int k = 0; k < w.length; k++) acc += w[k] * mag[lo + k];
                out[t * NMEL + j] = (float) Math.log(acc + 1e-6);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ transcription

    public Output transcribe(float[] audio16k, Progress progress) throws Exception {
        int chunks = Math.max(1, (audio16k.length + SEG - 1) / SEG);
        Tracker tr = new Tracker();
        for (int c = 0; c < chunks; c++) {
            if (progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
            tr.boundary(c * 5.0, c + 1 < chunks ? (c + 1) * 5.0 : -1);
            List<Integer> prompt = c > 0 ? tieSection(tr.openKeys()) : Collections.<Integer>emptyList();
            for (int t : prompt) tr.feed(t);
            decodeChunk(logmel(audio16k, c * SEG), prompt, tr, progress);
            if (progress != null) progress.onChunk(c + 1, chunks);
        }
        tr.finish();
        return tr.output(audio16k.length / (double) SAMPLE_RATE);
    }

    /** MT3Tokenizer.tie_section_token_ids: sorted (program, pitch), program once per run, then tie. */
    static List<Integer> tieSection(List<int[]> keys) {
        List<Integer> out = new ArrayList<Integer>();
        int prog = -1;
        for (int[] k : keys) {
            if (k[0] != prog) {
                out.add(PROG0 + k[0]);
                prog = k[0];
            }
            out.add(PITCH0 + k[1]);
        }
        out.add(TIE);
        return out;
    }

    private void decodeChunk(float[] mel, List<Integer> prompt, Tracker tr, Progress progress) throws Exception {
        long[] first = new long[3 + prompt.size()];
        first[0] = CARD + 1; // null dataset_name row
        first[1] = CARD + 2; // null instrument_group row
        first[2] = CARD;     // initial token
        for (int i = 0; i < prompt.size(); i++) first[3 + i] = prompt.get(i);
        long[] empty = {LAYERS, 1, 0, HEADS, HEAD_DIM};
        OnnxTensor pastK = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), empty);
        OnnxTensor pastV = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), empty);
        OnnxTensor melT = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), new long[]{1, FRAMES, NMEL});
        OnnxTensor noMel = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{1, 0, NMEL});
        OnnxTensor tokT = OnnxTensor.createTensor(env, LongBuffer.wrap(first), new long[]{1, first.length});
        OrtSession.Result prev = null;
        float[] logits = null;
        long[] one = new long[1];
        try {
            Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
            for (int step = 0; step < MAX_GEN - prompt.size(); step++) {
                if ((step & 31) == 0 && progress != null && progress.isCancelled()) throw new InterruptedException("cancelled");
                feed.put("mel", step == 0 ? melT : noMel);
                feed.put("tokens", tokT);
                feed.put("past_k", pastK);
                feed.put("past_v", pastV);
                OrtSession.Result r = session.run(feed);
                tokT.close();
                tokT = null;
                if (prev != null) prev.close(); // the previous cache is no longer needed
                else {
                    pastK.close();
                    pastV.close();
                    melT.close();
                }
                prev = r;
                FloatBuffer lb = ((OnnxTensor) r.get("logits").get()).getFloatBuffer();
                if (logits == null) logits = new float[lb.remaining()];
                lb.get(logits);
                int best = 0;
                for (int i = 1; i < VALID; i++) if (logits[i] > logits[best]) best = i;
                if (best == EOS) break;
                tr.feed(best);
                pastK = (OnnxTensor) r.get("new_k").get();
                pastV = (OnnxTensor) r.get("new_v").get();
                one[0] = best;
                tokT = OnnxTensor.createTensor(env, LongBuffer.wrap(one), new long[]{1, 1});
            }
        } finally {
            if (tokT != null) tokT.close();
            if (prev != null) prev.close();
            noMel.close();
        }
    }

    // ------------------------------------------------------------ token stream -> notes

    /** Port of events.OpenNoteTracker + decode_model_tokens, collecting finished notes. */
    static final class Tracker {
        private final LinkedHashMap<Long, Double> open = new LinkedHashMap<Long, Double>();
        private final List<double[]> notes = new ArrayList<double[]>(); // onset, offset, pitch, program, drum
        private double seek, nextSeek = -1;
        private int startTick, tick, program = -1, velocity = -1;
        private boolean prologue = true, skipRest, started;
        private final Set<Long> tieSet = new HashSet<Long>();

        private static long key(int program, int pitch) {
            return ((long) program << 8) | pitch;
        }

        void boundary(double seekTime, double next) {
            if (started && prologue) endAll(seek);
            seek = seekTime;
            nextSeek = next;
            startTick = (int) Math.round(seekTime * 100);
            tick = startTick;
            program = -1;
            velocity = -1;
            prologue = true;
            skipRest = false;
            tieSet.clear();
            started = true;
        }

        void feed(int tok) {
            if (tok < 0 || tok >= END) return;
            if (prologue) {
                if (tok == TIE) {
                    prologue = false;
                    velocity = -1;
                    for (Long k : new ArrayList<Long>(open.keySet())) if (!tieSet.contains(k)) end(k, seek);
                } else if (tok >= SHIFT0 && tok < PITCH0) {
                    prologue = false;
                    skipRest = true;
                    endAll(seek);
                } else if (tok >= PROG0 && tok < DRUM0) {
                    program = tok - PROG0;
                } else if (tok >= PITCH0 && tok < VEL0 && program >= 0) {
                    tieSet.add(key(program, tok - PITCH0));
                }
                return;
            }
            if (skipRest) return;
            if (tok >= SHIFT0 && tok < PITCH0) {
                int v = tok - SHIFT0;
                if (v > 0) tick = startTick + v;
            } else if (tok >= PROG0 && tok < DRUM0) {
                program = tok - PROG0;
            } else if (tok >= VEL0 && tok < TIE) {
                velocity = tok - VEL0;
            } else if (tok >= DRUM0) {
                double time = tick / 100.0;
                if (nextSeek < 0 || time < nextSeek) notes.add(new double[]{time, time + MIN_DUR, tok - DRUM0, 128, 1});
            } else if (tok >= PITCH0 && tok < VEL0) {
                if (program < 0 || velocity < 0) return;
                double time = tick / 100.0;
                if (nextSeek >= 0 && time >= nextSeek) return;
                long k = key(program, tok - PITCH0);
                if (open.containsKey(k)) end(k, time);
                if (velocity > 0) open.put(k, time);
            }
        }

        void finish() {
            if (started && prologue) {
                endAll(seek);
                return;
            }
            for (Map.Entry<Long, Double> e : new ArrayList<Map.Entry<Long, Double>>(open.entrySet())) {
                end(e.getKey(), e.getValue() + MIN_DUR);
            }
        }

        private void end(long k, double time) {
            Double on = open.remove(k);
            if (on != null) notes.add(new double[]{on, time, (int) (k & 0xFF), (int) (k >> 8), 0});
        }

        private void endAll(double time) {
            for (Long k : new ArrayList<Long>(open.keySet())) end(k, time);
        }

        /** Sorted (program, pitch) pairs currently held open. */
        List<int[]> openKeys() {
            List<int[]> ks = new ArrayList<int[]>();
            for (Long k : open.keySet()) ks.add(new int[]{(int) (k >> 8), (int) (k & 0xFF)});
            Collections.sort(ks, new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
                }
            });
            return ks;
        }

        /** validate_notes + trim_overlapping_notes, then pitched notes in the piano range. */
        Output output(double seconds) {
            Map<Long, List<double[]>> channels = new HashMap<Long, List<double[]>>();
            for (double[] n : notes) {
                if (n[0] > n[1]) n[1] = Math.max(n[1], n[0] + MIN_DUR);
                else if (n[4] == 0 && n[1] - n[0] < 0.01) n[1] = n[0] + MIN_DUR;
                long ch = ((long) n[3] << 9) | ((long) n[2] << 1) | (long) n[4];
                List<double[]> l = channels.get(ch);
                if (l == null) channels.put(ch, l = new ArrayList<double[]>());
                l.add(n);
            }
            Comparator<double[]> byOnset = new Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    return Double.compare(a[0], b[0]);
                }
            };
            List<double[]> kept = new ArrayList<double[]>();
            for (List<double[]> l : channels.values()) {
                Collections.sort(l, byOnset);
                for (int i = 1; i < l.size(); i++) if (l.get(i - 1)[1] > l.get(i)[0]) l.get(i - 1)[1] = l.get(i)[0];
                for (double[] n : l) if (n[0] < n[1] && n[4] == 0 && n[2] >= 21 && n[2] <= 108) kept.add(n);
            }
            Collections.sort(kept, new Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    int c = Double.compare(a[0], b[0]);
                    return c != 0 ? c : Double.compare(a[2], b[2]);
                }
            });
            List<Note> res = new ArrayList<Note>(kept.size());
            int[] progs = new int[kept.size()];
            for (int i = 0; i < kept.size(); i++) {
                double[] n = kept.get(i);
                progs[i] = (int) n[3];
                res.add(new Note((int) n[2], n[0], n[1], VELOCITY));
            }
            return new Output(res, progs, seconds);
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
