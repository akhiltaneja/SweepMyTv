package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextPaint;
import android.view.View;
import java.util.Locale;

/** Content and sizing for a card in the rotating header. */
final class HomeInsightCard extends View {
    private final HomeInsights.Card card;
    private final float dp;
    private final HeaderWidgetStyle style;
    private final String city, month, day, countdown, newsLabel;
    private int primary, secondary;

    HomeInsightCard(Context context, HomeInsights.Card card) {
        super(context);
        this.card = card;
        style = new HeaderWidgetStyle(context);
        dp = style.dp;
        String[] source = card.body.split(" · ", 2);
        newsLabel = source.length < 2 ? "AI, tech & markets"
                : source[0].endsWith(" " + source[1]) ? source[0] : card.body;
        city = card.kicker.replace("AIR · ", "").split(",", 2)[0];
        countdown = card.foot.split(" · ", 2)[0];
        java.util.regex.Matcher date = java.util.regex.Pattern.compile(".*?([0-9]{1,2}) +([A-Za-z]+)").matcher(card.body);
        if ("festival".equals(card.kind) && date.find()) {
            day = date.group(1);
            String name = date.group(2);
            month = name.substring(0, Math.min(3, name.length())).toUpperCase(Locale.ROOT);
        } else {
            day = "—";
            month = "";
        }
        setContentDescription(card.kicker + ", " + card.metric + ", " + card.body
                + (card.foot.isEmpty() ? "" : ", " + card.foot));
    }

    void setColors(int primary, int secondary) {
        if (this.primary == primary && this.secondary == secondary) return;
        this.primary = primary;
        this.secondary = secondary;
        invalidate();
    }

    HomeInsights.Card value() {
        return card;
    }

    int surfaceColor(int base) {
        if (!"air".equals(card.kind) || "—".equals(card.metric)) return base;
        return (base & 0xFF000000) | (TimeOfDay.blend(base, card.color, .16f) & 0x00FFFFFF);
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth() / dp;
        int inset = HeaderWidgetStyle.PADDING;
        int afterBadge = HeaderWidgetStyle.AFTER_BADGE;
        float badgeTextWidth = width - afterBadge - inset;
        switch (card.kind) {
            case "air":
                int accent = Color.luminance(primary) < .45f
                        ? TimeOfDay.blend(card.color, primary, .65f) : card.color;
                style.badge(canvas, accent);
                style.badgeText(canvas, card.metric, card.metric.length() > 3 ? 10 : 14, 23, accent);
                String status = card.foot.isEmpty() ? card.body : card.body + " · " + card.foot;
                style.pair(canvas, "AQI · " + city, status, afterBadge, badgeTextWidth, primary, secondary);
                break;
            case "news":
                headline(canvas, inset, width - 2 * inset);
                break;
            default:
                style.badge(canvas, primary);
                style.badgeText(canvas, month, 7, 15, secondary);
                style.badgeText(canvas, day, 11, 26, primary);
                String label = card.target > 0 ? "Next festival · " + countdown : "Local festivals";
                style.pair(canvas, label, card.metric, afterBadge, badgeTextWidth, primary, secondary);
                break;
        }
    }

    private void headline(Canvas canvas, float x, float width) {
        String title = card.metric;
        float size = HeaderWidgetStyle.VALUE_SIZE;
        TextPaint paint = style.paint(size, primary, true);
        if (paint.measureText(title) <= width * dp) {
            style.pair(canvas, newsLabel, title, x, width, primary, secondary);
            return;
        }
        style.line(canvas, newsLabel, x, 10, width, HeaderWidgetStyle.LABEL_SIZE, secondary, false);
        size = 10;
        paint = style.paint(size, primary, true);
        int end = paint.breakText(title, true, Math.max(0, width) * dp, null);
        int space = title.lastIndexOf(' ', end - 1);
        if (space > 0) end = space;
        style.line(canvas, title.substring(0, end), x, 21, width, size, primary, true);
        style.line(canvas, title.substring(end).trim(), x, 32, width, size, primary, true);
    }
}
