package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FormulaOneTest {
    static final long NOW = FormulaOne.time("2026-10-31T12:00:00Z");
    static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    static FormulaOne.State fixture(long now) throws Exception {
        JSONObject raw = new JSONObject();
        for (String name : new String[]{"calendar", "drivers", "teams", "latest", "previous1", "previous2", "status"}) {
            try (InputStream in = FormulaOneTest.class.getResourceAsStream("/f1/" + name + ".json")) {
                assertNotNull(in); raw.put(name, new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                raw.put(name + "_at", now);
            }
        }
        for (String[] item : new String[][]{{"open_sessions", "open-sessions"}, {"open_drivers", "open-drivers"}}) {
            try (InputStream in = FormulaOneTest.class.getResourceAsStream("/f1/" + item[1] + ".json")) {
                raw.put(item[0], new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8))).put(item[0] + "_at", now);
            }
        }
        try (InputStream in = FormulaOneTest.class.getResourceAsStream("/f1/open-result.json")) {
            JSONObject saved = new JSONObject().put("at", now).put("rows", new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            raw.put("open_results", new JSONObject().put("11398", saved));
        }
        return new FormulaOne.State(raw);
    }
    static FormulaOne.Card card(List<FormulaOne.Card> cards, String key) {
        for (FormulaOne.Card c : cards) if (key.equals(c.key)) return c;
        throw new AssertionError("Missing card " + key);
    }

    @Test public void realFixturesProduceLocationCountdownRecentResultsAndPoints() throws Exception {
        List<FormulaOne.Card> cards = fixture(NOW).cards(NOW, IST);
        FormulaOne.Card next = card(cards, "next");
        assertEquals("Mexico City Grand Prix", next.title);
        assertEquals("1d 08h 00m 00s", next.metric);
        assertTrue(next.body.contains("Mexico")); assertTrue(next.body.contains("Mon 2 Nov · 01:30 IST"));
        assertTrue(card(cards, "weekend").body.contains("Qualifying"));
        assertTrue(card(cards, "latest").body.contains("25 pts"));
        assertEquals("United States Grand Prix", card(cards, "latest").title);
        assertNotNull(card(cards, "previous1")); assertNotNull(card(cards, "previous2"));
        assertTrue(card(cards, "drivers").metric.contains("365")); assertTrue(card(cards, "drivers").foot.contains("R18"));
        assertTrue(card(cards, "teams").metric.contains("607"));
    }

    @Test public void onlyFreshSourceConfirmedSessionsAreLive() throws Exception {
        FormulaOne.State state = fixture(NOW);
        JSONObject session = state.raw.getJSONObject("status").getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0);
        session.put("date", "2026-10-31T11:00Z");
        session.getJSONObject("status").getJSONObject("type").put("state", "in");
        FormulaOne.Card status = card(state.cards(NOW, IST), "status");
        assertTrue(status.live); assertTrue(status.kicker.startsWith("LIVE"));
        status = card(state.cards(NOW + 3 * 60_000, IST), "status");
        assertFalse(status.live); assertTrue(status.foot.startsWith("Cached")); assertEquals("Status needs refresh", status.metric);
        state.raw.put("status_error", true);
        assertFalse(card(state.cards(NOW, IST), "status").live);
        state.raw.put("status_error", false).put("status_at", NOW + 60_000);
        assertFalse("A future cache timestamp cannot confirm a live session", card(state.cards(NOW, IST), "status").live);
    }

    @Test public void passingTheScheduledStartNeverInventsLiveState() throws Exception {
        long now = FormulaOne.time("2026-11-01T20:30:00Z");
        List<FormulaOne.Card> cards = fixture(now).cards(now, IST);
        for (FormulaOne.Card c : cards) assertFalse(c.live);
        assertEquals("Awaiting race status", card(cards, "next").metric);
        assertEquals(0, card(cards, "next").target);
        assertEquals("Start time reached", FormulaOne.countdown(now - 1, now));
        assertEquals("01h 02m 03s", FormulaOne.countdown(now + 3_723_000, now));
    }

    @Test public void mosaicHasOneCountdownAndPublishedPracticeClassification() throws Exception {
        List<FormulaOne.Card> cards = fixture(NOW).dashboard(NOW, IST);
        assertEquals(6, cards.size());
        assertEquals(1, cards.stream().filter(c -> c.target > 0).count());
        FormulaOne.Card session = card(cards, "classification");
        assertTrue(session.title.contains("Practice 2")); assertFalse(session.live);
        assertEquals("Charles LECLERC", session.table.get(0).name);
        assertEquals("1:17.827", session.table.get(0).value);
        assertTrue(session.body.contains("35 laps"));
        assertTrue(card(cards, "weekend").body.contains("Practice 2 · Completed"));
        assertTrue(card(cards, "weekend").body.contains("Practice 3 · Scheduled"));
        assertEquals("1:20.500", FormulaOne.duration(new JSONArray("[81.2,80.5,null]")));
        assertEquals("—", FormulaOne.duration(JSONObject.NULL));
        assertEquals(-1, FormulaOneDashboard.neighbor(0,0,1));
        assertEquals(-1, FormulaOneDashboard.neighbor(2,1,0));
        assertEquals(0, FormulaOneDashboard.neighbor(3,-1,0));
        assertEquals(1, FormulaOneDashboard.neighbor(0,1,0));
        assertEquals(3, FormulaOneDashboard.neighbor(1,0,1));
        assertEquals(1, FormulaOneDashboard.neighbor(4,0,-1));
    }

    @Test public void stalePracticeIsNotPresentedAsLiveTiming() throws Exception {
        FormulaOne.State state = fixture(NOW);
        state.raw.getJSONObject("open_results").getJSONObject("11398").put("error", true);
        FormulaOne.Card session = card(state.dashboard(NOW, IST), "classification");
        assertTrue(session.foot.startsWith("Cached")); assertFalse(session.live);
        assertTrue(session.body.contains("Live positions, gaps, tyres and flags are not connected"));
        List<FormulaOne.Card> empty = FormulaOne.State.empty().dashboard(NOW, IST);
        assertTrue(card(empty, "classification").table.isEmpty());
        for (FormulaOne.Card c : empty) assertFalse(c.live);
    }

    @Test public void unknownTimesAndMissingSourcesStayHonest() throws Exception {
        FormulaOne.State state = fixture(NOW);
        JSONArray races = state.raw.getJSONObject("calendar").getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        for (int i = 0; i < races.length(); i++) if (races.getJSONObject(i).getString("raceName").equals("Mexico City Grand Prix")) races.getJSONObject(i).remove("time");
        FormulaOne.Card next = card(state.cards(NOW, IST), "next");
        assertEquals(0, next.target); assertEquals("Start time TBC", next.metric);
        List<FormulaOne.Card> empty = FormulaOne.State.empty().cards(NOW, IST);
        assertEquals("Schedule unavailable", card(empty, "next").metric);
        assertEquals("Standings unavailable", card(empty, "drivers").metric);
        for (FormulaOne.Card c : empty) assertFalse(c.live);
        state.raw.put("calendar_error", true);
        assertTrue(card(state.cards(NOW, IST), "next").foot.startsWith("Cached"));
    }
}
