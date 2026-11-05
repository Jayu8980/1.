package com.hft.piano2midi.core.sheet;

import com.hft.piano2midi.core.music.Key;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lays a {@link Score} out on pages (A4 by default, in points) and draws it with SMuFL
 * glyphs (Bravura). The same code draws the on-screen sheet and the PDF pages.
 */
public final class SheetLayout {

    // SMuFL code points
    private static final char G_CLEF = '\uE050', F_CLEF = '\uE062', BRACE = '\uE000';
    private static final char HEAD_BLACK = '\uE0A4', HEAD_HALF = '\uE0A3', HEAD_WHOLE = '\uE0A2';
    private static final char FLAG8_UP = '\uE240', FLAG8_DOWN = '\uE241', FLAG16_UP = '\uE242', FLAG16_DOWN = '\uE243';
    private static final char REST_WHOLE = '\uE4E3', REST_HALF = '\uE4E4', REST_QUARTER = '\uE4E5',
            REST_8 = '\uE4E6', REST_16 = '\uE4E7';
    private static final char SHARP = '\uE262', FLAT = '\uE260', NATURAL = '\uE261', DOT = '\uE1E7';
    private static final char MET_QUARTER = '\uE1D5';
    private static final char TS_0 = '\uE080';
    private static final char OTTAVA_ALTA = '\uE511', OTTAVA_BASSA = '\uE51C';
    private static final char PAREN_L = '\uE51A', PAREN_R = '\uE51B';

    private static final float HEAD_W = 1.18f, WHOLE_W = 1.69f;
    private static final int[] BOTTOM_STEP = {30, 18}; // E4 (treble), G2 (bass)
    private static final int[] SHARP_STEPS_TREBLE = {38, 35, 39, 36, 33, 37, 34};
    private static final int[] FLAT_STEPS_TREBLE = {34, 37, 33, 36, 32, 35, 31};

    public final float pageW, pageH, sp;
    private final float margin = 40f, topMargin = 44f, titleH = 62f;
    private final Score score;
    private final int staves;

    /** Per measure horizontal layout. */
    private static final class MLayout {
        int[] units;          // slot start units, ascending, last = unitsPerBar
        float[] slotX;        // natural x of each slot start (relative, unstretched)
        boolean[] slotAcc;
        float width;          // natural width
        // placement
        int page, system;
        float x, scale;       // left x and stretch factor
    }

    private static final class System {
        int page, first, last;  // measure range [first, last]
        float top;              // y of top staff line of first staff
        float contentX;         // x where measures start
    }

    private final MLayout[] ml;
    private final List<System> systems = new ArrayList<System>();
    private int pageCount;

    public SheetLayout(Score score, float pageW, float pageH, float staffSpace) {
        this.score = score;
        this.pageW = pageW;
        this.pageH = pageH;
        this.sp = staffSpace;
        this.staves = score.staffCount();
        ml = new MLayout[score.measures.size()];
        for (int m = 0; m < ml.length; m++) ml[m] = measureLayout(m);
        breakSystems();
    }

    public SheetLayout(Score score) {
        this(score, 595f, 842f, 5.6f);
    }

    public int pageCount() {
        return pageCount;
    }

    // ----------------------------------------------------------- measurement

    private MLayout measureLayout(int m) {
        Score.Measure meas = score.measures.get(m);
        TreeSet<Integer> us = new TreeSet<Integer>();
        Map<Integer, Boolean> acc = new HashMap<Integer, Boolean>();
        boolean onlyRests = true;
        for (List<Score.Event> evs : meas.staves) {
            for (Score.Event e : evs) {
                us.add(e.start);
                if (!e.measureRest) onlyRests = false;
                for (Score.Head h : e.heads) if (h.accidental) acc.put(e.start, true);
            }
        }
        us.add(score.unitsPerBar);
        MLayout l = new MLayout();
        l.units = new int[us.size()];
        l.slotX = new float[us.size()];
        l.slotAcc = new boolean[us.size()];
        int i = 0;
        for (int u : us) l.units[i++] = u;
        float x = 1.3f * sp;
        for (i = 0; i < l.units.length; i++) {
            l.slotAcc[i] = acc.containsKey(l.units[i]);
            if (l.slotAcc[i]) x += 1.2f * sp;
            l.slotX[i] = x;
            if (i + 1 < l.units.length) {
                int d = l.units[i + 1] - l.units[i];
                x += sp * (1.9f + 1.15f * (float) (Math.log(1 + d) / Math.log(2)));
            }
        }
        l.width = Math.max(onlyRests ? 7 * sp : 0, x + 0.4f * sp);
        return l;
    }

