package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/** Typography and spacing shared by the three home header widgets. Coordinates are in dp. */
final class HeaderWidgetStyle {
    static final int RADIUS = 11;
    static final int PADDING = 9;
    static final int LABEL_BASELINE = 13;
    static final int VALUE_BASELINE = 27;
    static final int BADGE_LEFT = 6;
    static final int BADGE_WIDTH = 30;
    static final int AFTER_BADGE = 43;
    static final float LABEL_SIZE = 8.5f;
    static final float VALUE_SIZE = 11.5f;

    final float dp;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    HeaderWidgetStyle(Context context) {
        dp = context.getResources().getDisplayMetrics().density;
        Typeface font = context.getResources().getFont(R.font.outfit);
        label.setTypeface(font);
        label.setFontVariationSettings("'wght' 500");
        value.setTypeface(font);
        value.setFontVariationSettings("'wght' 650");
    }

    TextPaint paint(float size, int color, boolean strong) {
        TextPaint paint = strong ? value : label;
        paint.setTextSize(size * dp);
        paint.setColor(color);
        return paint;
    }

    void line(Canvas canvas, String text, float x, float y, float width, float size, int color, boolean strong) {
        TextPaint paint = paint(size, color, strong);
        CharSequence fit = TextUtils.ellipsize(text, paint, Math.max(0, width) * dp, TextUtils.TruncateAt.END);
        canvas.drawText(fit, 0, fit.length(), x * dp, y * dp, paint);
    }

    void pair(Canvas canvas, String label, String value, float x, float width, int primary, int secondary) {
        line(canvas, label, x, LABEL_BASELINE, width, LABEL_SIZE, secondary, false);
        line(canvas, value, x, VALUE_BASELINE, width, VALUE_SIZE, primary, true);
    }

    void badge(Canvas canvas, int color) {
        canvas.drawRoundRect(BADGE_LEFT * dp, 5 * dp, (BADGE_LEFT + BADGE_WIDTH) * dp, 31 * dp,
                7 * dp, 7 * dp, paint(LABEL_SIZE, (color & 0xFFFFFF) | 0x24000000, true));
    }

    void badgeText(Canvas canvas, String text, float size, float baseline, int color) {
        TextPaint paint = paint(size, color, true);
        float center = (BADGE_LEFT + BADGE_WIDTH / 2f) * dp;
        canvas.drawText(text, center - paint.measureText(text) / 2, baseline * dp, paint);
    }
}
