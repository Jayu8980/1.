package com.hft.piano2midi.core.sheet;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.music.ChordRecognizer;
import com.hft.piano2midi.core.music.Key;
import com.hft.piano2midi.core.music.Part;
import com.hft.piano2midi.core.music.Tempo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Quantized, notation-ready score: notes snapped to a 16th-note grid, split into
 * measures, one chordal voice per staff, durations broken into notatable values
 * with ties, accidentals, beam groups and chord symbols.
 */
public final class Score {

    public static final int TREBLE = 0, BASS = 1;
    /** Units (16th notes) per beat. */
    public static final int UPB = 4;

    public static final class Head {
        public final int pitch, step, alter, part;
        public boolean accidental, tieStart, tieEnd;

        Head(int pitch, int step, int alter, int part) {
            this.pitch = pitch;
            this.step = step;
            this.alter = alter;
            this.part = part;
        }
    }

    public static final class Event {
        public final int start, dur, base, dots;
        public final boolean rest;
        public boolean measureRest;
        public final List<Head> heads = new ArrayList<Head>();
        public int beam = -1;

        Event(int start, int dur, boolean rest) {
            this.start = start;
            this.dur = dur;
            this.rest = rest;
            switch (dur) {
                case 12: base = 8; dots = 1; break;
                case 6: base = 4; dots = 1; break;
                case 3: base = 2; dots = 1; break;
                default: base = dur; dots = 0;
            }
        }
    }

    public static final class Measure {
        public final List<List<Event>> staves = new ArrayList<List<Event>>();
    }

    public static final class ChordSymbol {
        public final int measure, unit;
        public final String name;

        ChordSymbol(int measure, int unit, String name) {
            this.measure = measure;
            this.unit = unit;
            this.name = name;
        }
    }

    public final String title;
    public final Tempo tempo;
    public final Key key;
    public final int[] clefs;
    public final int unitsPerBar;
    public final List<Measure> measures = new ArrayList<Measure>();
    public final List<ChordSymbol> chords = new ArrayList<ChordSymbol>();

    private Score(String title, Tempo tempo, Key key, int[] clefs) {
        this.title = title;
        this.tempo = tempo;
        this.key = key;
        this.clefs = clefs;
        this.unitsPerBar = tempo.beatsPerBar * UPB;
    }

    public int staffCount() {
        return clefs.length;
    }

    public double unitSec() {
        return tempo.beatSec() / UPB;
    }

    // ------------------------------------------------------------------ build

    private static final class Seg {
        final int s, e;
        final List<Head> heads; // null = rest

        Seg(int s, int e, List<Head> heads) {
            this.s = s;
            this.e = e;
            this.heads = heads;
        }
    }

    /**
     * @param grandStaff true: treble + bass staff (piano); false: one staff whose clef follows the range
     */
    public static Score build(List<Note> notes, Tempo tempo, Key key, List<ChordRecognizer.Chord> chordList,
                              boolean grandStaff, String title) {
        int[] clefs;
        if (grandStaff) {
            clefs = new int[]{TREBLE, BASS};
        } else {
            List<Integer> ps = new ArrayList<Integer>();
            for (Note n : notes) ps.add(n.pitch);
            Collections.sort(ps);
            int median = ps.isEmpty() ? 60 : ps.get(ps.size() / 2);
            clefs = new int[]{median >= 57 ? TREBLE : BASS};
        }
        Score sc = new Score(title, tempo, key, clefs);
        double unit = sc.unitSec();
        int mu = sc.unitsPerBar;

        // quantize and assign to staves
        List<TreeMap<Integer, List<int[]>>> groups = new ArrayList<TreeMap<Integer, List<int[]>>>();
        for (int i = 0; i < clefs.length; i++) groups.add(new TreeMap<Integer, List<int[]>>());
        int total = 0;
        for (Note n : notes) {
            int s = Math.max(0, (int) Math.round((n.onset - tempo.origin) / unit));
            int e = (int) Math.round((n.offset - tempo.origin) / unit);
            if (e <= s) e = s + 1;
            int staff = 0;
            if (grandStaff) {
                if (n.part == Part.MELODY) staff = n.pitch < 53 ? 1 : 0;
                else if (n.part == Part.BASS) staff = n.pitch > 67 ? 0 : 1;
                else staff = n.pitch >= 60 ? 0 : 1;
            }
            List<int[]> g = groups.get(staff).get(s);
            if (g == null) {
                g = new ArrayList<int[]>();
                groups.get(staff).put(s, g);
            }
            boolean dup = false;
            for (int[] x : g) {
                if (x[0] == n.pitch) {
                    x[1] = Math.max(x[1], e);
                    dup = true;
                }
            }
            if (!dup) g.add(new int[]{n.pitch, e, n.part});
            total = Math.max(total, e);
        }
        int nMeasures = Math.max(1, (total + mu - 1) / mu);
        for (int m = 0; m < nMeasures; m++) {
            Measure meas = new Measure();
            for (int i = 0; i < clefs.length; i++) meas.staves.add(new ArrayList<Event>());
            sc.measures.add(meas);
        }

        for (int st = 0; st < clefs.length; st++) {
            // one chord per onset; it lasts until its longest note ends or the next onset
            List<Seg> segs = new ArrayList<Seg>();
            List<Integer> starts = new ArrayList<Integer>(groups.get(st).keySet());
            int cursor = 0;
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i);
                int next = i + 1 < starts.size() ? starts.get(i + 1) : Integer.MAX_VALUE;
                int maxE = 0;
                List<Head> heads = new ArrayList<Head>();
                List<int[]> g = groups.get(st).get(s);
                Collections.sort(g, new java.util.Comparator<int[]>() {
                    @Override
                    public int compare(int[] a, int[] b) {
                        return Integer.compare(a[0], b[0]);
                    }
                });
                for (int[] x : g) {
                    maxE = Math.max(maxE, x[1]);
                    Key.Spelled sp = key.spell(x[0]);
                    heads.add(new Head(x[0], sp.step, sp.alter, x[2]));
                }
                int e = Math.min(maxE, next);
                // don't leave tiny rests: extend to the next onset if the gap is a single 16th
                if (next != Integer.MAX_VALUE && next - e == 1) e = next;
                if (s > cursor) segs.add(new Seg(cursor, s, null));
                segs.add(new Seg(s, e, heads));
                cursor = e;
            }
            if (cursor < nMeasures * mu) segs.add(new Seg(cursor, nMeasures * mu, null));

