package com.sweepmytv.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * The picks row: the current pick is a large card at the left edge, the others small cards after it.
 * Moving to another pick springs the widths and slides the row, so the current one always sits at the left.
 * Each child is one pick's view (card on top, title and description below); the card is the child's first child.
 */
public class PicksRow extends ViewGroup {
    static final int LARGE_W = 440, SMALL_W = 134, CARD_H = 186, GAP = 18, LEFT = 40, TEXT_H = 48;

    private final float dp;
    private float[] curX = new float[0], curW = new float[0], toX, toW;
    private Spring[] springX = new Spring[0], springW = new Spring[0];
    private int anchor;
    private boolean animating;
    private Runnable onFrame;
    private long frameNanos;
    private final Runnable frame = this::step;

    public PicksRow(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
    }

    int anchor() { return anchor; }

    /** Called on every animation frame (the focus ring follows the large card). */
    void setOnFrame(Runnable r) { onFrame = r; }

    void setItems(List<View> views, int anchor) {
        removeCallbacks(frame);
        animating = false;
        removeAllViews();
        for (View v : views) addView(v);
        int n = views.size();
        curX = new float[n];
        curW = new float[n];
        springX = new Spring[n];
        springW = new Spring[n];
        this.anchor = Math.max(0, Math.min(anchor, n - 1));
        targets();
        for (int i = 0; i < n; i++) {
            springX[i] = new Spring(0.32f, 0.86f, toX[i], 0.1f);
            springW[i] = new Spring(0.32f, 0.86f, toW[i], 0.1f);
        }
        System.arraycopy(toX, 0, curX, 0, n);
        System.arraycopy(toW, 0, curW, 0, n);
        requestLayout();
    }

    void setAnchor(int a, boolean animate) {
        int n = getChildCount();
        if (n == 0) return;
        anchor = Math.max(0, Math.min(a, n - 1));
        targets();
        for (int i = 0; i < n; i++) {
            springX[i].target = toX[i];
            springW[i].target = toW[i];
            if (!animate) {
                springX[i].snap(toX[i]);
                springW[i].snap(toW[i]);
                curX[i] = toX[i];
                curW[i] = toW[i];
            }
        }
        if (!animate) {
            requestLayout();
            return;
        }
        if (!animating) {
            animating = true;
            frameNanos = 0;
            postOnAnimation(frame);
        }
    }

    /** One frame of the slide: springs keep their speed when you press again mid-slide. */
    private void step() {
        long now = System.nanoTime();
        float dt = frameNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - frameNanos) / 1e9f);
        frameNanos = now;
        boolean moving = false;
        for (int i = 0; i < springX.length && i < curX.length; i++) {
            moving |= springX[i].step(dt) | springW[i].step(dt);
            curX[i] = springX[i].value;
            curW[i] = springW[i].value;
        }
        // Only this row changes geometry. Requesting a root layout every frame also remeasured
        // all the F1/recommendation rows below it on low-powered TVs.
        layoutItems();
        if (onFrame != null) onFrame.run();
        if (moving) postOnAnimation(frame);
        else animating = false;
    }

    /** The large card of the current pick (for the focus ring), or null. */
    View anchorCard() {
        if (anchor >= getChildCount()) return null;
        View item = getChildAt(anchor);
        return item instanceof ViewGroup ? ((ViewGroup) item).getChildAt(0) : item;
    }

    private void targets() {
        int n = getChildCount();
        toX = new float[n];
        toW = new float[n];
        for (int i = 0; i < n; i++) {
            if (i < anchor) {
                toX[i] = LEFT - (anchor - i) * (SMALL_W + GAP);
                toW[i] = SMALL_W;
            } else if (i == anchor) {
                toX[i] = LEFT;
                toW[i] = LARGE_W;
            } else {
                toX[i] = LEFT + LARGE_W + GAP + (i - anchor - 1) * (SMALL_W + GAP);
                toW[i] = SMALL_W;
            }
        }
        for (int i = 0; i < n; i++) {
            View meta = getChildAt(i).findViewWithTag("meta");
            if (meta != null) meta.animate().alpha(i == anchor ? 1f : 0f).setDuration(200).start();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = Math.round((CARD_H + TEXT_H) * dp);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(Math.round(curW[i] * dp), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        animating = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) { layoutItems(); }

    private void layoutItems() {
        int height=Math.round((CARD_H+TEXT_H)*dp);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int width=Math.round(curW[i]*dp),x=Math.round(curX[i]*dp);
            if(c.getMeasuredWidth()!=width||c.isLayoutRequested())
                c.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
            c.layout(x, 0, x + width, height);
        }
        invalidate();
    }
}
