package com.sweepmytv.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.util.Arrays;

/** Finds distinct colours in a small sample of the original artwork. */
final class ArtworkColour {
    static final long STEP_MS = 15 * 60_000L;
    private static final int[] EMPTY = new int[0];

    static int[] from(File file) {
        Bitmap sample = CardArtwork.decode(file, 48, 48, false, true);
        if (sample == null) return EMPTY;
        try {
            int[] pixels = new int[sample.getWidth() * sample.getHeight()];
            sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
            return palette(pixels);
        } finally {
            sample.recycle();
        }
    }

    static int[] palette(int[] pixels) {
        float[] scores = new float[25];
        int[] reds = new int[25], greens = new int[25], blues = new int[25], counts = new int[25];
        float[] hsv = new float[3];
        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 128) continue;
            Color.colorToHSV(pixel, hsv);
            if (hsv[2] < .08f) continue;
            int bucket = hsv[1] < .12f ? 24 : Math.round(hsv[0] / 15f) % 24;
            scores[bucket] += .35f + hsv[1] * .65f;
            reds[bucket] += Color.red(pixel);
            greens[bucket] += Color.green(pixel);
            blues[bucket] += Color.blue(pixel);
            counts[bucket]++;
        }
        int[] colours = new int[3];
        float minimum = 0;
        int found = 0;
        for (; found < colours.length; found++) {
            int best = 0;
            for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
            if (scores[best] == 0 || scores[best] < minimum) break;
            if (found == 0) minimum = scores[best] * .15f;
            int count = counts[best];
            colours[found] = Color.rgb(reds[best] / count, greens[best] / count, blues[best] / count);
            scores[best] = 0;
            if (best < 24) {
                scores[(best + 1) % 24] = 0;
                scores[(best + 23) % 24] = 0;
            }
        }
        return found == 0 ? EMPTY : Arrays.copyOf(colours, found);
    }

    static int at(int[] colours, int fallback, long step) {
        if (colours != null && colours.length > 1) return colours[(int) Math.floorMod(step, (long) colours.length)];
        int colour = colours == null || colours.length == 0 ? fallback : colours[0];
        if (colour == 0) return 0;
        switch ((int) Math.floorMod(step, 3L)) {
            case 1: return TimeOfDay.blend(colour, Color.BLACK, .24f);
            case 2: return TimeOfDay.blend(colour, Color.WHITE, .17f);
            default: return colour;
        }
    }

    private ArtworkColour() {}
}
