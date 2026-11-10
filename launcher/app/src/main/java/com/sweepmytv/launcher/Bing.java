package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Talkies settings → Wallpaper → Bing picture of the day: the photo on Bing's home page, saved as files/bing.jpg and
 * fetched at most once every few hours while the home screen is in front. Off by default: it means asking Bing once a
 * day. Your own wallpaper.jpg wins over it. Bing allows its pictures as wallpaper only; Talkies skips a day whose
 * picture isn't. An idea from Plume (MIT).
 */
final class Bing {
    private static final String TAG = "GlassBing";
    static final String FILE = "bing.jpg";
    private static final long STALE_MS = 3 * 60 * 60_000L, RETRY_MS = 30 * 60_000L;
    private final Context c;
    private final SharedPreferences prefs;
    private final Executor background;
    private boolean busy;

    Bing(Context c, Executor background) {
        this.c = c.getApplicationContext();
        this.prefs = c.getSharedPreferences("bing", Context.MODE_PRIVATE);
        this.background = background;
    }

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, FILE);
    }

    /** "Kunětická hora Castle, Bohemia, Czechia (© Marek Saroch/Alamy)", for About. */
    String caption() { return prefs.getString("caption", null); }

    /** Fetches today's picture if the last look was long enough ago; {@code changed} runs on the main thread. */
    /** The network is back: a check that failed while it was down may run again now. */
    void networkBack() {
        if (prefs.getBoolean("failed", false)) prefs.edit().remove("checked").apply();
    }

    void refreshIfStale(Runnable changed) {
        long now = System.currentTimeMillis();
        if (busy || now - prefs.getLong("checked", 0) < (prefs.getBoolean("failed", false) ? RETRY_MS : STALE_MS)) return;
        busy = true;
        background.execute(() -> {
            boolean ok = false, fresh = false;
            try {
                String mkt = Locale.getDefault().toLanguageTag();
                JSONObject img = new JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=" + mkt))
                        .getJSONArray("images").getJSONObject(0);
                String day = img.optString("startdate");
                File f = file(c);
                if (f != null && img.optBoolean("wp", true) && !(day.equals(prefs.getString("day", "")) && f.isFile())) {
                    File tmp = new File(f.getPath() + ".part");
                    download("https://www.bing.com" + img.getString("urlbase") + "_1920x1080.jpg", tmp);
                    fresh = tmp.renameTo(f);
                    if (fresh) prefs.edit().putString("day", day).putString("caption", img.optString("copyright", null)).apply();
                }
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "Couldn't get Bing's picture: " + e);
            }
            prefs.edit().putLong("checked", System.currentTimeMillis()).putBoolean("failed", !ok).apply();
            final boolean changedNow = fresh;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                busy = false;
                if (changedNow) changed.run();
            });
        });
    }

    /** Turning it off: drop the picture, so the next time on fetches today's. */
    void clear() {
        File f = file(c);
        if (f != null) f.delete();
        prefs.edit().clear().apply();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        return h;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
    }
}
