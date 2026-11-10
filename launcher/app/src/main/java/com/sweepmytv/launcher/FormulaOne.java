package com.sweepmytv.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public schedules/results/standings from Jolpica; best-effort session status from ESPN. No telemetry subscription. */
final class FormulaOne {
    static final String KEY = "formula_one";
    static final String API = "https://api.jolpi.ca/ergast/f1/";
    static final String STATUS = "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard";
    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    static final class Card {
        String key, title = "", kicker = "", metric = "", foot = "", body = "";
        final List<String> lines = new ArrayList<>();
        long target;
        boolean live;
        final List<Line> table = new ArrayList<>();
    }

    static final class Line {
        String rank, name, value, team, portrait = "", logo = ""; int color;
        Line(String rank, String name, String value, String team, int color) {
            this.rank = rank; this.name = name; this.value = value; this.team = team; this.color = color;
        }
    }

    static final class State {
        final JSONObject raw;
        State(JSONObject raw) { this.raw = raw; }
        static State empty() { return new State(new JSONObject()); }
        private JSONObject data(String key) { JSONObject d = raw.optJSONObject(key); return d == null ? new JSONObject() : d; }
        private JSONArray races(String key) {
            JSONObject mr = data(key).optJSONObject("MRData");
            JSONObject table = mr == null ? null : mr.optJSONObject("RaceTable");
            JSONArray races = table == null ? null : table.optJSONArray("Races");
            return races == null ? new JSONArray() : races;
        }
        private String stamp(String key, String source, long now, TimeZone zone, long staleAfter) {
            long at = raw.optLong(key + "_at");
            if (at <= 0) return source + " · not loaded";
            boolean stale = now < at || now - at > staleAfter || raw.optBoolean(key + "_error");
            return (stale ? "Cached · " : "") + source + " · checked " + local(at, zone);
        }