    private float prefixWidth(boolean first) {
        float w = (staves > 1 ? 1.2f * sp : 0) + 0.8f * sp + 3.4f * sp;
        w += Math.abs(score.key.fifths) * 1.05f * sp + (score.key.fifths != 0 ? 0.8f * sp : 0);
        if (first) w += 2.8f * sp;
        return w + 0.6f * sp;
    }

    /**
     * Space (in staff spaces) a range of measures needs above the top staff and below the
     * bottom staff: high/low notes with their stems, 8va/8vb brackets and chord symbols.
     */
    private float[] extents(int first, int last) {
        int topLine = BOTTOM_STEP[score.clefs[0]] + 8;
        int botLine = BOTTOM_STEP[score.clefs[staves - 1]];
        int hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
        boolean up8 = false, down8 = false, chords = false;
        for (int m = first; m <= last; m++) {
            for (Score.Event e : score.measures.get(m).staves.get(0)) {
                for (Score.Head h : e.heads) hi = Math.max(hi, h.disp);
                if (e.ottava > 0) up8 = true;
            }
            for (Score.Event e : score.measures.get(m).staves.get(staves - 1)) {
                for (Score.Head h : e.heads) lo = Math.min(lo, h.disp);
                if (e.ottava < 0) down8 = true;
            }
        }
        for (Score.ChordSymbol c : score.chords) if (c.measure >= first && c.measure <= last) chords = true;
        float top = Math.max(0, (hi - topLine) / 2f) + (hi > topLine - 4 ? 3.0f : 0);
        if (up8) top += 2.6f;
        if (chords) top += 2.8f;
        float bot = Math.max(0, (botLine - lo) / 2f) + (lo < botLine + 4 ? 3.0f : 0);
        if (down8) bot += 2.6f;
        return new float[]{Math.max(5.5f, top + 1f), Math.max(4f, bot + 1f)};
    }

    private float staffTop(System s, int staff) {
        return s.top + staff * 11f * sp; // 4 sp staff + 7 sp gap
    }

    private void breakSystems() {
        float usable = pageW - 2 * margin;
        int m = 0, page = 0;
        float y = topMargin + titleH;
        boolean first = true;
        while (m < ml.length) {
            System s = new System();
            float prefix = prefixWidth(first);
            float avail = usable - prefix;
            float w = 0;
            int last = m;
            while (last < ml.length && (last == m || w + ml[last].width <= avail)) {
                w += ml[last].width;
                last++;
            }
            last--;
            boolean finalSystem = last == ml.length - 1;
            float scale = avail / w;
            if (finalSystem && w < 0.65f * avail) scale = 1f;
            float[] ext = extents(m, last);
            float height = (ext[0] + 4 + (staves > 1 ? 11 : 0) + ext[1] + 1.5f) * sp;
            if (y + height > pageH - margin && y > topMargin + (page == 0 ? titleH : 0) + 1) {
                page++;
                y = topMargin;
            }
            s.page = page;
            s.first = m;
            s.last = last;
            s.top = y + ext[0] * sp;
            s.contentX = margin + prefix;
            float x = s.contentX;
            for (int k = m; k <= last; k++) {
                ml[k].page = page;
                ml[k].system = systems.size();
                ml[k].x = x;
                ml[k].scale = scale;
                x += ml[k].width * scale;
            }
            systems.add(s);
            y += height;
            m = last + 1;
            first = false;
        }
        pageCount = page + 1;
    }

    private float unitX(MLayout l, int u) {
        int i = 0;
        while (i + 1 < l.units.length && l.units[i + 1] <= u) i++;
        if (i + 1 >= l.units.length) return l.x + l.slotX[i] * l.scale;
        float f = (u - l.units[i]) / (float) (l.units[i + 1] - l.units[i]);
        float x0 = l.slotX[i], x1 = l.slotX[i + 1] - (l.slotAcc[i + 1] ? 1.2f * sp : 0);
        return l.x + (x0 + (x1 - x0) * f) * l.scale;
    }

