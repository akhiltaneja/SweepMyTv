package com.sweepmytv.launcher;

import android.view.KeyEvent;

/** Recognizes two complete short presses using input timestamps, even if the launcher is busy opening. */
final class HomeDoublePress {
    static final long WINDOW_MS = 320;
    private static final long HOLD_MS = 600;
    private long downAt = -1;
    private long releasedAt = -1;
    private int keyCode, deviceId, scanCode;
    private boolean second;

    boolean handle(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            long gap = event.getEventTime() - releasedAt;
            second = downAt < 0 && releasedAt >= 0 && gap >= 0 && gap <= WINDOW_MS && matches(event);
            releasedAt = -1;
            downAt = event.getDownTime();
            keyCode = event.getKeyCode();
            deviceId = event.getDeviceId();
            scanCode = event.getScanCode();
            return second;
        }
        if (downAt < 0 || downAt != event.getDownTime() || !matches(event)) return false;
        if (event.getAction() != KeyEvent.ACTION_UP) return second;
        boolean consumed = second;
        releasedAt = !consumed && isShortRelease(event)
                ? event.getEventTime() : -1;
        downAt = -1;
        second = false;
        return consumed;
    }

    static boolean isShortRelease(KeyEvent event) {
        long duration = event.getEventTime() - event.getDownTime();
        return event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && duration >= 0 && duration < HOLD_MS;
    }

    private boolean matches(KeyEvent event) {
        return event.getKeyCode() == keyCode && event.getDeviceId() == deviceId && event.getScanCode() == scanCode;
    }

    void clear() {
        downAt = releasedAt = -1;
        second = false;
    }
}