        List<Card> cards(long now, TimeZone zone) {
            List<Card> out = new ArrayList<>();
            List<JSONObject> calendar = new ArrayList<>();
            JSONArray races = races("calendar");
            for (int i = 0; i < races.length(); i++) if (races.optJSONObject(i) != null) calendar.add(races.optJSONObject(i));
            calendar.sort(Comparator.comparing(o -> o.optString("date")));
            JSONObject next = null;
            for (JSONObject race : calendar) {
                long start = raceTime(race);
                if ((start > now || (start > 0 && now - start < 6 * HOUR && !raceCompleted(race)))
                        || (start == 0 && race.optString("date").compareTo(Instant.ofEpochMilli(now).atZone(ZoneId.of("UTC")).toLocalDate().toString()) >= 0)) {
                    next = race; break;
                }
            }
            Card upcoming = card("next", next == null ? "Next Grand Prix" : next.optString("raceName"), "NEXT GRAND PRIX");
            upcoming.foot = stamp("calendar", "Jolpica", now, zone, 24 * HOUR);
            if (next == null) {
                upcoming.metric = calendar.isEmpty() ? "Schedule unavailable" : "Awaiting next schedule";
                upcoming.lines.add(calendar.isEmpty() ? "Connect to load the F1 calendar" : "No upcoming race in this calendar");
            } else {
                long start = raceTime(next);
                upcoming.target = start > now ? start : 0;
                upcoming.metric = start == 0 ? "Start time TBC" : start > now ? countdown(start, now) : "Awaiting race status";
                JSONObject circuit = next.optJSONObject("Circuit");
                JSONObject location = circuit == null ? null : circuit.optJSONObject("Location");
                String place = location == null ? "" : location.optString("locality") + ", " + location.optString("country");
                upcoming.lines.add(place);
                upcoming.lines.add(start == 0 ? next.optString("date") : local(start, zone));
                upcoming.body = place + "\n" + (circuit == null ? "" : circuit.optString("circuitName"))
                        + "\nRound " + next.optString("round") + " · " + next.optString("season")
                        + "\nRace: " + (start == 0 ? next.optString("date") + " · time TBC" : local(start, zone));
            }
            out.add(upcoming);
            Card status = sessionStatus(now, zone);
            if (status != null) out.add(status);
            if (next != null) {
                Card weekend = card("weekend", next.optString("raceName"), "RACE WEEKEND");
                weekend.metric = "Weekend schedule"; weekend.foot = upcoming.foot;
                String[][] sessions = {{"FirstPractice", "Practice 1"}, {"SecondPractice", "Practice 2"},
                        {"ThirdPractice", "Practice 3"}, {"SprintQualifying", "Sprint qualifying"},
                        {"Sprint", "Sprint"}, {"Qualifying", "Qualifying"}};
                List<String[]> ordered = new ArrayList<>();
                for (String[] entry : sessions) {
                    JSONObject session = next.optJSONObject(entry[0]);
                    if (session != null) ordered.add(new String[]{session.optString("date"), entry[1], session.optString("time")});
                }
                ordered.sort(Comparator.comparing((String[] s) -> s[0]).thenComparing(s -> s[2]));
                for (String[] session : ordered) {
                    long at = time(session[0] + "T" + session[2]);
                    String line = session[1] + " · " + (at == 0 ? session[0] + " · time TBC" : local(at, zone));
                    weekend.body += line + "\n";
                    if (weekend.lines.size() < 2 && (at == 0 || at >= now)) weekend.lines.add(line);
                }
                weekend.body += "Race · " + (raceTime(next) == 0 ? next.optString("date") + " · time TBC" : local(raceTime(next), zone));
                out.add(weekend);
            }
            java.util.Set<String> resultIds = new HashSet<>();
            for (String key : new String[]{"latest", "previous1", "previous2", "open_sessions"}) {
                JSONObject race = races(key).optJSONObject(0);
                if (race == null) continue;
                if (!resultIds.add(race.optString("season") + ":" + race.optString("round"))) continue;
                Card result = card(key, race.optString("raceName"), key.equals("latest") ? "LATEST RACE RESULT" : "RECENT RACE RESULT");
                result.foot = stamp(key, "Jolpica · R" + race.optString("round"), now, zone, 24 * HOUR);
                result.body = race.optString("date") + " · " + race.optString("season") + "\n";
                JSONArray positions = race.optJSONArray("Results");
                for (int i = 0; positions != null && i < positions.length(); i++) {
                    JSONObject entry = positions.optJSONObject(i); if (entry == null) continue;
                    String driver = driver(entry.optJSONObject("Driver"));
                    JSONObject finish = entry.optJSONObject("Time");
                    String timing = finish == null ? entry.optString("status") : finish.optString("time");
                    String line = entry.optString("position") + ". " + driver + " · " + entry.optString("points") + " pts";
                    JSONObject constructor = entry.optJSONObject("Constructor");
                    result.table.add(new Line(entry.optString("position"), driver, timing, constructor == null ? "" : constructor.optString("name"), 0xFFFF5147));
                    if (i == 0) result.metric = driver + " wins";
                    else if (i < 3) {
                        JSONObject who = entry.optJSONObject("Driver");
                        result.lines.add("P" + entry.optString("position") + " " + (who == null ? driver : who.optString("code", driver))
                                + " " + entry.optString("points") + " pts");
                    }
                    result.body += line + " · " + timing + " · " + entry.optString("laps") + " laps\n";
                    JSONObject fast = entry.optJSONObject("FastestLap");
                    if (fast != null && "1".equals(fast.optString("rank"))) {
                        JSONObject lapTime = fast.optJSONObject("Time");
                        result.body += "Fastest lap: " + driver + " · " + (lapTime == null ? "Time unavailable" : lapTime.optString("time")) + "\n";
                    }
                }
                out.add(result);
            }
            for (boolean drivers : new boolean[]{true, false}) {
                String key = drivers ? "drivers" : "teams";
                JSONObject mr = data(key).optJSONObject("MRData");
                JSONObject table = mr == null ? null : mr.optJSONObject("StandingsTable");
                JSONArray tables = table == null ? null : table.optJSONArray("StandingsLists");
                JSONObject season = tables == null ? null : tables.optJSONObject(0);
                JSONArray entries = season == null ? null : season.optJSONArray(drivers ? "DriverStandings" : "ConstructorStandings");
                Card standings = card(key, drivers ? "Driver standings" : "Team standings", "CHAMPIONSHIP POINTS");
                standings.metric = "Standings unavailable";
                standings.foot = stamp(key, "Jolpica" + (season == null ? "" : " · " + season.optString("season") + " R" + season.optString("round")), now, zone, 24 * HOUR);
                for (int i = 0; entries != null && i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    JSONObject team = entry.optJSONObject("Constructor");
                    String name = drivers ? driver(entry.optJSONObject("Driver")) : team == null ? "" : team.optString("name");
                    String line = entry.optString("position") + ". " + name + " · " + entry.optString("points") + " pts";
                    standings.table.add(new Line(entry.optString("position"), name, entry.optString("points"), "PTS", 0xFF99BDEB));
                    if (i == 0) standings.metric = name + " · " + entry.optString("points");
                    else if (i < 3) {
                        JSONObject who = entry.optJSONObject("Driver");
                        String shortName = drivers && who != null ? who.optString("familyName", name) : name;
                        standings.lines.add("P" + entry.optString("position") + " " + shortName + " " + entry.optString("points"));
                    }
                    standings.body += line + " · " + entry.optString("wins") + " wins\n";
                }
                out.add(standings);
            }
            Card calendarCard = card("calendar", "Coming up", "UPCOMING GRANDS PRIX");
            calendarCard.foot = upcoming.foot;
            for (JSONObject race : calendar) if (raceTime(race) > now) {
                String line = race.optString("raceName") + " · " + local(raceTime(race), zone);
                if (calendarCard.metric.isEmpty()) calendarCard.metric = race.optString("raceName");
                else if (calendarCard.lines.size() < 2) calendarCard.lines.add(line);
                calendarCard.body += line + "\n";
            }
            if (!calendarCard.body.isEmpty()) out.add(calendarCard);
            return out;
        }

