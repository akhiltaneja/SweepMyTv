package com.sweepmytv.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Optional, best-effort scores from ESPN’s public scoreboards. */
final class SportsScores implements AutoCloseable {
    private static final String SOCCER = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final String CRICKET = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates=";
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long[] due = new long[2];
    private final boolean[] loading = new boolean[2];
    private boolean closed;
    private final File cacheDirectory;

    SportsScores(Context context) {
        cacheDirectory = new File(context.getNoBackupFilesDir(), "sports-scores");
    }

    List<Cricket.Match> cached(boolean football, long now) {
        File source = new File(cacheDirectory, football ? "football.json" : "cricket.json");
        try {
            if (source.length() > 256 * 1024 || new File(source + ".bak").length() > 256 * 1024) return List.of();
            JSONObject root = new JSONObject(new String(new android.util.AtomicFile(source).readFully(), StandardCharsets.UTF_8));
            long savedAt = root.optLong("checked_at");
            if (root.optInt("version") != 1 || savedAt <= 0 || savedAt > now + 300_000
                    || now - savedAt > 86_400_000L) return List.of();
            JSONArray records = root.getJSONArray("matches");
            List<Cricket.Match> restored = new ArrayList<>();
            for (int i = 0; i < Math.min(64, records.length()); i++) {
                JSONObject record = records.getJSONObject(i);
                Cricket.Match match = new Cricket.Match();
                match.id = record.getString("id");
                match.name = record.getString("name");
                match.scheduledAt = record.getLong("scheduled_at");
                match.football = football;
                match.live = record.optBoolean("live");
                match.paused = record.optBoolean("paused");
                match.ended = record.optBoolean("ended");
                match.intl = record.optBoolean("intl");
                match.status = record.optString("status");
                match.start = record.optString("start");
                match.phase = record.optString("phase");
                match.session = record.optString("session");
                match.kind = record.optString("kind");
                match.league = record.optString("league");
                match.venue = record.optString("venue");
                match.full1 = record.optString("full1");
                match.full2 = record.optString("full2");
                match.short1 = record.optString("short1");
                match.short2 = record.optString("short2");
                match.t1 = record.optString("t1");
                match.t2 = record.optString("t2");
                match.s1 = record.optString("s1");
                match.s2 = record.optString("s2");
                match.img1 = record.optString("img1");
                match.img2 = record.optString("img2");
                match.cachedAt = savedAt;
                restored.add(match);
            }
            return restored;
        } catch (Exception unavailable) {
            return List.of();
        }
    }

    void saveCached(boolean football, List<Cricket.Match> matches, long now) {
        android.util.AtomicFile file = new android.util.AtomicFile(
                new File(cacheDirectory, football ? "football.json" : "cricket.json"));
        FileOutputStream output = null;
        try {
            JSONArray records = new JSONArray();
            for (Cricket.Match match : matches.subList(0, Math.min(64, matches.size()))) {
                records.put(new JSONObject()
                        .put("id", match.id).put("name", match.name).put("scheduled_at", match.scheduledAt)
                        .put("live", match.live).put("paused", match.paused).put("ended", match.ended)
                        .put("intl", match.intl).put("status", match.status).put("start", match.start)
                        .put("phase", match.phase).put("session", match.session).put("kind", match.kind)
                        .put("league", match.league).put("venue", match.venue)
                        .put("full1", match.full1).put("full2", match.full2)
                        .put("short1", match.short1).put("short2", match.short2)
                        .put("t1", match.t1).put("t2", match.t2)
                        .put("s1", match.s1).put("s2", match.s2)
                        .put("img1", match.img1).put("img2", match.img2));
            }
            byte[] bytes = new JSONObject().put("version", 1).put("checked_at", now)
                    .put("matches", records).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 256 * 1024) return;
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (Exception unavailable) {
            if (output != null) file.failWrite(output);
        }
    }

