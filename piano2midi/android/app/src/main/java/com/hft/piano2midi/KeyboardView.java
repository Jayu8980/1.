package com.hft.piano2midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import com.hft.piano2midi.core.music.Part;

/**
 * 88-key piano keyboard. Keys light up in their part's colour while pressed (by
 * playback or by touch), sink slightly, and fade out smoothly after release.
 */
public final class KeyboardView extends View {

    interface Listener {
        void onKeyDown(int pitch);

        void onKeyUp(int pitch);
    }

    private static final float FADE_SEC = 0.35f;

    private final KeyGeometry geo = new KeyGeometry();
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint felt = new Paint();
    private final RectF r = new RectF();
    private final float density;

    /** Part (0..2) currently holding each key, or -1. Index = MIDI pitch. */
    private final int[] down = new int[128];
    private final float[] level = new float[128];
    private final int[] lastColor = new int[128];
    private final boolean[] touchDown = new boolean[128];
    private final SparseIntArray pointerKey = new SparseIntArray();
    private long lastFrame;
    private Listener listener;
    private int touchColor = 0xFFFFCA28;
    /** Wait mode: keys to play next (pulsing gold) and recently wrong keys (red flash). */
    private final boolean[] expectedKey = new boolean[128];
    private final long[] wrongUntil = new long[128];
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public KeyboardView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 128; i++) down[i] = -1;
        white.setColor(0xFFFAFAFA);
        black.setColor(0xFF1C1C22);
        border.setColor(0xFF9E9E9E);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, 0.6f * density));
        label.setColor(0xFF8A8A8A);
        label.setTextAlign(Paint.Align.CENTER);
        felt.setColor(0xFFB71C1C);
    }

    void setExpected(int[] pitches) {
        boolean changed = false;
        boolean[] n = new boolean[128];
        if (pitches != null) for (int p : pitches) if (p >= 0 && p < 128) n[p] = true;
        for (int p = 0; p < 128; p++) if (n[p] != expectedKey[p]) {
            expectedKey[p] = n[p];
            changed = true;
        }
        if (changed) postInvalidateOnAnimation();
    }

    void flashWrong(int pitch) {
        if (pitch < 0 || pitch > 127) return;
        wrongUntil[pitch] = SystemClock.uptimeMillis() + 400;
        postInvalidateOnAnimation();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTouchColor(int c) {
        touchColor = c;
    }

    /** @param parts part per pitch (index 0..127), -1 for released */
    void setActive(int[] parts) {
        boolean changed = false;
        for (int p = 0; p < 128; p++) {
            int v = touchDown[p] ? Part.COUNT : parts[p];
            if (v != down[p]) {
                down[p] = v;
                changed = true;
                if (v >= 0) {
                    level[p] = 1f;
                    lastColor[p] = colorFor(v);
                }
            }
        }
        if (changed) postInvalidateOnAnimation();
    }

    private int colorFor(int part) {
        return part >= 0 && part < Part.COUNT ? Part.COLORS[part] : touchColor;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        geo.layout(w);
        label.setTextSize(Math.min(9 * density, geo.whiteW * 0.55f));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec) : (int) (96 * density);
        setMeasuredDimension(w, h);
    }

    private static int blend(int base, int col, float k) {
        int a = 0xFF;
        int r = (int) (((base >> 16) & 0xFF) * (1 - k) + ((col >> 16) & 0xFF) * k);
        int g = (int) (((base >> 8) & 0xFF) * (1 - k) + ((col >> 8) & 0xFF) * k);
        int b = (int) ((base & 0xFF) * (1 - k) + (col & 0xFF) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = false;
        for (int p = 0; p < 128; p++) {
            if (down[p] < 0 && level[p] > 0) {
                level[p] = Math.max(0, level[p] - dt / FADE_SEC);
                animating |= level[p] > 0;
            }
        }

        int w = getWidth(), h = getHeight();
        float top = 3 * density;
        canvas.drawRect(0, 0, w, top, felt);
        float depth = 2.5f * density;
        // white keys
        for (int p = KeyGeometry.LOW; p <= KeyGeometry.HIGH; p++) {
            if (KeyGeometry.isBlack(p)) continue;
            float k = down[p] >= 0 ? 1f : level[p];
            float sink = down[p] >= 0 ? depth : 0;
            r.set(geo.left(p) + 0.5f, top, geo.right(p) - 0.5f, h - depth + sink);
            white.setColor(k > 0 ? blend(0xFFFAFAFA, lastColor[p], 0.85f * k) : 0xFFFAFAFA);
            canvas.drawRoundRect(r, 2 * density, 2 * density, white);
            canvas.drawRoundRect(r, 2 * density, 2 * density, border);
            if (p % 12 == 0 && geo.whiteW > 6 * density) {
                canvas.drawText("C" + (p / 12 - 1), (geo.left(p) + geo.right(p)) / 2, h - depth - 4 * density, label);
            }
        }
        // black keys
        float bh = top + (h - top) * 0.62f;
        for (int p = KeyGeometry.LOW; p <= KeyGeometry.HIGH; p++) {
            if (!KeyGeometry.isBlack(p)) continue;
            float k = down[p] >= 0 ? 1f : level[p];
            float sink = down[p] >= 0 ? depth * 0.6f : 0;
            r.set(geo.left(p), top, geo.right(p), bh + sink);
            int base = 0xFF1C1C22;
            int c = k > 0 ? blend(base, lastColor[p], 0.9f * k) : base;
            black.setShader(new LinearGradient(0, top, 0, bh, c, blend(c, 0xFF000000, 0.35f), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, 1.5f * density, 1.5f * density, black);
            if (k > 0) {
                // a soft glow above active keys
                glow.setColor((lastColor[p] & 0x00FFFFFF) | ((int) (90 * k) << 24));
                canvas.drawRect(geo.left(p) - 1, 0, geo.right(p) + 1, top, glow);
            }
        }
        black.setShader(null);
        for (int p = KeyGeometry.LOW; p <= KeyGeometry.HIGH; p++) {
            if (KeyGeometry.isBlack(p)) continue;
            float k = down[p] >= 0 ? 1f : level[p];
            if (k > 0) {
                glow.setColor((lastColor[p] & 0x00FFFFFF) | ((int) (120 * k) << 24));
                canvas.drawRect(geo.left(p), 0, geo.right(p), top, glow);
            }
        }
        // wait-mode hints on top
        boolean anyHint = false;
        float pulse = 0.55f + 0.45f * (float) Math.sin(now / 160.0);
        hintPaint.setStyle(Paint.Style.STROKE);
        hintPaint.setStrokeWidth(3 * density);
        for (int p = KeyGeometry.LOW; p <= KeyGeometry.HIGH; p++) {
            boolean wrongNow = wrongUntil[p] > now;
            if (!expectedKey[p] && !wrongNow) continue;
            anyHint = true;
            boolean blackKey = KeyGeometry.isBlack(p);
            float bottom = blackKey ? top + (h - top) * 0.62f : h - depth;
            r.set(geo.left(p) + 2, top + 2, geo.right(p) - 2, bottom - 2);
            if (wrongNow) {
                hintPaint.setStyle(Paint.Style.FILL);
                hintPaint.setColor(0xB0E53935);
                canvas.drawRoundRect(r, 2 * density, 2 * density, hintPaint);
                hintPaint.setStyle(Paint.Style.STROKE);
            } else {
                hintPaint.setColor(((int) (255 * pulse) << 24) | 0xFFC400);
                canvas.drawRoundRect(r, 2 * density, 2 * density, hintPaint);
            }
        }
        if (animating || anyHint) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int h = getHeight();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int key = geo.keyAt(e.getX(i), e.getY(i) / h);
                pointerKey.put(e.getPointerId(i), key);
                press(key);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int key = geo.keyAt(e.getX(i), e.getY(i) / h);
                    int old = pointerKey.get(id, -1);
                    if (old != key) {
                        if (old >= 0) unpress(old);
                        pointerKey.put(id, key);
                        press(key);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) {
                    for (int i = 0; i < pointerKey.size(); i++) unpress(pointerKey.valueAt(i));
                    pointerKey.clear();
                } else {
                    int id = e.getPointerId(e.getActionIndex());
                    int key = pointerKey.get(id, -1);
                    pointerKey.delete(id);
                    if (key >= 0) unpress(key);
                }
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void press(int key) {
        touchDown[key] = true;
        down[key] = Part.COUNT;
        level[key] = 1f;
        lastColor[key] = touchColor;
        invalidate();
        if (listener != null) listener.onKeyDown(key);
    }

    private void unpress(int key) {
        touchDown[key] = false;
        down[key] = -1;
        invalidate();
        if (listener != null) listener.onKeyUp(key);
    }
}