    private float slotHeadX(MLayout l, int u) {
        for (int i = 0; i < l.units.length; i++) if (l.units[i] == u) return l.x + l.slotX[i] * l.scale;
        return unitX(l, u);
    }

    public int measureCount() {
        return ml.length;
    }

    /** Box around measure m (0-based): {page, x0, x1, yTop, yBottom} or null. */
    public float[] measureBox(int m) {
        if (m < 0 || m >= ml.length) return null;
        MLayout l = ml[m];
        System s = systems.get(l.system);
        float x0 = l.x, x1 = l.x + l.width * l.scale;
        return new float[]{l.page, x0, x1, s.top - 1.5f * sp, staffTop(s, staves - 1) + 5.5f * sp};
    }

    /** Playback cursor position for a time in seconds: {page, x, yTop, yBottom} or null. */
    public float[] locate(double sec) {
        double rel = (sec - score.tempo.origin) / score.unitSec();
        if (rel < 0) rel = 0;
        int m = (int) (rel / score.unitsPerBar);
        if (m >= ml.length) return null;
        MLayout l = ml[m];
        double u = rel - m * score.unitsPerBar;
        int ui = (int) Math.floor(u);
        float xa = unitX(l, ui), xb = unitX(l, Math.min(score.unitsPerBar, ui + 1));
        float x = xa + (float) (u - ui) * (xb - xa) + 0.5f * HEAD_W * sp;
        System s = systems.get(l.system);
        return new float[]{l.page, x, s.top - 2 * sp, staffTop(s, staves - 1) + 6 * sp};
    }

    // ----------------------------------------------------------------- drawing

    private float stepY(System s, int staff, int step) {
        int clef = score.clefs[staff];
        return staffTop(s, staff) + 4 * sp - (step - BOTTOM_STEP[clef]) * sp / 2f;
    }

    private static final class TieStart {
        final float x, y;
        final boolean up;
        final int system;

        TieStart(float x, float y, boolean up, int system) {
            this.x = x;
            this.y = y;
            this.up = up;
            this.system = system;
        }
    }

    public void drawPage(Gfx g, int page) {
        g.setColor(0xFF000000);
        if (page == 0) drawHeader(g);
        if (page > 0) {
            g.text(score.title + "  ·  " + (page + 1), pageW - margin, topMargin - 18, 8, false, Gfx.RIGHT);
        }
        Map<String, TieStart> ties = new HashMap<String, TieStart>();
        for (int si = 0; si < systems.size(); si++) {
            System s = systems.get(si);
            if (s.page != page) {
                if (s.page < page) ties.clear();
                continue;
            }
            drawSystem(g, si, s, ties);
        }
        g.setColor(0xFF7A7A7A);
        g.text("Transcribed with Piano2MIDI · hFT-Transformer", margin, pageH - 22, 7, false, Gfx.LEFT);
        g.setColor(0xFF000000);
    }

    private void drawHeader(Gfx g) {
        g.text(score.title, pageW / 2, topMargin + 14, 17, true, Gfx.CENTER);
        g.text(score.key.name() + " · " + score.tempo.beatsPerBar + "/4", pageW / 2, topMargin + 32, 9, false, Gfx.CENTER);
        float y = topMargin + titleH - 10;
        g.glyph(MET_QUARTER, margin, y, 4 * 2.6f);
        g.text("= " + Math.round(score.tempo.bpm), margin + 6.5f, y, 10, false, Gfx.LEFT);
    }

