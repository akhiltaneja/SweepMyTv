package com.sweepmytv.launcher;

import android.content.Context;
import android.content.pm.PackageManager;

/** One Sweep TV bundle. No upstream APK feed is a valid update source for this app.
 * A public Sweep TV release channel has not been configured; releases are built,
 * verified and installed from the maintenance computer. Do not claim "up to date".
 */
final class SelfUpdate {
    private SelfUpdate() {}
    interface Listener { void changed(String status); }
    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; }
        catch(PackageManager.NameNotFoundException ignored) { return ""; }
    }
    static boolean takesReleases(Context c) { return false; }
    static String available(Context c) { return null; }
    static String summary(Context c) { return installed(c)+" · Managed Sweep TV release"; }
    static boolean checkIfStale(Context c) { return false; }
    static void checkAndInstall(Context c,Listener listener) { listener.changed("Updates are managed with your Sweep TV releases"); }
}
