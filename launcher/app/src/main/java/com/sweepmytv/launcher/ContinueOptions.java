package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Display choices only: saved history, progress and deep links are never removed. */
final class ContinueOptions {
    static final String YOUTUBE = "continue_youtube", ARTWORK = "continue_title_artwork";
    static final String SHOW_TAB = "show_continue_tab";
    private static final String EXCLUDED_APPS = "continue_excluded_apps";
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile(
            "(?i)^(?:season\\s+|s)(\\d{1,4})\\s*(?:[·.:-]\\s*)?(?:episode\\s+|e)(\\d{1,4})(?!\\d)");
    private ContinueOptions() { }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home",Context.MODE_PRIVATE); }
    static boolean showTab(Context c) { return prefs(c).getBoolean(SHOW_TAB, true); }
    static boolean youtube(Context c) { return prefs(c).getBoolean(YOUTUBE,false); }
    static boolean titleArtwork(Context c) { return prefs(c).getBoolean(ARTWORK,false); }

    static Episode episode(Trending.Pick pick) {
        if (!RecentStore.TAB.equals(pick.tab)) return null;
        java.util.regex.Matcher match = EPISODE.matcher(pick.meta);
        if (!match.find()) return null;
        int number = Integer.parseInt(match.group(2));
        if (number == 0) return null;
        return new Episode(Integer.parseInt(match.group(1)), number, match.end());
    }

    static String captionMeta(Trending.Pick pick) {
        String meta = pick.meta;
        if (pick.service != null && !pick.service.isEmpty()) {
            String provider = " · " + pick.service;
            if (meta.equals(pick.service)) meta = "";
            else if (meta.endsWith(provider)) meta = meta.substring(0, meta.length() - provider.length());
        }
        if (!pick.savedResume) return meta;
        return meta.isEmpty() ? "Saved resume" : meta + " · Saved resume";
    }

    static String caption(Trending.Pick pick, Trending.TitleMetadata metadata) {
        Episode episode = episode(pick);
        String progress = episode == null
                ? captionMeta(pick)
                : episode.caption(pick, metadata == null ? 0 : metadata.seasonSize(episode.season));
        String kind = metadata == null ? pick.mediaKind : metadata.kind;
        String type = "tv".equals(kind) ? "Series" : "movie".equals(kind) ? "Film" : "";
        String genres = metadata == null || metadata.genres.isEmpty() ? pick.providerGenres : metadata.genres;
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        for (String part : new String[]{progress, type, genres}) if (!part.isEmpty()) parts.add(part);
        return String.join(" · ", parts);
    }

    static final class Episode {
        final int season, number, labelEnd;

        Episode(int season, int number, int labelEnd) {
            this.season = season;
            this.number = number;
            this.labelEnd = labelEnd;
        }

        String caption(Trending.Pick pick, int seasonSize) {
            String label = "S" + season + " · E" + number;
            if (seasonSize >= number) {
                int left = seasonSize - number;
                label += " · " + left + (left == 1 ? " Episode Left" : " Episodes Left");
            }
            String meta = captionMeta(pick);
            return meta.length() <= labelEnd ? label : label + meta.substring(labelEnd);
        }
    }

    static boolean hiddenFromPicker(String pkg) {
        return "com.google.android.videos".equals(pkg)
                || SpotifyPicks.PACKAGE.equals(pkg) || "com.spotify.music".equals(pkg)
                || "com.google.android.youtube.tvmusic".equals(pkg)
                || "com.google.android.apps.youtube.music".equals(pkg);
    }
    static boolean isYouTube(String pkg) {
        return "com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube".equals(pkg)
                || "com.amazon.firetv.youtube".equals(pkg);
    }
    static boolean include(Context c, String pkg) {
        if (pkg == null) return true;
        if (isYouTube(pkg)) return youtube(c);
        return !prefs(c).getStringSet(EXCLUDED_APPS, java.util.Collections.emptySet()).contains(pkg);
    }

    static void setIncluded(Context c, String pkg, boolean included) {
        SharedPreferences prefs = prefs(c);
        if (isYouTube(pkg)) {
            prefs.edit().putBoolean(YOUTUBE, included).apply();
            return;
        }
        java.util.Set<String> excluded = new java.util.HashSet<>(
                prefs.getStringSet(EXCLUDED_APPS, java.util.Collections.emptySet()));
        if (included) excluded.remove(pkg);
        else excluded.add(pkg);
        prefs.edit().putStringSet(EXCLUDED_APPS, excluded).apply();
    }
}