    private void drawSystem(Gfx g, int si, System s, Map<String, TieStart> ties) {
        float left = margin + (staves > 1 ? 1.2f * sp : 0);
        float right = ml[s.last].x + ml[s.last].width * ml[s.last].scale;
        float lineW = 0.13f * sp;
        float em = 4 * sp;
        // staff lines
        for (int st = 0; st < staves; st++) {
            float top = staffTop(s, st);
            for (int k = 0; k < 5; k++) g.line(left, top + k * sp, right, top + k * sp, lineW);
        }
        float sysTop = staffTop(s, 0), sysBot = staffTop(s, staves - 1) + 4 * sp;
        g.line(left, sysTop, left, sysBot, 0.16f * sp);
        if (staves > 1) {
            // brace glyph is one em tall: scale it to the system height
            float h = sysBot - sysTop;
            g.glyph(BRACE, margin - 0.2f * sp, sysBot, h);
        }
        // clefs, key and time signatures
        boolean first = si == 0;
        for (int st = 0; st < staves; st++) {
            int clef = score.clefs[st];
            float top = staffTop(s, st);
            float x = left + 0.8f * sp;
            if (clef == Score.TREBLE) g.glyph(G_CLEF, x, top + 3 * sp, em);
            else g.glyph(F_CLEF, x, top + sp, em);
            x += 3.4f * sp;
            int f = score.key.fifths;
            int shift = clef == Score.TREBLE ? 0 : -14;
            for (int k = 0; k < Math.abs(f); k++) {
                int step = (f > 0 ? SHARP_STEPS_TREBLE[k] : FLAT_STEPS_TREBLE[k]) + shift;
                g.glyph(f > 0 ? SHARP : FLAT, x, stepY(s, st, step), em);
                x += 1.05f * sp;
            }
            if (f != 0) x += 0.8f * sp;
            if (first) {
                int n = score.tempo.beatsPerBar;
                g.glyph((char) (TS_0 + n), x, top + sp, em);
                g.glyph((char) (TS_0 + 4), x, top + 3 * sp, em);
            }
        }
        // measure number
        if (!first) {
            g.text(String.valueOf(s.first + 1), left, sysTop - 2.2f * sp, 1.6f * sp, false, Gfx.LEFT);
        }
        // measures
        for (int m = s.first; m <= s.last; m++) {
            MLayout l = ml[m];
            float xEnd = l.x + l.width * l.scale;
            boolean isLast = m == ml.length - 1;
            if (isLast) {
                g.line(xEnd - 0.9f * sp, sysTop, xEnd - 0.9f * sp, sysBot, 0.16f * sp);
                g.rect(xEnd - 0.5f * sp, sysTop, 0.5f * sp, sysBot - sysTop);
            } else {
                g.line(xEnd, sysTop, xEnd, sysBot, 0.16f * sp);
            }
            for (int st = 0; st < staves; st++) drawMeasureStaff(g, si, s, m, st, ties);
        }
        float ottavaTop = drawOttavas(g, si, s);

        // chord symbols (shifted right if they would collide with the previous one)
        float chordEnd = -1e9f;
        float chordY = Math.min(sysTop - 3.6f * sp, ottavaTop - 1.6f * sp);
        float csize = 1.9f * sp;
        for (Score.ChordSymbol c : score.chords) {
            if (c.measure < s.first || c.measure > s.last) continue;
            float x = Math.max(unitX(ml[c.measure], c.unit), chordEnd + 0.8f * sp);
            g.text(c.name, x, chordY, csize, true, Gfx.LEFT);
            chordEnd = x + c.name.length() * 0.62f * csize;
        }
        // unresolved ties run to the end of the system
        for (Map.Entry<String, TieStart> e : ties.entrySet()) {
            TieStart t = e.getValue();
            if (t.system == si) {
                drawTie(g, t.x, right + 0.3f * sp, t.y, t.up);
                ties.put(e.getKey(), new TieStart(-1, t.y, t.up, -1));
            }
        }
    }

