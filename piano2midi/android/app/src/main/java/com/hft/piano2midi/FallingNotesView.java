package com.hft.piano2midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.music.ChordRecognizer;
import com.hft.piano2midi.core.music.Part;
import com.hft.piano2midi.core.music.Tempo;

import java.util.Collections;
import java.util.List;

/** Notes fall toward the keyboard below and hit their key exactly when they sound. */
public final class FallingNotesView extends View {

    private final KeyGeometry geo = new KeyGeometry();
    private final Paint bg = new Paint(), lane = new Paint(), bar = new Paint(), beat = new Paint();
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chordText = new Paint(Paint.ANTI_ALIAS_FLAG), hit = new Paint();
    private final RectF r = new RectF();
    private final float density;

    private List<Note> notes = Collections.emptyList();
    private List<ChordRecognizer.Chord> chords = Collections.emptyList();
    private Tempo tempo;
    private int partMask = Part.ALL;
    private double pos;
    private double maxDur = 0;
    /** Seconds of music visible above the hit line. */
    private double window = 3.0;
    /** Live mode: time flows upward, the newest notes appear at the keyboard. */
    private boolean live;
    private double loopA = -1, loopB = -1;
    private final Paint loopPaint = new Paint();

    public FallingNotesView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        bg.setColor(0xFF15161C);
        lane.setColor(0xFF1C1D25);
        bar.setColor(0xFF3A3D4A);
        beat.setColor(0xFF24262F);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        chordText.setColor(0xFFE0E0E0);
        chordText.setTextSize(13 * density);
        chordText.setFakeBoldText(true);
        hit.setColor(0x33FFFFFF);
        loopPaint.setColor(0xFFFF9800);
    }

    void setData(List<Note> notes, List<ChordRecognizer.Chord> chords, Tempo tempo) {
        this.notes = notes;
        this.chords = chords != null ? chords : Collections.<ChordRecognizer.Chord>emptyList();
        this.tempo = tempo;
        maxDur = 0;
        for (Note n : notes) maxDur = Math.max(maxDur, n.offset - n.onset);
        invalidate();
    }

    void setLive(boolean on) {
        live = on;
        window = on ? 6.0 : 3.0;
        invalidate();
    }

    /** Marks loop start / end lines (a < 0 clears). */
    void setLoop(double a, double b) {
        loopA = a;
        loopB = b;
        invalidate();
    }

    void setPartMask(int mask) {
        partMask = mask;
        invalidate();
    }

    void setPosition(double sec) {
        pos = sec;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        geo.layout(w);
    }

    private float yFor(double t, float h) {
        return live ? (float) (h - (pos - t) / window * h) : (float) (h - (t - pos) / window * h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        for (int p = KeyGeometry.LOW; p <= KeyGeometry.HIGH; p++) {
            if (p % 12 == 0 || p % 12 == 5) c.drawRect(geo.left(p), 0, geo.left(p) + 1, h, lane);
        }
        if (tempo != null && !live) {
            double beatSec = tempo.beatSec();
            long b0 = (long) Math.floor((pos - tempo.origin) / beatSec);
            for (long b = b0; ; b++) {
                double t = tempo.origin + b * beatSec;
                float y = yFor(t, h);
                if (y < 0) break;
                if (y > h) continue;
                c.drawRect(0, y, w, y + (b % tempo.beatsPerBar == 0 ? 1.5f * density : 1), b % tempo.beatsPerBar == 0 ? bar : beat);
            }
        }
        if (loopA >= 0 && !live) {
            for (double t : new double[]{loopA, loopB}) {
                float y = yFor(t, h);
                if (y >= 0 && y <= h) c.drawRect(0, y - density, w, y + density, loopPaint);
            }
        }
        double t0 = live ? pos - window : pos - 0.05, t1 = live ? pos + 0.05 : pos + window;
        // notes are sorted by onset; start from the first that may still be visible
        int lo = 0, hi = notes.size();
        double from = t0 - maxDur;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (notes.get(mid).onset < from) lo = mid + 1;
            else hi = mid;
        }
        float rad = 3 * density;
        for (int i = lo; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (n.onset > t1) break;
            if (n.offset < t0) continue;
            boolean on = (partMask & (1 << n.part)) != 0;
            int col = Part.COLORS[n.part];
            boolean sounding = n.onset <= pos && n.offset > pos;
            float ya = yFor(n.offset, h), yb = yFor(n.onset, h);
            float y0 = Math.min(ya, yb), y1 = Math.max(ya, yb);
            float x0 = geo.left(n.pitch) + 1, x1 = geo.right(n.pitch) - 1;
            r.set(x0, y0, x1, Math.max(y0 + 3 * density, y1));
            int alpha = on ? (sounding ? 0xFF : 0xE0) : 0x30;
            note.setColor((col & 0x00FFFFFF) | (alpha << 24));
            if (KeyGeometry.isBlack(n.pitch)) note.setColor(darken(note.getColor()));
            c.drawRoundRect(r, rad, rad, note);
            if (on && sounding) {
                edge.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, edge);
            }
        }
        // chord names at their onset
        for (ChordRecognizer.Chord ch : chords) {
            if (live) break;
            if (ch.root < 0 || ch.end < pos || ch.start > t1) continue;
            float y = yFor(ch.start, h);
            if (y > h) y = h;
            c.drawText(ch.name, 6 * density, y - 4 * density, chordText);
        }
        c.drawRect(0, h - 2 * density, w, h, hit);
    }

    private static int darken(int c) {
        int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (a << 24) | ((int) (r * 0.72f) << 16) | ((int) (g * 0.72f) << 8) | (int) (b * 0.72f);
    }
}
