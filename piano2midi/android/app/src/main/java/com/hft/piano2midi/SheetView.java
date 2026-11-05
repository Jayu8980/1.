package com.hft.piano2midi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.hft.piano2midi.core.sheet.SheetLayout;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows the engraved score as a column of pages with a playback cursor. Only the pages
 * visible in the parent scroll view are drawn, from a small bitmap cache, so moving the
 * cursor during playback stays cheap.
 */
public final class SheetView extends View {

    private static final int CACHE_PAGES = 3;

    private SheetLayout layout;
    private Typeface music;
    private final Paint shadow = new Paint(), cursor = new Paint(), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float gap;
    private float[] loc;
    private final LinkedHashMap<Integer, Bitmap> cache = new LinkedHashMap<Integer, Bitmap>(4, 0.75f, true);

    public SheetView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = getResources().getDisplayMetrics().density;
        gap = 10 * d;
        shadow.setColor(0x22000000);
        cursor.setColor(0x663F51B5);
        hint.setColor(0xFF8A8F9C);
        hint.setTextSize(13 * d);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    void setLayout(SheetLayout l, Typeface musicFont) {
        layout = l;
        music = musicFont;
        loc = null;
        clearCache();
        requestLayout();
        invalidate();
    }

    private void clearCache() {
        for (Bitmap b : cache.values()) b.recycle();
        cache.clear();
    }

    private float scale() {
        return layout == null || getWidth() == 0 ? 1 : getWidth() / layout.pageW;
    }

    /** Moves the cursor; returns the top of the cursor's system in view coordinates (or -1). */
    float setPosition(double sec) {
        if (layout == null) return -1;
        loc = layout.locate(sec);
        invalidate();
        if (loc == null) return -1;
        float s = scale();
        return loc[0] * (layout.pageH * s + gap) + loc[2] * s;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w != ow) clearCache();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h;
        if (layout == null) {
            h = (int) (200 * getResources().getDisplayMetrics().density);
        } else {
            float s = w / layout.pageW;
            h = (int) Math.ceil(layout.pageCount() * (layout.pageH * s + gap));
        }
        setMeasuredDimension(w, h);
    }

    private Bitmap pageBitmap(int p, int w, int ph) {
        Bitmap b = cache.get(p);
        if (b != null) return b;
        b = Bitmap.createBitmap(w, ph, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        float s = w / layout.pageW;
        c.scale(s, s);
        layout.drawPage(new AndroidGfx(c, music), p);
        cache.put(p, b);
        if (cache.size() > CACHE_PAGES) {
            Iterator<Map.Entry<Integer, Bitmap>> it = cache.entrySet().iterator();
            Map.Entry<Integer, Bitmap> eldest = it.next();
            it.remove();
            eldest.getValue().recycle();
        }
        return b;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layout == null) {
            c.drawText(getContext().getString(R.string.sheet_empty), getWidth() / 2f, getHeight() / 2f, hint);
            return;
        }
        int w = getWidth();
        float s = scale();
        int ph = (int) (layout.pageH * s);
        float vTop = 0, vBot = getHeight();
        if (getParent() instanceof View) {
            View p = (View) getParent();
            vTop = p.getScrollY() - getTop();
            vBot = vTop + p.getHeight();
        }
        for (int p = 0; p < layout.pageCount(); p++) {
            float top = p * (ph + gap);
            if (top + ph < vTop || top > vBot) continue;
            c.drawRect(2, top + 2, w + 2, top + ph + 2, shadow);
            c.drawBitmap(pageBitmap(p, w, ph), 0, top, bmp);
            if (loc != null && (int) loc[0] == p) {
                c.drawRect(loc[1] * s - 2, top + loc[2] * s, loc[1] * s + 2, top + loc[3] * s, cursor);
            }
        }
    }
}
