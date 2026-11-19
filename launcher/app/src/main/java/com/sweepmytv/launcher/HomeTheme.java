package com.sweepmytv.launcher;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

enum HomeTheme {
    SUNLIGHT("Sunlight", "Changes with the sky"),
    APP_COLOURS("App colours", "Follows titles and apps"),
    MIDNIGHT("Midnight", "Deep navy", 0xFF10182E, 0xFF23395D, 0xFF101C33, 0xFFADC7FF),
    FOREST("Forest", "Pine green", 0xFF101E1A, 0xFF25463A, 0xFF142B23, 0xFF9EE4BC),
    EMBER("Ember", "Warm copper", 0xFF251917, 0xFF593926, 0xFF321F1A, 0xFFFAC088),
    GRAPHITE("Graphite", "Neutral charcoal", 0xFF191A20, 0xFF373944, 0xFF202129, 0xFFD3D5E2),
    BLACK_GLOW("Black glow", "Near-black with app and title colours",
            0xFF08090B, 0xFF090A0C, 0xFF000000, 0xFFF5F5F7);

    static final String FIXED_PREF = "fixed_home_theme";
    static final String RANDOMISE_SINCE = "randomise_themes_since";
    static final String DOCK_SURFACE = "dock_surface";
    static final long HOUR_MS = 3_600_000L;

    final String label, description;
    final TimeOfDay.Palette preview;

    HomeTheme(String label, String description) {
        this(label, description, null);
    }

    HomeTheme(String label, String description, int top, int mid, int bottom, int accent) {
        this(label, description, new TimeOfDay.Palette(top, mid, bottom, accent, label));
    }

    HomeTheme(String label, String description, TimeOfDay.Palette preview) {
        this.label = label;
        this.description = description;
        this.preview = preview;
    }

    static HomeTheme selected(SharedPreferences prefs) {
        if (prefs.getBoolean(TimeOfDay.PREF, false)) return SUNLIGHT;
        String saved = prefs.getString(FIXED_PREF, "");
        for (HomeTheme theme : values()) {
            if (theme.fixed() && theme.name().equals(saved)) return theme;
        }
        return APP_COLOURS;
    }

    boolean fixed() {
        return preview != null;
    }

    boolean usesGlow() {
        return this == APP_COLOURS || this == BLACK_GLOW;
    }

    TimeOfDay.Palette palette(long hour) {
        if (!fixed() || usesGlow()) return preview;
        switch ((int) Math.floorMod(hour, 4L)) {
            case 1: return new TimeOfDay.Palette(preview.top, TimeOfDay.blend(preview.mid, preview.top, .38f),
                    TimeOfDay.blend(preview.bottom, preview.mid, .45f), preview.accent, label);
            case 2: return new TimeOfDay.Palette(TimeOfDay.blend(preview.top, preview.mid, .47f),
                    TimeOfDay.blend(preview.mid, preview.accent, .08f), preview.bottom, preview.accent, label);
            case 3: return new TimeOfDay.Palette(TimeOfDay.blend(preview.top, preview.bottom, .45f),
                    TimeOfDay.blend(preview.mid, preview.bottom, .25f), preview.mid, preview.accent, label);
            default: return preview;
        }
    }

    static final class Rotation {
        private long started = Long.MIN_VALUE;
        private HomeTheme[] order;

        /** A null slot is reserved for Custom wallpaper. The order survives launcher restarts. */
        HomeTheme at(long since, long now) {
            if (order == null || started != since) {
                HomeTheme[] themes = values();
                order = Arrays.copyOf(themes, themes.length + 1);
                Collections.shuffle(Arrays.asList(order), new Random(since));
                started = since;
            }
            long hours = Math.max(0, now - since) / HOUR_MS;
            return order[(int) (hours % order.length)];
        }
    }

    void save(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(TimeOfDay.PREF, this == SUNLIGHT);
        if (fixed()) editor.putString(FIXED_PREF, name());
        else editor.remove(FIXED_PREF);
        editor.apply();
    }
}
