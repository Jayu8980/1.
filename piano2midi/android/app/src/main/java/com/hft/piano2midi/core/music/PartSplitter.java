package com.hft.piano2midi.core.music;

import com.hft.piano2midi.core.Note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a polyphonic piano transcription into melody, chord (accompaniment) and bass parts.
 *
 * Notes are grouped into onset clusters (notes struck together). The top note of a cluster
 * becomes melody and the bottom note becomes bass ("skyline" from above and below), unless
 * an earlier melody/bass note is still clearly sounding beyond it, which marks the new note
 * as accompaniment played underneath (or above) a held line. Everything else is chords.
 */
public final class PartSplitter {

    /** Onsets closer than this are treated as one chord. */
    private static final double CLUSTER_SEC = 0.05;
    /** A held note "still sounds" if it continues at least this long after the new onset. */
    private static final double HOLD_SEC = 0.12;

    private PartSplitter() {}

    public static void split(List<Note> notes, int melodyFloor, int bassCeiling) {
        List<Note> sorted = new ArrayList<Note>(notes);
        Collections.sort(sorted, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                int c = Double.compare(a.onset, b.onset);
                return c != 0 ? c : Integer.compare(a.pitch, b.pitch);
            }
        });
        Note melody = null, bass = null;
        int i = 0;
        while (i < sorted.size()) {
            double t = sorted.get(i).onset;
            int j = i;
            while (j < sorted.size() && sorted.get(j).onset - t <= CLUSTER_SEC) j++;
            List<Note> cl = sorted.subList(i, j);
            for (Note n : cl) n.part = Part.CHORDS;
            Note top = cl.get(0), bot = cl.get(0);
            for (Note n : cl) {
                if (n.pitch > top.pitch) top = n;
                if (n.pitch < bot.pitch) bot = n;
            }
            boolean single = cl.size() == 1;
            boolean topOk = top.pitch >= melodyFloor && !(melody != null && melody.offset - t > HOLD_SEC
                    && top.pitch < melody.pitch);
            boolean botOk = bot.pitch <= bassCeiling && !(bass != null && bass.offset - t > HOLD_SEC
                    && bot.pitch > bass.pitch);
            if (single) {
                // a lone note goes to whichever line it fits best
                if (top.pitch >= 60 ? topOk : !botOk && topOk) {
                    top.part = Part.MELODY;
                    melody = top;
                } else if (botOk) {
                    bot.part = Part.BASS;
                    bass = bot;
                }
            } else {
                if (topOk) {
                    top.part = Part.MELODY;
                    melody = top;
                }
                if (botOk && bot != top) {
                    bot.part = Part.BASS;
                    bass = bot;
                }
            }
            i = j;
        }
    }

    public static void split(List<Note> notes) {
        split(notes, 55, 59);
    }

    public static List<Note> filter(List<Note> notes, int partMask) {
        List<Note> out = new ArrayList<Note>();
        for (Note n : notes) if ((partMask & (1 << n.part)) != 0) out.add(n);
        return out;
    }
}
