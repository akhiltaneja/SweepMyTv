package com.sweepmytv.launcher;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SportsScoresTest {
    @Test public void savedScoresRestoreAfterRestartWithACachedLabelAndExpire() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        Cricket.Match match = SportsScores.parse(event(false, "", "in"), "Example tour",
                false, ZoneId.of("UTC"), true);
        SportsScores first = new SportsScores(context);
        first.saveCached(false, List.of(match), now);
        first.close();
        SportsScores restarted = new SportsScores(context);
        try {
            List<Cricket.Match> restored = restarted.cached(false, now + 1000);
            assertEquals(1, restored.size());
            assertEquals(match.s1, restored.get(0).s1);
            assertEquals(match.scheduledAt, restored.get(0).scheduledAt);
            assertEquals("Cached", SportsDashboard.status(restored.get(0)));
            assertTrue(SportsDashboard.detail(restored.get(0)).startsWith("Cached · "));
            assertEquals("Live", SportsDashboard.status(match));
            assertTrue(restarted.cached(false, now + 86_400_001L).isEmpty());
            assertTrue(restarted.cached(true, now).isEmpty());
        } finally {
            restarted.close();
        }
    }

    @Test public void scheduledFootballUsesLocalDateAndSuppressesPlaceholderScores() throws Exception {
        Cricket.Match match = SportsScores.parse(event(true, "STATUS_SCHEDULED", "pre"), "Premier League",
                true, ZoneId.of("Asia/Kolkata"), true);
        assertEquals("Home vs Away", match.name);
        assertEquals("Mon, 00:30", match.start);
        assertEquals("", match.s1);
        assertFalse(match.live);
    }

    @Test public void cricketKeepsAnInningsScoreAndTreatsStumpsAsPaused() throws Exception {
        JSONObject fixture = event(false, "", "in");
        fixture.getJSONObject("status").put("session", "Day 2").getJSONObject("type").put("description", "Stumps");
        JSONObject game = fixture.getJSONArray("competitions").getJSONObject(0);
        game.put("description", "1st Test").put("venue", new JSONObject("{\"address\":{\"city\":\"Johannesburg\"}}"));
        Cricket.Match match = SportsScores.parse(fixture, "Bangladesh tour of South Africa",
                false, ZoneId.of("UTC"), true);
        assertTrue(match.paused);
        assertFalse(match.live);
        assertEquals("120/3", match.s1);
        assertEquals("Stumps & day over", match.status);
        assertEquals("Stumps", SportsDashboard.status(match));
        assertEquals("Day 2", match.session);
        assertEquals("1st Test", match.kind);
        assertEquals("Johannesburg", match.venue);
        assertEquals("Bangladesh tour of South Africa", match.league);
    }

    @Test public void liveAndPausedComeBeforeUpcomingToursAndResultsAreOmitted() throws Exception {
        Cricket.Match upcoming = SportsScores.parse(event(false, "", "pre"), "Nepal Premier League",
                false, ZoneId.of("Asia/Kolkata"), true);
        Cricket.Match tour = SportsScores.parse(event(false, "", "pre"), "Sri Lanka tour of Pakistan",
                false, ZoneId.of("Asia/Kolkata"), true);
        Cricket.Match live = SportsScores.parse(event(false, "", "in"), "Women’s Big Bash League",
                false, ZoneId.of("Asia/Kolkata"), true);
        Cricket.Match ended = SportsScores.parse(event(false, "", "post"), "Earlier cricket final",
                false, ZoneId.of("Asia/Kolkata"), true);
        assertEquals("Mon 16 Nov, 00:30", tour.start);
        assertEquals("", tour.s1);
        assertEquals(List.of(live, tour, upcoming), SportsDashboard.fixtures(List.of(ended, upcoming, live, tour)));
    }

    @Test public void aLiveFlagWithOnlyAStartTimeStaysUpcoming() throws Exception {
        JSONObject fixture = event(false, "", "in");
        fixture.getJSONObject("status").put("summary", "Starts at 14:00 local time");
        var teams = fixture.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
        for (int i = 0; i < teams.length(); i++) teams.getJSONObject(i).put("score", "");
        Cricket.Match match = SportsScores.parse(fixture, "England Lions tour of West Indies",
                false, ZoneId.of("UTC"), true);
        assertEquals("Upcoming", SportsDashboard.status(match));
        assertEquals("Sun 15 Nov, 19:00", match.start);
    }

    private static JSONObject event(boolean football, String name, String state) throws Exception {
        JSONObject status = new JSONObject().put("type", new JSONObject().put("name", name).put("state", state))
                .put("summary", "Stumps &amp; day over");
        JSONObject game = new JSONObject("""
                {"competitors":[
                  {"homeAway":"away","score":"120/3","team":{"displayName":"Away","abbreviation":"AWY"}},
                  {"homeAway":"home","score":"90","team":{"displayName":"Home","abbreviation":"HOM"}}]}
                """);
        JSONObject event = new JSONObject().put("id", "fixture").put("date", "2026-11-15T19:00Z")
                .put("competitions", new org.json.JSONArray().put(game));
        (football ? game : event).put("status", status);
        return event;
    }
}