    void refresh(boolean football, boolean india, boolean clock24, Consumer<List<Cricket.Match>> ready) {
        int feed = football ? 1 : 0;
        long now = System.currentTimeMillis();
        if (closed || loading[feed] || now < due[feed]) return;
        loading[feed] = true;
        worker.execute(() -> {
            List<Cricket.Match> matches = null;
            try {
                matches = fetch(football, india, clock24, now);
                saveCached(football, matches, System.currentTimeMillis());
            } catch (Exception unavailable) {
                // Keep the last successful result through a temporary provider error.
            }
            List<Cricket.Match> result = matches;
            main.post(() -> {
                loading[feed] = false;
                due[feed] = System.currentTimeMillis() + (result == null ? 300_000
                        : result.stream().anyMatch(match -> match.live) ? 90_000 : 900_000);
                if (!closed && result != null) ready.accept(result);
            });
        });
    }

    private static List<Cricket.Match> fetch(boolean football, boolean india, boolean clock24, long now) throws Exception {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        Map<String, Cricket.Match> matches = new LinkedHashMap<>();
        if (football) {
            String previous = "?dates=" + today.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
            String[] leagues = india ? new String[]{"eng.1", "uefa.champions", "ind.1"}
                    : new String[]{"eng.1", "uefa.champions"};
            boolean retrieved = false;
            for (String league : leagues) {
                for (String query : new String[]{"", previous}) {
                    try {
                        JSONObject score = get(SOCCER + league + "/scoreboard" + query);
                        JSONArray list = score.optJSONArray("leagues");
                        JSONObject title = list == null ? null : list.optJSONObject(0);
                        collect(matches, score, title == null ? "Football" : title.optString("name", "Football"),
                                true, zone, clock24);
                        retrieved = true;
                    } catch (Exception unavailable) {
                        // One unavailable league or day should not hide the other scoreboards.
                    }
                }
            }
            if (!retrieved) throw new IOException("Football scores unavailable");
        } else {
            boolean retrieved = false;
            for (LocalDate date : new LocalDate[]{today, today.plusDays(1), today.plusDays(2), today.minusDays(1)}) {
                try {
                    JSONArray panels = get(CRICKET + date.format(DateTimeFormatter.BASIC_ISO_DATE)).optJSONArray("scores");
                    for (int i = 0; panels != null && i < panels.length(); i++) {
                        JSONObject panel = panels.optJSONObject(i);
                        if (panel == null) continue;
                        JSONArray leagues = panel.optJSONArray("leagues");
                        JSONObject title = leagues == null ? null : leagues.optJSONObject(0);
                        collect(matches, panel, title == null ? "Cricket" : title.optString("name", "Cricket"),
                                false, zone, clock24);
                    }
                    retrieved = true;
                } catch (Exception unavailable) {
                    // ESPN may have no panel on one of the dates.
                }
            }
            if (!retrieved) throw new IOException("Cricket scores unavailable");
        }
        List<Cricket.Match> ordered = new ArrayList<>(matches.values());
        ordered.removeIf(match -> !match.live && !match.paused && (match.ended
                ? match.scheduledAt < now - (football ? 2 : 5) * 86_400_000L : match.scheduledAt < now - 3_600_000L));
        ordered.sort(Comparator.comparingInt((Cricket.Match match) -> match.live ? 0 : match.ended ? 2 : 1)
                .thenComparingLong(match -> match.ended ? -match.scheduledAt : match.scheduledAt));
        return football ? new ArrayList<>(ordered.subList(0, Math.min(12, ordered.size()))) : ordered;
    }

