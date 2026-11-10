package com.sweepmytv.launcher;

import android.graphics.Bitmap;

/**
 * Keeps text readable over a wallpaper (your own or Bing's). The photo is measured once, in strips from top to bottom,
 * and each strip gets just enough of the theme's veil (the scrim) for text to stand out at 4.5:1 contrast, the
 * WCAG level for body text, on nine in ten of its pixels: white text needs the strip dark enough, dark text light
 * enough. A dark photo gets little or no veil in the dark look; a bright one, in the light look. With Appearance on
 * Auto, Talkies picks whichever look the photo needs less veil for.
 */
final class Legibility {
    /** Gradient stops, top to bottom, evenly spaced. */
    static final int STOPS = 9;
    private static final int W = 48, H = 27;
    private static final float WHITE_MAX = 0.183f, DARK_MIN = 0.229f;  // 4.5:1 against #F5F5F7 / #1D1D1F
    private static final float[] LINEAR = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** Veil alpha (0–1) per stop for white text over the dark scrim, and for dark text over the light one. */
    final float[] forDark, forLight;

    private Legibility(float[] forDark, float[] forLight) {
        this.forDark = forDark;
        this.forLight = forLight;
    }

    /** True when the photo suits dark text (the light look) better than white. */
    boolean prefersLight() {
        float d = 0, l = 0;
        for (int i = 0; i < STOPS; i++) { d += forDark[i]; l += forLight[i]; }
        return l < d;
    }

    static Legibility of(Bitmap photo, int darkScrimRgb, int lightScrimRgb) {
        Bitmap small = Bitmap.createScaledBitmap(photo, W, H, true);
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        if (small != photo) small.recycle();
        return new Legibility(stops(px, darkScrimRgb, true), stops(px, lightScrimRgb, false));
    }

    /**
     * The veil each stop needs. The gradient between two stops never drops below the smaller of them, so each stop
     * takes the larger need of the two strips beside it, and every strip is covered by both of its ends.
     */
    private static float[] stops(int[] px, int scrim, boolean whiteText) {
        int segs = STOPS - 1;
        float[] need = new float[segs];
        for (int s = 0; s < segs; s++) {
            int y0 = s * H / segs, y1 = Math.max(y0 + 1, (s + 1) * H / segs);
            need[s] = veil(px, y0, y1, scrim, whiteText);
        }
        float[] out = new float[STOPS];
        for (int i = 0; i < STOPS; i++) out[i] = Math.max(i > 0 ? need[i - 1] : 0, i < segs ? need[i] : 0);
        return out;
    }

    /** The least veil (in 5 % steps, at most 90 %) that makes this strip readable. */
    private static float veil(int[] px, int y0, int y1, int scrim, boolean whiteText) {
        int n = (y1 - y0) * W;
        float[] lum = new float[n];
        int sr = (scrim >> 16) & 0xFF, sg = (scrim >> 8) & 0xFF, sb = scrim & 0xFF;
        for (int step = 0; step <= 18; step++) {
            float a = step * 0.05f;
            int k = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < W; x++) {
                    int c = px[y * W + x];
                    int r = Math.round(((c >> 16) & 0xFF) * (1 - a) + sr * a);
                    int g = Math.round(((c >> 8) & 0xFF) * (1 - a) + sg * a);
                    int b = Math.round((c & 0xFF) * (1 - a) + sb * a);
                    lum[k++] = 0.2126f * LINEAR[r] + 0.7152f * LINEAR[g] + 0.0722f * LINEAR[b];
                }
            }
            java.util.Arrays.sort(lum);
            boolean ok = whiteText ? lum[(int) (n * 0.9f)] <= WHITE_MAX : lum[(int) (n * 0.1f)] >= DARK_MIN;
            if (ok) return a;
        }
        return 0.9f;
    }
}
