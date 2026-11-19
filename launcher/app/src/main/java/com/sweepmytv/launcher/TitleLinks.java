package com.sweepmytv.launcher;

import android.content.Intent;
import android.net.Uri;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Resolves a catalog work by ID. Only known provider URLs may become app intents. */
final class TitleLinks {
    private static final int TIMEOUT_MS = 2500;
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String PRIME_VIDEO = "com.amazon.amazonvideo.livingroom";
    private static final String PRIME_ID = "[A-Z0-9]{10,26}";
    private final LruCache<String, String> cache = new LruCache<>(96);

    static boolean catalog(Trending.Pick pick) {
        return pick.id > 0 && ("movie".equals(pick.kind) || "tv".equals(pick.kind));
    }

    private static String property(Trending.Pick pick) {
        if (!catalog(pick) || pick.pkg == null) return null;
        switch (pick.pkg) {
            case "com.apple.atve.androidtv.appletv": return "tv".equals(pick.kind) ? "P9751" : "P9586";
            case NETFLIX: return "P1874";
            case "com.crunchyroll.crunchyroid": return "P11330";
            case PRIME_VIDEO: return "P8055";
            case "in.startv.hotstar": return "P11049";
            case "com.sonyliv": return "tv".equals(pick.kind) ? "P9465" : null;
            default: return null;
        }
    }

    static boolean supports(Trending.Pick pick) { return property(pick) != null; }

    private static String key(Trending.Pick pick, String market) {
        return pick.kind + ":" + pick.id + ":" + pick.pkg + ":" + market;
    }

    String cached(Trending.Pick pick, String market) { return cache.get(key(pick, market)); }

    String resolve(Trending trending, Trending.Pick pick, String market) throws Exception {
        String property = property(pick);
        if (property == null) return "";
        String entity = trending.titleEntity(pick, TIMEOUT_MS);
        String url = "";
        if (entity.matches("Q[1-9][0-9]{0,15}")) {
            HttpURLConnection request = (HttpURLConnection) new URL("https://www.wikidata.org/w/api.php"
                    + "?action=wbgetclaims&format=json&entity=" + entity + "&property=" + property).openConnection();
            request.setConnectTimeout(TIMEOUT_MS);
            request.setReadTimeout(TIMEOUT_MS);
            request.setInstanceFollowRedirects(false);
            request.setRequestProperty("User-Agent", "SweepTV/1.3 (https://github.com/akhiltaneja/SweepMyTv)");
            try (InputStream stream = request.getInputStream()) {
                if (request.getResponseCode() != 200) throw new java.io.IOException("Wikidata response");
                JSONObject result = new JSONObject(new String(Trending.readAll(stream), StandardCharsets.UTF_8));
                if (result.has("error")) throw new java.io.IOException("Wikidata lookup failed");
                JSONObject claims = result.optJSONObject("claims");
                url = url(pick, market, claims == null ? null : claims.optJSONArray(property));
            } finally {
                request.disconnect();
            }
        }
        cache.put(key(pick, market), url);
        return url;
    }

    static String url(Trending.Pick pick, String market, JSONArray claims) {
        String property = property(pick);
        if (claims == null || property == null) return "";
        String value = null;
        boolean preferred = false;
        boolean ambiguous = false;
        for (int i = 0; i < claims.length(); i++) {
            JSONObject claim = claims.optJSONObject(i);
            if (claim == null || "deprecated".equals(claim.optString("rank"))) continue;
            JSONObject snak = claim.optJSONObject("mainsnak");
            JSONObject data = snak == null ? null : snak.optJSONObject("datavalue");
            if (data == null || !(data.opt("value") instanceof String)) continue;
            String next = data.optString("value");
            boolean preferredClaim = "preferred".equals(claim.optString("rank"));
            if (preferred && !preferredClaim) continue;
            if (value == null || preferredClaim && !preferred) {
                value = next;
                preferred = preferredClaim;
                ambiguous = false;
            } else if (!next.equals(value)) {
                ambiguous = true;
            }
        }
        if (value == null || ambiguous) return "";

        switch (property) {
            case "P9751": case "P9586":
                String storefront = market.matches("[A-Z]{2}") ? "/" + market.toLowerCase(java.util.Locale.ROOT) : "";
                return value.matches("umc\\.cmc\\.[a-z0-9]{22,25}") ? "https://tv.apple.com" + storefront
                        + ("tv".equals(pick.kind) ? "/show/" : "/movie/") + value : "";
            case "P1874":
                return value.matches("[0-9]{1,12}") ? "https://www.netflix.com/title/" + value : "";
            case "P11330":
                return value.matches("[A-Z0-9]{9,10}") ? "https://www.crunchyroll.com/series/" + value : "";
            case "P8055":
                return value.matches(PRIME_ID) ? primeVideoDetail(value).toString() : "";
            case "P11049":
                return value.matches("[0-9]{1,15}") ? "https://www.hotstar.com/" + value : "";
            case "P9465":
                return value.matches("1[0-9]{9}") ? "https://www.sonyliv.com/shows/" + value : "";
            default:
                return "";
        }
    }

    static Intent appIntent(Trending.Pick pick, String url) {
        Uri target = Uri.parse(url);
        String path = target.getPath();
        if (PRIME_VIDEO.equals(pick.pkg) && "https".equals(target.getScheme())
                && ("www.primevideo.com".equalsIgnoreCase(target.getHost()) || "primevideo.com".equalsIgnoreCase(target.getHost()))
                && path != null && path.matches("/detail/(?:[^/]+/)?" + PRIME_ID + "/?")) {
            target = primeVideoDetail(target.getLastPathSegment());
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, target).setPackage(pick.pkg).addCategory(Intent.CATEGORY_BROWSABLE);
        if (NETFLIX.equals(pick.pkg) && "https".equals(target.getScheme()) && "www.netflix.com".equalsIgnoreCase(target.getHost())
                && path != null && path.matches("/(?:[a-z]{2}/)?title/[0-9]{1,12}/?")) {
            // Netflix TV uses this source for title delivery to its existing activity.
            intent.putExtra("source", "30").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        return intent;
    }

    private static Uri primeVideoDetail(String id) {
        return Uri.parse("https://app.primevideo.com/detail").buildUpon().appendQueryParameter("gti", id).build();
    }
}
