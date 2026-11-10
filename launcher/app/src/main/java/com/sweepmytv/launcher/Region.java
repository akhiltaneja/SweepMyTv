package com.sweepmytv.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where the TV is, for the picks (which titles stream here), cricket (which app has the rights) and the weather's
 * °C or °F. In order: files/region.txt (a two-letter country code you can push, for a TV whose settings say
 * otherwise), the time zone's country (where the TV actually is: many TVs are left on "English (United States)"
 * wherever they are), the language setting's country, and finally the US.
 */
final class Region {
    private static final List<String> ENGLISH = Arrays.asList("US", "GB", "AU", "CA", "NZ", "IE");
    private static final List<String> FAHRENHEIT = Arrays.asList("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH");

    private Region() { }

    /** Two-letter country code, e.g. "IN". */
    static String country(Context c) {
        String override = override(c);
        if (override != null) return override;
        try {
            String tz = android.icu.util.TimeZone.getRegion(TimeZone.getDefault().getID());
            if (tz != null && tz.matches("[A-Z]{2}") && !tz.equals("ZZ")) return tz;
        } catch (RuntimeException ignored) {
            // Unknown zone ("UTC", "GMT"): fall back to the language setting.
        }
        String loc = Locale.getDefault().getCountry();
        return loc != null && loc.matches("[A-Z]{2}") ? loc : "US";
    }

    private static String override(Context c) {
        File f = new File(c.getExternalFilesDir(null), "region.txt");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            String s = n > 0 ? new String(b, 0, n, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT) : "";
            return s.matches("[A-Z]{2}") ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "India", "the US", "the UK": for "New in …". */
    static String name(String cc) {
        switch (cc) {
            case "US": return "the US";
            case "GB": return "the UK";
            case "AE": return "the UAE";
            case "NL": return "the Netherlands";
            case "PH": return "the Philippines";
            default:
                String n = new Locale("", cc).getDisplayCountry(Locale.ENGLISH);
                return n.isEmpty() || n.length() > 14 ? cc : n;
        }
    }

    /** Countries whose own titles are mostly in English: the Trending mix is then just "popular here". */
    static boolean englishSpeaking(String cc) { return ENGLISH.contains(cc); }

    static boolean fahrenheit(String cc) { return FAHRENHEIT.contains(cc); }

    /** "Hindi", "Korean"…: a language's English name from its ISO 639-1 code, or "" when unknown. */
    static String language(String code) {
        if (code == null || code.isEmpty()) return "";
        String n = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        return n.equalsIgnoreCase(code) ? "" : n;
    }
}
