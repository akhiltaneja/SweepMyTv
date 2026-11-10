package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.os.*;
import android.view.KeyEvent;
import android.content.*;
import android.content.pm.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class RemoteResumeTest {
    @Test public void heldVolumeRepeatsWithoutFirmwareRepeatEventsAndStopsOnRelease() {
        List<Integer> calls=new ArrayList<>();VolumeRepeater repeat=new VolumeRepeater(new Handler(Looper.getMainLooper()),calls::add);
        assertTrue(repeat.handle(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP)));assertEquals(Arrays.asList(1),calls);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(950));assertTrue(calls.size()>4);
        repeat.handle(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_UP));int before=calls.size();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(before,calls.size());
        repeat.handle(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));assertEquals(Integer.valueOf(-1),calls.get(calls.size()-1));repeat.stop();
    }
    @Test public void tapsAreSingleStepsAndOtherKeysOrCancellationStopTheRepeat() {
        List<Integer> calls=new ArrayList<>();VolumeRepeater repeat=new VolumeRepeater(new Handler(Looper.getMainLooper()),calls::add);
        repeat.handle(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));repeat.handle(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(Arrays.asList(-1),calls);
        repeat.handle(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP));assertFalse(repeat.handle(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_HOME)));int before=calls.size();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(before,calls.size());
    }
    private Context appleInstalled(){
        Context app=RuntimeEnvironment.getApplication();android.content.pm.ActivityInfo activity=new android.content.pm.ActivityInfo();activity.packageName=AppleResumeCache.PACKAGE;activity.name=AppleResumeCache.PACKAGE+".MainActivity";activity.applicationInfo=new ApplicationInfo(app.getApplicationInfo());activity.applicationInfo.packageName=activity.packageName;activity.enabled=true;activity.exported=true;
        PackageInfo installed=new PackageInfo();installed.packageName=activity.packageName;installed.applicationInfo=activity.applicationInfo;org.robolectric.shadows.ShadowPackageManager pm=Shadows.shadowOf(app.getPackageManager());pm.installPackage(installed);pm.addOrUpdateActivity(activity);IntentFilter filter=new IntentFilter(Intent.ACTION_MAIN);filter.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);pm.addIntentFilterForActivity(new ComponentName(activity.packageName,activity.name),filter);return app;
    }
    private Trending.Pick episode(int id){Trending.Pick p=new Trending.Pick();p.pkg=AppleResumeCache.PACKAGE;p.id=id;p.title="Episode "+id;p.meta="Season 2 · Episode "+id+" · Apple TV";p.service="Apple TV";p.at=123456;p.intentUri="apple-tv://resume/"+id;p.progress=.4f;return p;}
    @Test public void appleQueueSurvivesAnEmptyProviderAtBootWithRealResumeData() {
        Context app=appleInstalled();long now=System.currentTimeMillis();Trending.Pick first=episode(1);AppleResumeCache.reconcile(app,Arrays.asList(first),true,now);
        List<Trending.Pick> restored=AppleResumeCache.reconcile(app,new ArrayList<>(),false,now+10_000);assertEquals(1,restored.size());Trending.Pick saved=restored.get(0);assertEquals(first.intentUri,saved.intentUri);assertEquals(first.meta,saved.meta);assertEquals(first.at,saved.at);assertEquals(first.progress,saved.progress,0);assertTrue(saved.savedResume);assertTrue(saved.displayMeta().contains("Saved resume"));
        assertTrue(AppleResumeCache.restore(app,now+8*24*3600_000L).isEmpty());
    }
    @Test public void republishedQueueReplacesSnapshotAndExplicitProviderWithdrawalClearsIt() {
        Context app=appleInstalled();long now=System.currentTimeMillis();AppleResumeCache.reconcile(app,Arrays.asList(episode(1)),true,now);Trending.Pick replacement=episode(2);
        List<Trending.Pick> live=AppleResumeCache.reconcile(app,Arrays.asList(replacement),true,now+1000);assertSame(replacement,live.get(0));assertFalse(replacement.savedResume);assertEquals(2,AppleResumeCache.restore(app,now+2000).get(0).id);
        assertTrue(AppleResumeCache.reconcile(app,new ArrayList<>(),true,now+3000).isEmpty());assertTrue(AppleResumeCache.restore(app,now+4000).isEmpty());
    }
    @Test public void titleArtworkSnapshotPreservesTheProviderLandscapeAtBoot() throws Exception {
        Context app=appleInstalled();app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.ARTWORK,true).commit();Trending.Pick p=episode(1);
        java.io.File wide=new java.io.File(app.getExternalFilesDir(null),"provider-landscape.png"),tall=new java.io.File(app.getExternalFilesDir(null),"catalog-portrait.png");
        for(java.io.File file:new java.io.File[]{wide,tall}){android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(file==wide?640:320,file==wide?360:480,android.graphics.Bitmap.Config.ARGB_8888);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
        p.image=wide;p.poster=tall;long now=System.currentTimeMillis();AppleResumeCache.reconcile(app,Arrays.asList(p),true,now);Trending.Pick restored=AppleResumeCache.restore(app,now+1000).get(0);assertEquals(wide,restored.image);assertEquals(tall,restored.poster);assertEquals(p.intentUri,restored.intentUri);assertEquals(p.progress,restored.progress,0);
    }
    @Test public void cachedResumeHonoursTitleArtworkAndKeepsRemovalIdentity() throws Exception {
        Context app=appleInstalled();long now=System.currentTimeMillis();Trending.Pick p=episode(1);java.io.File still=new java.io.File(app.getExternalFilesDir(null),"still.jpg"),poster=new java.io.File(app.getExternalFilesDir(null),"poster.jpg");java.nio.file.Files.write(still.toPath(),new byte[]{1});android.graphics.Bitmap art=android.graphics.Bitmap.createBitmap(320,480,android.graphics.Bitmap.Config.ARGB_8888);try(java.io.FileOutputStream out=new java.io.FileOutputStream(poster)){art.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}art.recycle();p.image=still;p.poster=poster;AppleResumeCache.reconcile(app,Arrays.asList(p),true,now);
        app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.ARTWORK,true).commit();Trending.Pick restored=AppleResumeCache.restore(app,now+1000).get(0);assertNull(restored.image);assertEquals(poster,restored.poster);assertEquals(p.meta,restored.meta);
    }
}
