package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;

/**
 * Two tabs of picks, kept only when TMDB says the title streams (subscription, not rent/buy) in the TV's country
 * (Region) on an app installed on this TV. Idea from Free My TV (MIT). The apps are the SERVICES below, plus any
 * installed app whose name is a streaming service TMDB lists for the country (Viaplay, Canal+, RTL+…).
 * With none of them installed, the same picks open their trailer in YouTube instead ("elsewhere").
 *   Trending      what's popular now on your apps here: titles from this country and English titles, mixed 2:1
 *                 (TMDB's own trending list is global and almost all English), minus anything in "New in";
 *                 in English-speaking countries simply what's popular here
 *   New in …      this country's films (last 6 months) and series (new episodes in the last 60 days)
 *
 * The TMDB key lives in a file on the TV (files/tmdb_key.txt), never in the APK. Picks and their backdrops are
 * cached in files/trending/. The list is refreshed at most every 6 hours, and only when the home screen asks.
 */
final class Trending {
    private static final String TAG = "GlassTrending";
    private static final String API = "https://api.themoviedb.org/3";
    /** Backdrops at 1280 px for the large card (about 1040 px on screen); posters at 342 px for the small ones. */
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/w1280", POSTERS = "https://image.tmdb.org/t/p/w500";
    /** The TV's country, read at each refresh (Region). */
    private String region = "US";
    private static final long RETRY_MS = 30 * 60_000L;
    // Give the scheduled catalog refresh time to publish while the Home row rotates from its cache.
    static final long FEED_PUBLISH_DELAY_MS = 10 * 60_000L;
    private static final int MAX_PICKS = 24, MAX_CHECKS = 72;
    static final int SHOWN = 4;
    static final String TRENDING = "trending", INDIA = "india", ENGLISH = "english_discovery", RELEASES = "new_releases";
    /** Bump when picks.json changes shape; the next home screen refreshes right away. */
    private static final int CACHE_VERSION = 16;
    /** YouTube on Android TV, Fire TV, then the phone app, for the trailers when no streaming app is installed. */
    private static final String[] YOUTUBE = {"com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"};
    /** For one refresh: no streaming app matched, so picks are trailers in this YouTube app. */
    private String elsewhere;
    /** TMDB genre ids → names, fetched once per refresh. */
    private final Map<Integer, String> genres = new java.util.HashMap<>();

    /** TMDB provider name (lowercase substring) → TV app package, and the name we show. */
    private static final String[][] SERVICES = {
        {"netflix", "com.netflix.ninja", "Netflix"},
        {"prime video", "com.amazon.amazonvideo.livingroom", "Prime Video"},
        {"hotstar", "in.startv.hotstar", "JioHotstar"},
        {"zee5", "com.graymatrix.did", "ZEE5"},
        {"sony liv", "com.sonyliv", "SonyLIV"},
        {"sonyliv", "com.sonyliv", "SonyLIV"},
        {"apple tv", "com.apple.atve.androidtv.appletv", "Apple TV"},
        {"mx player", "com.mxtech.videoplayer.television", "MX Player"},
        {"airtel xstream", "tv.airtel.xstream.tvapp", "Xstream Play"},
        {"fancode", "com.fancode.tv", "FanCode"},
        {"disney plus", "com.disney.disneyplus", "Disney+"},
        {"hulu", "com.hulu.livingroomplus", "Hulu"},
        {"max", "com.wbd.stream", "Max"},
        {"peacock", "com.peacocktv.peacockandroid", "Peacock"},
        {"paramount", "com.cbs.ott", "Paramount+"},
        {"crunchyroll", "com.crunchyroll.crunchyroid", "Crunchyroll"},
        {"mubi", "com.mubi", "MUBI"},
    };

    static boolean isStreamingApp(String pkg) {
        for (String[] service : SERVICES) if (service[1].equals(pkg)) return true;
        for (String youtube : YOUTUBE) if (youtube.equals(pkg)) return true;
        return false;
    }

    static final class Pick {
        int id;
        String kind;     // "movie" or "tv"
        String title, pkg, service, tab, lang = "", market = "";
        File image;          // landscape picture (TMDB backdrop, or the app's artwork)
        File poster;         // TMDB poster, for the small portrait cards; null when there is none
        transient String backdropPath, posterPath;  // TMDB image paths, while a refresh downloads them
        boolean savedResume; // Last known Apple TV entry while its provider is republishing after startup.
        String displayMeta(){return meta+(savedResume?" · Saved resume":"");}
        long at;            // Recently watched: when it was last watched
        String intentUri;   // Recently watched (Watch Next): opens the right title or episode; a trailer pick: the trailer
        String mediaKind = "", providerGenres = "";
        String meta = "";   // the line under a big card, e.g. "Film · Action · Telugu · Netflix"
        String overview = "", year = "", releaseDate = "";
        String externalId = "", cast = "", ratingSource = "TMDB";
        boolean weeklyTrending, sweep;
        int editorialRank;
        float rating;
        float progress = -1; // Continue: how far in (0–1), or -1 when unknown
        Object extra;        // Cricket: the Cricket.Match this card shows

        String key() { return "pick:" + tab + ":" + catalogIdentity(); }
        String catalogIdentity() { return kind + ":" + (externalId.isEmpty() ? id : externalId); }
    }

    interface Listener { void onPicks(List<Pick> all); }

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** For one refresh: several TMDB requests and image downloads at a time (set in fetch, shut down after). */
    private ExecutorService net;
    private final Map<String, java.util.concurrent.Future<JSONObject>> providerLookups = new java.util.HashMap<>();
    private static final int PARALLEL = 6, LOOKAHEAD = 6;
    private static final long SETUP_MS = 7 * 24 * 3600_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private int appsRevision;
    private volatile String displayedSource = "";

    Trending(Context c) {
        ctx = c.getApplicationContext();
        File base = c.getExternalFilesDir(null);
        dir = new File(base, "trending");
        prefs = c.getSharedPreferences("trending", Context.MODE_PRIVATE);
    }

    void readCached(Listener listener) {
        String source = catalogSource();
        Set<String> chosen = languages();
        worker.execute(() -> {
            List<Pick> saved = cached();
            main.post(() -> {
                if (source.equals(catalogSource()) && chosen.equals(languages())) listener.onPicks(saved);
            });
        });
    }

    void close() { worker.shutdownNow(); }

    // ---------------------------------------------------------------- languages (Sweep TV settings → Picks languages)

