package com.hft.piano2midi.core.synth;

import com.hft.piano2midi.core.Note;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Time-ordered note-on/off events for the synth; the channel of each note is its part. */
public final class Sequence {

    private final double[] time;
    private final int[] packed; // on(1) | channel<<1 | key<<5 | vel<<12
    public final double duration;
    private int cursor;

    public Sequence(List<Note> notes, int partMask) {
        List<double[]> evs = new ArrayList<double[]>();
        double dur = 0;
        for (Note n : notes) {
            if ((partMask & (1 << n.part)) == 0) continue;
            double on = Math.max(0, n.onset), off = Math.max(on + 0.03, n.offset);
            evs.add(new double[]{on, 1, n.part, n.pitch, Math.max(1, Math.min(127, n.velocity))});
            evs.add(new double[]{off, 0, n.part, n.pitch, 0});
            dur = Math.max(dur, off);
        }
        double[][] arr = evs.toArray(new double[0][]);
        Arrays.sort(arr, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                int c = Double.compare(a[0], b[0]);
                return c != 0 ? c : Double.compare(a[1], b[1]); // offs first
            }
        });
        time = new double[arr.length];
        packed = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            time[i] = arr[i][0];
            packed[i] = (int) arr[i][1] | ((int) arr[i][2] << 1) | ((int) arr[i][3] << 5) | ((int) arr[i][4] << 12);
        }
        duration = dur;
    }

    /** Positions the cursor at the first event at or after t. */
    public void seek(double t) {
        int lo = 0, hi = time.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (time[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        cursor = lo;
    }

    /** Sends all events with time < t to the synth. */
    public void dispatchUntil(Synth synth, double t) {
        while (cursor < time.length && time[cursor] < t) {
            int p = packed[cursor++];
            int ch = (p >> 1) & 0xF, key = (p >> 5) & 0x7F, vel = (p >> 12) & 0x7F;
            if ((p & 1) != 0) synth.noteOn(ch, key, vel);
            else synth.noteOff(ch, key);
        }
    }

    public boolean finished() {
        return cursor >= time.length;
    }
}
