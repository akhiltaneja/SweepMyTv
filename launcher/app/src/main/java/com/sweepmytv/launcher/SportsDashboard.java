package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A single row of cricket scorecards designed for reading at TV distance. */
final class SportsDashboard {
    private static final int LIMIT = 3;
    private static final int CARD_HEIGHT = 184, INSET = 40, GAP = 14;
    static final int BODY_HEIGHT = CARD_HEIGHT + 16;
    static final int SECTION_HEIGHT = BODY_HEIGHT + 38;

    private SportsDashboard() { }

    static List<Cricket.Match> fixtures(List<Cricket.Match> matches) {
        List<Cricket.Match> selected = new ArrayList<>(matches);
        selected.removeIf(match -> match.ended);
        selected.sort(Comparator.comparingInt(SportsDashboard::priority).thenComparingLong(match -> match.scheduledAt));
        return new ArrayList<>(selected.subList(0, Math.min(LIMIT, selected.size())));
    }

    private static int priority(Cricket.Match match) {
        String series = clean(match.league).toLowerCase(Locale.ROOT);
        boolean tour = match.intl || series.contains(" tour of ") || series.contains("world cup");
        if (match.live) return tour ? 0 : 2;
        if (match.paused) return tour ? 1 : 3;
        return tour ? 4 : 5;
    }

    static int[] cell(int index, int screenWidth) {
        int width = (screenWidth - 2 * INSET - (LIMIT - 1) * GAP) / LIMIT;
        return new int[]{INSET + index * (width + GAP), 8, width, CARD_HEIGHT};
    }

    static String status(Cricket.Match match) {
        if (match.cachedAt > 0) return "Cached";
        if (match.live) return "Live";
        if (match.paused) return clean(match.phase).isEmpty() ? "In progress" : match.phase;
        return "Upcoming";
    }

