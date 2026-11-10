package com.sweepmytv.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Play Store "updates available" notifications, for the bell at the top right. With the Google TV launcher disabled
 * nothing else shows them. WatchService (which holds notification access anyway) passes the active notifications
 * here; only the Play Store's update channels are read, every other notification is ignored. Idea from Matinee (MIT).
 */
final class Updates {
    static final class Item {
        String title, text;
        PendingIntent open;
    }

    private static final String PLAY_STORE = "com.android.vending";
    /** The Play Store's channels on this TV (dumpsys notification): "Updates available" and "Required". */
    private static final List<String> CHANNELS = Arrays.asList("updates-available", "required");
    /** Lets `adb shell cmd notification post -t <title> glass-update-test <text>` try the bell. */
    private static final String TEST_TAG = "glass-update-test";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static List<Item> items = Collections.emptyList();
    private static Runnable listener;

    private Updates() { }

    static synchronized List<Item> list() { return items; }

    /** Called on the main thread whenever the list changes. */
    static void setListener(Runnable r) { listener = r; }

    static void update(StatusBarNotification[] active) {
        List<Item> out = new ArrayList<>();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                boolean store = PLAY_STORE.equals(sbn.getPackageName()) && CHANNELS.contains(n.getChannelId());
                boolean test = "com.android.shell".equals(sbn.getPackageName()) && TEST_TAG.equals(sbn.getTag());
                if (!store && !test) continue;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && active.length > 1) continue;
                Bundle x = n.extras;
                Item it = new Item();
                it.title = str(x.getCharSequence(Notification.EXTRA_TITLE));
                CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
                it.text = str(big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT));
                it.open = n.contentIntent;
                if (!it.title.isEmpty() || !it.text.isEmpty()) out.add(it);
            }
        }
        synchronized (Updates.class) { items = out; }
        MAIN.post(() -> { if (listener != null) listener.run(); });
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }
}
