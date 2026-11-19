package com.sweepmytv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class RottenTomatoesPicksTest {
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom";
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void keepsEditorialOrderAndSkipsFilmsWithoutAnEligibleProviderMatch() throws Exception {
        JSONArray movies = new JSONArray()
                .put(entry(9, "tt0000090", 94))
                .put(entry(2, "", 89))
                .put(entry(3, "", 96))
                .put(entry(4, "", 99))
                .put(entry(7, "", 98))
                .put(entry(1, "", 90))
                .put(entry(1, "", 90))
                .put(entry(5, "", 95).put("reviewUrl", "https://www.rottentomatoes.com.invalid/m/example"));
        RottenTomatoesPicks picks = RottenTomatoesPicks.parse(new JSONObject().put("version", 1).put("movies", movies));

        Trending.Pick managed = movie(-81, NETFLIX, "IN");
        managed.externalId = "tt0000090";
        Trending.Pick tmdb = movie(1, NETFLIX, "IN");
        tmdb.intentUri = "netflix://title/1";
        Trending.Pick series = movie(7, NETFLIX, "IN");
        series.kind = "tv";
        var catalog = List.of(tmdb, managed, movie(2, NETFLIX, "IN"), movie(3, PRIME, "IN"),
                movie(4, NETFLIX, "US"), movie(5, NETFLIX, "IN"), series);
        Channels.Channel row = picks.row(catalog, "IN", Set.of(NETFLIX));

        assertEquals(RottenTomatoesPicks.KEY, row.key);
        assertEquals(2, row.items.size());
        assertEquals(managed.externalId, row.items.get(0).externalId);
        assertEquals(tmdb.intentUri, row.items.get(1).intentUri);
        assertTrue(row.items.get(1).meta.contains("90%"));
        assertFalse(tmdb.meta.contains("Rotten Tomatoes"));
        assertTrue(picks.row(catalog, "IN", Set.of()).items.isEmpty());
    }

    private JSONObject entry(int id, String imdb, int score) throws Exception {
        return new JSONObject().put("tmdbId", id).put("imdbId", imdb).put("tomatometer", score)
                .put("reviewUrl", "https://www.rottentomatoes.com/m/fixture_" + id);
    }

    private Trending.Pick movie(int id, String app, String region) throws Exception {
        File art = folder.newFile();
        Trending.Pick pick = RecommendationsTest.pick(id, app, art);
        pick.kind = "movie";
        pick.market = region;
        pick.poster = art;
        return pick;
    }
}
