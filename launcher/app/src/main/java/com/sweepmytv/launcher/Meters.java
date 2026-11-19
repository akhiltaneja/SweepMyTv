package com.sweepmytv.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional free capacity and uptime, displayed beside the rotating header card. */
public class Meters extends View {
    private static final int MIN_COLUMN_WIDTH = 42, COLUMN_GAP = 8;
    private final HeaderWidgetStyle style;
    private int primary, secondary;
    private final List<String> labels = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private final float dp;
    private String summary = "";

    public Meters(Context context, AttributeSet attrs) {
        super(context, attrs);
        style = new HeaderWidgetStyle(context);
        dp = style.dp;
    }

    void setColors(int primary, int secondary) {
        this.primary = primary;
        this.secondary = secondary;
        invalidate();
    }

    void update() {
        Context context = getContext();
        SharedPreferences prefs = HomeOptions.prefs(context);
        labels.clear();
        values.clear();
        if (HomeOptions.Metric.RAM.shown(prefs)) {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                add("RAM", capacity(info.availMem));
            }
        }
        if (HomeOptions.Metric.STORAGE.shown(prefs)) {
            try {
                long bytes = new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
                add("Storage", String.format(Locale.getDefault(), bytes < 1e9 ? "%.2f GB" : "%.1f GB", bytes / 1e9));
            } catch (RuntimeException ignored) { }
        }
        if (HomeOptions.Metric.UPTIME.shown(prefs)) {
            long minutes = SystemClock.elapsedRealtime() / 60_000;
            add("Uptime", minutes / 60 + "h " + minutes % 60 + "m");
        }
        List<String> descriptions = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            descriptions.add(label + " " + values.get(i) + ("Uptime".equals(label) ? "" : " free"));
        }
        summary = String.join(", ", descriptions);
        setContentDescription(summary);
        invalidate();
    }

    private void add(String label, String value) {
        labels.add(label);
        values.add(value);
    }

    String content() { return summary; }

    private static String capacity(long bytes) {
        return bytes < 1e9 ? Math.round(bytes / 1e6) + " MB" : String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9);
    }

    @Override protected void onMeasure(int width, int height) {
        float desiredWidth = 2 * HeaderWidgetStyle.PADDING;
        for (int i = 0; i < values.size(); i++) desiredWidth += columnWidth(i);
        desiredWidth += Math.max(0, values.size() - 1) * COLUMN_GAP;
        setMeasuredDimension(resolveSize(Math.round(desiredWidth * dp), width),
                resolveSize(Math.round(36 * dp), height));
    }

    private String columnLabel(int i) {
        String label = labels.get(i);
        return "Uptime".equals(label) ? label : label + " free";
    }

    private float columnWidth(int i) {
        float label = style.paint(HeaderWidgetStyle.LABEL_SIZE, secondary, false).measureText(columnLabel(i));
        float value = style.paint(HeaderWidgetStyle.VALUE_SIZE, primary, true).measureText(values.get(i));
        return Math.max(MIN_COLUMN_WIDTH, (float) Math.ceil(Math.max(label, value) / dp));
    }

    @Override protected void onDraw(Canvas canvas) {
        float x = HeaderWidgetStyle.PADDING;
        for (int i = 0; i < labels.size(); i++) {
            float column = columnWidth(i);
            float max = Math.min(column, getWidth() / dp - x - HeaderWidgetStyle.PADDING);
            style.pair(canvas, columnLabel(i), values.get(i), x, max, primary, secondary);
            x += column + COLUMN_GAP;
        }
    }
}