    private static void collect(Map<String, Cricket.Match> out, JSONObject board, String competition,
                                boolean football, ZoneId zone, boolean clock24) {
        JSONArray events = board.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            try {
                Cricket.Match match = parse(events.getJSONObject(i), competition, football, zone, clock24);
                if (match != null) out.putIfAbsent(match.id, match);
            } catch (Exception incomplete) {
                // A malformed match should not hide the rest of the board.
            }
        }
    }

    static Cricket.Match parse(JSONObject event, String competition, boolean football, ZoneId zone, boolean clock24) throws Exception {
        JSONObject game = event.getJSONArray("competitions").getJSONObject(0);
        JSONArray teams = game.getJSONArray("competitors");
        if (teams.length() != 2 || event.optString("id").isEmpty()) return null;
        JSONObject first = teams.getJSONObject(0), second = teams.getJSONObject(1);
        if (football && "away".equals(first.optString("homeAway"))) {
            JSONObject home = second;
            second = first;
            first = home;
        }
        JSONObject a = first.getJSONObject("team"), b = second.getJSONObject("team");
        JSONObject status = football ? game.getJSONObject("status") : event.getJSONObject("status");
        JSONObject type = status.getJSONObject("type");
        String state = type.optString("state");
        String summary = status.optString("summary").trim();
        // The cricket panel can flag a fixture as in progress before it has a score or has started.
        if (!football && "in".equals(state) && first.optString("score").isEmpty() && second.optString("score").isEmpty()
                && summary.toLowerCase(Locale.ROOT).startsWith("starts at ")) state = "pre";
        OffsetDateTime at = OffsetDateTime.parse(event.getString("date"));
        Cricket.Match match = new Cricket.Match();
        match.id = "espn-" + event.getString("id");
        match.scheduledAt = at.toInstant().toEpochMilli();
        match.football = football;
        match.phase = type.optString("description");
        String condition = (type.optString("name") + " " + match.phase).toUpperCase(Locale.ROOT);
        match.paused = !football && "in".equals(state)
                && (condition.contains("STUMPS") || condition.contains("RAIN")
                || condition.contains("LUNCH") || condition.contains("TEA") || condition.contains("INNINGS BREAK")
                || condition.contains("DELAY") || condition.contains("INTERRUPTED"));
        match.live = "in".equals(state) && !match.paused;
        match.ended = "post".equals(state);
        match.kind = football ? competition : game.optString("description");
        match.league = competition;
        JSONObject venue = game.optJSONObject("venue");
        JSONObject address = venue == null ? null : venue.optJSONObject("address");
        match.venue = address == null ? "" : address.optString("city");
        match.session = status.optString("session");
        match.full1 = a.optString("displayName");
        match.full2 = b.optString("displayName");
        match.short1 = a.optString("abbreviation", match.full1);
        match.short2 = b.optString("abbreviation", match.full2);
        match.t1 = football ? a.optString("shortDisplayName", match.short1) : match.short1;
        match.t2 = football ? b.optString("shortDisplayName", match.short2) : match.short2;
        match.img1 = espnLogo(a);
        match.img2 = espnLogo(b);
        match.name = match.full1 + " vs " + match.full2;
        match.s1 = "pre".equals(state) ? "" : first.optString("score", "");
        match.s2 = "pre".equals(state) ? "" : second.optString("score", "");
        String detail = type.optString("shortDetail", type.optString("description", ""));
        match.status = football ? detail : status.optString("summary", detail).replace("&amp;", "&");
        if (match.live && football) match.status = status.optString("displayClock", detail);
        if ("pre".equals(state)) {
            String pattern = football ? (clock24 ? "EEE, HH:mm" : "EEE, h:mm a")
                    : (clock24 ? "EEE d MMM, HH:mm" : "EEE d MMM, h:mm a");
            DateTimeFormatter format = DateTimeFormatter.ofPattern(pattern, Locale.getDefault());
            match.start = format.format(at.atZoneSameInstant(zone));
        }
        return match;
    }

    private static String espnLogo(JSONObject team) {
        String logo = team.optString("logo");
        return logo.startsWith("https://a.espncdn.com/") ? logo : "";
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(6_000);
        connection.setRequestProperty("Accept", "application/json");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int size; (size = in.read(buffer)) != -1; ) {
                if (out.size() + size > MAX_RESPONSE) throw new IOException("Scoreboard response too large");
                out.write(buffer, 0, size);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
