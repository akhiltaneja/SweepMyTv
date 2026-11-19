package com.sweepmytv.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Matches an approved editorial list to the current regional streaming catalog. */
final class RottenTomatoesPicks {
    static final String KEY = "sweep_rt_popular";
    static final String TITLE = "Rotten Tomatoes® Popular Picks";
    private static final int MIN_SCORE = 90;
    private static final int MAX_ASSET_BYTES = 64 * 1024;
    private final List<Entry> entries;

    static final class Entry {
        final int tmdbId, score;
        final String imdbId, reviewUrl;

        Entry(int tmdbId, String imdbId, int score, String reviewUrl) {
            this.tmdbId = tmdbId;
            this.imdbId = imdbId;
            this.score = score;
            this.reviewUrl = reviewUrl;
        }

        String label() {
            return "Rotten Tomatoes® " + score + "%";
        }
    }

    private RottenTomatoesPicks(List<Entry> entries) {
        this.entries = entries;
    }

    static RottenTomatoesPicks load(Context context) {
        try (InputStream input = context.getAssets().open("rt-popular-picks.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (bytes.size() + read > MAX_ASSET_BYTES) throw new IOException("Editorial list too large");
                bytes.write(buffer, 0, read);
            }
            return parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } catch (FileNotFoundException absent) {
            return new RottenTomatoesPicks(Collections.emptyList());
        } catch (IOException | JSONException invalid) {
            Log.w("RottenTomatoesPicks", "Could not load the editorial list");
            return new RottenTomatoesPicks(Collections.emptyList());
        }
    }

    static RottenTomatoesPicks parse(JSONObject feed) throws JSONException {
        if (feed.optInt("version") != 1) throw new JSONException("Unsupported editorial list version");
        JSONArray list = feed.getJSONArray("movies");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject movie = list.optJSONObject(i);
            if (movie == null) continue;
            int tmdbId = movie.optInt("tmdbId");
            String imdbId = movie.optString("imdbId");
            if (!imdbId.matches("tt[0-9]{1,12}")) imdbId = "";
            double score = movie.optDouble("tomatometer");
            String url = movie.optString("reviewUrl");
            if ((tmdbId > 0 || !imdbId.isEmpty()) && score == Math.rint(score)
                    && score >= MIN_SCORE && score <= 100
                    && url.matches("https://www\\.rottentomatoes\\.com/m/[a-z0-9_\\-]+")) {
                entries.add(new Entry(tmdbId, imdbId, (int) score, url));
            }
        }
        return new RottenTomatoesPicks(entries);
    }

    boolean configured() {
        return !entries.isEmpty();
    }

    Channels.Channel row(List<Trending.Pick> catalog, String region, Set<String> installedApps) {
        Channels.Channel row = new Channels.Channel();
        row.key = KEY;
        row.title = TITLE;
        Map<Integer, Trending.Pick> byTmdb = new HashMap<>();
        Map<String, Trending.Pick> byImdb = new HashMap<>();
        for (Trending.Pick pick : catalog) {
            if (!"movie".equals(pick.kind) || !region.equals(pick.market) || !installedApps.contains(pick.pkg)
                    || !Recommendations.provider(pick.pkg)) continue;
            if (pick.id > 0) byTmdb.putIfAbsent(pick.id, pick);
            if (!pick.externalId.isEmpty()) byImdb.putIfAbsent(pick.externalId, pick);
        }
        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            Trending.Pick movie = byTmdb.get(entry.tmdbId);
            if (movie == null) movie = byImdb.get(entry.imdbId);
            if (movie == null || !seen.add(movie.catalogIdentity())) continue;
            Trending.Pick card = Recommendations.copy(movie, entry.label(), KEY);
            card.extra = entry;
            row.items.add(card);
        }
        row.count = row.items.size();
        return row;
    }
}
