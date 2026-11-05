package com.hft.piano2midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.music.ChordRecognizer;
import com.hft.piano2midi.core.music.Part;
import com.hft.piano2midi.core.music.Tempo;

import java.util.Collections;
import java.util.List;

/**
 * Scrollable piano roll (time on x, pitch A0..C8 on y). Notes are coloured by part,
 * disabled parts are dimmed, sounding notes are highlighted, a chord lane runs on top
 * and tapping sets the playback position.
 */
public final class PianoRollView extends View {

    interface SeekListener {
        void onSeek(double sec);
    }

    private static final int LOW = 21, HIGH = 108;
    private static final float MAX_WIDTH_PX = 60000f;

    private List<Note> notes = Collections.emptyList();
    private List<ChordRecognizer.Chord> chords = Collections.emptyList();
    private Tempo tempo;
    private int partMask = Part.ALL;
    private double duration = 0;
    private double playhead = -1;
    private final float basePxPerSec;
    private float pxPerSec;
    private final float laneH;
    private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(), barPaint = new Paint(), cPaint = new Paint();
    private final Paint headPaint = new Paint(), textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chordPaint = new Paint(Paint.ANTI_ALIAS_FLAG), bgPaint = new Paint(), lanePaint = new Paint();
    private final Rect clip = new Rect();
    private final GestureDetector gestures;
    private SeekListener seekListener;
    private double loopA = -1, loopB = -1;
    private final Paint loopPaint = new Paint();

    public PianoRollView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = getResources().getDisplayMetrics().density;
        basePxPerSec = 56 * d;
        pxPerSec = basePxPerSec;
        laneH = 18 * d;
        bgPaint.setColor(0xFF1E1F26);
        lanePaint.setColor(0xFF2A2C36);
        gridPaint.setColor(0xFF262832);
        barPaint.setColor(0xFF3D4050);
        cPaint.setColor(0xFF2B2E3A);
        headPaint.setColor(0xFFFF5252);
        headPaint.setStrokeWidth(2 * d);
        textPaint.setColor(0xFF9AA0B4);
        textPaint.setTextSize(10 * d);
        chordPaint.setColor(0xFFFFE082);
        loopPaint.setColor(0x30FF9800);
        chordPaint.setTextSize(11 * d);
        chordPaint.setFakeBoldText(true);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (seekListener != null && duration > 0) seekListener.onSeek(Math.max(0, e.getX() / pxPerSec));
                return true;
            }
        });
    }

    void setSeekListener(SeekListener l) {
        seekListener = l;
    }

    public void setData(List<Note> notes, List<ChordRecognizer.Chord> chords, Tempo tempo, double duration) {
        this.notes = notes;
        this.chords = chords != null ? chords : Collections.<ChordRecognizer.Chord>emptyList();
        this.tempo = tempo;
        this.duration = duration;
        pxPerSec = duration > 0 ? (float) Math.min(basePxPerSec, MAX_WIDTH_PX / duration) : basePxPerSec;
        requestLayout();
        invalidate();
    }

    /** Shades the loop range in seconds (a < 0 clears). */
    public void setLoop(double a, double b) {
        loopA = a;
        loopB = b;
        invalidate();
    }

    public void setPartMask(int mask) {
        partMask = mask;
        invalidate();
    }

    public void setPlayhead(double sec) {
        playhead = sec;
        invalidate();
    }

    public float xFor(double sec) {
        return (float) (sec * pxPerSec);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minW = MeasureSpec.getSize(widthMeasureSpec);
        int w = Math.max(minW, (int) Math.ceil(duration * pxPerSec) + 1);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            h = (int) (300 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.getClipBounds(clip);
        canvas.drawRect(clip, bgPaint);
        canvas.drawRect(clip.left, 0, clip.right, laneH, lanePaint);
        float rollTop = laneH;
        float rowH = (h - rollTop) / (float) (HIGH - LOW + 1);
        for (int p = LOW; p <= HIGH; p++) {
            if (p % 12 == 0) {
                float y = rollTop + (HIGH - p) * rowH;
                canvas.drawRect(clip.left, y, clip.right, y + rowH, cPaint);
            }
        }
        double t0 = clip.left / pxPerSec, t1 = clip.right / pxPerSec;
        if (loopA >= 0) canvas.drawRect(xFor(loopA), 0, xFor(loopB), h, loopPaint);
        if (tempo != null) {
            double beat = tempo.beatSec();
            long b0 = (long) Math.floor((t0 - tempo.origin) / beat), b1 = (long) Math.ceil((t1 - tempo.origin) / beat);
            boolean dense = beat * pxPerSec < 8 * getResources().getDisplayMetrics().density;
            for (long b = b0; b <= b1; b++) {
                float x = xFor(tempo.origin + b * beat);
                boolean isBar = Math.floorMod(b, tempo.beatsPerBar) == 0;
                if (!isBar && dense) continue;
                canvas.drawLine(x, rollTop, x, h, isBar ? barPaint : gridPaint);
                if (isBar && b >= 0) {
                    long bar = b / tempo.beatsPerBar + 1;
                    if (!dense || bar % 4 == 1) canvas.drawText(String.valueOf(bar), x + 3, rollTop + textPaint.getTextSize(), textPaint);
                }
            }
        }
        for (ChordRecognizer.Chord c : chords) {
            if (c.root < 0 || c.end < t0 || c.start > t1) continue;
            canvas.drawText(c.name, xFor(c.start) + 3, laneH - 5 * getResources().getDisplayMetrics().density, chordPaint);
        }
        for (Note n : notes) {
            if (n.offset < t0 || n.onset > t1) continue;
            float x0 = xFor(n.onset), x1 = Math.max(x0 + 2, xFor(n.offset));
            float y = rollTop + (HIGH - n.pitch) * rowH;
            boolean on = (partMask & (1 << n.part)) != 0;
            boolean sounding = playhead >= n.onset && playhead < n.offset;
            int col = Part.COLORS[n.part];
            float k = 0.55f + 0.45f * Math.min(127, n.velocity) / 127f;
            int a = on ? (int) (255 * k) : 40;
            if (on && sounding) col = 0xFFFFFFFF;
            notePaint.setColor((col & 0x00FFFFFF) | (a << 24));
            canvas.drawRect(x0, y, x1, y + Math.max(1.5f, rowH - 0.5f), notePaint);
        }
        if (playhead >= 0) {
            float x = xFor(playhead);
            canvas.drawLine(x, 0, x, h, headPaint);
        }
        if (notes.isEmpty()) {
            String msg = getContext().getString(R.string.roll_empty);
            float tw = textPaint.measureText(msg);
            canvas.drawText(msg, Math.max(8, (Math.min(w, getRootView().getWidth()) - tw) / 2), h / 2f, textPaint);
        }
    }
}
