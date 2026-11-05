package com.hft.piano2midi.core.music;

import com.hft.piano2midi.core.Note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * "One note at a time": turns a polyphonic note list into a single monophonic line.
 * Notes struck together (within 50 ms) are reduced to one according to the rule, each note
 * is cut where the next one starts (no overlaps), and fragments shorter than 30 ms are dropped.
 */
public final class Monophonic {

    public static final int HIGHEST = 0, LOWEST = 1, LOUDEST = 2;

    private static final double CLUSTER = 0.05, MIN_LEN = 0.03, GAP = 0.002;

    private Monophonic() {}

    public static List<Note> apply(List<Note> notes, int rule) {
        List<Note> sorted = new ArrayList<Note>(notes);
        Collections.sort(sorted, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                int c = Double.compare(a.onset, b.onset);
                return c != 0 ? c : Integer.compare(a.pitch, b.pitch);
            }
        });
        // 1. one note per onset cluster
        List<Note> picked = new ArrayList<Note>();
        int i = 0;
        while (i < sorted.size()) {
            double t = sorted.get(i).onset;
            Note best = sorted.get(i);
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).onset - t <= CLUSTER) {
                Note n = sorted.get(j);
                if (rule == HIGHEST ? n.pitch > best.pitch
                        : rule == LOWEST ? n.pitch < best.pitch
                        : n.velocity > best.velocity || (n.velocity == best.velocity && n.pitch > best.pitch)) {
                    best = n;
                }
                j++;
            }
            picked.add(best);
            i = j;
        }
        // 2. skyline across clusters: a note starting under a still-sounding "better" note is
        //    accompaniment and is skipped (for HIGHEST / LOWEST)
        List<Note> line = new ArrayList<Note>();
        for (Note n : picked) {
            if (!line.isEmpty() && rule != LOUDEST) {
                Note prev = line.get(line.size() - 1);
                boolean prevSounding = prev.offset - n.onset > 0.12;
                boolean worse = rule == HIGHEST ? n.pitch < prev.pitch : n.pitch > prev.pitch;
                if (prevSounding && worse) continue;
            }
            line.add(n);
        }
        // 3. no overlaps: cut each note where the next one starts; drop tiny fragments
        List<Note> out = new ArrayList<Note>();
        for (int k = 0; k < line.size(); k++) {
            Note n = line.get(k);
            double end = n.offset;
            if (k + 1 < line.size()) end = Math.min(end, line.get(k + 1).onset - GAP);
            if (end - n.onset < MIN_LEN) continue;
            Note m = new Note(n.pitch, n.onset, end, n.velocity);
            m.part = n.part;
            out.add(m);
        }
        return out;
    }

    /** True if no two notes overlap in time. */
    public static boolean isMonophonic(List<Note> notes) {
        List<Note> s = new ArrayList<Note>(notes);
        Collections.sort(s, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return Double.compare(a.onset, b.onset);
            }
        });
        for (int k = 1; k < s.size(); k++) if (s.get(k).onset < s.get(k - 1).offset - 1e-9) return false;
        return true;
    }
}