    private void drawMeasureStaff(Gfx g, int si, System s, int m, int st, Map<String, TieStart> ties) {
        MLayout l = ml[m];
        List<Score.Event> evs = score.measures.get(m).staves.get(st);
        float em = 4 * sp;
        float top = staffTop(s, st);
        int mid = BOTTOM_STEP[score.clefs[st]] + 4;

        // stem directions per event (beam groups share one direction)
        Map<Integer, Boolean> groupUp = new HashMap<Integer, Boolean>();
        Map<Integer, Integer> groupSum = new HashMap<Integer, Integer>();
        for (Score.Event e : evs) {
            if (e.beam < 0 || e.heads.isEmpty()) continue;
            int lo = e.heads.get(0).disp, hi = e.heads.get(e.heads.size() - 1).disp;
            int sum = groupSum.containsKey(e.beam) ? groupSum.get(e.beam) : 0;
            groupSum.put(e.beam, sum + (hi - mid) + (lo - mid));
        }
        for (Map.Entry<Integer, Integer> en : groupSum.entrySet()) groupUp.put(en.getKey(), en.getValue() < 0);

        int n = evs.size();
        float[] stemX = new float[n], stemEnd = new float[n], headX = new float[n];
        boolean[] up = new boolean[n];
        for (int i = 0; i < n; i++) {
            Score.Event e = evs.get(i);
            float x = slotHeadX(l, e.start);
            headX[i] = x;
            if (e.rest) {
                drawRest(g, e, l, x, top);
                continue;
            }
            int lo = e.heads.get(0).disp, hi = e.heads.get(e.heads.size() - 1).disp;
            up[i] = e.beam >= 0 ? groupUp.get(e.beam) : (hi - mid) + (lo - mid) < 0;
            float yLo = stepY(s, st, lo), yHi = stepY(s, st, hi);
            boolean whole = e.base == 16;
            float hw = (whole ? WHOLE_W : HEAD_W) * sp;
            char head = whole ? HEAD_WHOLE : e.base == 8 ? HEAD_HALF : HEAD_BLACK;

            // noteheads (seconds are displaced to the other side of the stem)
            float[] hx = new float[e.heads.size()];
            boolean displacedAny = false;
            if (up[i]) {
                int prev = -100;
                boolean prevDisp = false;
                for (int k = 0; k < e.heads.size(); k++) {
                    int stp = e.heads.get(k).disp;
                    boolean disp = !whole && stp - prev == 1 && !prevDisp;
                    hx[k] = disp ? x + hw - 0.12f * sp : x;
                    displacedAny |= disp;
                    prev = stp;
                    prevDisp = disp;
                }
            } else {
                int prev = 100;
                boolean prevDisp = false;
                for (int k = e.heads.size() - 1; k >= 0; k--) {
                    int stp = e.heads.get(k).disp;
                    boolean disp = !whole && prev - stp == 1 && !prevDisp;
                    hx[k] = disp ? x - hw + 0.12f * sp : x;
                    displacedAny |= disp;
                    prev = stp;
                    prevDisp = disp;
                }
            }
            int accCol = 0, lastAccStep = 1000;
            for (int k = e.heads.size() - 1; k >= 0; k--) {
                Score.Head h = e.heads.get(k);
                float y = stepY(s, st, h.disp);
                drawLedgers(g, s, st, h.disp, hx[k], hw);
                g.glyph(head, hx[k], y, em);
                if (h.accidental) {
                    if (lastAccStep - h.disp < 6) accCol++;
                    else accCol = 0;
                    lastAccStep = h.disp;
                    char a = h.alter > 0 ? SHARP : h.alter < 0 ? FLAT : NATURAL;
                    float aw = (a == SHARP ? 1.0f : a == FLAT ? 0.9f : 0.67f) * sp;
                    float minX = Math.min(x, hx[k]);
                    g.glyph(a, minX - aw - 0.25f * sp - accCol * 1.1f * sp, y, em);
                }
                if (e.dots > 0) {
                    float dy = ((h.disp - BOTTOM_STEP[score.clefs[st]]) % 2 == 0) ? -sp / 2 : 0;
                    float dx = x + hw + (displacedAny && up[i] ? hw : 0) + 0.35f * sp;
                    g.glyph(DOT, dx, y + dy, em);
                }
                // ties
                String key = st + ":" + h.step;
                if (h.tieEnd) {
                    TieStart t = ties.remove(key);
                    if (t != null) {
                        float x1 = t.system == si ? t.x : s.contentX - 1.2f * sp;
                        drawTie(g, x1, hx[k] - 0.15f * sp, y, t.up);
                    }
                }
                if (h.tieStart) ties.put(key, new TieStart(hx[k] + hw + 0.15f * sp, y, !up[i], si));
            }
            if (!whole) {
                stemX[i] = up[i] ? x + hw - 0.06f * sp : x + 0.06f * sp;
                float len = 3.4f * sp;
                stemEnd[i] = up[i] ? Math.min(yHi - len, top + 2 * sp) : Math.max(yLo + len, top + 2 * sp);
            }
        }

        // beams
        int i = 0;
        while (i < n) {
            Score.Event e = evs.get(i);
            if (e.beam < 0) {
                if (!e.rest && e.base != 16) {
                    drawStem(g, s, st, e, stemX[i], stemEnd[i], up[i]);
                    if (e.base <= 2) {
                        char f = e.base == 2 ? (up[i] ? FLAG8_UP : FLAG8_DOWN) : (up[i] ? FLAG16_UP : FLAG16_DOWN);
                        g.glyph(f, stemX[i] - 0.06f * sp, stemEnd[i], em);
                    }
                }
                i++;
                continue;
            }
            int j = i;
            while (j < n && evs.get(j).beam == e.beam) j++;
            boolean u = up[i];
            float x0 = stemX[i], x1 = stemX[j - 1];
            float slope = x1 > x0 ? (stemEnd[j - 1] - stemEnd[i]) / (x1 - x0) : 0;
            slope = Math.max(-0.15f, Math.min(0.15f, slope));
            float shift = 0;
            for (int k = i; k < j; k++) {
                float y = stemEnd[i] + slope * (stemX[k] - x0);
                float d = u ? y - stemEnd[k] : stemEnd[k] - y;
                if (d > shift) shift = d;
            }
            float y0 = stemEnd[i] + (u ? -shift : shift);
            float bt = 0.5f * sp, gap = 0.75f * sp;
            for (int k = i; k < j; k++) {
                float y = y0 + slope * (stemX[k] - x0);
                drawStem(g, s, st, evs.get(k), stemX[k], y, u);
            }
            beam(g, x0, y0, x1, y0 + slope * (x1 - x0), bt, u);
            for (int k = i; k < j; k++) {
                if (evs.get(k).base != 1) continue;
                float yo = u ? gap : -gap;
                boolean nextSixteenth = k + 1 < j && evs.get(k + 1).base == 1;
                boolean prevSixteenth = k > i && evs.get(k - 1).base == 1;
                if (nextSixteenth) {
                    float xa = stemX[k], xb = stemX[k + 1];
                    beam(g, xa, y0 + slope * (xa - x0) + yo, xb, y0 + slope * (xb - x0) + yo, bt, u);
                } else if (!prevSixteenth) {
                    float xa = stemX[k], xb = k == i ? xa + 1.2f * sp : xa - 1.2f * sp;
                    beam(g, xa, y0 + slope * (xa - x0) + yo, xb, y0 + slope * (xb - x0) + yo, bt, u);
                }
            }
            i = j;
        }
    }

