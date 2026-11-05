package com.hft.piano2midi.core.sheet;

/** Minimal drawing surface so the engraver can draw to Android Canvas, PDF pages or Java2D. */
public interface Gfx {
    int LEFT = 0, CENTER = 1, RIGHT = 2;

    void setColor(int argb);

    void line(float x1, float y1, float x2, float y2, float width);

    void rect(float x, float y, float w, float h);

    /** Draws a SMuFL glyph from the music font; size is the em size (= 4 staff spaces). */
    void glyph(char codepoint, float x, float y, float size);

    void text(String s, float x, float y, float size, boolean bold, int align);

    void beginPath();

    void moveTo(float x, float y);

    void lineTo(float x, float y);

    void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3);

    void fillPath();
}
