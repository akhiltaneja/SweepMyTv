package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-screen overlay that draws the one focus ring: a white outline around the focused card or tile, moved
 * (with a small spring) by the activity, with a soft halo around it so it reads from across the room. In the day
 * theme a faint dark edge keeps it visible on light backgrounds; while an app is picked up for moving the ring is
 * thicker. The halo is six thin strokes fading outwards: no blur, so it costs nothing per frame.
 */
public class HighlightView extends View {
    final RectF rect = new RectF();
    private float radius;
    private boolean day, moving;
    private int accent;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final RectF tmp = new RectF();
    private static final float HALO_DP = 12f;
    private static final int HALO_BANDS = 6;

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0x33000000);
        edge.setStrokeWidth(dp);
        halo.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setWillNotDraw(false);
    }

    void setRing(RectF r, float radiusPx) {
        rect.set(r);
        radius = radiusPx;
        invalidate();
    }

    void setDay(boolean d) { day = d; invalidate(); }

    void setAccent(int color) { accent=color;ring.setColor(color==0?0xFFFFFFFF:color);invalidate(); }

    void setMoving(boolean m) { moving = m; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rect.isEmpty()) return;
        float w = (moving ? 5f : 4f) * dp;
        // The halo: thin bands side by side just outside the ring, each fainter than the last, so it fades out
        // smoothly over 12 dp (overlapping wide strokes showed as steps).
        int haloRgb = accent!=0?accent&0xFFFFFF:day?0x000000:0xFFFFFF, strongest = day ? 0x22 : 0x34;
        float band = HALO_DP / HALO_BANDS * dp;
        halo.setStrokeWidth(band);
        for (int i = 0; i < HALO_BANDS; i++) {
            halo.setColor((Math.round(strongest * (1 - i / (float) HALO_BANDS)) << 24) | haloRgb);
            float out = w + band * i + band / 2;
            tmp.set(rect);
            tmp.inset(-out, -out);
            canvas.drawRoundRect(tmp, radius + out, radius + out, halo);
        }
        ring.setStrokeWidth(w);
        tmp.set(rect);
        tmp.inset(-w / 2, -w / 2);
        canvas.drawRoundRect(tmp, radius + w / 2, radius + w / 2, ring);
        if (day) {
            tmp.inset(-w / 2 - dp / 2, -w / 2 - dp / 2);
            canvas.drawRoundRect(tmp, radius + w + dp / 2, radius + w + dp / 2, edge);
        }
    }
}
