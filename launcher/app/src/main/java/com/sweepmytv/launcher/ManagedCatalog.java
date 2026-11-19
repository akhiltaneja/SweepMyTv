package com.sweepmytv.launcher;

import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;

/** Operator-published regional discovery feed. Requests contain no profile, account or history data. */
final class ManagedCatalog {
    private enum Service {
        APPLE("apple-tv-plus", "com.apple.atve.androidtv.appletv", "Apple TV", "tv.apple.com"),
        NETFLIX("netflix", "com.netflix.ninja", "Netflix", "netflix.com", "www.netflix.com"),
        PRIME("prime", "com.amazon.amazonvideo.livingroom", "Prime Video", "www.primevideo.com", "primevideo.com", "www.amazon.in", "www.amazon.com"),
        HOTSTAR("jiohotstar", "in.startv.hotstar", "JioHotstar", "www.hotstar.com", "hotstar.com", "www.jiohotstar.com", "jiohotstar.com"),
        SONY("sony-liv", "com.sonyliv", "SonyLIV", "www.sonyliv.com", "sonyliv.com"),
        ZEE("zee5", "com.graymatrix.did", "ZEE5", "www.zee5.com", "zee5.com");

        final String id, pkg, label;
        final List<String> hosts;

        Service(String id, String pkg, String label, String... hosts) {
            this.id = id;
            this.pkg = pkg;
            this.label = label;
            this.hosts = Arrays.asList(hosts);
        }

        static Service named(String id) {
            for (Service service : values()) if (service.id.equals(id)) return service;
            return null;
        }
    }

    private static final List<String> ART_HOSTS = Arrays.asList("image.tmdb.org", "btttr.cc", "images.metahub.space", "live.metahub.space",
            "img10.hotstar.com", "origin-staticv2.sonyliv.com", "bingeddata.s3.amazonaws.com",
            "ch-static-image-prod.s3.ap-south-1.amazonaws.com");
    private static final int MAX_BYTES = 512 * 1024;

    static boolean hasInstalledProvider(Predicate<String> installed) {
        for (Service service : Service.values()) if (installed.test(service.pkg)) return true;
        return false;
    }

    static boolean testLoopback(String base) {
        return BuildConfig.CATALOG_COMPARISON && base.equals("http://127.0.0.1:4190/catalog");
    }

