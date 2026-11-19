package com.sweepmytv.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;

/** A bounded circular title reel. Logical titles repeat; views and artwork caches do not grow. */
public class PicksRow extends ViewGroup {
    static final int LARGE_W = 440, SMALL_W = 134, CARD_H = 186, GAP = 18, LEFT = 40;
    private final float dp;
    private int smallWidth = SMALL_W, expandedWidth = LARGE_W, cardHeight = CARD_H, gap = GAP;
    private int logicalCount, selected, cycle;
    private Spring[] widths = new Spring[0];
    private final Spring camera = new Spring(0.32f, 0.86f, 0, 0.1f);
    private boolean animating;
    private Runnable onFrame;
    private long frameNanos;
    private final Runnable frame = this::step;

    public PicksRow(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
    }

    int anchor() { return logicalCount == 0 ? 0 : selected % logicalCount; }
    int selectedIndex() { return selected; }
    View anchorItem() { return selected < getChildCount() ? getChildAt(selected) : null; }
    Spring camera() { return camera; }

    /** Cover the viewport plus recycling guards, with a fixed number of repeated views. */
    static int viewCount(int titles, int viewport, int width, int gap) {
        if (titles < 2) return titles;
        int needed = (int) Math.ceil(viewport / (double) (width + gap)) + 3;
        return Math.max(titles, ((needed + titles - 1) / titles) * titles);
    }

    void fixedCards(int width, int height) {
        smallWidth = expandedWidth = width;
        cardHeight = height;
    }

    static int expandedWidthFor(int width, int height) {
        if (width <= 0 || height <= 0) return Math.round(CARD_H * 16f / 9);
        return Math.max(1, Math.min(LARGE_W, Math.round(CARD_H * (float) width / height)));
    }

    void setExpandedWidth(int width, boolean animate) {
        if (width == expandedWidth) return;
        expandedWidth = width;
        targets(animate);
    }

    void setOnFrame(Runnable r) { onFrame = r; }
    void setItems(List<View> views, int anchor) { setItems(views, views.size(), anchor); }

    void setItems(List<View> views, int titles, int anchor) {
        removeCallbacks(frame);
        animating = false;
        removeAllViews();
        for (View view : views) addView(view);
        logicalCount = titles;
        selected = Math.max(0, Math.min(anchor, titles - 1));
        cycle = 0;
        widths = new Spring[views.size()];
        for (int i = 0; i < widths.length; i++)
            widths[i] = new Spring(0.32f, 0.86f, i == selected ? expandedWidth : smallWidth, 0.1f);
        camera.snap(selected * (smallWidth + gap));
        requestLayout();
    }

    void setAnchor(int anchor, boolean animate) { setAnchor(anchor, animate, 0); }

    /** Direction disambiguates a two-title reel and keeps boundary movement going the same way. */
    void setAnchor(int anchor, boolean animate, int direction) {
        if (logicalCount == 0) return;
        int next = Math.max(0, Math.min(anchor, logicalCount - 1));
        int delta = next - anchor();
        if (direction != 0 && logicalCount > 1) delta = direction;
        else if (Math.abs(delta) > logicalCount / 2) delta -= Integer.signum(delta) * logicalCount;
        int position = selected + delta;
        cycle += Math.floorDiv(position, getChildCount());
        selected = Math.floorMod(position, getChildCount());
        targets(animate);
    }

    private float targetPeriod() {
        return getChildCount() * (smallWidth + gap) + expandedWidth - smallWidth;
    }

    private void targets(boolean animate) {
        for (int i = 0; i < widths.length; i++) {
            widths[i].target = i == selected ? expandedWidth : smallWidth;
            if (!animate) widths[i].snap(widths[i].target);
        }
        camera.target = selected * (smallWidth + gap) + cycle * targetPeriod();
        if (!animate) {
            camera.snap(camera.target);
            rebase();
            layoutItems();
            requestLayout();
        } else if (!animating) {
            animating = true;
            frameNanos = 0;
            postOnAnimation(frame);
        }
    }

    private void rebase() {
        // Only after settling: rebasing mid-resize would change the camera's phase.
        camera.snap(selected * (smallWidth + gap));
        cycle = 0;
    }

    private void step() {
        long now = System.nanoTime();
        float dt = frameNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - frameNanos) / 1e9f);
        frameNanos = now;
        boolean moving = camera.step(dt);
        for (Spring width : widths) moving |= width.step(dt);
        if (!moving) rebase();
        layoutItems();
        if (onFrame != null) onFrame.run();
        if (moving) postOnAnimation(frame);
        else animating = false;
    }

    View anchorCard() {
        View item = anchorItem();
        return item instanceof ViewGroup ? ((ViewGroup) item).getChildAt(0) : item;
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED
                ? getResources().getDisplayMetrics().widthPixels : MeasureSpec.getSize(w);
        int height = Math.round(cardHeight * dp);
        for (int i = 0; i < getChildCount(); i++)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(Math.round(widths[i].value * dp), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        animating = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { layoutItems(); }

    private void layoutItems() {
        float period = 0;
        for (Spring width : widths) period += width.value + gap;
        if (period <= 0) return;
        float base = 0;
        int height = Math.round(cardHeight * dp);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float position = base - camera.value;
            // Recycle fully to the left; the next cycle is already laid out at the right.
            if (logicalCount > 1)
                position -= (float) Math.floor((position + widths[i].value + gap) / period) * period;
            int width = Math.round(widths[i].value * dp), x = Math.round((LEFT + position) * dp);
            if (child.getMeasuredWidth() != width || child.isLayoutRequested())
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            child.layout(x, 0, x + width, height);
            base += widths[i].value + gap;
        }
        invalidate();
    }
}
