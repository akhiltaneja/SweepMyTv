package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class PersonalisationTest {
    @Test public void sweepUpdateCheckComparesPublishedPreviewsNumerically() throws Exception {
        org.json.JSONArray releases = new org.json.JSONArray();
        for (String version : new String[]{"v1.3.0-sweep.25", "v1.3.0-sweep.100", "v1.3.0-sweep.9", "v3.5.0"}) {
            releases.put(new JSONObject().put("draft", false).put("prerelease", true)
                    .put("published_at", "2026-11-16T00:00:00Z").put("tag_name", version));
        }
        releases.put(new JSONObject().put("draft", true).put("tag_name", "v1.3.0-sweep.999"));
        assertEquals("Update available · 1.3.0-sweep.100", SelfUpdate.releaseStatus("1.3.0-sweep.99", releases));
        assertEquals("Up to date · 1.3.0-sweep.100", SelfUpdate.releaseStatus("1.3.0-sweep.100", releases));
        assertTrue(SelfUpdate.releaseStatus("1.3.0-sweep.101", releases).startsWith("Installed build is newer"));
        assertEquals("No Sweep TV releases published", SelfUpdate.releaseStatus("1.3.0-sweep.101", new org.json.JSONArray()));
    }

    @Test public void greetingIsOptionalAndNamesStayLocalAndEditable() {
        Context app=RuntimeEnvironment.getApplication();assertTrue(Keys.write(app,HomeOptions.NAME,"Akhil"));
        assertEquals("Good afternoon, Akhil",HomeOptions.greeting(15,HomeOptions.name(app)));
        assertEquals("Good morning",HomeOptions.greeting(8,""));assertEquals("Good evening, Akhil",HomeOptions.greeting(20,"Akhil"));
        long now=System.currentTimeMillis();assertTrue(HomeOptions.header(app,now).contains("Akhil"));
        HomeOptions.prefs(app).edit().putBoolean(HomeOptions.GREETING,false).commit();
        assertEquals("",HomeOptions.header(app,now));
        assertTrue(Keys.write(app,HomeOptions.NAME,""));assertEquals("",HomeOptions.name(app));
    }
    @Test public void statusMetricsCanBeHiddenAndOldCodexDataIsIgnored() {
        Context app = RuntimeEnvironment.getApplication();
        var prefs = HomeOptions.prefs(app);
        Meters widget = new Meters(app, null);
        prefs.edit().putBoolean(HomeOptions.Metric.UPTIME.key, true).apply();
        widget.update();
        widget.measure(0, 0);
        int fullWidth = widget.getMeasuredWidth();
        assertTrue(widget.content().contains("RAM"));
        assertTrue(widget.content().contains("GB free"));

        prefs.edit().putBoolean(HomeOptions.Metric.RAM.key, false).apply();
        widget.update();
        widget.measure(0, 0);
        assertFalse(widget.content().contains("RAM"));
        assertTrue(widget.getMeasuredWidth() < fullWidth);
        assertTrue(widget.content().contains("Storage"));

        prefs.edit().putBoolean("meters", false).putBoolean("widget_codex", true).apply();
        HomeOptions.migrateDeviceMetrics(prefs);
        widget.update();
        assertEquals(View.GONE, widget.getVisibility());
        assertTrue(Keys.write(app, "codex_usage.json", "{}"));
        prefs.edit().putBoolean(HomeOptions.Metric.UPTIME.key, true).apply();
        HomeOptions.migrateDeviceMetrics(prefs);
        widget.update();
        assertEquals(View.VISIBLE, widget.getVisibility());
        assertTrue(widget.content().startsWith("Uptime"));
        assertFalse(widget.content().contains("Storage"));
    }
    @Test public void hidingWholeSectionsPreservesTheirItemsAndCanBeReversed() {
        Context app=RuntimeEnvironment.getApplication();Channels.Channel row=new Channels.Channel();row.key="example.channel";row.title="My section";Trending.Pick pick=new Trending.Pick();pick.title="Film";pick.intentUri="example://resume";row.items.add(pick);
        java.util.List<Channels.Channel> rows=new java.util.ArrayList<>();rows.add(row);SectionOptions.set(app,row.key,false);SectionOptions.filter(app,rows);assertTrue(rows.isEmpty());assertEquals("example://resume",row.items.get(0).intentUri);
        SectionOptions.set(app,row.key,true);rows.add(row);SectionOptions.filter(app,rows);assertEquals(1,rows.size());
    }
    @Test public void releaseInfoIsBundledAndOldUpstreamNotificationsCannotCreateUpdates() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        android.content.pm.PackageInfo installed=app.getPackageManager().getPackageInfo(app.getPackageName(),0);try(java.io.InputStream in=app.getAssets().open("sweep-release.json")){installed.versionName=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getString("version");}
        org.robolectric.Shadows.shadowOf(app.getPackageManager()).installPackage(installed);
        app.getSharedPreferences("upstream_updates",0).edit().putString("latest","v99.0.0").putString("body","Stale upstream notice").commit();
        app.getSharedPreferences("updates",0).edit().putString("latest","v99.0.0").commit();
        assertFalse(SelfUpdate.takesReleases(app));assertFalse(SelfUpdate.checkIfStale(app));assertNull(SelfUpdate.available(app));
        assertFalse(SelfUpdate.summary(app).contains("Up to date"));
        assertFalse(ReleaseInfo.notes(app).get(0).contains("unavailable"));assertFalse(ReleaseInfo.notes(app).isEmpty());
        assertTrue(ReleaseInfo.components(app).get(0).name.contains("v3.5.0"));
        assertTrue(ReleaseInfo.components(app).get(0).detail.contains("not a wholesale import"));
        installed.versionName="0.0.0";org.robolectric.Shadows.shadowOf(app.getPackageManager()).installPackage(installed);
        assertTrue(ReleaseInfo.components(app).isEmpty());assertTrue(ReleaseInfo.notes(app).get(0).contains("unavailable"));
    }
}
