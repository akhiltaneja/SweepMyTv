package com.sweepmytv.launcher;

import android.content.Context;

/** Shows the same gradient and crossfade as Home, in a small preview. */
final class ThemePreview extends Backdrop {
    private static final int[] SKY_MINUTES = {320, 385, 720, 1080, 1290};
    private static final long HOLD_MS = 1600;
    private final HomeTheme theme;
    private final int[] glows;
    private final int frames;
    private int frame;
    private final Runnable advance = this::advanceFrame;

    ThemePreview(Context context, HomeTheme theme, int[] glows) {
        super(context, null);
        this.theme = theme;
        this.glows = glows;
        frames = theme == HomeTheme.SUNLIGHT ? SKY_MINUTES.length : glows.length;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        renderFrame();
    }

    private void renderFrame() {
        if (theme.usesGlow()) {
            if (frames > 0) setGlow(glows[frame]);
            return;
        }
        TimeOfDay.Palette palette = theme.fixed() ? theme.preview : TimeOfDay.palette(SKY_MINUTES[frame]);
        setTimeColors(palette.top, palette.mid, palette.bottom);
    }

    private void advanceFrame() {
        frame = (frame + 1) % frames;
        renderFrame();
        postDelayed(advance, HOLD_MS);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        removeCallbacks(advance);
        if (visible && frames > 1 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            postDelayed(advance, HOLD_MS);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(advance);
        super.onDetachedFromWindow();
    }
}