            for (Seg seg : segs) {
                int pos = seg.s;
                while (pos < seg.e) {
                    int m = pos / mu;
                    int p = pos - m * mu;
                    int v = pick(p, Math.min(seg.e, (m + 1) * mu) - pos, mu, seg.heads == null);
                    Event ev = new Event(p, v, seg.heads == null);
                    if (seg.heads != null) {
                        for (Head h : seg.heads) {
                            Head c = new Head(h.pitch, h.step, h.alter, h.part);
                            c.tieEnd = pos > seg.s;
                            c.tieStart = pos + v < seg.e;
                            ev.heads.add(c);
                        }
                    }
                    sc.measures.get(m).staves.get(st).add(ev);
                    pos += v;
                }
            }
        }

        for (Measure meas : sc.measures) {
            for (int st = 0; st < clefs.length; st++) {
                List<Event> evs = meas.staves.get(st);
                boolean allRest = true;
                for (Event e : evs) allRest &= e.rest;
                if (allRest) {
                    evs.clear();
                    Event r = new Event(0, mu, true);
                    r.measureRest = true;
                    evs.add(r);
                }
                // accidentals
                Map<Integer, Integer> state = new HashMap<Integer, Integer>();
                for (Event e : evs) {
                    for (Head h : e.heads) {
                        Integer cur = state.get(h.step);
                        int c = cur != null ? cur : key.signatureAlter(Math.floorMod(h.step, 7));
                        h.accidental = !h.tieEnd && c != h.alter;
                        state.put(h.step, h.alter);
                    }
                }
                // beams: 8ths/16ths within one beat
                int id = 0;
                int i = 0;
                while (i < evs.size()) {
                    Event e = evs.get(i);
                    if (e.rest || e.base > 2) {
                        i++;
                        continue;
                    }
                    int beat = e.start / UPB;
                    int j = i + 1;
                    while (j < evs.size() && !evs.get(j).rest && evs.get(j).base <= 2
                            && evs.get(j).start / UPB == beat && (evs.get(j).start + evs.get(j).dur - 1) / UPB == beat) {
                        j++;
                    }
                    if (j - i >= 2 && (e.start + e.dur - 1) / UPB == beat) {
                        for (int k = i; k < j; k++) evs.get(k).beam = id;
                        id++;
                    }
                    i = Math.max(j, i + 1);
                }
            }
        }

        // chord symbols
        String last = null;
        if (chordList != null) {
            for (ChordRecognizer.Chord c : chordList) {
                if (c.root < 0) {
                    last = null;
                    continue;
                }
                if (c.name.equals(last)) continue;
                int u = Math.max(0, (int) Math.round((c.start - tempo.origin) / unit));
                int m = u / mu;
                if (m >= nMeasures) break;
                sc.chords.add(new ChordSymbol(m, u - m * mu, c.name));
                last = c.name;
            }
        }
        return sc;
    }

    /** Largest notatable duration for position p (in the bar) and remaining length r. */
    private static int pick(int p, int r, int mu, boolean rest) {
        int[] vals = rest ? new int[]{16, 8, 4, 2, 1} : new int[]{16, 12, 8, 6, 4, 3, 2, 1};
        for (int v : vals) {
            if (v > r || p + v > mu) continue;
            boolean ok;
            if (v == 16) ok = p == 0;
            else if (v == 8) ok = mu == 16 ? p % 8 == 0 : p % 4 == 0;
            else if (v >= 3) ok = p % 4 == 0;
            else if (v == 2) ok = p % 2 == 0;
            else ok = true;
            if (ok) return v;
        }
        return 1;
    }
}