    static String detail(Cricket.Match match) {
        if (match == null) return "Live scores and upcoming fixtures will appear here.";
        String detail = !match.live && !match.paused && match.start != null ? match.start : clean(match.status);
        return match.cachedAt > 0 ? "Cached · " + detail : detail;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    static final class MatchCard extends View {
        private static final int SURFACE = 0xFF191E28, DIVIDER = 0xFF313846;
        private static final int TEXT = 0xFFF5F7FB, MUTED = 0xFFB6BFCD;
        private static final int LIVE = 0xFFFF8984, PAUSED = 0xFFF6C685, UPCOMING = 0xFFAEC7F4;
        private static final float EDGE = 14;
        private final Cricket.Match match;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF area = new RectF(), badge = new RectF(), logoBounds = new RectF();
        private final Typeface regular, bold;
        private final float dp;
        private final int accent;
        private final String state, series, subtitle, summary;
        private StaticLayout seriesText, summaryText;
        private Bitmap firstLogo, secondLogo;

        MatchCard(Context context, Cricket.Match match, float dp) {
            super(context);
            this.match = match;
            this.dp = dp;
            Typeface outfit = getResources().getFont(R.font.outfit);
            if (Build.VERSION.SDK_INT >= 28) {
                regular = Typeface.create(outfit, 450, false);
                bold = Typeface.create(outfit, 650, false);
            } else {
                regular = outfit;
                bold = Typeface.create(outfit, Typeface.BOLD);
            }
            if (match == null) {
                accent = MUTED;
                state = "";
                series = "No matches to show";
                subtitle = "";
                summary = detail(null);
                setContentDescription("No cricket match data. " + summary);
            } else {
                accent = match.cachedAt > 0 ? MUTED : match.live ? LIVE : match.paused ? PAUSED : UPCOMING;
                state = status(match);
                series = clean(match.league).isEmpty() ? match.name : match.league;
                List<String> meta = new ArrayList<>(3);
                for (String part : new String[]{match.kind, match.venue, match.session})
                    if (!clean(part).isEmpty()) meta.add(part);
                subtitle = String.join(" · ", meta);
                String detail = detail(match);
                summary = detail.isEmpty() || detail.equalsIgnoreCase(state) ? subtitle : detail;
                String scores = clean(match.s1).isEmpty() && clean(match.s2).isEmpty() ? ""
                        : clean(match.s1) + " to " + clean(match.s2) + ". ";
                setContentDescription(state + ". " + series + ". " + match.name + ". " + scores + summary);
            }
        }

        void setLogo(boolean first, Bitmap bitmap) {
            if (first) firstLogo = bitmap;
            else secondLogo = bitmap;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            area.set(0, 0, width / dp, height / dp);
            float inner = area.width() - 2 * EDGE;
            if (inner <= 0) return;
            style(11, accent, bold);
            float badgeWidth = state.isEmpty() ? 0 : Math.min(inner * .49f, paint.measureText(state) + 16);
            badge.set(area.right - EDGE - badgeWidth, 13, area.right - EDGE, 34);
            seriesText = paragraph(series, 15, TEXT, bold, inner - (badgeWidth == 0 ? 0 : badgeWidth + 8));
            summaryText = paragraph(summary, 13, match != null && !match.live && !match.paused ? UPCOMING : MUTED, regular, inner);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(dp, dp);
            paint.setColor(SURFACE);
            canvas.drawRoundRect(area, 12, 12, paint);
            paint.setColor(DIVIDER);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(.5f, .5f, area.right - .5f, area.bottom - .5f, 12, 12, paint);
            paint.setStyle(Paint.Style.FILL);
            if (seriesText == null) { canvas.restore(); return; }

            drawParagraph(canvas, seriesText, 13);
            if (match == null) {
                drawParagraph(canvas, summaryText, 45);
                canvas.restore();
                return;
            }

            paint.setColor((accent & 0xFFFFFF) | 0x22000000);
            canvas.drawRoundRect(badge, 6, 6, paint);
            text(canvas, state, badge.left + 8, 27, badge.width() - 16, 11, accent, bold);
            text(canvas, subtitle, EDGE, 61, area.right - 2 * EDGE, 12, MUTED, regular);
            team(canvas, firstLogo, match.full1, match.t1, match.s1, 88);
            team(canvas, secondLogo, match.full2, match.t2, match.s2, 125);
            paint.setColor(DIVIDER);
            canvas.drawRect(EDGE, 145, area.right - EDGE, 146, paint);
            drawParagraph(canvas, summaryText, 151);
            canvas.restore();
        }

        private void team(Canvas canvas, Bitmap logo, String fullName, String shortName, String value, float baseline) {
            float left = EDGE;
            if (logo != null) {
                float scale = Math.min(22f / logo.getWidth(), 22f / logo.getHeight());
                float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
                logoBounds.set(left + (22 - w) / 2, baseline - 7 - h / 2,
                        left + (22 + w) / 2, baseline - 7 + h / 2);
                paint.setColor(TEXT);
                canvas.drawBitmap(logo, null, logoBounds, paint);
                left += 30;
            }
            String score = clean(value);
            int split = score.indexOf(" (");
            String overs = split < 0 ? "" : score.substring(split + 1);
            if (split >= 0) score = score.substring(0, split);
            float right = area.right - EDGE;
            float maxScore = (right - left) * .54f;
            float scoreWidth = 0;
            if (!score.isEmpty()) {
                style(17, TEXT, bold);
                float ideal = paint.measureText(score);
                float size = ideal <= maxScore ? 17 : Math.max(12, 17 * maxScore / ideal);
                style(size, TEXT, bold);
                scoreWidth = Math.min(maxScore, paint.measureText(score));
                text(canvas, score, right - scoreWidth, baseline, scoreWidth + 1, size, TEXT, bold);
                if (!overs.isEmpty()) {
                    style(10, MUTED, regular);
                    float oversWidth = Math.min(maxScore, paint.measureText(overs));
                    text(canvas, overs, right - oversWidth, baseline + 13, oversWidth + 1, 10, MUTED, regular);
                }
            }
            float nameWidth = right - left - (scoreWidth == 0 ? 0 : scoreWidth + 8);
            String name = clean(fullName).isEmpty() ? clean(shortName) : fullName;
            text(canvas, name, left, baseline, nameWidth, 15, TEXT, bold);
        }

        private void drawParagraph(Canvas canvas, StaticLayout layout, float top) {
            canvas.save();
            canvas.translate(EDGE, top);
            layout.draw(canvas);
            canvas.restore();
        }

        private StaticLayout paragraph(String value, int size, int colour, Typeface typeface, float width) {
            TextPaint style = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            style.setTypeface(typeface);
            style.setFakeBoldText(typeface == bold);
            style.setTextSize(size);
            style.setColor(colour);
            return StaticLayout.Builder.obtain(value, 0, value.length(), style, Math.max(1, (int) width))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).build();
        }

        private void style(float size, int colour, Typeface typeface) {
            paint.setTypeface(typeface);
            paint.setFakeBoldText(typeface == bold);
            paint.setTextSize(size);
            paint.setColor(colour);
        }

        private void text(Canvas canvas, String value, float x, float baseline, float width, float size,
                          int colour, Typeface typeface) {
            if (value.isEmpty() || width <= 0) return;
            style(size, colour, typeface);
            canvas.drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, baseline, paint);
        }
    }
}
