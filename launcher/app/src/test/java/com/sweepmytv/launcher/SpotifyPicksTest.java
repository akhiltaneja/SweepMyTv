package com.sweepmytv.launcher;

import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SpotifyPicksTest {
    @Test public void indiaGetsLocalAndGlobalPlaylistsWithNoDuplicates() {
        List<String[]> india = SpotifyPicks.playlists("IN");
        Set<String> ids = new HashSet<>();
        assertEquals(7, india.size());
        assertEquals("Top 50 - India", india.get(0)[1]);
        for (String[] playlist : india) assertTrue(ids.add(playlist[0]));
        assertEquals("Today’s Top Hits", SpotifyPicks.playlists("US").get(0)[1]);
        assertEquals("Spotify · Music picks", SpotifyPicks.row().title);
    }

    @Test public void playlistLinksOpenTheExactItemInSpotify() throws Exception {
        String id = SpotifyPicks.playlists("IN").get(0)[0];
        Trending.Pick pick = SpotifyPicks.pick(id, "India chart", null);
        Intent intent = Intent.parseUri(pick.intentUri, Intent.URI_INTENT_SCHEME);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("com.spotify.tv.android", intent.getPackage());
        assertEquals("https://open.spotify.com/playlist/" + id, intent.getDataString());
        assertEquals(-1f, pick.progress, 0f);
    }

    @Test public void onlySpotifyHttpsArtworkIsAccepted() {
        assertTrue(SpotifyPicks.approvedArtwork("https://i.scdn.co/image/cover"));
        assertTrue(SpotifyPicks.approvedArtwork("https://charts-images.scdn.co/photo"));
        assertFalse(SpotifyPicks.approvedArtwork("http://i.scdn.co/image/cover"));
        assertFalse(SpotifyPicks.approvedArtwork("https://i.scdn.co.example.org/image/cover"));
        assertFalse(SpotifyPicks.approvedArtwork("content://i.scdn.co/image/cover"));
    }
}