    /** The original languages picks may be in, as TMDB's codes (ta, en, fr…); empty for all of them. */
    Set<String> languages() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String c : prefs.getString("languages", ContentLanguages.DEFAULT).split(",")) if (!c.isEmpty()) out.add(c);
        return out;
    }

    /** Saves the choice; the picks refetch at the next refresh check, not after six hours. */
    void setLanguages(Set<String> codes) {
        prefs.edit().putString("languages", String.join(",", codes)).apply();
    }

    /**
     * The languages this country's lists bring up, most common first, then English: what the Languages screen offers.
     * Before a refresh has counted them, the languages of the saved picks; empty with none.
     */
    List<String> seenLanguages() {
        List<String> out = new ArrayList<>();
        for (String c : prefs.getString("seen_languages", "").split(",")) if (!c.isEmpty()) out.add(c);
        if (!out.isEmpty()) return out;
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Pick p : cached()) if (!p.lang.isEmpty() && !p.lang.equals("xx")) count.merge(p.lang, 1, Integer::sum);
        out.addAll(count.keySet());
        out.sort((x, y) -> count.get(y) - count.get(x));
        if (!out.isEmpty() && !out.contains("en")) out.add("en");
        return out;
    }

    /** "Tamil", "French"; TMDB's own codes Android doesn't know by their name. */
    static String languageName(String code) {
        if (code.equals("cn")) return "Cantonese";
        String name = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() || name.equals(code) ? code : name;
    }

    /** Counts the original languages in candidate lists, and saves them most common first (English last if absent). */
    private void noteLanguages(boolean replace, List<JSONObject> lists) {
        Map<String, Integer> count = new LinkedHashMap<>();
        if (!replace) {
            List<String> old = seenLanguages();
            for (int i = 0; i < old.size(); i++) count.put(old.get(i), 1000 - i);  // kept, ahead of what's new
        }
        for (JSONObject o : lists) {
            String c = o.optString("original_language", "");
            if (!c.isEmpty() && !c.equals("xx")) count.merge(c, 1, Integer::sum);
        }
        List<String> order = new ArrayList<>(count.keySet());
        order.sort((a, b) -> count.get(b) - count.get(a));
        if (!order.contains("en")) order.add("en");
        prefs.edit().putString("seen_languages", String.join(",", order)).apply();
    }

    String key() { return Keys.activeTmdbKey(ctx); }

    String catalogSource() {
        return Keys.catalogEngine(ctx);
    }

    static List<Pick> shelf(List<Pick> catalog, String tab) {
        Map<String, Pick> distinct = new LinkedHashMap<>();
        for (Pick p : catalog) {
            if (!tab.equals(p.tab)) continue;
            Pick previous = distinct.get(p.catalogIdentity());
            if (previous == null || previous.intentUri == null && p.intentUri != null) {
                distinct.put(p.catalogIdentity(), p);
            }
        }
        return new ArrayList<>(distinct.values());
    }

    List<Pick> cached() {
        String engine = catalogSource();
        if (BuildConfig.CATALOG_COMPARISON && "tmdb".equals(engine)) {
            List<Pick> titles = key() == null ? new ArrayList<>() : cachedTmdb();
            if (!titles.isEmpty()) return sourceTitles(titles, "TMDB");
        }
        List<Pick> titles = strictSweep(engine);
        if (!titles.isEmpty()) return sourceTitles(titles, "streamradar".equals(engine) ? "StreamRadar" : "Sweep TV");
        if (BuildConfig.CATALOG_COMPARISON && !"sweep".equals(engine) && titles.isEmpty()) {
            titles = strictSweep("streamradar");
            if (!titles.isEmpty()) return sourceTitles(titles, "StreamRadar fallback");
            if (Keys.read(ctx, Keys.TMDB) != null) {
                titles = cachedTmdb();
                if (!titles.isEmpty()) return sourceTitles(titles, "TMDB fallback");
            }
        }
        return sourceTitles(titles, "More to explore");
    }

    String displayedSource() { return displayedSource; }

    private List<Pick> sourceTitles(List<Pick> titles, String label) {
        displayedSource = label;
        List<Pick> ready = new ArrayList<>();
        for (Pick pick : titles) {
            if (!pick.sweep || readySweepPick(pick)) ready.add(pick);
        }
        return ready;
    }

    static boolean readySweepPick(Pick pick) {
        if (!hasSweepMetadata(pick) || !ArtworkAssets.portrait(pick.poster)) return false;
        int[] size = ArtworkAssets.size(pick.image);
        if (size[0] <= 0 || size[1] <= 0) pick.image = pick.poster;
        return true;
    }

    private static boolean hasSweepMetadata(Pick pick) {
        return pick.intentUri != null && !pick.lang.isEmpty() && !pick.overview.trim().isEmpty()
                && pick.poster != null;
    }

    List<Pick> cachedProviderFallback(String engine, Set<String> apps) {
        if (!BuildConfig.CATALOG_COMPARISON) return List.of();
        List<Pick> fallback = new ArrayList<>();
        if (!"sweep".equals(engine)) {
            for (Pick pick : strictSweep("sweep")) {
                if (apps.contains(pick.pkg) && Recommendations.appCandidate(pick) && readySweepPick(pick)) {
                    fallback.add(pick);
                }
            }
        }
        if (!"tmdb".equals(engine) && Keys.read(ctx, Keys.TMDB) != null) {
            for (Pick pick : cachedTmdb()) {
                if (apps.contains(pick.pkg) && Recommendations.isAppRow(pick.tab) && ArtworkAssets.portrait(pick.poster)) {
                    fallback.add(pick);
                }
            }
        }
        return fallback;
    }

    List<Pick> cachedSweepRecommendations() {
        List<Pick> ready = new ArrayList<>();
        for (Pick pick : strictSweep("sweep")) if (readySweepPick(pick)) ready.add(pick);
        return ready;
    }

    private List<Pick> sweepPool(String engine) {
        if (BuildConfig.CATALOG_COMPARISON && "streamradar".equals(engine)) {
            try {
                // Cache files are named by the complete image URL. The two source lists stay independent.
                return ManagedCatalog.sweepAsset(ctx.getAssets(), "comparison/IN.json", new File(dir, "sweep"));
            } catch (Exception ignored) { }
            return new ArrayList<>();
        }
        return ManagedCatalog.sweep(Region.country(ctx), new File(dir, "sweep"), ctx.getAssets());
    }

    private List<Pick> strictSweep(String engine) {
        String market = Region.country(ctx);
        Set<String> chosen = languages();
        List<Pick> out = new ArrayList<>();
        for (Pick pick : sweepPool(engine)) {
            if (market.equals(pick.market) && installed(pick.pkg) && ContentLanguages.allows(chosen, pick.lang)) out.add(pick);
        }
        return out;
    }

    List<Pick> explore() {
        return explore(true);
    }

    private List<Pick> explore(boolean requireReady) {
        String market = Region.country(ctx);
        List<Pick> pool = sweepPool("sweep");
        Set<String> chosen = languages();
        boolean hasProvider = ManagedCatalog.hasInstalledProvider(this::installed);
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (boolean installedApp : new boolean[]{true, false}) {
            if (!installedApp && hasProvider) break;
            for (Pick p : pool) {
                if (out.size() == 8) return out;
                if (installed(p.pkg) != installedApp || !ContentLanguages.allows(chosen, p.lang)
                        || requireReady && !readySweepPick(p)
                        || !seen.add(p.catalogIdentity())) continue;
                String language = p.lang.isEmpty() ? "Language unknown" : Region.language(p.lang);
                Pick card = Recommendations.copy(p, (language.isEmpty() ? p.lang : language)
                        + (market.equals(p.market) ? "" : " · India catalog"), Recommendations.EXPLORE);
                card.meta = p.service + " · " + card.meta;
                if (!market.equals(p.market)) card.intentUri = null;
                out.add(card);
            }
        }
        return out;
    }

    private List<Pick> cachedTmdb() {
        List<Pick> out = new ArrayList<>();
        String market = Region.country(ctx);
        File cache = dir;
        Set<String> chosen = languages();
        try (InputStream in = new FileInputStream(new File(cache, "picks.json"))) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pick p = new Pick();
                p.id = o.getInt("id");
                p.kind = o.getString("kind");
                p.title = o.getString("title");
                p.pkg = o.getString("pkg");
                p.service = o.getString("service");
                p.image = new File(cache, o.getString("image"));
                String poster = o.optString("poster", "");
                p.poster = poster.isEmpty() || !new File(cache, poster).isFile() ? null : new File(cache, poster);
                p.tab = o.optString("tab", TRENDING);
                p.lang = o.optString("lang", "");
                p.market = o.optString("market");
                if (!p.market.isEmpty() && !market.equals(p.market)) continue;
                if (Recommendations.isAppRow(p.tab) && (!market.equals(p.market) || !o.optBoolean("availability_verified"))) continue;
                p.meta = o.optString("meta", "");
                p.overview = o.optString("overview", "");
                p.year = o.optString("year", "");
                p.rating = (float)o.optDouble("rating",0);
                p.externalId = o.optString("external_id");
                p.cast = o.optString("cast");
                p.ratingSource = o.optString("rating_source", "TMDB");
                p.releaseDate=o.optString("release_date","");p.weeklyTrending=o.optBoolean("weekly_trending",false);
                String uri = o.optString("uri", "");
                p.intentUri = uri.isEmpty() ? null : uri;
                if (ContentLanguages.allows(chosen,p.lang) && p.image.isFile() && installed(p.pkg)) out.add(p);
            }
        } catch (Exception e) {
            // Nothing saved yet.
        }
        return out;
    }

    /** Hours since the epoch in local time, so the picks change on the hour here (not at :30 in India). */
    static long localHour() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 3600_000L;
    }

    /** The picks for this hour: SHOWN of them, rotating through the saved list. */
    static List<Pick> forThisHour(List<Pick> all) {
        if (all.size() <= SHOWN) return all;
        long hour = localHour();
        int start = (int) ((hour * SHOWN) % all.size());
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < SHOWN; i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    /** Fetches a new list in the background when the saved one is old. Calls back on the main thread. */
    /** The network is back: a fetch that failed while it was down may run again now, without waiting out the retry. */
    void networkBack() {
        if (prefs.getLong("try_at", 0) > prefs.getLong("ok_at", 0)) prefs.edit().remove("try_at").apply();
    }

    void appsChanged() {
        appsRevision++;
        prefs.edit().remove("ok_at").remove("try_at").apply();
    }

    void refreshIfStale(Listener l) {
        String key = key();
        String cc = Region.country(ctx);
        String source = catalogSource();
        if (source.isEmpty() || running || worker.isShutdown()) return;
        long now = System.currentTimeMillis();
        // A new key fetches right away instead of waiting out the old key's timers.
        String was = prefs.getString("region", null);
        int sourceHash = java.util.Objects.hash(source, key, BuildConfig.MANAGED_CATALOG_BASE, BuildConfig.VERSION_CODE);
        if (was == null) prefs.edit().putString("region", cc).apply();  // first run with countries: just remember it
        if (prefs.getInt("key_hash", 0) != sourceHash || prefs.getInt("version", 0) != CACHE_VERSION
                || !source.equals(prefs.getString("catalog_source", "tmdb"))
                || (was != null && !cc.equals(was))
                || !prefs.getString("languages", ContentLanguages.DEFAULT).equals(prefs.getString("languages_fetched", ""))) {
            // A new key, a new cache layout, the TV now in another country, or other languages: fetch right away.
            prefs.edit().putInt("key_hash", sourceHash).putString("catalog_source", source)
                    .putInt("version", CACHE_VERSION).putString("region", cc)
                    .remove("ok_at").remove("try_at").apply();
        }
        if (!recommendationFetchDue(now, prefs.getLong("ok_at", 0), prefs.getLong("try_at", 0))) return;
        prefs.edit().putLong("try_at", now).apply();
        running = true;
        String langs = prefs.getString("languages", ContentLanguages.DEFAULT);
        int revision = appsRevision;
        worker.execute(() -> {
            Runnable publish = () -> {
                List<Pick> all = cached();
                main.post(() -> {
                    if (appsRevision == revision && langs.equals(prefs.getString("languages", ContentLanguages.DEFAULT))
                            && source.equals(catalogSource()) && java.util.Objects.equals(key, key())) l.onPicks(all);
                });
            };
            try {
                try {
                    if (BuildConfig.CATALOG_COMPARISON && "tmdb".equals(source) && key != null) fetch(key);
                    else fetchSweep(cc, source, publish);
                    // If the languages changed meanwhile, the next check fetches again.
                    prefs.edit().putLong("ok_at", now).putString("languages_fetched", langs).apply();
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception e) {
                    Log.w(TAG, "Refresh failed: " + e.getClass().getSimpleName());
                }
                publish.run();
                refreshComparisonRecommendations(cc, source, publish);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                main.post(() -> {
                    running = false;
                    if (appsRevision != revision) {
                        prefs.edit().remove("ok_at").remove("try_at").apply();
                        refreshIfStale(l);
                    } else if (!prefs.getString("languages", ContentLanguages.DEFAULT).equals(langs)
                            || !source.equals(catalogSource()) || !java.util.Objects.equals(key, key())) refreshIfStale(l);
                });
            }
        });
    }

    // ---------------------------------------------------------------- fetching

    static boolean recommendationFetchDue(long now, long success, long attempt) {
        long feedWindow = Recommendations.window(now - FEED_PUBLISH_DELAY_MS);
        if (success > 0 && success <= now && Recommendations.window(success - FEED_PUBLISH_DELAY_MS) == feedWindow) return false;
        return attempt <= 0 || attempt > now || Recommendations.window(attempt - FEED_PUBLISH_DELAY_MS) != feedWindow
                || now - attempt >= RETRY_MS;
    }

    private void refreshComparisonRecommendations(String market, String engine, Runnable publish) throws InterruptedException {
        if (!BuildConfig.CATALOG_COMPARISON || "sweep".equals(engine) || !engine.equals(catalogSource())) return;
        try {
            ManagedCatalog.refreshSweep(market, new File(dir, "sweep"));
        } catch (Exception unavailable) {
            // The last valid feed and bundled starter are still available.
        }
        if (!engine.equals(catalogSource())) return;
        List<Pick> portraits = new ArrayList<>();
        for (Pick pick : strictSweep("sweep")) if (hasSweepMetadata(pick)) portraits.add(pick);
        if (portraits.isEmpty()) return;
        publish.run();
        ExecutorService images = Executors.newFixedThreadPool(PARALLEL);
        try {
            downloadManagedPhase(artworkOrder(portraits), true, images, publish, engine);
        } finally {
            images.shutdownNow();
        }
    }

    private void fetchSweep(String market, String engine, Runnable publish) throws Exception {
        Exception refreshError = null;
        if ("sweep".equals(engine)) {
            try { ManagedCatalog.refreshSweep(market, new File(dir, "sweep")); }
            catch (Exception unavailable) { refreshError = unavailable; }
        }
        List<Pick> picks = strictSweep(engine);
        if (BuildConfig.CATALOG_COMPARISON && !"sweep".equals(engine) && picks.isEmpty()) {
            picks = strictSweep("streamradar");
            String fallbackKey = Keys.read(ctx, Keys.TMDB);
            if (picks.isEmpty() && fallbackKey != null) fetch(fallbackKey);
        }
        if (picks.isEmpty()) picks = explore(false);
        publish.run();
        ExecutorService images = Executors.newFixedThreadPool(PARALLEL);
        try {
            List<Pick> ordered = artworkOrder(picks);
            downloadManagedPhase(ordered, true, images, publish, engine);
            if (engine.equals(catalogSource())) downloadManagedPhase(ordered, false, images, publish, engine);
        } finally {
            images.shutdownNow();
        }
        if (refreshError != null) throw refreshError;
    }

    /** Let each visible provider and shelf receive a first card before deeper catalog entries. */
    static List<Pick> artworkOrder(List<Pick> picks) {
        Set<String> groups = new HashSet<>();
        List<Pick> ordered = new ArrayList<>(), rest = new ArrayList<>();
        for (Pick pick : picks) {
            List<Pick> destination = groups.add(pick.pkg + "\n" + pick.tab) ? ordered : rest;
            destination.add(pick);
        }
        ordered.addAll(rest);
        return ordered;
    }

    private void downloadManagedPhase(List<Pick> picks, boolean portraits, ExecutorService images, Runnable publish, String engine)
            throws InterruptedException {
        Map<File,String> jobs = new LinkedHashMap<>();
        for (Pick pick : picks) {
            File file = portraits ? pick.poster : pick.image;
            String url = portraits ? pick.posterPath : pick.backdropPath;
            if (file != null && url != null && !file.isFile()) jobs.putIfAbsent(file, url);
        }
        ExecutorCompletionService<Boolean> finished = new ExecutorCompletionService<>(images);
        jobs.forEach((file, url) -> finished.submit(() -> {
            download(url, file, true);
            return true;
        }));
        long publishedAt = 0;
        boolean dirty = false;
        for (int i = 0; i < jobs.size(); i++) {
            if (!engine.equals(catalogSource())) return;
            try {
                dirty |= finished.take().get();
            } catch (ExecutionException ignored) { }
            long now = android.os.SystemClock.elapsedRealtime();
            if (dirty && (publishedAt == 0 || now - publishedAt >= 2000 || i + 1 == jobs.size())) {
                publish.run();
                publishedAt = now;
                dirty = false;
            }
        }
    }

    private void fetch(String key) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        long started = System.currentTimeMillis();
        region = Region.country(ctx);
        net = Executors.newFixedThreadPool(PARALLEL);
        providerLookups.clear();
        try {
            fetchWith(key);
        } finally {
            net.shutdownNow();
            providerLookups.clear();
        }
        Log.i(TAG, "Refreshed for " + region + " in " + (System.currentTimeMillis() - started) + " ms");
    }

    /**
     * Genre names and India's list of streaming services hardly ever change: kept for a week (prefs "setup"), which
     * saves four requests per refresh.
     */
    private JSONObject setup(String key) throws Exception {
        try {
            JSONObject saved = new JSONObject(prefs.getString("setup", "{}"));
            if (System.currentTimeMillis() - saved.optLong("at") < SETUP_MS && saved.has("tv_providers")
                    && region.equals(saved.optString("region"))) return saved;
        } catch (Exception ignored) {
            // Fetch afresh.
        }
        JSONObject o = new JSONObject().put("at", System.currentTimeMillis()).put("region", region);
        for (String kind : new String[]{"movie", "tv"}) {
            o.put(kind + "_genres", get(key, "/genre/" + kind + "/list?language=en").getJSONArray("genres"));
            o.put(kind + "_providers", get(key, "/watch/providers/" + kind + "?watch_region=" + region).getJSONArray("results"));
        }
        prefs.edit().putString("setup", o.toString()).apply();
        return o;
    }

    private void fetchWith(String key) throws Exception {
        JSONObject setup = setup(key);
        genres.clear();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray g = setup.getJSONArray(kind + "_genres");
            for (int i = 0; i < g.length(); i++) genres.put(g.getJSONObject(i).getInt("id"), g.getJSONObject(i).getString("name"));
        }
        // 1. Which of the country's streaming services TMDB knows are installed here: ours by package, others by name.
        Map<Integer, String[]> services = new LinkedHashMap<>();
        Map<String, String[]> apps = installedByName();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray a = setup.getJSONArray(kind + "_providers");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String[] s = service(o.getString("provider_name"));
                if (s != null && ctx.getPackageManager().getLeanbackLaunchIntentForPackage(s[1]) == null) s = null;
                if (s == null) s = byName(apps, o.getString("provider_name"));
                if (s != null) services.put(o.getInt("provider_id"), s);
            }
        }
        Log.i(TAG, "Matched " + services.size() + " TMDB services to installed apps");
        elsewhere = null;
        if (services.isEmpty()) {
            for (String y : YOUTUBE) if (elsewhere == null && installed(y)) elsewhere = y;
            if (elsewhere == null) { save(new ArrayList<>()); return; }
            Log.i(TAG, "No streaming apps: trailers in " + elsewhere);
        }

        Set<String> chosen = languages();
        List<Pick> picks;
        try {
            picks = globalPicks(key, services, chosen);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Discovery refresh failed; retaining the saved discovery rows: " + e.getClass().getSimpleName());
            picks = cachedTmdb();
            picks.removeIf(p -> Recommendations.isAppRow(p.tab));
        }
        picks.addAll(appPicks(key, setup, services, chosen));
        save(downloadImages(picks));
    }

    private List<Pick> globalPicks(String key, Map<Integer, String[]> services, Set<String> chosen)
            throws Exception {
        // 2. Candidates for each tab, then 3. keep those that stream on an installed app (and fetch backdrops).
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&watch_region=" + region + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc" + (elsewhere == null ? "&with_watch_providers=" + ids : "");

        // The six candidate lists, all at once. With languages chosen (Picks languages), "New in …" keeps to them, and
        // Trending's two sides become those languages (from anywhere, not only this country) and English, if chosen.
        List<String> others = new ArrayList<>(chosen);
        others.remove("en");
        boolean withEnglish = chosen.isEmpty() || chosen.contains("en");
        String popular = "&vote_count.gte=10" + common;
        String origin=chosen.isEmpty()?"&with_origin_country="+region:"";
        java.util.concurrent.Future<JSONObject> newFilms=laterInLanguages(key,"/discover/movie?primary_release_date.gte="+day(-180)+"&primary_release_date.lte="+day(0)+origin+common,chosen);
        java.util.concurrent.Future<JSONObject> newShows=laterInLanguages(key,"/discover/tv?air_date.gte="+day(-60)+"&air_date.lte="+day(0)+origin+common,chosen);
        boolean anyLocal=chosen.isEmpty()||!others.isEmpty();
        Set<String> otherLanguages=new java.util.LinkedHashSet<>(others);
        java.util.concurrent.Future<JSONObject> localFilms=anyLocal?laterInLanguages(key,"/discover/movie?vote_count.gte=10"+origin+common,otherLanguages):null;
        java.util.concurrent.Future<JSONObject> localShows=anyLocal?laterInLanguages(key,"/discover/tv?vote_count.gte=10"+origin+common,otherLanguages):null;
        java.util.concurrent.Future<JSONObject> englishFilms=withEnglish?later(key,"/discover/movie?with_original_language=en"+popular):null;
        java.util.concurrent.Future<JSONObject> englishShows=withEnglish?later(key,"/discover/tv?with_original_language=en"+popular):null;
        java.util.concurrent.Future<JSONObject> discoveryFilms=elsewhere==null?laterInLanguages(key,"/discover/movie?vote_count.gte=10"+common,chosen):null;
        java.util.concurrent.Future<JSONObject> discoveryShows=elsewhere==null?laterInLanguages(key,"/discover/tv?vote_count.gte=10"+common,chosen):null;
        java.util.concurrent.Future<JSONObject> weeklyFilms=elsewhere==null?later(key,"/trending/movie/week"):null;
        java.util.concurrent.Future<JSONObject> weeklyShows=elsewhere==null?later(key,"/trending/tv/week"):null;
        String movieFresh=releasePath("movie",day(-90),day(0),common,false),tvFresh=releasePath("tv",day(-90),day(0),common,false);
        java.util.concurrent.Future<JSONObject> recentFilms=elsewhere==null?laterInLanguages(key,movieFresh,chosen):null;
        java.util.concurrent.Future<JSONObject> recentShows=elsewhere==null?laterInLanguages(key,tvFresh,chosen):null;
        java.util.concurrent.Future<JSONObject> recentEnglishFilms=elsewhere==null&&withEnglish?later(key,movieFresh+"&with_original_language=en"):null;
        java.util.concurrent.Future<JSONObject> recentEnglishShows=elsewhere==null&&withEnglish?later(key,tvFresh+"&with_original_language=en"):null;

        // "New in …" first, so Trending can leave its titles out.
        List<JSONObject> fresh = merge(tag(results(newFilms), "movie"), tag(results(newShows), "tv"));
        List<Pick> india = keepMixed(key, services, INDIA, new HashSet<>(), new int[]{1}, fresh);
        Set<String> inIndia = new HashSet<>();
        for (Pick p : india) inIndia.add(p.kind + p.id);

        // Trending: popular titles from this country and popular English titles, two of the first for each one of the
        // second. Where the country's own titles are English anyway (US, UK…), just what's popular here.
        List<JSONObject> local = merge(tag(results(localFilms), "movie"), tag(results(localShows), "tv"));
        List<JSONObject> english = merge(tag(results(englishFilms), "movie"), tag(results(englishShows), "tv"));
        // What languages this country watches, for the Languages screen: all of them only when nothing is chosen.
        List<JSONObject> seen = new ArrayList<>(fresh);
        seen.addAll(local);
        noteLanguages(chosen.isEmpty(), seen);
        List<Pick> trend = Region.englishSpeaking(region)
                ? keepMixed(key, services, TRENDING, inIndia, new int[]{0, 1}, english, local)
                : keepMixed(key, services, TRENDING, inIndia, new int[]{0, 0, 1}, local, english);

        List<Pick> picks = new ArrayList<>(trend);
        picks.addAll(india);
        if(elsewhere==null) {
            List<JSONObject> weekly=weeklyInLanguages(results(weeklyFilms),results(weeklyShows),chosen);
            List<JSONObject> englishPopular=merge(tag(results(discoveryFilms),"movie"),tag(results(discoveryShows),"tv"));
            picks.addAll(keepMixed(key,services,ENGLISH,new HashSet<>(),new int[]{0,0,1},weekly,englishPopular));
            List<JSONObject> englishNew=merge(tag(results(recentEnglishFilms),"movie"),tag(results(recentEnglishShows),"tv"));
            List<JSONObject> globalNew=merge(tag(results(recentFilms),"movie"),tag(results(recentShows),"tv"));
            picks.addAll(keepMixed(key,services,RELEASES,new HashSet<>(),new int[]{0,1},englishNew,globalNew));
        }
        return picks;
    }

    /** Build each service's own regional catalog instead of assigning shared results to the first matching app. */
    private List<Pick> appPicks(String key, JSONObject setup, Map<Integer, String[]> services, Set<String> chosen)
            throws Exception {
        Map<String, Map<String, java.util.concurrent.Future<JSONObject>>> requests = new LinkedHashMap<>();
        Map<String, String[]> packages = new LinkedHashMap<>();
        Map<String, Map<String, Set<Integer>>> providerIds = new LinkedHashMap<>();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray providers = setup.getJSONArray(kind + "_providers");
            Map<String, List<String>> ids = new LinkedHashMap<>();
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.getJSONObject(i);
                int id = provider.getInt("provider_id");
                // An add-on sold through another service cannot be watched in the standalone app.
                if (provider.getString("provider_name").toLowerCase(Locale.ROOT).endsWith(" channel")) continue;
                String[] service = services.get(id);
                if (service == null || !Recommendations.appProvider(service[1])
                        || ctx.getPackageManager().getLeanbackLaunchIntentForPackage(service[1]) == null) continue;
                packages.put(service[1], service);
                providerIds.computeIfAbsent(service[1], ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(kind, ignored -> new HashSet<>()).add(id);
                ids.computeIfAbsent(service[1], ignored -> new ArrayList<>()).add(String.valueOf(id));
            }
            for (Map.Entry<String, List<String>> entry : ids.entrySet()) {
                String path = "/discover/" + kind + "?include_adult=false&sort_by=popularity.desc"
                        + "&watch_region=" + region + "&with_watch_monetization_types=flatrate|free|ads"
                        + "&with_watch_providers=" + android.text.TextUtils.join("|", entry.getValue());
                requests.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>())
                        .put(kind, laterInLanguages(key, path, chosen));
            }
        }
        List<Pick> out = new ArrayList<>();
        List<Pick> previous = cached();
        for (Map.Entry<String, Map<String, java.util.concurrent.Future<JSONObject>>> app : requests.entrySet()) {
            String pkg = app.getKey(), tab = Recommendations.appKey(pkg);
            JSONArray movies = new JSONArray(), shows = new JSONArray();
            boolean failed = false;
            for (Map.Entry<String, java.util.concurrent.Future<JSONObject>> request : app.getValue().entrySet()) {
                try {
                    JSONArray titles = results(request.getValue());
                    if ("movie".equals(request.getKey())) movies = titles;
                    else shows = titles;
                } catch (java.util.concurrent.ExecutionException | org.json.JSONException e) {
                    failed = true;
                    Log.w(TAG, "Couldn't refresh " + pkg + " " + request.getKey() + " recommendations");
                }
            }
            List<Pick> fresh = new ArrayList<>();
            List<JSONObject> candidates = appCandidates(movies, shows, chosen);
            Set<String> excluded = java.util.Collections.emptySet();
            for (int i = 0; i < candidates.size() && fresh.size() < Recommendations.APP_LIMIT; i++) {
                lookAhead(key, candidates, i, excluded);
                JSONObject title = candidates.get(i);
                String kind = title.getString("_kind");
                try {
                    java.util.concurrent.Future<JSONObject> lookup = providerLookups.get(kind + title.getInt("id"));
                    if (lookup == null) continue;
                    JSONObject providers = lookup.get();
                    if (!availableInApp(providers, region, providerIds.get(pkg).get(kind))) continue;
                } catch (java.util.concurrent.ExecutionException e) {
                    failed = true;
                    continue;
                }
                Pick p = fill(new Pick(), packages.get(pkg), tab, kind, title);
                p.image = p.poster;
                p.market = region;
                fresh.add(p);
            }
            if (failed && fresh.isEmpty()) {
                for (Pick p : previous) if (tab.equals(p.tab)) fresh.add(p);
            }
            out.addAll(fresh);
        }
        return out;
    }

    static boolean availableInApp(JSONObject response, String region, Set<Integer> ids) {
        JSONObject results = response.optJSONObject("results");
        JSONObject market = results == null ? null : results.optJSONObject(region);
        if (market == null) return false;
        for (String type : new String[]{"flatrate", "free", "ads"}) {
            JSONArray providers = market.optJSONArray(type);
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject provider = providers.optJSONObject(i);
                if (provider != null && ids.contains(provider.optInt("provider_id"))) return true;
            }
        }
        return false;
    }

    private static final int MAX_APP_CANDIDATES = Recommendations.APP_LIMIT * 3;

    static List<JSONObject> appCandidates(JSONArray movies, JSONArray shows, Set<String> chosen) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.max(movies.length(), shows.length()) && out.size() < MAX_APP_CANDIDATES; i++) {
            addAppCandidate(out, seen, movies.optJSONObject(i), "movie", chosen);
            addAppCandidate(out, seen, shows.optJSONObject(i), "tv", chosen);
        }
        return out;
    }

    private static void addAppCandidate(List<JSONObject> out, Set<String> seen, JSONObject title,
                                        String kind, Set<String> chosen) throws Exception {
        if (out.size() >= MAX_APP_CANDIDATES || title == null || title.optBoolean("adult")
                || title.optInt("id") <= 0 || title.optString("tv".equals(kind) ? "name" : "title").trim().isEmpty()
                || !ContentLanguages.allows(chosen, title.optString("original_language"))
                || !title.optString("poster_path").matches("/[A-Za-z0-9]+[.](jpg|png|webp)")) return;
        if (seen.add(kind + ":" + title.getInt("id"))) out.add(title.put("_kind", kind));
    }

    static String releasePath(String kind,String since,String until,String common,boolean english) {
        String field="tv".equals(kind)?"first_air_date":"primary_release_date";
        return "/discover/"+kind+"?"+field+".gte="+since+"&"+field+".lte="+until+"&vote_count.gte=5"
                +common.replace("&sort_by=popularity.desc","&sort_by="+field+".desc")+(english?"&with_original_language=en":"");
    }

    static List<JSONObject> englishWeekly(JSONArray movies,JSONArray shows) throws Exception { return weeklyInLanguages(movies,shows,java.util.Collections.singleton("en")); }
    static List<JSONObject> weeklyInLanguages(JSONArray movies,JSONArray shows,Set<String> chosen) throws Exception {
        List<JSONObject> out=new ArrayList<>();
        for(JSONObject o:merge(tag(movies,"movie"),tag(shows,"tv")))if(ContentLanguages.allows(chosen,o.optString("original_language"))) {
            o.put("_weekly",true);out.add(o);
        }
        return out;
    }

    /** A candidate list's titles; none for a list that wasn't asked for. */
    private static JSONArray results(java.util.concurrent.Future<JSONObject> list) throws Exception {
        return list == null ? new JSONArray() : list.get().getJSONArray("results");
    }

    private java.util.concurrent.Future<JSONObject> later(String key, String path) {
        return net.submit(() -> get(key, path));
    }

    /** One request per original language: the API accepts a single language, not an OR expression. */
    private java.util.concurrent.Future<JSONObject> laterInLanguages(String key,String path,Set<String> codes) {
        if(codes.isEmpty())return later(key,path);
        return net.submit(()->{
            List<JSONArray> lists=new ArrayList<>();int size=0;
            for(String code:codes){JSONArray a=get(key,path+"&with_original_language="+code).getJSONArray("results");lists.add(a);size=Math.max(size,a.length());}
            JSONArray merged=new JSONArray();Set<Integer> seen=new HashSet<>();
            for(int i=0;i<size;i++)for(JSONArray a:lists)if(i<a.length()){
                JSONObject o=a.getJSONObject(i);if(ContentLanguages.allows(codes,o.optString("original_language"))&&seen.add(o.optInt("id")))merged.put(o);
            }
            return new JSONObject().put("results",merged);
        });
    }

    /** Starts the "where does it stream?" lookups for the next few titles in a queue, so they're ready when needed. */
    private void lookAhead(String key, List<JSONObject> queue, int from, Set<String> exclude) throws Exception {
        for (int i = from, n = 0; i < queue.size() && n < LOOKAHEAD; i++) {
            JSONObject o = queue.get(i);
            String id = o.getString("_kind") + o.getInt("id");
            if (exclude.contains(id)||!ContentLanguages.allows(languages(),o.optString("original_language"))) continue;
            n++;
            if (!providerLookups.containsKey(id)) {
                providerLookups.put(id, later(key, lookupPath(o.getString("_kind"), o.getInt("id"), o.optString("original_language", ""))));
            }
        }
    }

    /** Download each unique image once, including posters shared by the global and per-app rows. */
    private List<Pick> downloadImages(List<Pick> picks) throws InterruptedException {
        Map<File, java.util.concurrent.Future<?>> jobs = new LinkedHashMap<>();
        for (Pick p : picks) {
            String backdropBase = p.externalId.isEmpty() ? BACKDROPS : "";
            String posterBase = p.externalId.isEmpty() ? POSTERS : "";
            if (!java.util.Objects.equals(p.image,p.poster)) queueImage(jobs, p.image, backdropBase, p.backdropPath);
            queueImage(jobs, p.poster, posterBase, p.posterPath);
        }
        List<Pick> out = new ArrayList<>();
        for (Pick p : picks) {
            if (p.sweep) {
                imageReady(jobs, p.poster);
                imageReady(jobs, p.image);
                out.add(p);
                continue;
            }
            if (!imageReady(jobs, p.poster)) p.poster = null;
            if (!imageReady(jobs, p.image)) {
                if (p.externalId.isEmpty() || p.poster == null) continue;
                p.image = p.poster;
            }
            out.add(p);
        }
        return out;
    }

    private void queueImage(Map<File, java.util.concurrent.Future<?>> jobs, File file, String base, String path) {
        if (file == null || path == null || file.isFile()) return;
        jobs.computeIfAbsent(file, ignored -> net.submit(() -> { download(base + path, file, base.isEmpty()); return null; }));
    }

    private static boolean imageReady(Map<File, java.util.concurrent.Future<?>> jobs, File file)
            throws InterruptedException {
        if (file == null) return false;
        java.util.concurrent.Future<?> job = jobs.get(file);
        try {
            if (job != null) job.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
        return file.isFile();
    }

    /** Films and series alternating, without repeats or titles that have no backdrop. */
    private static List<JSONObject> merge(JSONArray movies, JSONArray shows) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.max(movies.length(), shows.length()); i++) {
            for (JSONArray a : new JSONArray[]{movies, shows}) {
                if (i >= a.length()) continue;
                JSONObject o = a.getJSONObject(i);
                if(o.optBoolean("adult",false))continue;
                if (o.optString("backdrop_path", "").isEmpty()) continue;
                if (seen.add(o.getString("_kind") + o.getInt("id"))) out.add(o);
            }
        }
        return out;
    }

    /**
     * Up to MAX_PICKS titles that stream in India on an installed app, taken from the queues in the given pattern
     * ({0, 0, 1} = two from the first queue, then one from the second). When a queue runs dry the others fill in,
     * so the mix only drifts if TMDB really has too few. Titles in {@code exclude} are skipped.
     */
    @SafeVarargs
    private final List<Pick> keepMixed(String key, Map<Integer, String[]> services, String tab, Set<String> exclude,
                                 int[] pattern, List<JSONObject>... queues) throws Exception {
        int[] next = new int[queues.length];
        List<Pick> picks = new ArrayList<>();
        Map<String, Integer> langs = new LinkedHashMap<>();
        int checks = 0;
        for (int slot = 0; picks.size() < MAX_PICKS && checks < MAX_CHECKS; slot++) {
            Pick got = null;
            boolean anyLeft = false;
            // Try this slot's queue first, then the others.
            for (int t = 0; t < queues.length && got == null; t++) {
                int q = (pattern[slot % pattern.length] + t) % queues.length;
                List<JSONObject> queue = queues[q];
                while (got == null && next[q] < queue.size() && checks < MAX_CHECKS) {
                    lookAhead(key, queue, next[q], exclude);
                    JSONObject o = queue.get(next[q]++);
                    if (exclude.contains(o.getString("_kind") + o.getInt("id"))||!ContentLanguages.allows(languages(),o.optString("original_language"))) continue;
                    checks++;
                    got = accept(key, services, tab, o);
                }
                if (next[q] < queue.size()) anyLeft = true;
            }
            if (got != null) {
                picks.add(got);
                exclude.add(got.kind + got.id);
                langs.merge(got.lang, 1, Integer::sum);
            } else if (!anyLeft) break;
        }
        Log.i(TAG, tab + ": " + picks.size() + " picks from " + checks + " checks, languages " + langs);
        return picks;
    }

    /** The title as a pick if it streams (subscription) in India on an installed app; downloads its backdrop. */
    private Pick accept(String key, Map<Integer, String[]> services, String tab, JSONObject o) throws Exception {
        if(!ContentLanguages.allows(languages(),o.optString("original_language")))return null;
        String kind = o.getString("_kind");
        java.util.concurrent.Future<JSONObject> lookup = providerLookups.get(kind + o.getInt("id"));
        JSONObject providers;
        try {
            providers = lookup != null ? lookup.get() : get(key, lookupPath(kind, o.getInt("id"), o.optString("original_language", "")));
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
        JSONObject details = providers;
        if (elsewhere != null) providers = details.getJSONObject("watch/providers");
        JSONObject in = providers.getJSONObject("results").optJSONObject(region);
        JSONArray flat = in == null ? null : in.optJSONArray("flatrate");
        String[] s = null;
        if (elsewhere != null) {
            // No streaming app here: the trailer in YouTube, saying where the title streams.
            String trailer = trailer(details, o.optString("original_language", ""));
            if (trailer == null) return null;
            String where = flat != null && flat.length() > 0 ? flat.getJSONObject(0).getString("provider_name") : null;
            s = new String[]{"", elsewhere, where == null ? "Trailer" : "Trailer · on " + shortName(where)};
            Pick t = new Pick();
            t.intentUri = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=" + trailer)).setPackage(elsewhere)
                    .toUri(android.content.Intent.URI_INTENT_SCHEME);
            return fill(t, s, tab, kind, o);
        }
        if (flat == null) return null;
        for (int i = 0; i < flat.length() && s == null; i++) s = services.get(flat.getJSONObject(i).getInt("provider_id"));
        if (s == null) return null;
        return fill(new Pick(), s, tab, kind, o);
    }

    /** Where it streams; with no streaming app here, also its details and videos (for the trailer) in one request. */
    private String lookupPath(String kind, int id, String lang) {
        return "/" + kind + "/" + id + (elsewhere == null ? "/watch/providers"
                : "?append_to_response=videos,watch/providers&include_video_language=en,null" + (lang.matches("[a-z]{2}") ? "," + lang : ""));
    }

    /** The title's YouTube trailer (official ones first, in English or its own language), or null. */
    private static String trailer(JSONObject details, String lang) throws Exception {
        JSONObject v = details.optJSONObject("videos");
        JSONArray a = v == null ? null : v.optJSONArray("results");
        if (a == null) return null;
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (!"YouTube".equals(x.optString("site")) || x.optString("key").isEmpty()) continue;
            String type = x.optString("type");
            int score = (type.equals("Trailer") ? 4 : type.equals("Teaser") ? 2 : 0) + (x.optBoolean("official") ? 1 : 0);
            if (type.equals("Trailer") || type.equals("Teaser")) {
                if (score > bestScore) { best = x.getString("key"); bestScore = score; }
            }
        }
        return best;
    }

    /** "Amazon Prime Video" → "Prime Video", "Netflix Standard with Ads" → "Netflix": our names where we have one. */
    private static String shortName(String provider) {
        String[] s = service(provider);
        return s != null ? s[2] : provider;
    }

    private Pick fill(Pick p, String[] s, String tab, String kind, JSONObject o) throws Exception {
        p.id = o.getInt("id");
        p.kind = kind;
        p.tab = tab;
        p.market = region;
        p.lang = o.optString("original_language", "");
        p.title = o.optString(kind.equals("tv") ? "name" : "title", "");
        p.overview = o.optString("overview", "");
        String date = o.optString(kind.equals("tv") ? "first_air_date" : "release_date", "");
        p.releaseDate=date;p.weeklyTrending=o.optBoolean("_weekly",false);
        p.year = date.length() >= 4 ? date.substring(0,4) : "";
        p.rating = (float)o.optDouble("vote_average",0);
        p.pkg = s[1];
        p.service = s[2];
        // "Film · Action · Telugu · Netflix": kind, first genre, language when not English, the app.
        StringBuilder m = new StringBuilder(kind.equals("tv") ? "Series" : "Film");
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null && g.length() > 0 && genres.containsKey(g.getInt(0))) {
            m.append(" · ").append(genres.get(g.getInt(0)).replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-Fi"));
        }
        if (!p.lang.equals("en") && !Region.language(p.lang).isEmpty()) m.append(" · ").append(Region.language(p.lang));
        m.append(" · ").append(p.service);
        p.meta = m.toString();
        // Pictures are downloaded once all picks are chosen (downloadImages), several at a time.
        p.image = new File(dir, kind + "_" + p.id + "_w1280.jpg");
        p.backdropPath = o.optString("backdrop_path", "");
        String posterPath = o.optString("poster_path", "");
        if (!posterPath.isEmpty() && !posterPath.equals("null")) {
            p.poster = new File(dir, kind + "_" + p.id + "_poster_w500.jpg");
            p.posterPath = posterPath;  // the backdrop alone is fine if this fails
        }
        return p;
    }

    /** Installed TV apps by simplified name ("viaplay", "canal+"), as {"", package, name}. */
    private Map<String, String[]> installedByName() {
        Map<String, String[]> out = new java.util.HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        android.content.Intent q = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            String n = simple(label);
            // YouTube shows the trailers; as a "streaming service" (YouTube Premium) it would only show its originals.
            if (n.length() >= 3 && !n.startsWith("youtube")) out.put(n, new String[]{"", ri.activityInfo.packageName, label});
        }
        return out;
    }

    /**
     * The installed app named like this TMDB service: the same name ("Viaplay", "RTL+"), or one is the other plus
     * more words ("Sky Go" for "Sky Go Italia"), when the shorter is at least four letters.
     */
    private static String[] byName(Map<String, String[]> apps, String provider) {
        String n = simple(provider);
        if (n.isEmpty()) return null;
        String[] exact = apps.get(n);
        if (exact != null) return exact;
        for (Map.Entry<String, String[]> e : apps.entrySet()) {
            String a = e.getKey();
            if ((a.length() >= 4 && n.startsWith(a + " ")) || (n.length() >= 4 && a.startsWith(n + " "))) return e.getValue();
        }
        return null;
    }

    /** Lower case, letters, digits, + and single spaces; "TV" and "Android TV" dropped ("Viaplay TV" → "viaplay"). */
    private static String simple(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+ ]", " ").replaceAll("\\s+", " ").trim();
        n = n.replaceAll("( android)? tv$", "").trim();
        return n;
    }

    private static String[] service(String providerName) {
        String n = providerName.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) if (n.contains(s[0])) return s;
        return null;
    }

    private static JSONArray tag(JSONArray a, String kind) throws Exception {
        for (int i = 0; i < a.length(); i++) a.getJSONObject(i).put("_kind", kind);
        return a;
    }

    private void save(List<Pick> picks) throws Exception {
        save(picks, dir);
    }

    private void save(List<Pick> picks, File cache) throws Exception {
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create " + cache);
        JSONArray a = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (Pick p : picks) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("tab", p.tab).put("title", p.title)
                    .put("pkg", p.pkg).put("service", p.service).put("lang", p.lang).put("meta", p.meta).put("image", p.image.getName())
                    .put("poster", p.poster == null ? "" : p.poster.getName()).put("uri", p.intentUri == null ? "" : p.intentUri)
                    .put("overview",p.overview).put("year",p.year).put("rating",p.rating)
                    .put("external_id",p.externalId).put("cast",p.cast).put("rating_source",p.ratingSource)
                    .put("release_date",p.releaseDate).put("weekly_trending",p.weeklyTrending).put("market",p.market).put("availability_verified",Recommendations.isAppRow(p.tab)));
            keep.add(p.image.getName());
            if (p.poster != null) keep.add(p.poster.getName());
        }
        File tmp = new File(cache, "picks.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(cache, "picks.json"))) throw new IOException("Cannot save picks");
        File[] files = cache.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jpg") && !keep.contains(f.getName())) f.delete();
    }

    private boolean installed(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String day(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Whether TMDB takes this key (Keys.OK, REJECTED or OFFLINE), for Control Center. Blocking. */
    static int check(String key) {
        try {
            get(key, "/configuration");
            return Keys.OK;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            return m.startsWith("HTTP 401") || m.startsWith("HTTP 404") ? Keys.REJECTED : Keys.OFFLINE;
        } catch (Exception e) {
            return Keys.REJECTED;  // a malformed key (spaces, odd characters) doesn't even make a URL
        }
    }

    /** TMDB accepts either a v3 API key (query parameter) or a v4 read access token (Bearer header). */
    private static JSONObject get(String key, String path) throws Exception {
        for(int attempt=0;;attempt++) {
            try {return request(key,path);}
            catch(IOException e) {
                String message=String.valueOf(e.getMessage());
                if(attempt>=2||(message.startsWith("HTTP 4")&&!message.startsWith("HTTP 429")))throw e;
                Thread.sleep(300L<<attempt);
            }
        }
    }

    String titleEntity(Pick pick, int timeout) throws Exception {
        String key = key();
        if (key == null) throw new IOException("TMDB key not set");
        return request(key, "/" + pick.kind + "/" + pick.id + "/external_ids", timeout, timeout)
                .optString("wikidata_id", "");
    }

    private final android.util.LruCache<String, TitleMetadata> titleMetadata = new android.util.LruCache<>(48);

    TitleMetadata details(Pick pick) throws Exception {
        String key = key();
        if (key == null) return null;
        String kind = pick.kind;
        int id = pick.id;
        if (!TitleLinks.catalog(pick)) {
            int verifiedSeries = TitleArtwork.verifiedSeries(pick, pick.image);
            if (verifiedSeries > 0) {
                kind = "tv";
                id = verifiedSeries;
            } else {
                String query = URLEncoder.encode(TitleArtwork.queryTitle(pick.title), "UTF-8");
                JSONObject results = request(key, "/search/multi?include_adult=false&query=" + query, 4000, 5000);
                ContinueOptions.Episode episode = ContinueOptions.episode(pick);
                String context = episode == null ? pick.meta : "Season " + episode.season;
                JSONObject match = TitleArtwork.match(results.optJSONArray("results"), pick.title, context,
                        pick.mediaKind, pick.releaseDate, path -> request(key, path, 4000, 5000));
                if (match == null) return null;
                kind = match.getString("media_type");
                id = match.getInt("id");
            }
        }
        String cacheKey = kind + ":" + id;
        TitleMetadata cached = titleMetadata.get(cacheKey);
        if (cached != null) return cached;
        JSONObject details = request(key, "/" + kind + "/" + id + "?append_to_response=credits", 4000, 5000);
        TitleMetadata result = new TitleMetadata(kind, details);
        titleMetadata.put(cacheKey, result);
        return result;
    }

    static final class TitleMetadata {
        final String kind, year, genres, overview, cast;
        final float rating;
        private final Map<Integer, Integer> seasonSizes = new java.util.HashMap<>();

        TitleMetadata(String kind, JSONObject details) {
            this.kind = kind;
            String date = details.optString("tv".equals(kind) ? "first_air_date" : "release_date");
            year = date.length() >= 4 && date.substring(0, 4).matches("[0-9]{4}") ? date.substring(0, 4) : "";
            overview = details.isNull("overview") ? "" : details.optString("overview");
            rating = details.optInt("vote_count") > 0 ? (float) details.optDouble("vote_average", 0) : 0;
            genres = names(details.optJSONArray("genres"), 2, " / ");
            JSONObject credits = details.optJSONObject("credits");
            cast = names(credits == null ? null : credits.optJSONArray("cast"), 4, ", ");
            JSONArray seasons = details.optJSONArray("seasons");
            for (int i = 0; "tv".equals(kind) && seasons != null && i < seasons.length(); i++) {
                JSONObject season = seasons.optJSONObject(i);
                if (season != null && season.optInt("season_number", -1) >= 0 && season.optInt("episode_count") > 0) {
                    seasonSizes.put(season.optInt("season_number"), season.optInt("episode_count"));
                }
            }
        }

        int seasonSize(int season) { return seasonSizes.getOrDefault(season, 0); }

        private static String names(JSONArray list, int limit, String separator) {
            Set<String> names = new java.util.LinkedHashSet<>();
            for (int i = 0; list != null && i < list.length() && names.size() < limit; i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry != null && !entry.isNull("name")) {
                    String name = entry.optString("name").trim();
                    if (!name.isEmpty()) names.add(name);
                }
            }
            return String.join(separator, names);
        }
    }

    private static JSONObject request(String key,String path) throws Exception {
        return request(key, path, 15_000, 20_000);
    }

    private static JSONObject request(String key, String path, int connectTimeout, int readTimeout) throws Exception {
        boolean bearer = key.length() > 40;
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            // The path is logged without the key.
            if (code != 200) throw new IOException("HTTP " + code + " for " + path.replaceAll("[?].*", ""));
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        // No disconnect() on success: reading the reply to the end returns the connection to the pool, so the next
        // request skips the TLS handshake (each new one cost about a second on this TV).
    }

    private static HttpURLConnection imageConnection(String url, boolean managed) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(managed ? 5000 : 15_000);
        c.setReadTimeout(managed ? 8000 : 30_000);
        if (managed) c.setInstanceFollowRedirects(false);
        return c;
    }

    private static void download(String url, File to, boolean managed) throws IOException {
        File directory = to.getParentFile();
        if (directory != null && !directory.isDirectory()
                && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create title artwork cache");
        }
        HttpURLConnection c = imageConnection(url, managed);
        if (managed) {
            try {
                if (c.getResponseCode() == 307) {
                    String target = ManagedCatalog.posterRedirect(url, c.getHeaderField("Location"));
                    if (target != null) {
                        c.disconnect();
                        c = imageConnection(target, true);
                    }
                }
            } catch (IOException unavailable) {
                c.disconnect();
                throw unavailable;
            }
        }
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            if (managed && (c.getResponseCode() != 200 || c.getContentType() == null
                    || !c.getContentType().startsWith("image/"))) throw new IOException("Managed image unavailable");
            byte[] b = new byte[16384];
            int total = 0;
            for (int n; (n = in.read(b)) > 0; ) {
                total += n;
                if (managed && total > 12 * 1024 * 1024) throw new IOException("Managed image exceeds size limit");
                out.write(b, 0, n);
            }
        } catch (IOException e) {
            c.disconnect();
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(to)) throw new IOException("Cannot save " + to.getName());
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }
}
