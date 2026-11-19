package com.sweepmytv.launcher;

import static org.junit.Assert.*;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ManagedCatalogTest {
    @Test public void incompleteSweepTitlesCanBeReadButAreWithheldFromTheScreen() throws Exception {
        JSONObject item = new JSONObject().put("id", "tt1234567").put("kind", "tv").put("title", "Offline title")
                .put("provider", "netflix").put("language", "en").put("editorial_rank", 2)
                .put("link", "https://www.netflix.com/title/1234567").put("summary", "Fixture synopsis");
        JSONObject feed = new JSONObject().put("version", 1).put("region", "IN")
                .put("titles", new JSONArray().put(item));
        List<Trending.Pick> picks = ManagedCatalog.parseSweep(feed, "IN", new File("test-cache"));
        assertEquals(1, picks.size());
        assertTrue(picks.get(0).sweep);
        assertNull(picks.get(0).image);
        assertEquals(2, picks.get(0).editorialRank);
        assertNotNull(picks.get(0).intentUri);
        assertFalse(Trending.readySweepPick(picks.get(0)));
        assertThrows(IOException.class, () -> ManagedCatalog.parseSweep(feed, "US", new File("test-cache")));
    }

    @Test public void rendersOnlyInstalledProvidersAndKeepsTheLanguageFilterStrict() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(title("tt1234567", "apple-tv-plus", "en", "https://tv.apple.com/in/show/example"));
        rows.put(title("tt1234568", "netflix", "en", "https://www.netflix.com/title/1234"));
        rows.put(title("tt1234569", "apple-tv-plus", "", "https://untrusted.example/other"));
        rows.put(title("tt1234567", "apple-tv-plus", "en", ""));
        JSONObject feed = new JSONObject().put("version", 1).put("region", "IN").put("titles", rows);
        List<Trending.Pick> titles = ManagedCatalog.parse(feed, "IN", new File("test-cache"),
                pkg -> pkg.equals("com.apple.atve.androidtv.appletv"));
        assertEquals(2, titles.size());
        assertEquals(List.of(titles.get(0)), ContentLanguages.filter(titles, Set.of("en")));
        assertEquals("IMDb", titles.get(0).ratingSource);
        assertFalse(TitleLinks.catalog(titles.get(0)));
        assertFalse(TitleCovers.discovery(titles.get(0)));
        Intent link = Intent.parseUri(titles.get(0).intentUri, Intent.URI_INTENT_SCHEME);
        assertEquals("com.apple.atve.androidtv.appletv", link.getPackage());
        assertNull(titles.get(1).intentUri);
        assertThrows(IOException.class, () -> ManagedCatalog.parse(feed, "US", new File("test-cache"), pkg -> true));
    }

    @Test public void aProviderOnlyTitleDoesNotBecomeTrending() throws Exception {
        JSONArray rows = new JSONArray().put(title("tt1234567", "apple-tv-plus", "en", "").put("row", "provider"));
        JSONObject feed = new JSONObject().put("version", 1).put("region", "IN").put("titles", rows);
        List<Trending.Pick> titles = ManagedCatalog.parse(feed, "IN", new File("test-cache"), pkg -> true);
        assertEquals(1, titles.size());
        assertEquals(Recommendations.appKey("com.apple.atve.androidtv.appletv"), titles.get(0).tab);
    }

    @Test public void decoratedPostersInOldFeedsUseTheExactTitlesCleanPoster() throws Exception {
        JSONObject film = title("tt1234567", "netflix", "en", "")
                .put("poster", "https://btttr.cc/poster/imdb/poster-default/tt7654321.jpg");
        JSONObject feed = new JSONObject().put("version", 1).put("region", "IN")
                .put("titles", new JSONArray().put(film));
        List<Trending.Pick> titles = ManagedCatalog.parse(feed, "IN", new File("test-cache"), pkg -> true);
        assertEquals("https://images.metahub.space/poster/large/tt1234567/img", titles.get(0).posterPath);
        String origin = "https://live.metahub.space/poster/large/tt1234567/img";
        assertEquals(origin, ManagedCatalog.posterRedirect(titles.get(0).posterPath, origin));
        assertNull(ManagedCatalog.posterRedirect(titles.get(0).posterPath, origin.replace("1234567", "7654321")));
        assertNull(ManagedCatalog.posterRedirect(titles.get(0).posterPath, origin.replace("live.metahub.space", "other.example")));
    }

    private static JSONObject title(String id, String provider, String language, String link) throws Exception {
        return new JSONObject().put("id", id).put("kind", "tv").put("title", id)
                .put("provider", provider).put("language", language).put("link", link).put("rating", 8.2)
                .put("poster", "https://image.tmdb.org/t/p/w500/fixture.jpg");
    }
}
