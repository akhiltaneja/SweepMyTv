package com.sweepmytv.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public Spotify playlist previews. No user data or playback credentials are requested. */
final class SpotifyPicks implements AutoCloseable {
    static final String PACKAGE = "com.spotify.tv.android";
    static final String KEY = "sweep_spotify";
    private static final String BASE = "https://open.spotify.com";
    private static final long REFRESH_MS = 24 * 3600_000L;
    private static final long RETRY_MS = 15 * 60_000L;
    private static final String[][] GLOBAL = {
            {"37i9dQZF1DXcBWIGoYBM5M", "Today’s Top Hits"},
            {"37i9dQZF1DX4JAvHpjipBk", "New Music Friday"},
            {"37i9dQZF1DWXRqgorJj26U", "Rock Classics"},
            {"37i9dQZF1DX4wta20PHgwo", "Late Night Jazz"},
            {"37i9dQZF1DX0XUsuxWHRQd", "RapCaviar"},
            {"37i9dQZF1DX4WYpdgoIcn6", "Chill Hits"}
    };
    private static final String[][] INDIA = {
            {"37i9dQZEVXbLZ52XmnySJg", "Top 50 - India"},
            {"37i9dQZF1DX0ieekvzt1Ic", "Hot Hits India"},
            {"37i9dQZF1DXbVhgADFy3im", "Trending Now India"}
    };

    interface Listener { void onMusic(Channels.Channel row); }

    private final Context context;
    private final File cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running, closed;
    private long retryAfter;
    private String market = "";

    SpotifyPicks(Context context) {
        this.context = context.getApplicationContext();
        File external = this.context.getExternalFilesDir(null);
        cache = new File(external == null ? this.context.getCacheDir() : external, "spotify");
    }

    static Channels.Channel row() {
        Channels.Channel row = new Channels.Channel();
        row.key = KEY;
        row.pkg = PACKAGE;
        row.title = "Spotify · Music picks";
        row.shown = true;
        return row;
    }

    static List<String[]> playlists(String country) {
        List<String[]> playlists = new ArrayList<>();
        if ("IN".equals(country)) java.util.Collections.addAll(playlists, INDIA);
        for (String[] playlist : GLOBAL) {
            if (playlists.size() == 7) break;
            playlists.add(playlist);
        }
        return playlists;
    }

    static Trending.Pick pick(String id, String name, File cover) {
        String link = BASE + "/playlist/" + id;
        Trending.Pick pick = new Trending.Pick();
        pick.id = id.hashCode();
        pick.pkg = PACKAGE;
        pick.tab = KEY;
        pick.kind = "playlist";
        pick.title = name;
        pick.service = "Spotify";
        pick.meta = "Playlist · Spotify";
        pick.image = cover;
        pick.intentUri = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE)
                .toUri(Intent.URI_INTENT_SCHEME);
        return pick;
    }

    void refresh(String country, Listener listener) {
        long now = System.currentTimeMillis();
        if (closed || running || country.equals(market) && now < retryAfter) return;
        running = true;
        market = country;
        retryAfter = now + RETRY_MS;
        worker.execute(() -> {
            List<String[]> playlists = playlists(country);
            listenerOnMain(listener, readRow(playlists));
            boolean complete = true;
            for (String[] playlist : playlists) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!refreshCover(playlist[0], now)) complete = false;
            }
            long interval = complete ? REFRESH_MS : RETRY_MS;
            Channels.Channel updated = readRow(playlists);
            main.post(() -> {
                running = false;
                if (closed) return;
                retryAfter = now + interval;
                listener.onMusic(updated);
            });
        });
    }

    private void listenerOnMain(Listener listener, Channels.Channel row) {
        main.post(() -> { if (!closed) listener.onMusic(row); });
    }

    private Channels.Channel readRow(List<String[]> playlists) {
        Channels.Channel row = row();
        for (String[] playlist : playlists) {
            JSONObject saved = ArtworkAssets.read(previewFile(playlist[0]));
            row.items.add(pick(playlist[0], saved.optString("title", playlist[1]),
                    ArtworkAssets.saved(cache, saved.optString("cover"))));
        }
        return row;
    }

    private File previewFile(String id) { return new File(cache, "playlist_" + id + ".json"); }

    private boolean refreshCover(String id, long now) {
        File file = previewFile(id);
        JSONObject previous = ArtworkAssets.read(file);
        if (now - previous.optLong("checked") < REFRESH_MS
                && ArtworkAssets.saved(cache, previous.optString("cover")) != null) return true;
        try {
            JSONObject preview = preview(id);
            String title = preview.optString("title").trim();
            String thumbnail = preview.optString("thumbnail_url");
            if (!"Spotify".equals(preview.optString("provider_name")) || title.isEmpty() || title.length() > 180) return false;
            File art = approvedArtwork(thumbnail) ? ArtworkAssets.load(context, thumbnail, cache) : null;
            String cover = art == null ? previous.optString("cover") : art.getName();
            ArtworkAssets.write(file, new JSONObject().put("title", title).put("cover", cover)
                    .put("checked", art == null ? previous.optLong("checked") : now));
            return art != null;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean approvedArtwork(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && ("i.scdn.co".equals(host)
                || "charts-images.scdn.co".equals(host) || "image-cdn-ak.spotifycdn.com".equals(host));
    }

    private static JSONObject preview(String id) throws Exception {
        String link = URLEncoder.encode(BASE + "/playlist/" + id, "UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + "/oembed?url=" + link).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Spotify preview unavailable");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    if (bytes.size() + read > 16384) throw new IOException("Spotify preview too large");
                    bytes.write(buffer, 0, read);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
