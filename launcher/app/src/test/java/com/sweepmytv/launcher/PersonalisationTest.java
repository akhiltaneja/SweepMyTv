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
    @Test public void greetingIsOptionalAndNamesStayLocalAndEditable() {
        Context app=RuntimeEnvironment.getApplication();assertTrue(Keys.write(app,HomeOptions.NAME,"Akhil"));
        assertEquals("Good afternoon, Akhil",HomeOptions.greeting(15,HomeOptions.name(app)));
        assertEquals("Good morning",HomeOptions.greeting(8,""));assertEquals("Good evening, Akhil",HomeOptions.greeting(20,"Akhil"));
        long now=System.currentTimeMillis();assertTrue(HomeOptions.header(app,now,"29° Sunny").contains("Akhil"));
        HomeOptions.prefs(app).edit().putBoolean("header_greeting",false).commit();assertEquals("29° Sunny",HomeOptions.header(app,now,"29° Sunny"));
        HomeOptions.prefs(app).edit().putBoolean("header_weather",false).commit();assertEquals("",HomeOptions.header(app,now,"29° Sunny"));
        assertTrue(Keys.write(app,HomeOptions.NAME,""));assertEquals("",HomeOptions.name(app));
    }
    @Test public void metricsCanAllBeRemovedIndependentlyAndStaleCodexIsLabelled() throws Exception {
        Context app=RuntimeEnvironment.getApplication();Meters widget=new Meters(app,null);widget.update();assertTrue(widget.content().contains("RAM"));assertTrue(widget.content().contains("Disk"));
        HomeOptions.stat(app,"ram",false);widget.update();assertFalse(widget.content().contains("RAM"));assertTrue(widget.content().contains("Disk"));
        HomeOptions.stat(app,"storage",false);widget.update();assertEquals(View.GONE,widget.getVisibility());
        long now=System.currentTimeMillis();JSONObject snapshot=new JSONObject().put("synced_at",now).put("used_percent",53).put("window_minutes",10080).put("resets_at",now+3600_000);
        CodexUsage usage=CodexUsage.parse(snapshot,now);assertNotNull(usage);assertEquals("week 47% left",usage.compact(now));assertEquals("Sync needed",usage.compact(now+16*60_000));
        assertTrue(Keys.write(app,CodexUsage.FILE,snapshot.toString()));HomeOptions.stat(app,"codex",true);widget.update();assertEquals(View.VISIBLE,widget.getVisibility());assertTrue(widget.content().contains("47% left"));
        HomeOptions.prefs(app).edit().putBoolean("meters",false).commit();widget.update();assertEquals(View.GONE,widget.getVisibility());
        assertNull(CodexUsage.parse(snapshot.put("used_percent",-1),now));
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
        assertTrue(ReleaseInfo.components(app).get(0).name.contains("v3.4.0"));
        assertTrue(ReleaseInfo.components(app).get(0).detail.contains("not a wholesale import"));
        installed.versionName="0.0.0";org.robolectric.Shadows.shadowOf(app.getPackageManager()).installPackage(installed);
        assertTrue(ReleaseInfo.components(app).isEmpty());assertTrue(ReleaseInfo.notes(app).get(0).contains("unavailable"));
    }
}
