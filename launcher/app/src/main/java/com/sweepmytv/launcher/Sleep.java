package com.sweepmytv.launcher;

// Adapted from tv-vibehack's launcher (Sleep.java, pr/control-center), MIT License,
// Copyright (c) 2026 Gathin. See THIRD_PARTY_NOTICES.md.

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Turn off now and the sleep timer. The standby itself is done by {@link SleepService}. A minute before the timer
 * runs out a card counts down over whatever is playing; OK on it gives 15 more minutes (an idea from CleanYourTV's
 * Sleep Timer add-on).
 */
final class Sleep {
    private static final String AT = "sleep_at";
    /** A timer that fires this late (the TV was off, the alarm waited) is dropped instead of sleeping a TV just turned on. */
    private static final long STALE_MS = 2 * 60_000L;
    private static final int[] STEPS = {30, 60, 90};
    private static final long WARN_MS = 60_000L;
    private static final String WARN = "com.sweepmytv.launcher.SLEEP_WARN";

    private Sleep() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Fires when the sleep timer runs out. */
    public static class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            long at = prefs(c).getLong(AT, 0);
            if (at <= 0) return;
            long now = System.currentTimeMillis();
            if (WARN.equals(intent.getAction())) {
                // The countdown card turns the TV off itself when it reaches 0; the final alarm is the fallback.
                if (Math.abs(now - (at - WARN_MS)) < STALE_MS) SleepService.warn(at);
                return;
            }
            prefs(c).edit().remove(AT).apply();
            if (Math.abs(now - at) < STALE_MS) SleepService.lock();
        }
    }

    /** The countdown reached 0: sleep now, and drop the fallback alarm. */
    static void fire(Context c) {
        if (prefs(c).getLong(AT, 0) <= 0) return;
        set(c, 0);
        SleepService.lock();
    }

    /** OK on the warning: 15 more minutes from now. */
    static void extend(Context c) { set(c, 15); }

    /** True when the TV went to standby; false when the turn-off service is not switched on. */
    static boolean now() { return SleepService.lock(); }

    static boolean available() { return SleepService.isRunning(); }

    /** Standby goes through the accessibility "lock screen" action, which Android added in 9; on 8 the rows are hidden. */
    static final boolean SUPPORTED = Build.VERSION.SDK_INT >= 28;

    /** Minutes left on the timer, or 0 when it is off. */
    static int minutesLeft(Context c) {
        long left = prefs(c).getLong(AT, 0) - System.currentTimeMillis();
        return left > 0 ? (int) Math.ceil(left / 60_000.0) : 0;
    }

    /** Off → 30 → 60 → 90 → off. From a running timer, jump to the next step above what is left. */
    static int cycle(Context c) {
        int left = minutesLeft(c), next = 0;
        for (int s : STEPS) if (s > left) { next = s; break; }
        set(c, next);
        return next;
    }

    private static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent warn = PendingIntent.getBroadcast(c, 1, new Intent(WARN).setClass(c, Alarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am != null) { am.cancel(pi); am.cancel(warn); }
        SleepService.cancelWarning();
        if (minutes <= 0) {
            prefs(c).edit().remove(AT).apply();
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(c).edit().putLong(AT, at).apply();
        if (am == null) return;
        exact(am, at - WARN_MS, warn);
        exact(am, at + 5_000, pi);  // fallback, a little after the countdown would have done it
    }

    private static void exact(AlarmManager am, long at, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }
}
