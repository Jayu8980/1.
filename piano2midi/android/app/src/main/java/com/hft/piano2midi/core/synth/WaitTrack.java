package com.hft.piano2midi.core.synth;

import com.hft.piano2midi.core.Note;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * "Wait mode" score-following: the notes the player has to play, grouped into chords
 * (wait points). Playback stops at each wait point until every pitch of the chord has been
 * pressed; presses slightly ahead of time count, so playing in time never stalls.
 *
 * Not thread-safe: used from the audio thread (presses arrive through the command queue).
 */
public final class WaitTrack {

    /** Notes whose onsets are this close form one chord. */
    private static final double CLUSTER = 0.06;
    /** The clock stops this much before the chord, so accompaniment jitter waits too. */
    private static final double LEAD = 0.03;
    /** Presses up to this long (musical seconds) before a chord count for it. */
    private static final double EARLY = 0.35;

    private final double[] time;
    private final int[][] pitches;
    private int index;
    private final double[] pressedAt = new double[128];
    private int correct, wrong;

    public WaitTrack(List<Note> notes, int partMask) {
        List<Note> sel = new ArrayList<Note>();
        for (Note n : notes) if ((partMask & (1 << n.part)) != 0) sel.add(n);
        Collections.sort(sel, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return Double.compare(a.onset, b.onset);
            }
        });
        List<Double> ts = new ArrayList<Double>();
        List<int[]> ps = new ArrayList<int[]>();
        int i = 0;
        while (i < sel.size()) {
            double t = sel.get(i).onset;
            int j = i;
            boolean[] in = new boolean[128];
            while (j < sel.size() && sel.get(j).onset - t <= CLUSTER) in[sel.get(j++).pitch] = true;
            int c = 0;
            for (boolean b : in) if (b) c++;
            int[] p = new int[c];
            c = 0;
            for (int k = 0; k < 128; k++) if (in[k]) p[c++] = k;
            ts.add(t);
            ps.add(p);
            i = j;
        }
        time = new double[ts.size()];
        pitches = new int[ts.size()][];
        for (int k = 0; k < time.length; k++) {
            time[k] = ts.get(k);
            pitches[k] = ps.get(k);
        }
        Arrays.fill(pressedAt, Double.NEGATIVE_INFINITY);
    }

    public int size() {
        return time.length;
    }

    /** Positions on the first chord at or after t and forgets earlier presses. */
    public void seek(double t) {
        int lo = 0, hi = time.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (time[mid] - LEAD < t - 1e-9) lo = mid + 1;
            else hi = mid;
        }
        index = lo;
        Arrays.fill(pressedAt, Double.NEGATIVE_INFINITY);
    }

    /** Musical time at which the clock has to stop next (or +inf). */
    public double nextStop() {
        return index < time.length ? time[index] - LEAD : Double.POSITIVE_INFINITY;
    }

    /** Pitches of the chord being waited for (empty when done). */
    public int[] expected() {
        return index < time.length ? pitches[index] : new int[0];
    }

    /** Accept chords of 3+ notes with one note missing (microphone input misses some partials). */
    private boolean lenient;

    public void setLenient(boolean on) {
        lenient = on;
    }

    private boolean satisfied() {
        if (index >= time.length) return true;
        double from = time[index] - LEAD - EARLY;
        int missing = 0;
        for (int p : pitches[index]) if (pressedAt[p] < from) missing++;
        int allowed = lenient && pitches[index].length >= 3 ? 1 : 0;
        return missing <= allowed;
    }

    /**
     * Called when the clock reaches {@link #nextStop()}: consumes the chord if it was already
     * played and returns true (keep going), otherwise false (wait).
     */
    public boolean arrive() {
        if (!satisfied()) return false;
        consume();
        return true;
    }

    /** While waiting: true once the chord is complete (then the clock may run again). */
    public boolean resolvedWhileWaiting() {
        if (!satisfied()) return false;
        consume();
        return true;
    }

    private void consume() {
        for (int p : pitches[index]) pressedAt[p] = Double.NEGATIVE_INFINITY;
        index++;
    }

    /** A key was pressed at musical time `now`. Counts it as correct or wrong. */
    public void press(int pitch, double now) {
        if (pitch < 0 || pitch > 127) return;
        boolean ok = false;
        // correct if it belongs to the current chord or the one right after it (playing ahead)
        for (int k = index; k < Math.min(time.length, index + 2) && !ok; k++) {
            if (time[k] - LEAD - EARLY > now && k > index) break;
            for (int p : pitches[k]) if (p == pitch) ok = true;
        }
        if (ok) {
            correct++;
            pressedAt[pitch] = now;
            return;
        }
        // a note of one of the next few chords (playing ahead): neither right nor wrong
        for (int k = index; k < Math.min(time.length, index + 5); k++) {
            for (int p : pitches[k]) if (p == pitch) return;
        }
        wrong++;
    }

    public int correct() {
        return correct;
    }

    public int wrong() {
        return wrong;
    }

    public void resetScore() {
        correct = 0;
        wrong = 0;
    }
}
