package com.sweepmytv.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks public Sweep TV release metadata. APK installation remains managed. */
final class SelfUpdate {
    private static final String RELEASES = "https://api.github.com/repos/akhiltaneja/SweepMyTv/releases?per_page=20";
    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})-sweep\\.(\\d{1,6})");
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private SelfUpdate() {}
    interface Listener { void changed(String status); }
    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; }
        catch(PackageManager.NameNotFoundException ignored) { return ""; }
    }

    static String checkPublished(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Sweep-TV");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "Release check unavailable. Press OK to try again.";
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Release list too large");
                    response.write(buffer, 0, count);
                }
                return releaseStatus(installed(context), new JSONArray(response.toString(StandardCharsets.UTF_8.name())));
            }
        } catch (IOException | org.json.JSONException ignored) {
            return "Release check unavailable. Press OK to try again.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String releaseStatus(String installed, JSONArray releases) {
        Matcher current = VERSION.matcher(installed);
        if (!current.matches()) return "Installed version unavailable";
        Matcher latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", true) || release.isNull("published_at")
                    || release.optString("published_at").isEmpty()) continue;
            Matcher candidate = VERSION.matcher(release.optString("tag_name"));
            if (candidate.matches() && (latest == null || compare(candidate, latest) > 0)) latest = candidate;
        }
        if (latest == null) return "No Sweep TV releases published";
        int comparison = compare(latest, current);
        String label = latest.group().replaceFirst("^v", "");
        if (comparison > 0) return "Update available · " + label;
        if (comparison < 0) return "Installed build is newer than the published release · " + label;
        return "Up to date · " + label;
    }

    private static int compare(Matcher first, Matcher second) {
        for (int part = 1; part <= 4; part++) {
            int order = Integer.compare(Integer.parseInt(first.group(part)), Integer.parseInt(second.group(part)));
            if (order != 0) return order;
        }
        return 0;
    }
    static boolean takesReleases(Context c) { return false; }
    static String available(Context c) { return null; }
    static String summary(Context c) { return installed(c)+" · Managed Sweep TV release"; }
    static boolean checkIfStale(Context c) { return false; }
    static void checkAndInstall(Context c,Listener listener) { listener.changed("Updates are managed with your Sweep TV releases"); }
}
