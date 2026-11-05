package com.hft.piano2midi;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.hft.piano2midi.core.sheet.Gfx;

/** Draws engraver output onto an Android Canvas (screen or PdfDocument page). */
final class AndroidGfx implements Gfx {
    private final Canvas c;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint music = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final char[] one = new char[1];

    AndroidGfx(Canvas c, Typeface musicFont) {
        this.c = c;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        fill.setStyle(Paint.Style.FILL);
        music.setTypeface(musicFont);
        setColor(0xFF000000);
    }

    @Override
    public void setColor(int argb) {
        stroke.setColor(argb);
        fill.setColor(argb);
        music.setColor(argb);
        text.setColor(argb);
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float width) {
        stroke.setStrokeWidth(width);
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    @Override
    public void rect(float x, float y, float w, float h) {
        c.drawRect(x, y, x + w, y + h, fill);
    }

    @Override
    public void glyph(char cp, float x, float y, float size) {
        music.setTextSize(size);
        one[0] = cp;
        c.drawText(one, 0, 1, x, y, music);
    }

    @Override
    public void text(String s, float x, float y, float size, boolean bold, int align) {
        text.setTextSize(size);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setTextAlign(align == CENTER ? Paint.Align.CENTER : align == RIGHT ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(s, x, y, text);
    }

    @Override
    public void beginPath() {
        path.reset();
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    @Override
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.cubicTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void fillPath() {
        path.close();
        c.drawPath(path, fill);
    }
}
