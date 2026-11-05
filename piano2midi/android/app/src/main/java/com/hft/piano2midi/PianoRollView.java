package com.hft.piano2midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.hft.piano2midi.core.Note;

import java.util.Collections;
import java.util.List;

/** Scrollable piano-roll preview of the transcription (time on x, pitch A0..C8 on y). */
public final class PianoRollView extends View {

    private static final int LOW = 21, HIGH = 108;

    private List<Note> notes = Collections.emptyList();
    private double duration = 0;
    private double playhead = -1;
    private final float basePxPerSec;
    private float pxPerSec;
    /** Keep the view a sane width for long recordings. */
    private static final float MAX_WIDTH_PX = 60000f;
    private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint cPaint = new Paint();
    private final Paint headPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Rect clip = new Rect();

    public PianoRollView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = getResources().getDisplayMetrics().density;
        basePxPerSec = 48 * d;
        pxPerSec = basePxPerSec;
        bgPaint.setColor(0xFF1E1F26);
        gridPaint.setColor(0xFF2A2C35);
        cPaint.setColor(0xFF3A3D4A);
        headPaint.setColor(0xFFFF5252);
        headPaint.setStrokeWidth(2 * d);
        textPaint.setColor(0xFF9AA0B4);
        textPaint.setTextSize(10 * d);
    }

    public void setNotes(List<Note> notes, double duration) {
        this.notes = notes;
        this.duration = duration;
        pxPerSec = duration > 0 ? (float) Math.min(basePxPerSec, MAX_WIDTH_PX / duration) : basePxPerSec;
        requestLayout();
        invalidate();
    }

    public void setPlayhead(double sec) {
        this.playhead = sec;
        invalidate();
    }

    public float xFor(double sec) {
        return (float) (sec * pxPerSec);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minW = MeasureSpec.getSize(widthMeasureSpec);
        int w = Math.max(minW, (int) Math.ceil(duration * pxPerSec) + 1);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            h = (int) (260 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.getClipBounds(clip);
        canvas.drawRect(clip, bgPaint);
        float rowH = h / (float) (HIGH - LOW + 1);
        // C rows
        for (int p = LOW; p <= HIGH; p++) {
            if (p % 12 == 0) {
                float y = (HIGH - p) * rowH;
                canvas.drawRect(clip.left, y, clip.right, y + rowH, cPaint);
            }
        }
        // second grid
        int s0 = (int) Math.floor(clip.left / pxPerSec);
        int s1 = (int) Math.ceil(clip.right / pxPerSec);
        for (int s = s0; s <= s1; s++) {
            float x = s * pxPerSec;
            canvas.drawLine(x, 0, x, h, gridPaint);
            if (s % (pxPerSec < basePxPerSec / 2 ? 30 : 5) == 0) canvas.drawText(s + "s", x + 3, textPaint.getTextSize() + 2, textPaint);
        }
        double t0 = clip.left / pxPerSec, t1 = clip.right / pxPerSec;
        for (Note n : notes) {
            if (n.offset < t0 || n.onset > t1) continue;
            float x0 = xFor(n.onset), x1 = Math.max(x0 + 2, xFor(n.offset));
            float y = (HIGH - n.pitch) * rowH;
            int v = Math.max(30, Math.min(127, n.velocity));
            float k = v / 127f;
            notePaint.setColor(Color.rgb((int) (80 + 175 * k), (int) (160 + 40 * k), 255 - (int) (120 * k)));
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
