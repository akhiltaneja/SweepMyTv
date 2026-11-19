package com.sweepmytv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class MarketCard extends View {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm",Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM",Locale.US);
    private final HeaderWidgetStyle style;
    private final NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);
    private final NumberFormat inr = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final NumberFormat points = NumberFormat.getNumberInstance(Locale.US);
    private int primary, secondary;
    private record QuoteFace(MarketQuotes.Asset asset, Drawable icon, String label, String price) {}
    private QuoteFace current, outgoing;
    private ValueAnimator slide;
    private float progress = 1f;

    public MarketCard(Context context,AttributeSet attrs) {
        super(context,attrs);
        style = new HeaderWidgetStyle(context);
        points.setMinimumFractionDigits(2);
        points.setMaximumFractionDigits(2);
    }

    void setColors(int primary, int secondary) {
        this.primary = primary;
        this.secondary = secondary;
        invalidate();
    }

    void bind(MarketQuotes.Asset asset, MarketQuotes.Quote quote, MarketQuotes.Unit unit, double displayPrice,
              boolean animate) {
        Drawable icon = current != null && current.asset == asset ? current.icon : getContext().getDrawable(asset.icon);
        boolean metal = asset.group == MarketQuotes.Group.METALS;
        String price, stamp;
        if (quote == null || !Double.isFinite(displayPrice)) {
            price = "—";
            stamp = quote == null ? "Quote unavailable" : "Exchange rate unavailable";
        } else {
            price = (unit == MarketQuotes.Unit.INR ? inr : unit == MarketQuotes.Unit.POINTS ? points : usd)
                    .format(displayPrice);
            java.time.ZonedDateTime observed = Instant.ofEpochMilli(quote.at).atZone(ZoneId.systemDefault());
            boolean today = java.time.LocalDate.now().equals(observed.toLocalDate());
            String time = (today ? HOUR : DAY).format(observed);
            String source = asset.feed == MarketQuotes.Feed.NASDAQ && !quote.session.isEmpty() ? quote.session : asset.source;
            stamp = source + " · " + time + (unit != asset.nativeUnit ? " · Converted at the daily exchange rate" : "");
        }
        String unitLabel = unit == MarketQuotes.Unit.POINTS ? "pts" : unit.name() + (metal ? "/oz" : "");
        String label = asset.title + " · " + ("—".equals(price) ? "Unavailable" : unitLabel);
        if (current != null && current.asset != asset) {
            stopSlide();
            if (animate && getWidth() > 0) {
                outgoing = current;
                progress = 0f;
                slide = ValueAnimator.ofFloat(0f, 1f);
                slide.setDuration(340);
                slide.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                slide.addUpdateListener(animation -> {
                    progress = (float) animation.getAnimatedValue();
                    if (progress >= 1f) outgoing = null;
                    invalidate();
                });
                slide.start();
            }
        }
        current = new QuoteFace(asset, icon, label, price);
        setContentDescription(asset.title + ", " + price + " "
                + (unit == MarketQuotes.Unit.POINTS ? "points" : unit.name())
                + (metal ? " per troy ounce" : "") + ", " + stamp);
        invalidate();
    }

    void stopSlide() {
        if (slide != null) slide.cancel();
        slide = null;
        outgoing = null;
        progress = 1f;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        stopSlide();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (current == null) return;
        if (outgoing == null) {
            drawQuote(canvas, current);
            return;
        }
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.translate(-getWidth() * progress, 0f);
        drawQuote(canvas, outgoing);
        canvas.translate(getWidth(), 0f);
        drawQuote(canvas, current);
        canvas.restore();
    }

    private void drawQuote(Canvas canvas, QuoteFace face) {
        int accent = Color.luminance(primary) < .45f
                ? TimeOfDay.blend(face.asset.color, primary, .56f) : face.asset.color;
        style.badge(canvas, accent);
        if (face.icon != null) {
            int left = Math.round((HeaderWidgetStyle.BADGE_LEFT + 4) * style.dp);
            int top = Math.round(7 * style.dp);
            int size = Math.round(22 * style.dp);
            face.icon.setBounds(left, top, left + size, top + size);
            face.icon.draw(canvas);
        }
        style.pair(canvas, face.label, face.price, HeaderWidgetStyle.AFTER_BADGE,
                getWidth() / style.dp - HeaderWidgetStyle.AFTER_BADGE - HeaderWidgetStyle.PADDING,
                primary, secondary);
    }
}
