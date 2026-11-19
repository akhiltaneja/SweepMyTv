package com.sweepmytv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/** Personalisation stays on the TV; it never queries Google account identities. */
final class HomeOptions {
    static final String NAME="profile_name.txt";
    static final String GREETING = "header_greeting";

    enum Metric {
        RAM("widget_ram", "RAM", true),
        STORAGE("widget_storage", "Storage (GB)", true),
        UPTIME("widget_uptime", "Uptime", false);

        final String key, label;
        final boolean defaultShown;

        Metric(String key, String label, boolean defaultShown) {
            this.key = key;
            this.label = label;
            this.defaultShown = defaultShown;
        }

        boolean shown(SharedPreferences prefs) {
            return prefs.getBoolean(key, defaultShown);
        }
    }

    static SharedPreferences prefs(Context c){return c.getSharedPreferences("home",0);}
    static Set<String> hiddenApps(Context context) {
        String saved = prefs(context).getString("hidden_apps", "");
        return saved.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(saved.split(",")));
    }
    static String name(Context c){String name=Keys.read(c,NAME);return cleanName(name);}
    static String cleanName(String name){if(name==null)return "";String s=name.replaceAll("[\\p{Cntrl}]"," ").trim().replaceAll("\\s+"," ");return s.length()>24?s.substring(0,24):s;}
    static String greeting(int hour,String name){String greeting=hour<5?"Good evening":hour<12?"Good morning":hour<17?"Good afternoon":"Good evening";String n=cleanName(name);return greeting+(n.isEmpty()?"":", "+n);}
    static String header(Context context, long now) {
        if (!prefs(context).getBoolean(GREETING, true)) return "";
        Calendar clock = Calendar.getInstance();
        clock.setTimeInMillis(now);
        return greeting(clock.get(Calendar.HOUR_OF_DAY), name(context));
    }

    static void migrateDeviceMetrics(SharedPreferences prefs) {
        if (!prefs.contains("meters")) return;
        SharedPreferences.Editor edit = prefs.edit().remove("meters");
        if (!prefs.getBoolean("meters", true)) {
            for (Metric metric : Metric.values()) edit.putBoolean(metric.key, false);
        }
        edit.apply();
    }
}