    /**
     * Draws the 8va / 8vb brackets of this system: the glyph at the start (parenthesised when
     * continued from the previous system), a dashed line, and a hook at the end.
     * @return the highest y used above the top staff (for placing chord symbols)
     */
    private float drawOttavas(Gfx g, int si, System s) {
        float minTop = Float.MAX_VALUE;
        float em = 4 * sp;
        for (Score.Ottava o : score.ottavas) {
            if (o.m1 < s.first || o.m0 > s.last) continue;
            boolean startsHere = o.m0 >= s.first;
            boolean endsHere = o.m1 <= s.last;
            int ma = Math.max(o.m0, s.first), mb = Math.min(o.m1, s.last);
            // extreme notehead (display position) inside this system's part of the bracket
            float extreme = o.dir > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            float x0 = 0, x1 = 0;
            for (int m = ma; m <= mb; m++) {
                List<Score.Event> evs = score.measures.get(m).staves.get(o.staff);
                int ea = m == o.m0 ? o.e0 : 0, eb = m == o.m1 ? o.e1 : evs.size() - 1;
                for (int e = ea; e <= eb && e < evs.size(); e++) {
                    Score.Event ev = evs.get(e);
                    float x = slotHeadX(ml[m], ev.start);
                    if (m == ma && e == ea) x0 = x;
                    x1 = x + HEAD_W * sp;
                    if (ev.rest) continue;
                    int step = o.dir > 0 ? ev.heads.get(ev.heads.size() - 1).disp : ev.heads.get(0).disp;
                    float y = stepY(s, o.staff, step);
                    extreme = o.dir > 0 ? Math.min(extreme, y) : Math.max(extreme, y);
                }
            }
            if (!startsHere) x0 = s.contentX - 0.5f * sp;
            if (!endsHere) x1 = ml[s.last].x + ml[s.last].width * ml[s.last].scale - 0.3f * sp;
            float staffTopY = staffTop(s, o.staff);
            float y;
            if (o.dir > 0) {
                y = Math.min(staffTopY - 2.2f * sp, extreme - 4.4f * sp); // clear of up-stems
                minTop = Math.min(minTop, y - 1.4f * sp);
            } else {
                y = Math.max(staffTopY + 6.2f * sp, extreme + 4.4f * sp);
            }
            float tx = x0;
            if (!startsHere) {
                g.glyph(PAREN_L, tx, y, em * 0.75f);
                tx += 0.6f * sp;
            }
            g.glyph(o.dir > 0 ? OTTAVA_ALTA : OTTAVA_BASSA, tx, y, em * 0.75f);
            tx += 2.4f * sp;
            if (!startsHere) {
                g.glyph(PAREN_R, tx - 0.2f * sp, y, em * 0.75f);
                tx += 0.6f * sp;
            }
            float ly = o.dir > 0 ? y - 0.6f * sp : y - 0.6f * sp;
            float dash = 0.7f * sp, gap = 0.5f * sp;
            for (float x = tx + 0.3f * sp; x < x1; x += dash + gap) {
                g.line(x, ly, Math.min(x1, x + dash), ly, 0.12f * sp);
            }
            if (endsHere) {
                float hook = o.dir > 0 ? 1.1f * sp : -1.1f * sp;
                g.line(x1, ly, x1, ly + hook, 0.12f * sp);
            }
        }
        return minTop == Float.MAX_VALUE ? staffTop(s, 0) : minTop;
    }

