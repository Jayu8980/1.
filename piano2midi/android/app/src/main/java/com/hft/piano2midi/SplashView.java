package com.hft.piano2midi;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.hft.piano2midi.core.music.Part;

/**
 * Animated intro shown on launch (continues the system splash on the same gradient):
 * a small keyboard fades in, keys light up one after another and coloured notes rise
 * from them (audio becoming MIDI), the title slides in, then everything fades out.
 */
final class SplashView extends View {

    private static final long DURATION = 1700;
    private static final int WHITE_KEYS = 8;
    // which white key lights up, when (0..1 of the animation) and in which part colour
    private static final int[] HIT_KEY = {0, 2, 4, 7, 4, 6};
    private static final float[] HIT_AT = {0.18f, 0.27f, 0.36f, 0.45f, 0.54f, 0.60f};
    private static final int[] HIT_PART = {Part.BASS, Part.CHORDS, Part.CHORDS, Part.MELODY, Part.CHORDS, Part.MELODY};

    private final Paint bg = new Paint(), key = new Paint(Paint.ANTI_ALIAS_FLAG), black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private float p; // animation progress 0..1
    private ValueAnimator anim;

    SplashView(Context ctx) {
        super(ctx);
        d = getResources().getDisplayMetrics().density;
        key.setColor(0xFFFFFFFF);
        black.setColor(0xFF1C1D2A);
        title.setColor(0xFFFFFFFF);
        title.setTextSize(34 * d);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextAlign(Paint.Align.CENTER);
        sub.setColor(0xD9FFFFFF);
        sub.setTextSize(15 * d);
        sub.setTextAlign(Paint.Align.CENTER);
        setClickable(true); // swallow touches while visible
    }

    /** For previews / tests: draw a fixed frame. */
    void setProgress(float progress) {
        p = progress;
        invalidate();
    }

    void play(final Runnable onDone) {
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                p = (Float) a.getAnimatedValue();
                invalidate();
                if (p >= 1f && onDone != null) onDone.run();
            }
        });
        anim.start();
    }

    private static float clamp(float x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }

    /** 0..1 ramp between a and b, eased. */
    private static float ramp(float x, float a, float b) {
        float t = clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, w, h,
                new int[]{getContext().getColor(R.color.hero_start), getContext().getColor(R.color.hero_center),
                        getContext().getColor(R.color.hero_end)}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float out = 1f - ramp(p, 0.82f, 1f);          // final fade-out of everything
        bg.setAlpha((int) (255 * out));
        c.drawRect(0, 0, w, h, bg);
        if (out <= 0) return;

        // keyboard
        float kbW = Math.min(w * 0.62f, 260 * d), keyW = kbW / WHITE_KEYS, kbH = keyW * 2.6f;
        float cx = w / 2f, kbTop = h * 0.46f;
        float appear = ramp(p, 0f, 0.18f);
        float kbLeft = cx - kbW / 2, rise = (1 - appear) * 24 * d;
        int a = (int) (255 * appear * out);

        // rising notes (drawn first so they emerge from behind the keys)
        for (int i = 0; i < HIT_KEY.length; i++) {
            float t = (p - HIT_AT[i]) / 0.4f;
            if (t <= 0 || t >= 1.2f) continue;
            float x0 = kbLeft + HIT_KEY[i] * keyW + keyW * 0.18f, x1 = x0 + keyW * 0.64f;
            float travel = kbH * 1.9f * Math.min(1f, t);
            float len = keyW * (1.2f + (i % 3) * 0.45f);
            float y1 = kbTop + rise - travel, y0 = y1 - len;
            float fade = clamp(1.25f - t);
            note.setColor((Part.COLORS[HIT_PART[i]] & 0x00FFFFFF) | ((int) (255 * fade * out) << 24));
            r.set(x0, y0, x1, y1);
            c.drawRoundRect(r, 4 * d, 4 * d, note);
        }

        for (int k = 0; k < WHITE_KEYS; k++) {
            float lit = 0;
            int part = 0;
            for (int i = 0; i < HIT_KEY.length; i++) {
                if (HIT_KEY[i] != k) continue;
                float t = (p - HIT_AT[i]) / 0.22f;
                if (t >= 0 && t < 1 && 1 - t > lit) {
                    lit = 1 - t;
                    part = HIT_PART[i];
                }
            }
            float x0 = kbLeft + k * keyW + d, x1 = kbLeft + (k + 1) * keyW - d;
            float press = lit > 0 ? 3 * d * lit : 0;
            r.set(x0, kbTop + rise + press, x1, kbTop + rise + kbH + press);
            int col = lit > 0 ? blend(0xFFFFFFFF, Part.COLORS[part], 0.8f * lit) : 0xFFFFFFFF;
            key.setColor((col & 0x00FFFFFF) | (a << 24));
            c.drawRoundRect(r, 5 * d, 5 * d, key);
            if (lit > 0) {
                glow.setColor((Part.COLORS[part] & 0x00FFFFFF) | ((int) (90 * lit * out) << 24));
                c.drawCircle((x0 + x1) / 2, kbTop + rise, keyW * 0.9f, glow);
            }
        }
        black.setAlpha(a);
        int[] blackAfter = {0, 1, 3, 4, 5}; // black keys after these white keys (C D _ F G A _)
        for (int k : blackAfter) {
            float bx = kbLeft + (k + 1) * keyW;
            r.set(bx - keyW * 0.3f, kbTop + rise, bx + keyW * 0.3f, kbTop + rise + kbH * 0.6f);
            c.drawRoundRect(r, 3 * d, 3 * d, black);
        }

        // title and subtitle
        float tIn = ramp(p, 0.3f, 0.6f);
        title.setAlpha((int) (255 * tIn * out));
        sub.setAlpha((int) (217 * ramp(p, 0.42f, 0.72f) * out));
        float ty = kbTop + rise + kbH + 64 * d + (1 - tIn) * 16 * d;
        c.drawText(getContext().getString(R.string.app_name), cx, ty, title);
        c.drawText(getContext().getString(R.string.splash_tagline), cx, ty + 28 * d, sub);
    }

    private static int blend(int a, int b, float k) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
