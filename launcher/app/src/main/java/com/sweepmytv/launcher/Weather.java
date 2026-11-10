package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the top-left line: the nearest airport's measured temperature (METAR, via the US National
 * Weather Service's aviationweather.gov, free and keyless) with rain and cloud from Open-Meteo for your exact
 * coordinates. When no airport within ~200 km has a report from the last two hours, all of it comes from Open-Meteo. The area comes from files/location.txt
 * (set with set-location.sh): a place name, looked up once with Open-Meteo's geocoding, or "lat,lon".
 * Refreshed at most every 30 minutes, and only when the home screen asks.
 */
final class Weather {
    private static final String TAG = "GlassWeather";
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 10 * 60_000L, MAX_AGE_S = 2 * 3600;

    interface Listener { void onWeather(String line); }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private volatile SkyWeather sky;

    Weather(Context c) {
        ctx = c.getApplicationContext();
        prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        try {sky=new SkyWeather(new JSONObject(prefs.getString("sky_forecast","{}")),prefs.getString("city",""),prefs.getLong("sky_at",0));}catch(Exception ignored) { }
    }

    /** The last line shown, or null when no location is set (the greeting stays). */
    String cached() {
        if (area() == null || !area().equals(prefs.getString("weather_area",null)) || !unit().equals(prefs.getString("unit", "C"))) return null;
        return prefs.getString("line", null);
    }

    private String unit() { return Region.fahrenheit(Region.country(ctx)) ? "F" : "C"; }

    /** The network is back: a fetch that failed while it was down may run again now, without waiting out the retry. */
    void networkBack() {
        if (prefs.getLong("try_at", 0) > prefs.getLong("ok_at", 0)) prefs.edit().remove("try_at").apply();
    }

