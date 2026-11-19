package com.sweepmytv.launcher;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The display settings offered in the one-time phone session. */
final class HomeDashboard {
    enum Option {
        TIME_BACKGROUND(TimeOfDay.PREF, "Background follows the time of day", false),
        GREETING(HomeOptions.GREETING, "Show greeting", true),
        RAM(HomeOptions.Metric.RAM),
        STORAGE(HomeOptions.Metric.STORAGE),
        UPTIME(HomeOptions.Metric.UPTIME),
        BITCOIN(MarketQuotes.Asset.BTC.key, "Bitcoin · BTC", true),
        NVIDIA(MarketQuotes.Asset.NVDA.key, "NVIDIA · NVDA", true),
        GOLD(MarketQuotes.Asset.GOLD.key, "Gold · XAU", true),
        INSIGHTS("insights", "AQI, news and festivals", true, HomeInsights.KEY),
        DISCOVERY("for_you", "Trending and recent tabs", true),
        FORMULA_ONE(FormulaOne.KEY, "Formula 1", false, FormulaOne.KEY),
        RECOMMENDED("recommendations", Recommendations.TITLE, true, Recommendations.KEY),
        RELEASES("new_releases", "Recently released", true, Recommendations.NEW_KEY);

        final String key, label, section;
        final boolean fallback;

        Option(HomeOptions.Metric metric) {
            this(metric.key, metric.label, metric.defaultShown);
        }

        Option(String key, String label, boolean fallback) {
            this(key, label, fallback, null);
        }

        Option(String key, String label, boolean fallback, String section) {
            this.key = key;
            this.label = label;
            this.fallback = fallback;
            this.section = section;
        }
    }

    private final Map<Option, Boolean> initial;

    HomeDashboard(Map<Option, Boolean> initial) {
        this.initial = new EnumMap<>(initial);
    }

    static HomeDashboard read(SharedPreferences prefs) {
        HomeOptions.migrateDeviceMetrics(prefs);
        Map<Option, Boolean> settings = new EnumMap<>(Option.class);
        Set<String> hidden = prefs.getStringSet(SectionOptions.HIDDEN, Collections.emptySet());
        for (Option option : Option.values()) {
            settings.put(option, prefs.getBoolean(option.key, option.fallback)
                    && (option.section == null || !hidden.contains(option.section)));
        }
        return new HomeDashboard(settings);
    }

    /** Accept exactly the fields shown in the dashboard, once each. */
    static Map<Option, Boolean> parse(String body) {
        Map<Option, Boolean> settings = new EnumMap<>(Option.class);
        for (String field : body.split("&", -1)) {
            String[] pair = field.split("=", -1);
            if (pair.length != 2 || !("1".equals(pair[1]) || "0".equals(pair[1]))) return null;
            Option match = null;
            for (Option option : Option.values()) {
                if (option.key.equals(pair[0])) {
                    match = option;
                    break;
                }
            }
            if (match == null || settings.put(match, "1".equals(pair[1])) != null) return null;
        }
        return settings.size() == Option.values().length ? settings : null;
    }

    /** Only save values changed on the phone, so an untouched field never overwrites a newer TV choice. */
    Set<Option> save(SharedPreferences prefs, Map<Option, Boolean> submitted) {
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> hidden = new HashSet<>(prefs.getStringSet(SectionOptions.HIDDEN, Collections.emptySet()));
        Set<Option> changed = EnumSet.noneOf(Option.class);
        for (Map.Entry<Option, Boolean> entry : submitted.entrySet()) {
            Option option = entry.getKey();
            boolean shown = entry.getValue();
            if (shown == initial.get(option)) continue;
            editor.putBoolean(option.key, shown);
            if (option == Option.TIME_BACKGROUND) editor.remove(HomeTheme.FIXED_PREF).remove(HomeTheme.RANDOMISE_SINCE);
            if (option.section != null) SectionOptions.update(hidden, option.section, shown);
            changed.add(option);
        }
        if (!changed.isEmpty()) editor.putStringSet(SectionOptions.HIDDEN, hidden).apply();
        return changed;
    }

    byte[] form(String path) {
        StringBuilder page = new StringBuilder("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Customize Home · Sweep TV</title>
            <style>
            :root{color-scheme:dark;font:16px/1.5 system-ui,sans-serif;background:#19191e;color:#f4f1ed}
            *{box-sizing:border-box}
            body{margin:0;padding:28px 18px}
            main{max-width:590px;margin:auto}
            h1{font-size:clamp(2rem,7vw,3rem);line-height:1.1;margin:8px 0 12px}
            p{margin:0 0 20px;color:#c9c4cb}
            .brand{color:#f5bf90;font-weight:700;margin:0}
            a{color:#f5bf90}
            fieldset{margin:0 0 16px;padding:6px 15px 12px;border:1px solid #514852;border-radius:18px;background:#27242b}
            legend{font-weight:700;padding:0 8px;color:#f5bf90}
            label{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0}
            label+label{border-top:1px solid #49424c}
            select,button{font:inherit;border-radius:10px;min-height:44px}
            select{background:#343039;color:#fff;border:1px solid #746979;padding:6px 10px}
            button{width:100%;border:0;background:#f5bf90;color:#281b15;font-weight:750;padding:12px}
            :focus-visible{outline:2px solid #f5bf90;outline-offset:3px}
            </style></head><body><main>
            <p class="brand">Sweep TV</p>
            <h1>Customize Home</h1>
            <p>Choose what appears on your TV, then save. This page closes its connection after one save or five minutes.</p>
            """);
        // The path is generated from URL-safe random bytes, and all visible labels are constants.
        page.append("<p><a href=\"").append(path).append("/wallpaper\">Choose a wallpaper</a></p>")
                .append("<form method=\"post\" action=\"").append(path).append("\">");
        group(page, "Appearance", Option.TIME_BACKGROUND, Option.GREETING);
        group(page, "Widgets", Option.RAM, Option.STORAGE, Option.UPTIME, Option.INSIGHTS);
        group(page, "Market prices", Option.BITCOIN, Option.NVIDIA, Option.GOLD);
        group(page, "Home sections", Option.DISCOVERY, Option.FORMULA_ONE, Option.RECOMMENDED, Option.RELEASES);
        return page.append("<button type=\"submit\">Save to TV</button></form></main></body></html>")
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private void group(StringBuilder page, String name, Option... options) {
        page.append("<fieldset><legend>").append(name).append("</legend>");
        for (Option option : options) {
            boolean on = initial.get(option);
            page.append("<label>").append(option.label).append("<select name=\"").append(option.key)
                    .append("\"><option value=\"1\"").append(on ? " selected" : "").append(">On</option>")
                    .append("<option value=\"0\"").append(on ? "" : " selected")
                    .append(">Off</option></select></label>");
        }
        page.append("</fieldset>");
    }
}
