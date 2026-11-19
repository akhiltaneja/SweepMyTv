package com.sweepmytv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/** Screen saver choices come from the services installed on this TV. */
final class ScreenSavers {
    static final String AERIAL = "com.neilturner.aerialviews";
    private static final String BACKDROP = "com.google.android.backdrop";

    static Map<ComponentName, String> available(Context context) {
        PackageManager packages = context.getPackageManager();
        Map<ComponentName, String> results = new LinkedHashMap<>();
        for (ResolveInfo match : packages.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0)) {
            if (match.serviceInfo == null || !"android.permission.BIND_DREAM_SERVICE".equals(match.serviceInfo.permission)) continue;
            ComponentName component = new ComponentName(match.serviceInfo.packageName, match.serviceInfo.name);
            String label = match.loadLabel(packages).toString();
            switch (component.getPackageName()) {
                case BACKDROP: label = "Default Android TV Backdrop"; break;
                case "com.mitv.dream": label = "Xiaomi Screensaver"; break;
                case AERIAL: label = "Aerial Views"; break;
            }
            results.put(component, label);
        }
        return results;
    }

    static ComponentName active(Context context) {
        String value = Settings.Secure.getString(context.getContentResolver(), "screensaver_components");
        ComponentName component = value == null ? null : ComponentName.unflattenFromString(value.split(",", 2)[0]);
        return component == null ? systemDefault(context) : component;
    }

    static ComponentName systemDefault(Context context) {
        String value = Settings.Secure.getString(context.getContentResolver(), "screensaver_default_component");
        return value == null ? null : ComponentName.unflattenFromString(value);
    }

    static String activeLabel(Context context) {
        return available(context).getOrDefault(active(context), "TV settings");
    }

    static boolean select(Context context, ComponentName component) {
        return available(context).containsKey(component)
                && Settings.Secure.putString(context.getContentResolver(), "screensaver_components", component.flattenToString());
    }

    private ScreenSavers() {}
}
