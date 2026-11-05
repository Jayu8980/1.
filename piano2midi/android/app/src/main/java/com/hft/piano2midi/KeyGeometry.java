package com.hft.piano2midi;

/** Horizontal layout of the 88 piano keys (A0..C8), shared by the keyboard and the falling notes. */
final class KeyGeometry {
    static final int LOW = 21, HIGH = 108, WHITE_KEYS = 52;

    static boolean isBlack(int pitch) {
        int pc = pitch % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    /** Index of the white key at or just below `pitch`. */
    static int whiteIndex(int pitch) {
        int n = 0;
        for (int p = LOW; p < pitch; p++) if (!isBlack(p)) n++;
        return n;
    }

    private final float[] left = new float[128], right = new float[128];
    float whiteW;

    void layout(float width) {
        whiteW = width / WHITE_KEYS;
        float blackW = whiteW * 0.62f;
        for (int p = LOW; p <= HIGH; p++) {
            int w = whiteIndex(p);
            if (isBlack(p)) {
                float c = w * whiteW;
                left[p] = c - blackW / 2;
                right[p] = c + blackW / 2;
            } else {
                left[p] = w * whiteW;
                right[p] = (w + 1) * whiteW;
            }
        }
    }

    float left(int p) {
        return left[p];
    }

    float right(int p) {
        return right[p];
    }

    /** Key under x; y fraction (0 top .. 1 bottom) decides black vs white. */
    int keyAt(float x, float yFrac) {
        if (yFrac < 0.62f) {
            for (int p = LOW; p <= HIGH; p++) if (isBlack(p) && x >= left[p] && x < right[p]) return p;
        }
        for (int p = LOW; p <= HIGH; p++) if (!isBlack(p) && x >= left[p] && x < right[p]) return p;
        return x < 0 ? LOW : HIGH;
    }
}
