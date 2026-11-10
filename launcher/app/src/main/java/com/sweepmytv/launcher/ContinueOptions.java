package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Display choices only: saved history, progress and deep links are never removed. */
final class ContinueOptions {
    static final String YOUTUBE = "continue_youtube", ARTWORK = "continue_title_artwork";
    private ContinueOptions() { }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home",Context.MODE_PRIVATE); }
    static boolean youtube(Context c) { return prefs(c).getBoolean(YOUTUBE,true); }
    static boolean titleArtwork(Context c) { return prefs(c).getBoolean(ARTWORK,false); }
    static boolean isYouTube(String pkg) {
        return "com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube".equals(pkg)
                || "com.amazon.firetv.youtube".equals(pkg);
    }
    static boolean include(Context c,String pkg) { return youtube(c)||!isYouTube(pkg); }
    static String summary(Context c) {
        return "YouTube " + (youtube(c)?"on":"off") + " · " + (titleArtwork(c)?"Title":"Still");
    }
}