    static void refreshSweep(String region, File cache) throws Exception {
        String base = BuildConfig.MANAGED_CATALOG_BASE.replaceAll("/+$", "");
        if (httpsHost(base) == null && !testLoopback(base)) return;
        HttpURLConnection request = (HttpURLConnection) new URL(base + "/" + region + ".json").openConnection();
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(3500);
        request.setReadTimeout(5000);
        byte[] bytes;
        try {
            if (request.getResponseCode() != 200) throw new IOException("Sweep TV catalog unavailable");
            try (InputStream input = request.getInputStream()) { bytes = bounded(input); }
        } finally {
            request.disconnect();
        }
        JSONObject feed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        parseSweep(feed, region, cache);
        android.util.AtomicFile file = new android.util.AtomicFile(new File(cache, region + ".json"));
        java.io.FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    static List<Trending.Pick> sweep(String region, File cache, AssetManager assets) {
        List<Trending.Pick> picks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        android.util.AtomicFile saved = new android.util.AtomicFile(new File(cache, region + ".json"));
        try (InputStream input = saved.openRead()) {
            addUnique(picks, seen, parseSweep(readFeed(input), region, cache));
        } catch (Exception ignored) { }
        try {
            List<Trending.Pick> starter = sweepAsset(assets, "sweep-starter/IN.json", cache);
            if (!picks.isEmpty()) for (Trending.Pick pick : starter) pick.editorialRank = 0;
            addUnique(picks, seen, starter);
        } catch (Exception ignored) { }
        return picks;
    }

    static List<Trending.Pick> sweepAsset(AssetManager assets, String path, File cache) throws Exception {
        try (InputStream input = assets.open(path)) {
            JSONObject feed = readFeed(input);
            return parseSweep(feed, "IN", cache);
        }
    }

    private static void addUnique(List<Trending.Pick> output, Set<String> seen, List<Trending.Pick> input) {
        for (Trending.Pick pick : input) {
            if (seen.add(pick.key() + ":" + pick.pkg)) output.add(pick);
        }
    }

    static List<Trending.Pick> parseSweep(JSONObject feed, String region, File cache) throws IOException {
        JSONArray items = feed.optJSONArray("titles");
        if (items == null || items.length() > 192) throw new IOException("Invalid Sweep TV catalog size");
        List<Trending.Pick> picks = parse(feed, region, cache, pkg -> true, true);
        if (picks.size() != items.length()) throw new IOException("Invalid or duplicate Sweep TV catalog title");
        return picks;
    }

    private static JSONObject readFeed(InputStream input) throws Exception {
        return new JSONObject(new String(bounded(input), StandardCharsets.UTF_8));
    }

    private static byte[] bounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1; ) {
            if (out.size() + read > MAX_BYTES) throw new IOException("Managed catalog exceeds its size limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static List<Trending.Pick> parse(JSONObject feed, String region, File cache, Predicate<String> installed)
            throws IOException {
        return parse(feed, region, cache, installed, false);
    }

    private static List<Trending.Pick> parse(JSONObject feed, String region, File cache, Predicate<String> installed,
                                            boolean sweep) throws IOException {
        if (feed.optInt("version") != 1 || !region.equals(feed.optString("region"))) {
            throw new IOException("Managed catalog does not match the TV region");
        }
        JSONArray items = feed.optJSONArray("titles");
        if (items == null) throw new IOException("Managed catalog has no title list");
        List<Trending.Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(items.length(), 192); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            String kind = item.optString("kind");
            String title = item.optString("title").trim();
            Service service = Service.named(item.optString("provider"));
            if (!id.matches("(tt[0-9]{1,12}|(?:streamradar|sweep|tmdb):[a-zA-Z0-9:_-]{1,70})")
                    || !("movie".equals(kind) || "tv".equals(kind)) || title.isEmpty() || title.length() > 200
                    || service == null || !installed.test(service.pkg)) continue;
            String poster = cleanPoster(item.optString("poster"), id);
            String background = art(item.optString("background"));
            if (!sweep && poster == null && background == null) continue;
            String tab;
            switch (item.optString("row")) {
                case "new": tab = Trending.INDIA; break;
                case "provider": tab = Recommendations.appKey(service.pkg); break;
                default: tab = Trending.TRENDING;
            }
            if (!seen.add(tab + ":" + kind + ":" + id + (sweep ? ":" + service.id : ""))) continue;

            Trending.Pick pick = new Trending.Pick();
            pick.sweep = sweep;
            pick.editorialRank = sweep ? Math.max(0, Math.min(10, item.optInt("editorial_rank", 0))) : 0;
            pick.externalId = id;
            pick.id = -(id.hashCode() & Integer.MAX_VALUE);
            pick.kind = kind;
            pick.title = title;
            pick.pkg = service.pkg;
            pick.service = service.label;
            pick.tab = tab;
            pick.market = region;
            String lang = item.optString("language");
            pick.lang = lang.matches("[a-z]{2}") ? lang : "";
            pick.meta = ("movie".equals(kind) ? "Film" : "Series") + " · " + service.label;
            pick.overview = item.optString("summary");
            pick.cast = item.optString("cast");
            pick.ratingSource = "IMDb";
            double rating = item.optDouble("rating", 0);
            pick.rating = rating > 0 && rating <= 10 ? (float) rating : 0;
            String date = item.optString("released");
            pick.releaseDate = date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? date : "";
            String year = item.optString("year");
            pick.year = year.matches("[0-9]{4}") ? year : "";
            pick.posterPath = poster;
            pick.backdropPath = background;
            pick.poster = poster == null ? null : imageFile(cache, poster);
            pick.image = background == null ? pick.poster : imageFile(cache, background);

            String link = item.optString("link");
            String host = httpsHost(link);
            if (host != null && service.hosts.contains(host) && !Uri.parse(link).getPathSegments().isEmpty()) {
                pick.intentUri = TitleLinks.appIntent(pick, link).toUri(Intent.URI_INTENT_SCHEME);
            }
            out.add(pick);
        }
        return out;
    }

    private static File imageFile(File cache, String url) {
        return new File(cache, ArtworkAssets.hash(url) + ".jpg");
    }

    private static String art(String url) {
        String host = httpsHost(url);
        return host != null && ART_HOSTS.contains(host) ? url : null;
    }

    private static String cleanPoster(String url, String id) {
        if (!"btttr.cc".equals(httpsHost(url))) return art(url);
        if (!id.matches("tt[0-9]{1,12}")) return null;
        return "https://images.metahub.space/poster/large/" + id + "/img";
    }

    static String posterRedirect(String original, String location) {
        if (!"images.metahub.space".equals(httpsHost(original))) return null;
        String path = URI.create(original).getPath();
        if (!path.matches("/poster/(large|original)/tt[0-9]{1,12}/img")) return null;
        String target = "https://live.metahub.space" + path;
        return target.equals(location) ? target : null;
    }

    private static String httpsHost(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) ? uri.getHost().toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private ManagedCatalog() {}
}