    void refreshIfStale(Listener l) {
        String area = area();
        if (area == null || running) return;
        long now = System.currentTimeMillis();
        // Running set-location.sh again (even with the same place) rewrites the file: fetch right away.
        long stamp = new File(ctx.getExternalFilesDir(null), "location.txt").lastModified();
        String config=area+"|"+stamp+"|"+unit()+"|sky1";
        boolean changed=!config.equals(prefs.getString("request_config",null));
        if (!changed && (now - prefs.getLong("ok_at", 0) < REFRESH_MS || now - prefs.getLong("try_at", 0) < RETRY_MS)) return;
        prefs.edit().putLong("file_at",stamp).putString("request_config",config).putLong("try_at", now).apply();
        running = true;
        worker.execute(() -> {
            try {
                double[] where = locate(area);
                String line = observe(where[0], where[1], area);
                prefs.edit().putString("line", line).putString("weather_area",area).putString("unit", unit()).putLong("ok_at", System.currentTimeMillis()).apply();
                Log.i(TAG, "Weather: " + line);
                main.post(() -> l.onWeather(line));
            } catch (Exception e) {
                Log.w(TAG, "Weather failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                main.post(() -> running = false);
            }
        });
    }

    private String area() {
        File f = new File(ctx.getExternalFilesDir(null), "location.txt");
        try (InputStream in = new FileInputStream(f)) {
            String s = new String(readAll(in), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? automaticArea() : s;
        } catch (IOException e) {
            return automaticArea();
        }
    }

    private String automaticArea(){return automatic()?"@auto":null;}
    boolean automatic(){return ctx.getSharedPreferences("home",Context.MODE_PRIVATE).getBoolean("automatic_city",true);}
    void automatic(boolean enabled){ctx.getSharedPreferences("home",Context.MODE_PRIVATE).edit().putBoolean("automatic_city",enabled).apply();}
    String locationLabel() {
        String manual=Keys.read(ctx,Keys.LOCATION);if(manual!=null)return manual.matches("[-+0-9.,\\s]+")?"Coordinates":manual;
        return automatic()?"Auto · "+prefs.getString("auto_city","approximate IP city"):"Off";
    }
    SkyWeather.Sample sky(long now) {
        SkyWeather saved=sky;String requested=area();
        return saved!=null&&requested!=null&&requested.equals(prefs.getString("sky_area",null))?saved.at(now):SkyWeather.fallback(now);
    }
    String solarSummary() {
        SkyWeather.Sample sample=sky(System.currentTimeMillis());if(!sample.solarKnown)return "Sun times awaiting weather";
        java.text.DateFormat time=new java.text.SimpleDateFormat("HH:mm",Locale.getDefault());
        return "Sunrise "+time.format(new java.util.Date(sample.sunrise))+" · Sunset "+time.format(new java.util.Date(sample.sunset));
    }

    /** Reuses the weather location; called only on a background worker, never input dispatch. */
    HomeInsights.Place widgetPlace() throws Exception {
        String requested=area();if(requested==null)return null;double[] ll=locate(requested);
        String cc=prefs.getString("location_country",Region.country(ctx));if(!cc.matches("[A-Za-z]{2}"))cc=Region.country(ctx);
        return new HomeInsights.Place(prefs.getString("city",requested),cc.toUpperCase(Locale.ROOT),prefs.getString("location_region",""),prefs.getString("location_zone",java.time.ZoneId.systemDefault().getId()),ll[0],ll[1],"@auto".equals(requested));
    }
    String widgetLocationKey(){String area=area();return area==null?"off":area+"|"+java.time.ZoneId.systemDefault().getId();}

    // ---------------------------------------------------------------- where

    /** Coordinates for the area: "lat,lon" as given, else a place-name search (cached until the area changes). */
    private synchronized double[] locate(String area) throws Exception {
        if("@auto".equals(area))return locateAutomatically();
        if (area.equals(prefs.getString("area", null)) && prefs.contains("lat") && prefs.contains("location_country")) {
            return new double[]{Double.longBitsToDouble(prefs.getLong("lat", 0)), Double.longBitsToDouble(prefs.getLong("lon", 0))};
        }
        double[] ll = null;
        String[] parts = area.split(",");
        if (parts.length == 2) {
            try { ll = new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())}; }
            catch (NumberFormatException ignored) { }
        }
        String found = area, country=Region.country(ctx), admin="", zone=java.time.ZoneId.systemDefault().getId();
        if (ll == null) {
            // The search takes one place name: try the whole text, then each part ("Indiranagar", "Bengaluru").
            List<String> tries = new ArrayList<>();
            tries.add(area);
            for (String p : parts) if (!p.trim().isEmpty()) tries.add(p.trim());
            for (String q : tries) {
                JSONArray r = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                        + URLEncoder.encode(q, "UTF-8")).optJSONArray("results");
                if (r != null && r.length() > 0) {
                    JSONObject o = r.getJSONObject(0);
                    ll = new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
                    found = o.optString("name") + ", " + o.optString("admin1") + ", " + o.optString("country");
                    country=o.optString("country_code",country);admin=o.optString("admin1","");zone=o.optString("timezone",zone);
                    break;
                }
            }
        }
        if (ll == null) throw new IOException("Couldn't find \"" + area + "\"");
        prefs.edit().putString("area", area).putLong("lat", Double.doubleToLongBits(ll[0]))
                .putLong("lon", Double.doubleToLongBits(ll[1])).putString("city",area).putString("location_source","manual").putString("location_country",country).putString("location_region",admin).putString("location_zone",zone).apply();
        Log.i(TAG, String.format(Locale.US, "Location \"%s\" → %s (%.3f, %.3f)", area, found, ll[0], ll[1]));
        return ll;
    }

    private double[] locateAutomatically() throws Exception {
        long now=System.currentTimeMillis();
        if(now>=prefs.getLong("auto_geo_at",0)&&now-prefs.getLong("auto_geo_at",0)<24*3600_000L&&prefs.contains("auto_lat")&&prefs.contains("auto_country")
                && java.time.ZoneId.systemDefault().getId().equals(prefs.getString("auto_device_zone",""))) {
            double lat=Double.longBitsToDouble(prefs.getLong("auto_lat",0)),lon=Double.longBitsToDouble(prefs.getLong("auto_lon",0));
            prefs.edit().putString("area","@auto").putString("city",prefs.getString("auto_city","Approximate city")).putString("location_source","ip").putString("location_country",prefs.getString("auto_country",Region.country(ctx))).putString("location_region",prefs.getString("auto_region","")).putString("location_zone",prefs.getString("auto_zone",java.time.ZoneId.systemDefault().getId())).apply();
            return new double[]{lat,lon};
        }
        if(now-prefs.getLong("auto_geo_try_at",0)<24*3600_000L&&prefs.getLong("auto_geo_at",0)==0)throw new IOException("Automatic city unavailable; using clock-only sky");
        prefs.edit().putLong("auto_geo_try_at",now).apply();
        ApproximateCity city=ApproximateCity.parse(get("https://api.ipapi.is/"),java.time.ZoneId.systemDefault(),now);
        if(city==null)throw new IOException("Automatic city uncertain; optional city override is available");
        prefs.edit().putString("auto_city",city.name).putLong("auto_lat",Double.doubleToLongBits(city.lat)).putLong("auto_lon",Double.doubleToLongBits(city.lon))
                .putString("auto_country",city.country).putString("auto_region",city.region).putString("auto_zone",city.zone).putLong("auto_geo_at",now).putString("auto_device_zone",java.time.ZoneId.systemDefault().getId()).putString("area","@auto").putString("city",city.name).putString("location_source","ip").putString("location_country",city.country).putString("location_region",city.region).putString("location_zone",city.zone).apply();
        return new double[]{city.lat,city.lon};
    }

    // ---------------------------------------------------------------- what

    /**
     * The nearest airport's measured temperature, with rain and cloud from Open-Meteo for your exact spot (an airport
     * 15–20 km away can be dry while it rains here). Without a recent airport report, both come from the model.
     */
    private String observe(double lat, double lon, String area) throws Exception {
        JSONObject forecast = get(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day,cloud_cover,precipitation"
                        + "&hourly=temperature_2m,weather_code,is_day,cloud_cover,precipitation&forecast_hours=48"
                        + "&daily=sunrise,sunset,weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=6", lat, lon));
        JSONObject here = forecast.getJSONObject("current");
        long skyAt=System.currentTimeMillis();
        sky=new SkyWeather(forecast,prefs.getString("city",""),skyAt);
        prefs.edit().putString("sky_forecast",forecast.toString()).putString("sky_area",area).putLong("sky_at",skyAt).putInt("sky_version",1).apply();
        saveForecast(forecast);
        // Today's sunrise and sunset ("2026-10-21T06:07"), for the launcher's automatic day/night look.
        JSONObject daily = forecast.optJSONObject("daily");
        if (daily != null) {
            String rise = daily.getJSONArray("sunrise").optString(0, ""), set = daily.getJSONArray("sunset").optString(0, "");
            if (rise.length() >= 16 && set.length() >= 16) {
                prefs.edit().putString("sunrise", rise.substring(11, 16)).putString("sunset", set.substring(11, 16)).apply();
            }
        }
        String condition = wmoCondition(here.getInt("weather_code"), here.optInt("is_day", 1) == 1);
        JSONObject station = nearestReport(lat, lon);
        if (station == null) return line(here.getDouble("temperature_2m"), condition, null);
        return line(station.getDouble("temp"), condition, null);  // the station is named in the log, not on screen
    }

    /**
     * Control Center → Weather: the next hours or the next days, as {when, what} rows ("3 PM", "30° Showers";
     * "Thu", "31° / 22°  Rain"). Empty until the first fetch. An idea from Luma (MIT).
     */
    java.util.List<String[]> forecast(boolean days) {
        java.util.List<String[]> out = new ArrayList<>();
        if (cached() == null) return out;
        java.text.DateFormat hour = android.text.format.DateFormat.is24HourFormat(ctx)
                ? new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()) : new java.text.SimpleDateFormat("h a", Locale.getDefault());
        long now = System.currentTimeMillis();
        for (String row : prefs.getString(days ? "days" : "hours", "").split("\n")) {
            String[] f = row.split("\t");
            if (f.length != 2) continue;
            if (!days) {
                // Hours are kept as their start time: one that has begun is no longer "next".
                long at;
                try { at = Long.parseLong(f[0]); } catch (NumberFormatException e) { continue; }
                if (at <= now) continue;
                f[0] = hour.format(new java.util.Date(at));
            }
            out.add(f);
            if (!days && out.size() == 6) break;
        }
        return out;
    }

    /** Keeps the forecast ready-made, so Control Center shows it at once and offline. */
    private void saveForecast(JSONObject forecast) {
        StringBuilder hours = new StringBuilder(), days = new StringBuilder();
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US),
                    day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone(forecast.optString("timezone", "UTC")));  // the place's local times
            java.text.DateFormat weekday = new java.text.SimpleDateFormat("EEEE", Locale.getDefault());
            JSONObject h = forecast.getJSONObject("hourly");
            JSONArray t = h.getJSONArray("time"), temp = h.getJSONArray("temperature_2m"), code = h.getJSONArray("weather_code"),
                    isDay = h.getJSONArray("is_day");
            for (int i = 0; i < t.length(); i++) {
                hours.append(in.parse(t.getString(i)).getTime()).append('\t')
                        .append(degrees(temp.getDouble(i))).append(' ').append(wmoCondition(code.getInt(i), isDay.optInt(i, 1) == 1)).append('\n');
            }
            JSONObject d = forecast.getJSONObject("daily");
            JSONArray dt = d.getJSONArray("time"), max = d.getJSONArray("temperature_2m_max"), min = d.getJSONArray("temperature_2m_min"),
                    dc = d.getJSONArray("weather_code");
            for (int i = 0; i < dt.length(); i++) {
                String when = i == 0 ? "Today" : i == 1 ? "Tomorrow" : weekday.format(day.parse(dt.getString(i)));
                days.append(when).append('\t').append(degrees(max.getDouble(i))).append(" / ").append(degrees(min.getDouble(i)))
                        .append("  ").append(wmoCondition(dc.getInt(i), true)).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "No forecast: " + e);
        }
        prefs.edit().putString("hours", hours.toString()).putString("days", days.toString()).putBoolean("hours_at", true).apply();
    }

    /** "31°", in Fahrenheit where that's the custom. */
    private String degrees(double c) {
        return Math.round(Region.fahrenheit(Region.country(ctx)) ? c * 9 / 5 + 32 : c) + "°";
    }

    /** The closest airport report from the last two hours within about 200 km, or null. */
    private JSONObject nearestReport(double lat, double lon) throws Exception {
        for (double box : new double[]{1.0, 2.0}) {
            String url = String.format(Locale.US, "https://aviationweather.gov/api/data/metar?format=json&hours=2&bbox=%.3f,%.3f,%.3f,%.3f",
                    lat - box, lon - box, lat + box, lon + box);
            JSONArray all;
            try {
                all = getArray(url);
            } catch (Exception e) {
                Log.w(TAG, "Airport reports unavailable: " + e.getClass().getSimpleName());
                return null;
            }
            JSONObject best = null;
            double bestKm = Double.MAX_VALUE;
            long now = System.currentTimeMillis() / 1000;
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                if (m.isNull("temp") || now - m.optLong("obsTime", 0) > MAX_AGE_S) continue;
                double km = km(lat, lon, m.getDouble("lat"), m.getDouble("lon"));
                // Newest report per station wins on equal distance (the list holds a couple of hours).
                if (km < bestKm || (km == bestKm && m.optLong("obsTime") > best.optLong("obsTime"))) { best = m; bestKm = km; }
            }
            if (best != null) {
                Log.i(TAG, String.format(Locale.US, "Nearest report: %s (%s), %.0f km", best.optString("icaoId"),
                        station(best.optString("name"), best.optString("icaoId")), bestKm));
                return best;
            }
        }
        return null;
    }

    /** "27° Showers"; in Fahrenheit where that's the custom (the US and a few others, Region). Both sources give °C. */
    private String line(double temp, String condition, String where) {
        if (Region.fahrenheit(Region.country(ctx))) temp = temp * 9 / 5 + 32;
        String s = Math.round(temp) + "° " + condition;
        return where == null || where.isEmpty() ? s : s + " · " + where;
    }

    /** Daytime by today's sunrise and sunset here, once known; until then 6:00 to 18:30. */
    boolean isDay() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        return minutes >= toMinutes(prefs.getString("sunrise", "06:00")) && minutes < toMinutes(prefs.getString("sunset", "18:30"));
    }

    private static int toMinutes(String hhmm) {
        try { return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5)); }
        catch (Exception e) { return 0; }
    }

    /** "Bengaluru/Kempegowda Intl, KA, IN" → "Kempegowda Airport". */
    private static String station(String name, String icao) {
        String s = name.split(",")[0].trim();
        if (s.contains("/")) s = s.substring(s.lastIndexOf('/') + 1).trim();
        s = s.replaceAll("\\b(Intl|International|Arpt|Apt)\\b", "Airport").replaceAll("\\s+", " ").trim();
        // Short words in station names are codes ("Hal" is HAL): capitalise them.
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(w.length() <= 3 && !w.equalsIgnoreCase("the") && !w.equalsIgnoreCase("of") ? w.toUpperCase(Locale.ROOT) : w);
        }
        s = b.toString().replace("Airport Airport", "Airport");
        if (!s.toLowerCase(Locale.ROOT).contains("airport") && !s.isEmpty()) s += " Airport";
        return s.isEmpty() ? icao : s;
    }

    /** WMO weather codes, as Open-Meteo uses them. */
    private static String wmoCondition(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    private static double km(double la1, double lo1, double la2, double lo2) {
        double r = 6371, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ---------------------------------------------------------------- http

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Sweep TV/1.0 (Android TV launcher)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + new URL(url).getHost());
            try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject get(String url) throws Exception { return new JSONObject(fetch(url)); }

    private static JSONArray getArray(String url) throws Exception {
        String s = fetch(url).trim();
        return s.isEmpty() ? new JSONArray() : new JSONArray(s);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }
}
