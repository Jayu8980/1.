package com.hft.piano2midi.core.music;

import com.hft.piano2midi.core.Note;

import java.util.ArrayList;
import java.util.List;

/** Template-matching chord recognition on a beat grid (one label per beat, then merged). */
public final class ChordRecognizer {

    public static final class Chord {
        public final double start;
        public double end;
        public final int root; // pitch class, -1 = no chord
        public final String name;

        Chord(double start, double end, int root, String name) {
            this.start = start;
            this.end = end;
            this.root = root;
            this.name = name;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.2f-%.2f %s", start, end, name);
        }
    }

    private static final int[][] TEMPLATES = {
            {0, 4, 7}, {0, 3, 7}, {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 3, 6}, {0, 5, 7}, {0, 2, 7}};
    private static final String[] SUFFIX = {"", "m", "7", "maj7", "m7", "dim", "sus4", "sus2"};
    private static final double[] PRIOR = {0.0, 0.0, -0.06, -0.08, -0.06, -0.10, -0.12, -0.14};

    private ChordRecognizer() {}

    private static final class Fit {
        int root = -1, type = 0;
        double score = -1e9;
    }

    /** Best chord template for the notes sounding in [t0, t1). */
    private static Fit fit(List<Note> notes, double t0, double t1, double beat, int prevRoot, int prevType) {
        double[] chroma = new double[12];
        int lowest = 200;
        double total = 0;
        for (Note n : notes) {
            double ov = Math.min(t1, n.offset) - Math.max(t0, n.onset);
            if (ov <= 0) continue;
            // notes struck inside the span count more than ones ringing over from before
            double w = ov / beat * (n.onset >= t0 - 0.03 ? 1.0 : 0.6) * (0.5 + n.velocity / 254.0);
            if (n.part == Part.BASS) w *= 1.3;
            chroma[n.pitch % 12] += w;
            total += w;
            // only notes that really sound in this span can act as the bass
            if (ov >= Math.min(0.5 * beat, 0.4 * (t1 - t0)) && n.pitch < lowest) lowest = n.pitch;
        }
        Fit f = new Fit();
        if (total < 0.15) return f;
        for (int r = 0; r < 12; r++) {
            for (int t = 0; t < TEMPLATES.length; t++) {
                boolean[] in = new boolean[12];
                for (int iv : TEMPLATES[t]) in[(r + iv) % 12] = true;
                double s = 0;
                for (int pc = 0; pc < 12; pc++) s += in[pc] ? chroma[pc] : -0.7 * chroma[pc];
                s = s / total + PRIOR[t];
                if (chroma[r] < 0.05 * total) s -= 0.3;
                if (lowest < 200 && lowest % 12 == r) s += 0.08;
                if (r == prevRoot && t == prevType) s += 0.05; // stickiness
                if (s > f.score) {
                    f.score = s;
                    f.root = r;
                    f.type = t;
                }
            }
        }
        if (f.score < 0.2) f.root = -1;
        return f;
    }

    /**
     * One chord per bar; in 4/4 a bar is split in two halves when both halves clearly fit
     * different chords better than a single chord fits the whole bar.
     */
    public static List<Chord> recognize(List<Note> notes, Tempo tempo, Key key) {
        List<Chord> out = new ArrayList<Chord>();
        if (notes.isEmpty()) return out;
        double end = 0;
        for (Note n : notes) end = Math.max(end, n.offset);
        double beat = tempo.beatSec(), bar = tempo.barSec();
        int bars = (int) Math.ceil((end - tempo.origin) / bar);
        int prevRoot = -2, prevType = -1;
        for (int b = 0; b < bars; b++) {
            double t0 = tempo.origin + b * bar, t1 = t0 + bar;
            Fit whole = fit(notes, t0, t1, beat, prevRoot, prevType);
            Fit[] segs = {whole};
            double[] bounds = {t0, t1};
            if (tempo.beatsPerBar == 4) {
                double tm = t0 + bar / 2;
                Fit h1 = fit(notes, t0, tm, beat, prevRoot, prevType);
                Fit h2 = fit(notes, tm, t1, beat, h1.root, h1.type);
                boolean differ = h1.root != h2.root || h1.type != h2.type;
                if (differ && h1.root >= 0 && h2.root >= 0 && (h1.score + h2.score) / 2 > whole.score + 0.12) {
                    segs = new Fit[]{h1, h2};
                    bounds = new double[]{t0, tm, t1};
                }
            }
            for (int k = 0; k < segs.length; k++) {
                Fit f = segs[k];
                if (f.root == prevRoot && (f.root < 0 || f.type == prevType) && !out.isEmpty()) {
                    out.get(out.size() - 1).end = bounds[k + 1];
                } else {
                    out.add(new Chord(bounds[k], bounds[k + 1], f.root,
                            f.root < 0 ? "N.C." : key.pcName(f.root) + SUFFIX[f.type]));
                }
                prevRoot = f.root;
                prevType = f.type;
            }
        }
        return out;
    }
}
