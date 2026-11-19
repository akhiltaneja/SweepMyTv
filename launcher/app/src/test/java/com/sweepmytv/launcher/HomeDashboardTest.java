package com.sweepmytv.launcher;

import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeDashboardTest {
    private static String form(boolean greeting) {
        return "background_by_time=0&header_greeting=" + (greeting ? "1" : "0")
                + "&widget_ram=1&widget_storage=1&widget_uptime=0&market_btc=1&market_nvda=1&market_gold=1&insights=1&for_you=1&formula_one=1&recommendations=1&new_releases=1";
    }

    @Test public void phoneDashboardUsesTheSameFormulaOneOptInDefault() {
        SharedPreferences prefs = HomeOptions.prefs(RuntimeEnvironment.getApplication());
        String html = new String(HomeDashboard.read(prefs).form("/pair"), StandardCharsets.UTF_8);
        assertTrue(html.contains("name=\"formula_one\"><option value=\"1\">On</option><option value=\"0\" selected>Off"));
    }

    @Test public void saveRespectsHiddenSectionsAndUneditedNewerSettings() {
        SharedPreferences prefs = HomeOptions.prefs(RuntimeEnvironment.getApplication());
        prefs.edit().putStringSet(SectionOptions.HIDDEN, Set.of(FormulaOne.KEY, "unrelated")).apply();
        HomeDashboard dashboard = HomeDashboard.read(prefs);
        assertTrue(new String(dashboard.form("/pair"), StandardCharsets.UTF_8)
                .contains("name=\"formula_one\"><option value=\"1\">On</option><option value=\"0\" selected>Off"));
        prefs.edit().putBoolean(HomeOptions.Metric.STORAGE.key, false).apply();
        assertEquals(Set.of(HomeDashboard.Option.GREETING, HomeDashboard.Option.FORMULA_ONE),
                dashboard.save(prefs, HomeDashboard.parse(form(false))));
        assertFalse(prefs.getBoolean("header_greeting", true));
        assertFalse(HomeOptions.Metric.STORAGE.shown(prefs));
        assertEquals(Set.of("unrelated"), prefs.getStringSet(SectionOptions.HIDDEN, Collections.emptySet()));
    }

    @Test public void phoneSunlightSwitchOnlyOverridesFixedThemeWhenChanged() {
        SharedPreferences prefs = HomeOptions.prefs(RuntimeEnvironment.getApplication());
        HomeTheme.MIDNIGHT.save(prefs);
        HomeDashboard dashboard = HomeDashboard.read(prefs);
        dashboard.save(prefs, HomeDashboard.parse(form(false)));
        assertEquals(HomeTheme.MIDNIGHT, HomeTheme.selected(prefs));

        dashboard.save(prefs, HomeDashboard.parse(form(false).replace("background_by_time=0", "background_by_time=1")));
        assertEquals(HomeTheme.SUNLIGHT, HomeTheme.selected(prefs));
        HomeDashboard.read(prefs).save(prefs, HomeDashboard.parse(form(false)));
        assertEquals(HomeTheme.APP_COLOURS, HomeTheme.selected(prefs));
    }

    @Test public void phoneSessionRejectsUnexpectedFieldsBeforeAllowingOneSave() throws Exception {
        Map<HomeDashboard.Option, Boolean> defaults = new EnumMap<>(HomeDashboard.Option.class);
        for (HomeDashboard.Option option : HomeDashboard.Option.values()) defaults.put(option, option.fallback);
        AtomicInteger saves = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        PhonePage server = new PhonePage(InetAddress.getByName("127.0.0.1"), new HomeDashboard(defaults),
                new PhonePage.Listener() {
                    public void checking() { }
                    public List<String> accept(Map<String, String> fields) { throw new AssertionError("Wrong form"); }
                    public List<String> acceptDashboard(Map<HomeDashboard.Option, Boolean> fields) {
                        saves.incrementAndGet();
                        assertFalse(fields.get(HomeDashboard.Option.GREETING));
                        return List.of("Saved");
                    }
                    public void closed(boolean expired) { closed.countDown(); }
                });
        try {
            assertEquals(404, request(new URL(new URL(server.url), "/wrong").toString(), null));
            assertEquals(200, request(server.url, null));
            assertEquals(400, request(server.url, form(false) + "&tmdb=0"));
            assertEquals(400, request(server.url, form(false) + "&meters=0"));
            assertEquals(0, saves.get());
            assertEquals(200, request(server.url, form(false)));
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertEquals(1, saves.get());
        } finally {
            server.close();
        }
    }

    private static int request(String url, String form) throws Exception {
        return request(url, form == null ? null : form.getBytes(StandardCharsets.US_ASCII),
                "application/x-www-form-urlencoded");
    }

    @Test public void dashboardWallpaperRouteUsesTheSameOneSaveSession() throws Exception {
        AtomicInteger saved = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        byte[] photo = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        HomeDashboard dashboard = HomeDashboard.read(HomeOptions.prefs(RuntimeEnvironment.getApplication()));
        PhonePage server = new PhonePage(InetAddress.getByName("127.0.0.1"), dashboard, new PhonePage.Listener() {
            public void checking() { }
            public List<String> accept(Map<String, String> fields) { throw new AssertionError("Wrong form"); }
            public List<String> acceptWallpaper(byte[] jpeg) {
                assertArrayEquals(photo, jpeg);
                saved.incrementAndGet();
                return List.of("Wallpaper saved");
            }
            public void closed(boolean expired) { closed.countDown(); }
        });
        try {
            assertTrue(new String(new URL(server.url).openStream().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("/wallpaper"));
            assertEquals(200, request(server.url + "/wallpaper", null));
            assertEquals(415, request(server.url + "/wallpaper", form(false)));
            assertEquals(0, saved.get());
            assertEquals(200, request(server.url + "/wallpaper", photo, "image/jpeg"));
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertEquals(1, saved.get());
        } finally {
            server.close();
        }
    }

    private static int request(String url, byte[] body, String type) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", type);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
