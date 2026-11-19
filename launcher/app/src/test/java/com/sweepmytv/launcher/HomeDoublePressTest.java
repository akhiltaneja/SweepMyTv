package com.sweepmytv.launcher;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.time.Duration;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeDoublePressTest {
    private final HomeDoublePress gesture = new HomeDoublePress();

    @Test public void secondTapIsConsumedForNativeAndMappedHome() {
        for (int code : new int[]{KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_1}) {
            gesture.clear();
            assertFalse(gesture.handle(event(1000, 1000, KeyEvent.ACTION_DOWN, code, 0)));
            assertFalse(gesture.handle(event(1000, 1050, KeyEvent.ACTION_UP, code, 0)));
            assertTrue(gesture.handle(event(1250, 1250, KeyEvent.ACTION_DOWN, code, 0)));
            assertTrue(gesture.handle(event(1250, 1300, KeyEvent.ACTION_UP, code, 0)));
            assertFalse(gesture.handle(event(1400, 1400, KeyEvent.ACTION_DOWN, code, 0)));
        }
    }

    @Test public void holdsCanceledReleasesAndMissedKeyUpsDoNotArmAnotherPress() {
        int code = KeyEvent.KEYCODE_HOME;
        assertFalse(gesture.handle(event(1000, 1000, KeyEvent.ACTION_DOWN, code, 0)));
        assertFalse(gesture.handle(event(1000, 1700, KeyEvent.ACTION_UP, code, 0)));
        assertFalse(gesture.handle(event(1800, 1800, KeyEvent.ACTION_DOWN, code, 0)));
        assertFalse(gesture.handle(event(1800, 1850, KeyEvent.ACTION_UP, code, KeyEvent.FLAG_CANCELED)));
        assertFalse(gesture.handle(event(1900, 1900, KeyEvent.ACTION_DOWN, code, 0)));
        assertFalse(gesture.handle(event(1980, 1980, KeyEvent.ACTION_DOWN, code, 0)));
        assertFalse(gesture.handle(event(1980, 2000, KeyEvent.ACTION_UP, code, 0)));
        assertFalse(gesture.handle(event(2500, 2500, KeyEvent.ACTION_DOWN, code, 0)));
    }

    @Test public void mappedHomeWaitsForSecondPressAndOpensOnlyTheDrawer() {
        SleepService service = mappedHomeService();
        int code = KeyEvent.KEYCODE_BUTTON_1;
        tap(service, code);
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        tap(service, code);
        Intent drawer = Shadows.shadowOf(service).getNextStartedActivity();
        assertNotNull(drawer);
        assertEquals(RemoteKeys.APP_DRAWER_GESTURE, drawer.getStringExtra(RemoteKeys.EXTRA_OPEN));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }

    @Test public void mappedSingleHomeOpensAfterTheWindow() {
        SleepService service = mappedHomeService();
        tap(service, KeyEvent.KEYCODE_BUTTON_1);
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeDoublePress.WINDOW_MS + 1));
        Intent home = Shadows.shadowOf(service).getNextStartedActivity();
        assertNotNull(home);
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME));
        assertNull(home.getStringExtra(RemoteKeys.EXTRA_OPEN));
    }

    @Test public void hiddenPatchWallReturnsHomeWithoutInterceptingXiaomiControls() {
        SleepService service = Robolectric.buildService(SleepService.class).create().get();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        String patchWall = "com.mitv.tvhome.atv";
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(patchWall);
        event.setClassName(patchWall + ".app.PatchWallActivity");
        service.onAccessibilityEvent(event);
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());

        HomeOptions.prefs(service).edit().putString("hidden_apps", patchWall).apply();
        for (String control : new String[]{".app.settings.MainSettingsActivity", ".app.tv.InputSourcePopupActivity"}) {
            event.setClassName(patchWall + control);
            service.onAccessibilityEvent(event);
            assertNull(Shadows.shadowOf(service).getNextStartedActivity());
        }
        for (String home : new String[]{".app.PatchWallActivity", ".app.BannerLogoA"}) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            event.setClassName(patchWall + home);
            service.onAccessibilityEvent(event);
            Intent returned = Shadows.shadowOf(service).getNextStartedActivity();
            assertNotNull(returned);
            assertEquals(HomeActivity.class.getName(), returned.getComponent().getClassName());
        }
    }

    @Test public void savedAppDrawerActionLaunchesTheDrawerOnPressAndRespectsPause() {
        SleepService service = mappedHomeService();
        int button = KeyEvent.KEYCODE_BUTTON_5;
        RemoteKeys.set(service, button, RemoteKeys.APP_DRAWER);
        tap(service, button);
        Intent drawer = Shadows.shadowOf(service).getNextStartedActivity();
        assertNotNull(drawer);
        assertEquals(RemoteKeys.APP_DRAWER, drawer.getStringExtra(RemoteKeys.EXTRA_OPEN));

        HomeOptions.prefs(service).edit().putBoolean("remote_keys_on", false).apply();
        tap(service, button);
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }

    private static SleepService mappedHomeService() {
        SleepService service = Robolectric.buildService(SleepService.class).create().get();
        service.getSharedPreferences("home", 0).edit().putBoolean("remote_keys_on", true).apply();
        RemoteKeys.set(service, KeyEvent.KEYCODE_BUTTON_1, RemoteKeys.HOME);
        return service;
    }

    private static void tap(SleepService service, int code) {
        long now = SystemClock.uptimeMillis();
        service.onKeyEvent(event(now, now, KeyEvent.ACTION_DOWN, code, 0));
        service.onKeyEvent(event(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static KeyEvent event(long down, long at, int action, int code, int flags) {
        return new KeyEvent(down, at, action, code, 0, 0, 1, 12, flags);
    }
}
