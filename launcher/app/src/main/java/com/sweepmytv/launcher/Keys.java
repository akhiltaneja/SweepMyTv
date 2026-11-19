package com.sweepmytv.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Your own keys and weather area, in the app's folder on the TV (files/tmdb_key.txt, cricket_key.txt, location.txt):
 * the same files the set-*.sh scripts push over adb and the settings editors write. Each reader
 * (Trending, Cricket, Weather) notices a new file on its next refresh. Never in the APK.
 */
final class Keys {
    static final String TMDB = "tmdb_key.txt", CRICKET = "cricket_key.txt", LOCATION = "location.txt";
    /** A key check's answer: the service accepted it, turned it down, or couldn't be reached. */
    static final int OK = 0, REJECTED = 1, OFFLINE = 2;

    private Keys() { }

    private static File file(Context c, String name) {
        return new File(TMDB.equals(name) ? c.getNoBackupFilesDir() : c.getExternalFilesDir(null), name);
    }

    static String activeTmdbKey(Context c) {
        return BuildConfig.CATALOG_COMPARISON && "tmdb".equals(catalogEngine(c)) ? read(c, TMDB) : null;
    }

    static String catalogEngine(Context c) {
        if (!BuildConfig.CATALOG_COMPARISON) return "sweep";
        String engine = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("catalog_engine", "sweep");
        return "tmdb".equals(engine) || "streamradar".equals(engine) ? engine : "sweep";
    }

    static void setCatalogEngine(Context c, String engine) {
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("catalog_engine", engine).apply();
    }

    /** The file's text, trimmed, or null when it's missing or empty. */
    static synchronized String read(Context c, String name) {
        String value = readFile(file(c, name));
        if (value == null && TMDB.equals(name)) {
            File legacy = new File(c.getExternalFilesDir(null), name);
            value = readFile(legacy);
            if (value != null && write(c, name, value)) legacy.delete();
        }
        return value;
    }

    private static String readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            String s = n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves the text, or removes the file when it's null or blank. Written whole, then renamed into place. */
    static synchronized boolean write(Context c, String name, String text) {
        File f = file(c, name);
        if (text == null || text.trim().isEmpty()) {
            if (TMDB.equals(name)) {
                File legacy = new File(c.getExternalFilesDir(null), name);
                if (legacy.exists() && !legacy.delete()) return false;
            }
            return !f.exists() || f.delete();
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.trim().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    /** "…3fb6": enough to tell keys apart, never the key. */
    static String masked(String key) {
        return key.length() <= 8 ? "set" : "…" + key.substring(key.length() - 4);
    }
}