        private boolean raceCompleted(JSONObject race) {
            JSONArray events = data("status").optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null || !race.optString("raceName").equals(e.optString("name"))) continue;
                JSONArray sessions = e.optJSONArray("competitions");
                for (int j = 0; sessions != null && j < sessions.length(); j++) {
                    JSONObject s = sessions.optJSONObject(j); if (s == null) continue;
                    JSONObject type = s.optJSONObject("type"), status = s.optJSONObject("status");
                    JSONObject state = status == null ? null : status.optJSONObject("type");
                    if (type != null && "Race".equalsIgnoreCase(type.optString("abbreviation")) && state != null
                            && "post".equals(state.optString("state")) && Math.abs(time(s.optString("date")) - raceTime(race)) < 24 * HOUR) return true;
                }
            }
            return false;
        }

        private Card sessionStatus(long now, TimeZone zone) {
            JSONArray events = data("status").optJSONArray("events");
            JSONObject chosen = null, owner = null; int best = -1; long bestTime = Long.MAX_VALUE;
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i); if (e == null) continue;
                JSONArray sessions = e.optJSONArray("competitions");
                for (int j = 0; sessions != null && j < sessions.length(); j++) {
                    JSONObject s = sessions.optJSONObject(j); if (s == null) continue;
                    long at = time(s.optString("date")); if (at <= 0 || at < now - 4 * 24 * HOUR || at > now + 10 * 24 * HOUR) continue;
                    JSONObject status = s.optJSONObject("status"), state = status == null ? null : status.optJSONObject("type");
                    String value = state == null ? "" : state.optString("state");
                    int priority = "in".equals(value) && at <= now && now - at < 10 * HOUR ? 3 : at > now ? 2 : 1;
                    long distance = Math.abs(at - now);
                    if (priority > best || (priority == best && distance < bestTime)) { best = priority; bestTime = distance; chosen = s; owner = e; }
                }
            }
            if (chosen == null) return null;
            JSONObject st = chosen.optJSONObject("status"), type = chosen.optJSONObject("type");
            JSONObject state = st == null ? null : st.optJSONObject("type");
            String value = state == null ? "" : state.optString("state");
            long at = time(chosen.optString("date"));
            boolean fresh = raw.optLong("status_at") > 0 && now >= raw.optLong("status_at")
                    && now - raw.optLong("status_at") <= 2 * MINUTE && !raw.optBoolean("status_error");
            Card c = card("status", owner.optString("name") + " · " + (type == null ? "Session" : type.optString("abbreviation")), "SESSION STATUS");
            c.live = "in".equals(value) && fresh && now >= at && now - at < 10 * HOUR;
            c.metric = c.live ? "Session in progress" : "post".equals(value) ? "Completed"
                    : "in".equals(value) ? "Status needs refresh" : at > now ? countdown(at, now) : "Awaiting status";
            if (at > now && "pre".equals(value)) { c.target = at; c.kicker = "NEXT SESSION"; }
            if (c.live) c.kicker = "LIVE SESSION · ESPN";
            c.lines.add(local(at, zone));
            c.foot = stamp("status", "ESPN", now, zone, 2 * MINUTE);
            c.body = c.title + "\nStart: " + local(at, zone) + "\n" + c.metric
                    + "\nRace classifications and points appear in the results cards when published.";
            return c;
        }

        private JSONArray open(String key) {
            JSONArray a = raw.optJSONArray(key); return a == null ? new JSONArray() : a;
        }
        private static Card find(List<Card> cards, String key) {
            for (Card c : cards) if (key.equals(c.key)) return c;
            return card(key, "Results unavailable", "FORMULA 1");
        }
        /** Six tiles in a fixed two-row mosaic. Only the next race has a ticking countdown. */
        List<Card> dashboard(long now, TimeZone zone) {
            List<Card> source = cards(now, zone), result = new ArrayList<>();
            Card next = find(source, "next"), last = find(source, "latest"), weekend = find(source, "weekend");
            if (last.table.isEmpty()) { last.title = "Previous Grand Prix"; last.metric = "Results unavailable"; }
            Card session = sessionClassification(now, zone);
            Card status = sessionStatus(now, zone);
            if (status != null && status.live) {
                session.kicker = "LIVE TIMING NOT CONNECTED";
                session.metric = "Last published: " + session.title;
                session.title = status.title + " · In progress";
                session.lines.add(0, status.title);
                session.body = status.title + " · session in progress (ESPN)\nLive timing is not connected. The table below is the last published classification.\n" + session.body;
            }
            if (weekend.lines.isEmpty()) weekend.lines.add("Schedule unavailable");
            // Display every scheduled weekend session, including completed ones, without extra countdowns.
            weekend.lines.clear();
            for (String line : weekend.body.split("\n")) if (!line.isEmpty()) weekend.lines.add(line);
            boolean weekendMatches = false;
            for (int i = 0; i < open("open_sessions").length(); i++) {
                JSONObject candidate = open("open_sessions").optJSONObject(i);
                if (candidate != null && "Race".equals(candidate.optString("session_name")) && next.target > 0
                        && Math.abs(time(candidate.optString("date_start")) - next.target) < 4 * HOUR) weekendMatches = true;
            }
            if (weekendMatches) {
                weekend.lines.clear();
                List<JSONObject> ordered = new ArrayList<>();
                for (int i = 0; i < open("open_sessions").length(); i++) if (open("open_sessions").optJSONObject(i) != null) ordered.add(open("open_sessions").optJSONObject(i));
                ordered.sort(Comparator.comparing(o -> o.optString("date_start")));
                for (JSONObject item : ordered) {
                    String id = item.optString("session_key");
                    JSONObject results = raw.optJSONObject("open_results"), saved = results == null ? null : results.optJSONObject(id);
                    JSONArray rows = saved == null ? null : saved.optJSONArray("rows");
                    String state = item.optBoolean("is_cancelled") ? "Cancelled" : rows != null && rows.length() > 0 ? "Completed" : time(item.optString("date_start")) > now ? "Scheduled" : "Awaiting result";
                    weekend.lines.add(item.optString("session_name") + " · " + state + " · " + local(time(item.optString("date_start")), zone));
                }
                weekend.body = String.join("\n", weekend.lines);
                weekend.foot = stamp("open_sessions", "OpenF1", now, zone, 24 * HOUR);
            }
            if (weekend.lines.isEmpty()) weekend.lines.add("Schedule unavailable");
            Card driverLeaders = find(source, "drivers"), teamLeaders = find(source, "teams");
            next.table.addAll(driverLeaders.table.subList(0, Math.min(3, driverLeaders.table.size())));
            result.add(next); result.add(last); result.add(session); result.add(driverLeaders); result.add(teamLeaders); result.add(weekend);
            for (Card tile : result) for (Line line : tile.table) {
                if (!"teams".equals(tile.key)) {
                    line.portrait = "driver-" + F1Assets.normalize(F1Assets.surname(line.name));
                    for (int i = 0; i < open("open_drivers").length(); i++) {
                        JSONObject person = open("open_drivers").optJSONObject(i);
                        if (person != null && F1Assets.normalize(person.optString("last_name")).equals(F1Assets.normalize(F1Assets.surname(line.name)))) {
                            line.team = person.optString("team_name");
                            try { line.color = android.graphics.Color.parseColor("#" + person.optString("team_colour")); } catch (Exception ignored) { }
                            break;
                        }
                    }
                } else line.team = line.name;
                line.logo = "team-" + F1Assets.team(line.team);
            }
            return result;
        }
        private Card sessionClassification(long now, TimeZone zone) {
            Card c = card("classification", "Weekend sessions", "SESSION CLASSIFICATION");
            c.metric = "Results not published"; c.lines.add("Free completed-session data");
            c.foot = stamp("open_sessions", "OpenF1", now, zone, 24 * HOUR);
            List<JSONObject> sessions = new ArrayList<>();
            for (int i = 0; i < open("open_sessions").length(); i++) {
                JSONObject item = open("open_sessions").optJSONObject(i);
                if (item != null && time(item.optString("date_end")) < now && !item.optBoolean("is_cancelled")) sessions.add(item);
            }
            sessions.sort((a, b) -> Long.compare(time(b.optString("date_end")), time(a.optString("date_end"))));
            JSONObject results = raw.optJSONObject("open_results");
            boolean shown = false;
            for (JSONObject session : sessions) {
                String key = session.optString("session_key");
                JSONObject saved = results == null ? null : results.optJSONObject(key);
                JSONArray rows = saved == null ? null : saved.optJSONArray("rows");
                if (rows == null || rows.length() == 0) continue;
                List<JSONObject> sorted = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) != null) sorted.add(rows.optJSONObject(i));
                sorted.sort(Comparator.comparingInt(x -> x.optInt("position", 999)));
                c.body += session.optString("session_name") + " · " + session.optString("location") + " · Completed\n" + local(time(session.optString("date_start")), zone) + "\n";
                if (!shown) {
                    c.title = session.optString("session_name") + " · " + session.optString("location");
                    c.metric = "Published classification"; c.lines.clear();
                    long at = saved.optLong("at");
                    c.foot = (at > now || now - at > 24 * HOUR || saved.optBoolean("error") ? "Cached · " : "") + "OpenF1 · checked " + local(at, zone);
                }
                for (JSONObject row : sorted) {
                    int number = row.optInt("driver_number"); JSONObject driver = null;
                    for (int i = 0; i < open("open_drivers").length(); i++) {
                        JSONObject d = open("open_drivers").optJSONObject(i);
                        if (d != null && d.optInt("driver_number") == number) { driver = d; break; }
                    }
                    String name = driver == null ? "Driver " + number : driver.optString("full_name", "Driver " + number);
                    String team = driver == null ? "" : driver.optString("team_name");
                    String timing = row.optBoolean("dsq") ? "DSQ" : row.optBoolean("dns") ? "DNS" : duration(row.opt("duration"));
                    String gap = row.optString("gap_to_leader");
                    String detail = row.optInt("position") + ". " + name + " · " + timing + " · " + row.optInt("number_of_laps") + " laps";
                    if (!gap.isEmpty() && !gap.equals("null")) detail += " · gap " + gap;
                    c.body += detail + "\n";
                    if (!shown) {
                        int color = 0xFF99BDEB;
                        try { color = android.graphics.Color.parseColor("#" + driver.optString("team_colour")); } catch (Exception ignored) { }
                        c.table.add(new Line(row.optString("position"), name, timing, team, color));
                    }
                }
                c.body += "\n"; shown = true;
            }
            if (!shown) c.body = "Completed practice, qualifying and race classifications will appear here when the free source publishes them.\nLive timing is not connected. Open F1 TV for live coverage.\n";
            c.body += "Free completed-session results · OpenF1. Live positions, gaps, tyres and flags are not connected.";
            return c;
        }
        Channels.Channel row(long now, TimeZone zone) {
            Channels.Channel row = new Channels.Channel(); row.key = KEY; row.name = row.title = "Formula 1 · Race center";
            for (Card c : dashboard(now, zone)) {
                Trending.Pick p = new Trending.Pick(); p.kind = KEY; p.tab = KEY; p.id = c.key.hashCode(); p.title = c.title;
                p.meta = c.foot; p.extra = c; row.items.add(p);
            }
            return row;
        }
    }

    static String duration(Object value) {
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = a.length() - 1; i >= 0; i--) if (!a.isNull(i)) return duration(a.opt(i));
            return "—";
        }
        if (!(value instanceof Number) || ((Number) value).doubleValue() <= 0) return "—";
        double seconds = ((Number) value).doubleValue();
        return String.format(Locale.US, "%d:%06.3f", (int) (seconds / 60), seconds % 60);
    }

    private final File cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private State state;
    private boolean running;
    interface Listener { void onState(State state); }
    FormulaOne(Context context) {
        cache = new File(context.getExternalFilesDir(null), "formula-one.json");
        state = State.empty();
        try (InputStream in = new FileInputStream(cache)) { state = new State(new JSONObject(read(in))); }
        catch (Exception ignored) { }
    }
    State cached() { return state; }
    void networkBack() {
        for (String key : new String[]{"calendar", "drivers", "teams", "latest", "status", "previous1", "previous2", "open_sessions"})
            if (state.raw.optBoolean(key + "_error")) state.raw.remove(key + "_try");
    }
    private static boolean due(JSONObject raw, String key, long interval, long now) {
        long at = raw.optLong(key + "_at"), attempt = raw.optLong(key + "_try");
        long retry = raw.optBoolean(key + "_error") ? 5 * MINUTE : Math.min(interval, 5 * MINUTE);
        return (at <= 0 || now < at || now - at >= interval) && (attempt <= 0 || now < attempt || now - attempt >= retry);
    }
    void refreshIfStale(Listener listener) {
        if (running) return;
        long now = System.currentTimeMillis();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("calendar", API + "current.json?limit=100");
        sources.put("drivers", API + "current/driverStandings.json?limit=100");
        sources.put("teams", API + "current/constructorStandings.json?limit=100");
        sources.put("latest", API + "current/last/results.json?limit=100");
        sources.put("status", STATUS);
        boolean needed = false;
        for (String key : sources.keySet()) if (due(state.raw, key, interval(key, now), now)) needed = true;
        if (due(state.raw, "open_sessions", 30 * MINUTE, now)) needed = true;
        if (!needed) return;
        running = true;
        worker.execute(() -> {
            try {
                JSONObject out = new JSONObject(state.raw.toString());
                for (Map.Entry<String, String> source : sources.entrySet()) {
                    String key = source.getKey();
                    if (!due(out, key, interval(key, now), now)) continue;
                    fetch(out, key, source.getValue(), now);
                }
                JSONObject latest = new State(out).races("latest").optJSONObject(0);
                if (latest != null && latest.optString("season").matches("\\d{4}")) {
                    for (int i = 1; i <= 2; i++) {
                        int round = latest.optInt("round") - i; if (round < 1) continue;
                        String key = "previous" + i, path = API + latest.optString("season") + "/" + round + "/results.json?limit=100";
                        boolean changed = !path.equals(out.optString(key + "_path"));
                        if ((changed && now - out.optLong(key + "_try") >= 5 * MINUTE) || due(out, key, 24 * HOUR, now)) {
                            fetch(out, key, path, now);
                            if (!out.optBoolean(key + "_error")) out.put(key + "_path", path);
                        }
                    }
                }
                refreshOpen(out, now);
                File tmp = new File(cache.getPath() + ".tmp");
                try (FileOutputStream stream = new FileOutputStream(tmp)) { stream.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(cache)) throw new java.io.IOException("Cannot save F1 cache");
                State next = new State(out);
                main.post(() -> { state = next; running = false; listener.onState(next); });
            } catch (Exception e) { main.post(() -> running = false); }
        });
    }
    private static void refreshOpen(JSONObject out, long now) throws Exception {
        if (!due(out, "open_sessions", 30 * MINUTE, now)) return;
        out.put("open_sessions_try", now);
        try {
            // A bounded current-weekend query; no live endpoints or credentials are used.
            String from = Instant.ofEpochMilli(now - 4 * 24 * HOUR).atZone(ZoneId.of("UTC")).toLocalDate().toString();
            String to = Instant.ofEpochMilli(now + 4 * 24 * HOUR).atZone(ZoneId.of("UTC")).toLocalDate().toString();
            JSONArray sessions = new JSONArray(getText("https://api.openf1.org/v1/sessions?date_start%3E=" + from + "&date_start%3C=" + to));
            out.put("open_sessions", sessions).put("open_sessions_at", now).put("open_sessions_error", false);
            JSONObject results = out.optJSONObject("open_results"); if (results == null) results = new JSONObject();
            JSONObject bounded = new JSONObject();
            List<JSONObject> completed = new ArrayList<>();
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i); if (session == null || session.optBoolean("is_cancelled")) continue;
                long end = time(session.optString("date_end"));
                // OpenF1's free historical window begins 30 minutes after a session ends.
                if (end > 0 && end + 31 * MINUTE <= now) completed.add(session);
            }
            completed.sort((a, b) -> Long.compare(time(b.optString("date_end")), time(a.optString("date_end"))));
            int requests = 0;
            for (JSONObject session : completed) {
                String id = session.optString("session_key"); if (!id.matches("\\d+")) continue;
                JSONObject saved = results.optJSONObject(id); if (saved == null) saved = new JSONObject();
                long at = saved.optLong("at"), attempt = saved.optLong("try");
                if (requests < 5 && (at == 0 || now < at || now - at > 24 * HOUR) && (attempt == 0 || now - attempt >= 5 * MINUTE)) {
                    saved.put("try", now); requests++;
                    try {
                        JSONArray rows = new JSONArray(getText("https://api.openf1.org/v1/session_result?session_key=" + id));
                        if (rows.length() == 0) throw new java.io.IOException("Results not yet published");
                        saved.put("rows", rows).put("at", now).put("error", false);
                    } catch (Exception e) { saved.put("error", true); }
                }
                bounded.put(id, saved);
            }
            out.put("open_results", bounded);
            if (!completed.isEmpty()) {
                String id = completed.get(0).optString("session_key");
                if (!id.equals(out.optString("open_driver_session")) || now - out.optLong("open_drivers_at") > 24 * HOUR) {
                    try {
                        JSONArray drivers = new JSONArray(getText("https://api.openf1.org/v1/drivers?session_key=" + id));
                        if (drivers.length() > 0) out.put("open_drivers", drivers).put("open_driver_session", id).put("open_drivers_at", now);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) { out.put("open_sessions_error", true); }
    }
    private long interval(String key, long now) {
        if (key.equals("calendar")) return 6 * HOUR;
        if (!key.equals("status")) return 30 * MINUTE;
        JSONArray races = state.races("calendar");
        for (int i = 0; i < races.length(); i++) {
            long start = raceTime(races.optJSONObject(i));
            if (start > now - 6 * HOUR && start < now + 72 * HOUR) return MINUTE;
        }
        return 15 * MINUTE;
    }
    private static void fetch(JSONObject out, String key, String url, long now) throws Exception {
        out.put(key + "_try", now);
        try {
            JSONObject body = get(url);
            if (!(key.equals("status") ? body.has("events") : body.has("MRData"))) throw new java.io.IOException("Unexpected F1 response");
            out.put(key, body).put(key + "_at", now).put(key + "_error", false);
        } catch (Exception e) { out.put(key + "_error", true); }
    }
    private static JSONObject get(String url) throws Exception { return new JSONObject(getText(url)); }
    private static String getText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "SweepTV/1.2.4 F1-dashboard Android-TV");
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(10_000); c.setReadTimeout(15_000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("F1 source unavailable");
            try (InputStream in = c.getInputStream()) { return read(in); }
        } finally { c.disconnect(); }
    }
    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0;) { if (out.size() + n > 2_000_000) throw new java.io.IOException("F1 reply too large"); out.write(buffer, 0, n); }
        return out.toString(StandardCharsets.UTF_8.name());
    }
    private static Card card(String key, String title, String kicker) { Card c = new Card(); c.key = key; c.title = title; c.kicker = kicker; return c; }
    static long time(String value) { try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); } catch (Exception e) { return 0; } }
    private static long raceTime(JSONObject race) { return race == null ? 0 : time(race.optString("date") + "T" + race.optString("time")); }
    private static String driver(JSONObject driver) { return driver == null ? "Unknown driver" : (driver.optString("givenName") + " " + driver.optString("familyName")).trim(); }
    static String local(long at, TimeZone zone) {
        return at <= 0 ? "Time TBC" : DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z", Locale.ENGLISH).withZone(zone.toZoneId()).format(Instant.ofEpochMilli(at));
    }
    static String countdown(long target, long now) {
        if (target <= now) return "Start time reached";
        long seconds = (target - now + 999) / 1000, days = seconds / 86400;
        return (days > 0 ? days + "d " : "") + String.format(Locale.US, "%02dh %02dm %02ds", seconds / 3600 % 24, seconds / 60 % 60, seconds % 60);
    }
}