    private void beam(Gfx g, float xa, float ya, float xb, float yb, float t, boolean up) {
        float d = up ? t : -t;
        g.beginPath();
        g.moveTo(xa, ya);
        g.lineTo(xb, yb);
        g.lineTo(xb, yb + d);
        g.lineTo(xa, ya + d);
        g.fillPath();
    }

    private void drawStem(Gfx g, System s, int st, Score.Event e, float x, float end, boolean up) {
        float yLo = stepY(s, st, e.heads.get(0).disp), yHi = stepY(s, st, e.heads.get(e.heads.size() - 1).disp);
        float from = up ? yLo : yHi;
        g.line(x, from, x, end, 0.12f * sp);
    }

    private void drawLedgers(Gfx g, System s, int st, int step, float x, float hw) {
        int bottom = BOTTOM_STEP[score.clefs[st]];
        float ext = 0.4f * sp;
        for (int k = bottom - 2; k >= step; k -= 2) {
            float y = stepY(s, st, k);
            g.line(x - ext, y, x + hw + ext, y, 0.16f * sp);
        }
        for (int k = bottom + 10; k <= step; k += 2) {
            float y = stepY(s, st, k);
            g.line(x - ext, y, x + hw + ext, y, 0.16f * sp);
        }
    }

    private void drawRest(Gfx g, Score.Event e, MLayout l, float x, float top) {
        float em = 4 * sp;
        if (e.measureRest) {
            float cx = l.x + l.width * l.scale / 2 - 0.56f * sp;
            g.glyph(REST_WHOLE, cx, top + sp, em);
            return;
        }
        switch (e.base) {
            case 16: g.glyph(REST_WHOLE, x, top + sp, em); break;
            case 8: g.glyph(REST_HALF, x, top + 2 * sp, em); break;
            case 4: g.glyph(REST_QUARTER, x, top + 2 * sp, em); break;
            case 2: g.glyph(REST_8, x, top + 2 * sp, em); break;
            default: g.glyph(REST_16, x, top + 2 * sp, em); break;
        }
    }

    private void drawTie(Gfx g, float x1, float x2, float y, boolean up) {
        if (x2 - x1 < 0.6f * sp) x2 = x1 + 0.6f * sp;
        float dir = up ? -1 : 1;
        float y0 = y + dir * 0.55f * sp;
        float h = Math.min(1.6f * sp, 0.25f * sp + 0.08f * (x2 - x1));
        float t = 0.18f * sp;
        float dx = (x2 - x1) * 0.25f;
        g.beginPath();
        g.moveTo(x1, y0);
        g.cubicTo(x1 + dx, y0 + dir * h, x2 - dx, y0 + dir * h, x2, y0);
        g.cubicTo(x2 - dx, y0 + dir * (h - t), x1 + dx, y0 + dir * (h - t), x1, y0);
        g.fillPath();
    }

    /** Convenience for UIs: the key's display name. */
    public Key key() {
        return score.key;
    }
}
