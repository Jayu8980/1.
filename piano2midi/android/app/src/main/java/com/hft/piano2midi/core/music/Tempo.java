package com.hft.piano2midi.core.music;

import com.hft.piano2midi.core.Note;

import java.util.List;

/** Beat grid: tempo, time signature (x/4) and the time of the first downbeat. */
public final class Tempo {
    public final double bpm;
    public final int beatsPerBar;
    /** Time (s) of the first downbeat; at or before the first note. */
    public final double origin;

    public Tempo(double bpm, int beatsPerBar, double origin) {
        this.bpm = bpm;
        this.beatsPerBar = beatsPerBar;
        this.origin = origin;
    }

    public double beatSec() {
        return 60.0 / bpm;
    }

    public double barSec() {
        return beatSec() * beatsPerBar;
    }

    // ------------------------------------------------------------ estimation

    private static final int FPS = 100;

    /**
     * Onset strength at 100 Hz. With lowOnly, only the lowest note of each chord counts,
     * weighted strongly by how low it is (bass notes mark downbeats far more than the
     * "pah" chords of an oom-pah accompaniment).
     */
    private static float[] onsetEnvelope(List<Note> notes, boolean lowOnly) {
        double last = 0;
        for (Note n : notes) last = Math.max(last, n.onset);
        int len = (int) (last * FPS) + 8;
        float[] env = new float[len];
        for (int idx = 0; idx < notes.size(); idx++) {
            Note n = notes.get(idx);
            float w = 0.3f + n.velocity / 127f;
            if (lowOnly) {
                boolean lowest = true;
                for (Note o : notes) {
                    if (o != n && Math.abs(o.onset - n.onset) < 0.05 && o.pitch < n.pitch) {
                        lowest = false;
                        break;
                    }
                }
                if (!lowest) continue;
                w *= (float) Math.exp((60 - n.pitch) / 10.0);
            }
            int i = (int) Math.round(Math.max(0, n.onset) * FPS);
            for (int d = -2; d <= 2; d++) {
                int k = i + d;
                if (k >= 0 && k < len) env[k] += w * (float) Math.exp(-0.5 * d * d);
            }
        }
        return env;
    }

    private static double sampleEnv(float[] env, double pos) {
        int i = (int) Math.floor(pos);
        if (i < 0 || i + 1 >= env.length) return 0;
        double f = pos - i;
        return env[i] * (1 - f) + env[i + 1] * f;
    }

    /** Estimates the tempo (40-220 BPM, biased toward ~110 BPM) and the beat phase. */
    public static Tempo estimate(List<Note> notes, int beatsPerBar) {
        if (notes.isEmpty()) return new Tempo(120, beatsPerBar, 0);
        float[] env = onsetEnvelope(notes, false);
        double best = -1, bestLag = 50;
        int minLag = FPS * 60 / 220, maxLag = FPS * 60 / 40;
        double[] ac = new double[maxLag + 2];
        for (int lag = minLag; lag <= maxLag + 1 && lag < env.length; lag++) {
            double s = 0;
            for (int i = 0; i + lag < env.length; i++) s += env[i] * env[i + lag];
            ac[lag] = s;
        }
        for (int lag = minLag; lag <= maxLag && lag < env.length; lag++) {
            // combine with the double period, so beats are preferred over sub-beats
            double s = ac[lag] + (2 * lag <= maxLag + 1 ? 0.5 * ac[Math.min(2 * lag, ac.length - 1)] : 0);
            double oct = Math.log(lag / (FPS * 60 / 110.0)) / Math.log(2);
            double w = Math.exp(-0.5 * oct * oct / (0.8 * 0.8));
            if (s * w > best) {
                best = s * w;
                bestLag = lag;
            }
        }
        // parabolic refinement
        int l = (int) bestLag;
        if (l > minLag && l + 1 < ac.length) {
            double a = ac[l - 1], b = ac[l], c = ac[l + 1];
            double den = a - 2 * b + c;
            if (den < 0) bestLag = l + 0.5 * (a - c) / den;
        }
        double bpm = 60.0 * FPS / bestLag;
        bpm = Math.round(bpm);
        return withBpm(notes, bpm, beatsPerBar);
    }

    /** Finds the beat phase and downbeat for a given tempo. */
    public static Tempo withBpm(List<Note> notes, double bpm, int beatsPerBar) {
        if (notes.isEmpty()) return new Tempo(bpm, beatsPerBar, 0);
        float[] env = onsetEnvelope(notes, false);
        float[] low = onsetEnvelope(notes, true);
        double period = 60.0 * FPS / bpm;
        double bestPhase = 0, best = -1;
        for (double ph = 0; ph < period; ph += 0.5) {
            double s = 0;
            for (double p = ph; p < env.length; p += period) s += sampleEnv(env, p);
            if (s > best) {
                best = s;
                bestPhase = ph;
            }
        }
        // downbeat: the bar phase whose beats carry the most bass/low onsets and accents
        double first = Double.MAX_VALUE;
        for (Note n : notes) first = Math.min(first, n.onset);
        double[] score = new double[beatsPerBar];
        double maxScore = 0;
        for (int k = 0; k < beatsPerBar; k++) {
            for (double p = bestPhase + k * period; p < env.length; p += period * beatsPerBar) {
                score[k] += sampleEnv(low, p) + 0.25 * sampleEnv(env, p);
            }
            maxScore = Math.max(maxScore, score[k]);
        }
        // near-ties (e.g. an octave bass on beats 1 and 3): prefer a bar starting at the first note
        double barFrames = period * beatsPerBar;
        double bestBar = -1;
        int bestK = 0;
        for (int k = 0; k < beatsPerBar; k++) {
            double d = (first * FPS - (bestPhase + k * period)) % barFrames;
            if (d < 0) d += barFrames;
            boolean atFirst = Math.min(d, barFrames - d) < period / 4;
            double s = score[k] + (atFirst ? 0.06 * maxScore : 0);
            if (s > bestBar) {
                bestBar = s;
                bestK = k;
            }
        }
        double origin = (bestPhase + bestK * period) / FPS;
        double bar = 60.0 / bpm * beatsPerBar;
        double half16 = 60.0 / bpm / 8; // half a 16th: notes this early still quantize to the downbeat
        while (origin > first + half16) origin -= bar;
        while (origin + bar <= first - half16) origin += bar;
        return new Tempo(bpm, beatsPerBar, origin);
    }
}
