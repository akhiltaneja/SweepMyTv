package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowBluetoothAdapter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomePreviewTest {
    private static final String[] PACKAGES = {"com.apple.atve.androidtv.appletv", "com.netflix.ninja",
            "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
            "com.formulaone.production", "com.sonyliv", "com.fancode.tv", "example.music", "example.photos",
            "example.settings", "example.library", "example.games"};
    private static final String[] LABELS = {"Apple TV", "Netflix", "YouTube", "Prime Video", "JioHotstar",
            "F1 TV", "Sony LIV", "FanCode", "Music", "Photos", "Settings", "Library", "Games"};
    private static final int[] COLORS = {0xFF161719, 0xFF121212, 0xFFFAFAFA, 0xFF0865CE, 0xFF1028A5,
            0xFFEA1015, 0xFF21174B, 0xFFFF762B, 0xFFFA2255, 0xFF2C5E69, 0xFF595C65, 0xFF375462, 0xFF9638D8};

    @Before public void installFixtureApps() throws Exception {
        // There is no physical Bluetooth adapter in the local UI-rendering test environment.
        ShadowBluetoothAdapter.setIsBluetoothSupported(false);
        Application app = RuntimeEnvironment.getApplication();
        assertTrue(Keys.write(app, "region.txt", "US"));
        app.getSharedPreferences("home", 0).edit().putBoolean("automatic_city",false).putBoolean("insights",false).putString("scores", "off").putBoolean("for_you", false).putBoolean("formula_one", false).commit();
        ShadowPackageManager pm = Shadows.shadowOf(app.getPackageManager());
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        File tiles = new File(app.getExternalFilesDir(null), "tiles"); assertTrue(tiles.mkdirs() || tiles.isDirectory());
        for (int i = 0; i < PACKAGES.length; i++) {
            ActivityInfo ai = new ActivityInfo();
            ai.packageName = PACKAGES[i]; ai.name = PACKAGES[i] + ".MainActivity";
            ai.applicationInfo = new ApplicationInfo(app.getApplicationInfo());
            ai.applicationInfo.packageName = PACKAGES[i];
            ai.nonLocalizedLabel = LABELS[i]; ai.enabled = true; ai.exported = true;
            PackageInfo installed = new PackageInfo();
            installed.packageName = PACKAGES[i]; installed.applicationInfo = ai.applicationInfo;
            pm.installPackage(installed);
            pm.addOrUpdateActivity(ai);
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(new ComponentName(ai.packageName, ai.name), filter);
            ResolveInfo ri = new ResolveInfo(); ri.activityInfo = ai; ri.nonLocalizedLabel = LABELS[i];
            Bitmap bitmap = Bitmap.createBitmap(512, 304, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(COLORS[i]);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(i == 2 ? 0xFF161719 : Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(54);
            String label = LABELS[i];
            canvas.drawText(label, (512 - paint.measureText(label)) / 2, 172, paint);
            try (FileOutputStream out = new FileOutputStream(new File(tiles, PACKAGES[i] + ".jpg"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        }
    }

    @Test public void everyTopTitleShelfWrapsBothWaysAndKeepsItsExistingCards() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("home",0).edit().putBoolean("for_you",true).commit();
        var controller=Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity activity=controller.get();drain(activity);
        List<Trending.Pick> picks=loopPicks(activity,Trending.TRENDING);
        for(String name:List.of("recentPicks","trendPicks","indiaPicks")) {
            Field data=HomeActivity.class.getDeclaredField(name);data.setAccessible(true);data.set(activity,new java.util.ArrayList<>(picks));
        }
        for(int tab=0;tab<3;tab++) {
            call(activity,"focusTabs",new Class[]{int.class},tab);
            call(activity,"buildPicksRow",new Class[]{int.class},0);
            call(activity,"focusPicks",new Class[]{int.class},2);
            PicksRow row=(PicksRow)field(activity,"picksRow");View first=row.getChildAt(0);
            List<Trending.Pick> shown=(List<Trending.Pick>)field(activity,"rowPicks");
            key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(0,row.anchor());assertSame(first,row.getChildAt(0));assertEquals(PicksRow.viewCount(3,960,PicksRow.SMALL_W,PicksRow.GAP),row.getChildCount());
            assertEquals(shown.get(0).title,((android.widget.TextView)activity.findViewById(R.id.pick_caption_title)).getText().toString());
            key(activity,KeyEvent.KEYCODE_DPAD_LEFT);assertEquals(2,row.anchor());
            assertEquals(shown.get(2).title,((android.widget.TextView)activity.findViewById(R.id.pick_caption_title)).getText().toString());
            long now=android.os.SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,3));
            assertEquals("Holding Right also crosses the boundary",0,row.anchor());
            key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(1,row.anchor());
            key(activity,KeyEvent.KEYCODE_DPAD_UP);assertEquals(1,field(activity,"zone"));
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(1,row.anchor());
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(3,field(activity,"zone"));
        }
        controller.pause().stop().destroy();
    }

    @Test public void recommendationAndAppRowsKeepTheNextCycleVisibleAndMoveForward() throws Exception {
        var controller=Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity activity=controller.get();drain(activity);
        List<Channels.Channel> rows=new java.util.ArrayList<>();
        for(String name:List.of(Recommendations.KEY,Recommendations.NEW_KEY,Recommendations.appKey(PACKAGES[1]),RottenTomatoesPicks.KEY,PACKAGES[1]+"|Netflix",SpotifyPicks.KEY)) {
            Channels.Channel row=new Channels.Channel();row.key=name;row.pkg=PACKAGES[1];row.title="Example recommendations";
            row.items.addAll(loopPicks(activity,name));rows.add(row);
        }
        ChannelSnapshot snapshot=new ChannelSnapshot(rows);
        call(activity,"buildChannelRows",new Class[]{List.class,java.util.Map.class},snapshot.rows,snapshot.signatures);
        layout(activity.findViewById(R.id.root));
        for(int r=0;r<rows.size();r++) {
            call(activity,"focusChan",new Class[]{int.class,int.class},r,2);settle();
            key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(0,((int[])field(activity,"chanCol"))[r]);assertEquals(r,field(activity,"chanIdx"));
            Spring scroll=((List<Spring>)field(activity,"chanSprings")).get(r);
            assertTrue("Right advances into the next visible cycle", scroll.target > scroll.value);
            android.widget.HorizontalScrollView viewport=((List<android.widget.HorizontalScrollView>)field(activity,"chanScrolls")).get(r);
            assertEquals(0,viewport.getScrollX());
            Object views=((java.util.Map<?,?>)field(activity,"channelViews")).get(rows.get(r).key);
            assertEquals(rows.get(r).items.get(0).title,((android.widget.TextView)field(views,"captionTitle")).getText().toString());
            float rightTarget=scroll.target;
            key(activity,KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(2,((int[])field(activity,"chanCol"))[r]);assertTrue(scroll.target < rightTarget);
            assertEquals(0,viewport.getScrollX());
            assertEquals(rows.get(r).items.get(2).title,((android.widget.TextView)field(views,"captionTitle")).getText().toString());
            assertTrue(((List<List<View>>)field(activity,"chanCards")).get(r).size() >= 3);
        }
        call(activity,"focusChan",new Class[]{int.class,int.class},0,0);
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(1,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);assertEquals(0,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);assertEquals(3,field(activity,"zone"));
        controller.pause().stop().destroy();
    }

    @Test public void aSingleTitleOrAnEmptyRefreshDoesNotBreakHorizontalNavigation() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("home",0).edit().putBoolean("for_you",true).commit();
        var controller=Robolectric.buildActivity(HomeActivity.class).setup();HomeActivity activity=controller.get();drain(activity);
        Field catalog=HomeActivity.class.getDeclaredField("trendPicks");catalog.setAccessible(true);
        catalog.set(activity,List.of(loopPicks(activity,Trending.TRENDING).get(0)));
        call(activity,"focusTabs",new Class[]{int.class},1);call(activity,"buildPicksRow",new Class[]{int.class},0);
        call(activity,"focusPicks",new Class[]{int.class},0);
        key(activity,KeyEvent.KEYCODE_DPAD_LEFT);key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(0,((PicksRow)field(activity,"picksRow")).anchor());
        ((List<?>)field(activity,"rowPicks")).clear();
        key(activity,KeyEvent.KEYCODE_DPAD_LEFT);key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(3,field(activity,"zone"));
        controller.pause().stop().destroy();
    }

    private static List<Trending.Pick> loopPicks(HomeActivity activity,String tab) {
        List<Trending.Pick> picks=new java.util.ArrayList<>();
        for(int i=0;i<3;i++) {
            Trending.Pick p=new Trending.Pick();p.id=800+i;p.kind="tv";p.title="Loop title "+i;
            p.pkg=PACKAGES[1];p.service="Netflix";p.tab=tab;
            p.image=new File(activity.getExternalFilesDir(null),"tiles/"+PACKAGES[1]+".jpg");picks.add(p);
        }
        return picks;
    }

    @Test public void cachedSportsSectionsExistBeforeTheFirstRecommendationResult() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("home", 0).edit().putBoolean("formula_one", true)
                .putBoolean("sport_cricket", true).commit();
        long now = System.currentTimeMillis();
        Cricket.Match saved = new Cricket.Match();
        saved.id = "cached-fixture";
        saved.name = "Home vs Away";
        saved.league = "Example tour";
        saved.full1 = saved.t1 = "Home";
        saved.full2 = saved.t2 = "Away";
        saved.scheduledAt = now;
        saved.live = true;
        SportsScores scores = new SportsScores(app);
        scores.saveCached(false, List.of(saved), now);
        scores.close();
        var controller = Robolectric.buildActivity(HomeActivity.class).create();
        HomeActivity activity = controller.get();
        List<Channels.Channel> rows = (List<Channels.Channel>) field(activity, "chanRows");
        assertEquals(List.of(FormulaOne.KEY, "cricket"), rows.stream().map(row -> row.key)
                .collect(java.util.stream.Collectors.toList()));
        Cricket.Match restored = (Cricket.Match) rows.get(1).items.get(0).extra;
        assertEquals("Cached", SportsDashboard.status(restored));
        assertFalse((boolean) field(activity, "inFront"));
        controller.destroy();
    }

    @Test public void completedRowsAppearWhileNewArtworkIsQueuedAndTheViewerUsesTheTopTabs() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        ExecutorService worker = (ExecutorService) field(activity, "channelWorker");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var blocker = worker.submit(() -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Channels.Channel cached = new Channels.Channel();
        cached.key = "cached-app-row";
        cached.pkg = PACKAGES[0];
        cached.title = "Cached app section";
        Trending.Pick card = new Trending.Pick();
        card.pkg = cached.pkg;
        card.kind = "tv";
        card.title = "Cached title";
        cached.items.add(card);
        Field pending = HomeActivity.class.getDeclaredField("pendingChannels");
        pending.setAccessible(true);
        pending.set(activity, new ChannelSnapshot(List.of(cached)));
        Field zone = HomeActivity.class.getDeclaredField("zone");
        zone.setAccessible(true);
        zone.setInt(activity, 1);
        Field lastKey = HomeActivity.class.getDeclaredField("lastKeyAt");
        lastKey.setAccessible(true);
        lastKey.setLong(activity, android.os.SystemClock.uptimeMillis());
        try {
            call(activity, "showChannels", new Class[]{List.class}, List.of());
            ((Runnable) field(activity, "applyPreparedChannels")).run();
            List<Channels.Channel> shown = (List<Channels.Channel>) field(activity, "chanRows");
            assertEquals("cached-app-row", shown.get(0).key);
        } finally {
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void originalDockGridAndSettingsRemainReachable() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        View root=activity.findViewById(R.id.root);layout(root);
        assertEquals(7,field(activity,"dockCount"));assertEquals(6,((List<?>)field(activity,"gridTiles")).size());
        call(activity,"focusDock",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();
        assertEquals(4,field(activity,"zone"));assertEquals(0,field(activity,"gridIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(3,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(0,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(5,field(activity,"zone"));
        call(activity,"openSweepSettings",new Class[]{int.class},0);settle();
        for(Object row:(List<?>)field(activity,"menuItems")) {
            android.widget.TextView label=((View)row).findViewById(R.id.input_label);
            if(label!=null)assertNotEquals("Banner quality",label.getText().toString());
        }
        assertFalse(SelfUpdate.takesReleases(activity));assertNull(SelfUpdate.available(activity));
        save(root,"sweep-base-settings.png");
    }

    @Test public void everyHomeAppTileLaunchesItsOwnActivity() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        List<AppEntry> apps = (List<AppEntry>) field(activity, "apps");
        int dockCount = (int) field(activity, "dockCount");
        assertEquals(PACKAGES.length, apps.size());
        for (int i = 0; i < apps.size(); i++) {
            boolean docked = i < dockCount;
            call(activity, docked ? "focusDock" : "focusGrid", new Class[]{int.class}, docked ? i : i - dockCount);
            key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
            String pkg = apps.get(i).pkg;
            assertNotNull(pkg, launched);
            assertEquals(new ComponentName(pkg, pkg + ".MainActivity"), launched.getComponent());
            assertEquals(Intent.ACTION_MAIN, launched.getAction());
        }
    }

    @Test public void systemShowsInstalledVersionAndAboutKeepsReleaseNotesAndCredits() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        activity.getSharedPreferences("upstream_updates",0).edit().putString("latest","v99.0.0").commit();
        call(activity,"openSweepSettings",new Class[]{int.class},0);settle();
        enterCategory(activity, 6);settle();
        List<String> system = menuLabels(activity);
        assertEquals(SelfUpdate.installed(activity), menuValue(activity, system.indexOf("Updates")));
        assertTrue(system.contains("Weather location"));
        assertTrue(java.util.Collections.disjoint(system, List.of("Send location from phone", "Bluetooth", "Storage", "Remote buttons")));
        selectSetting(activity, "About");
        selectSetting(activity, "What's new");
        assertEquals("What's new in Sweep TV", field(activity, "settingsHeading"));
        key(activity, KeyEvent.KEYCODE_BACK);
        selectSetting(activity, "Included components");
        assertTrue(menuLabels(activity).containsAll(List.of("CleanYourTV v3.5.0", "Open-source licences")));
        key(activity, KeyEvent.KEYCODE_BACK);
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("System", field(activity, "settingsHeading"));
        assertEquals(system.indexOf("About"), field(activity, "menuIdx"));
    }

    @Test public void selectedScreenSaverAppearsFirstAndAerialControlsStayInsideItsPage() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        ComponentName backdrop = new ComponentName("com.google.android.backdrop", "com.google.android.backdrop.Backdrop");
        ComponentName aerial = new ComponentName(ScreenSavers.AERIAL, ScreenSavers.AERIAL + ".Dream");
        ComponentName xiaomi = new ComponentName("com.mitv.dream", "com.mitv.dream.ScreenSaver");
        for (ComponentName component : new ComponentName[]{backdrop, aerial, xiaomi}) {
            android.content.pm.ServiceInfo service = packages.addServiceIfNotPresent(component);
            service.permission = android.Manifest.permission.BIND_DREAM_SERVICE;
            service.exported = true;
            packages.addOrUpdateService(service);
            packages.addIntentFilterForService(component, new IntentFilter("android.service.dreams.DreamService"));
        }
        android.provider.Settings.Secure.putString(app.getContentResolver(), "screensaver_components", aerial.flattenToString());
        android.provider.Settings.Secure.putString(app.getContentResolver(), "screensaver_default_component", backdrop.flattenToString());
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        call(activity, "openSweepSettings", new Class[]{int.class}, 3);
        enterCategory(activity, 3);

        List<View> rows = (List<View>) field(activity, "menuItems");
        List<String> labels = menuLabels(activity);
        assertEquals(List.of("Aerial Views", "Aerial Views settings"), labels.subList(0, 2));
        assertTrue(labels.contains("Xiaomi Screensaver"));
        assertEquals(View.VISIBLE, rows.get(0).findViewWithTag("selection-tick").getVisibility());
        assertSame(rows.get(0).getParent(), rows.get(1).getParent());
        assertEquals("", menuValue(activity, labels.indexOf("Default Android TV Backdrop")));
        assertFalse(labels.contains("Open Aerial Views"));
        selectSetting(activity, "Default Android TV Backdrop");
        assertEquals(backdrop, ScreenSavers.active(activity));
        assertEquals("Default Android TV Backdrop", menuLabels(activity).get(0));
        assertFalse(menuLabels(activity).contains("Aerial Views settings"));
        selectSetting(activity, "Aerial Views");
        assertEquals(aerial, ScreenSavers.active(activity));
        assertEquals("Screen saver", field(activity, "settingsHeading"));
        selectSetting(activity, "Aerial Views settings");
        assertEquals("Aerial Views settings", field(activity, "settingsHeading"));
        assertTrue(menuLabels(activity).containsAll(List.of("Auto", "4K SDR", "Open Aerial Views")));
        assertFalse(menuLabels(activity).contains("Use Aerial Views as screen saver"));
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("Screen saver", field(activity, "settingsHeading"));
        assertEquals(1, field(activity, "menuIdx"));
    }

    @Test public void homeDrawerShowsDockAndGridAppsAndBackClosesIt() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .putExtra(RemoteKeys.EXTRA_OPEN, RemoteKeys.APP_DRAWER);
        call(activity,"onNewIntent",new Class[]{Intent.class},open);
        settle();
        assertEquals("All apps",field(activity,"settingsHeading"));
        List<View> rows=(List<View>)field(activity,"menuItems");
        assertEquals(((List<?>)field(activity,"apps")).size(),rows.size());
        assertTrue(rows.size()>(int)field(activity,"dockCount"));
        List<String> labels = new java.util.ArrayList<>();
        for (View row : rows) {
            android.widget.TextView title=row.findViewById(R.id.input_label);
            android.widget.ImageView icon=row.findViewById(R.id.drawer_app_icon);
            assertNotNull(icon.getDrawable());
            assertNull(row.getBackground());
            labels.add(title.getText().toString());
        }
        assertEquals("Apple TV", labels.get(0));
        assertTrue(labels.indexOf("YouTube") < labels.indexOf("Library"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(1,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(7,field(activity,"menuIdx"));
        activity.getSharedPreferences("home", 0).edit().putBoolean("drawer_show_other_apps", false).apply();
        call(activity,"onNewIntent",new Class[]{Intent.class},open);
        settle();
        List<View> filtered = (List<View>)field(activity,"menuItems");
        assertEquals(8, filtered.size());
        assertEquals("YouTube", filtered.get(7).getContentDescription());
        key(activity,KeyEvent.KEYCODE_BACK);
        settle();
        assertEquals(3,field(activity,"zone"));
        assertEquals(View.GONE,((View)field(activity,"settingsPopup")).getVisibility());
    }

    @Test public void drawerHintStaysDismissedOnlyAfterUsingTheGesture() throws Exception {
        var controller = Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true);
        HomeActivity activity = controller.get();
        drain(activity);
        var prefs = activity.getSharedPreferences("home", 0);
        prefs.edit().putBoolean("remote_keys_on", true).apply();
        Field running = SleepService.class.getDeclaredField("running");
        running.setAccessible(true);
        Object previous = running.get(null);
        running.set(null, Robolectric.buildService(SleepService.class).get());
        try {
            call(activity, "focusDock", new Class[]{int.class}, 0);
            View hint = activity.findViewById(R.id.drawer_hint);
            assertEquals(View.VISIBLE, hint.getVisibility());
            call(activity, "openRemotePanel", new Class[]{String.class}, RemoteKeys.APP_DRAWER);
            assertEquals(View.GONE, hint.getVisibility());
            key(activity, KeyEvent.KEYCODE_BACK);
            assertEquals(View.VISIBLE, hint.getVisibility());
            prefs.edit().putBoolean(RemoteKeys.DOUBLE_HOME_DRAWER, false).apply();
            call(activity, "updateDrawerHint", new Class[]{});
            assertEquals(View.GONE, hint.getVisibility());
            call(activity, "openRemotePanel", new Class[]{String.class}, RemoteKeys.APP_DRAWER_GESTURE);
            assertFalse(prefs.getBoolean(RemoteKeys.DRAWER_GESTURE_USED, false));
            prefs.edit().putBoolean(RemoteKeys.DOUBLE_HOME_DRAWER, true).apply();
            call(activity, "openRemotePanel", new Class[]{String.class}, RemoteKeys.APP_DRAWER_GESTURE);
            key(activity, KeyEvent.KEYCODE_BACK);
            assertTrue(prefs.getBoolean(RemoteKeys.DRAWER_GESTURE_USED, false));
            assertEquals(View.GONE, hint.getVisibility());
        } finally {
            running.set(null, previous);
            controller.pause().stop().destroy();
        }
    }

    @Test public void stockForYouCardsAndHomeNavigationAreRestored() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        Field enabled=HomeActivity.class.getDeclaredField("forYou");enabled.setAccessible(true);enabled.setBoolean(activity,true);
        List<Trending.Pick> catalog=(List<Trending.Pick>)field(activity,"trendPicks");catalog.clear();
        for(int i=0;i<3;i++) {
            Trending.Pick pick=new Trending.Pick();pick.id=100+i;pick.kind="tv";pick.tab=Trending.TRENDING;
            pick.pkg=PACKAGES[0];pick.title="Stock catalog title "+i;pick.meta="Series · Apple TV";
            pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+pick.pkg+".jpg");catalog.add(pick);
        }
        call(activity,"applyForYou",new Class[]{});call(activity,"buildPicksRow",new Class[]{int.class},0);
        call(activity,"focusDock",new Class[]{int.class},0);drain(activity);
        assertEquals(View.VISIBLE,activity.findViewById(R.id.for_you_head).getVisibility());
        assertEquals(1f,activity.findViewById(R.id.picks).getAlpha(),0f);
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(2,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);settle();assertEquals(1,((PicksRow)field(activity,"picksRow")).anchor());
        PicksRow shelf = activity.findViewById(R.id.picks);
        List<Trending.Pick> displayed = (List<Trending.Pick>)field(activity,"rowPicks");
        android.widget.TextView caption = activity.findViewById(R.id.pick_caption_title);
        assertEquals(displayed.get(1).title,caption.getText().toString());
        assertNull(shelf.getChildAt(0).findViewWithTag("name"));
        assertEquals(displayed.get(0).title,((ViewGroup)shelf.getChildAt(0)).getChildAt(0).getContentDescription());
        call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));settle();
        assertEquals(3,field(activity,"zone"));assertEquals(0,field(activity,"dockIdx"));
        View root=activity.findViewById(R.id.root);layout(root);
        View clock=activity.findViewById(R.id.big_clock);
        View tabs=activity.findViewById(R.id.for_you_head);
        View picks=activity.findViewById(R.id.picks);
        assertTrue("Clock and tabs have separate space",clock.getBottom()<tabs.getTop());
        assertTrue("Tabs and posters have separate space",tabs.getBottom()<picks.getTop());
        assertTrue(activity.findViewById(R.id.tab_trending).isSelected());
        save(root,"sweep-original-home.png");
    }

    private static void drain(HomeActivity activity) throws Exception {
        settle();((ExecutorService)field(activity,"background")).submit(()->{}).get(10,TimeUnit.SECONDS);settle();
        ((ExecutorService)field(activity,"channelWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);settle();
    }

    @Test public void continueChoicesFilterBothSourcesWithoutLosingResumeLinks() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        List<Trending.Pick> sessions=new java.util.ArrayList<>(),shared=new java.util.ArrayList<>();
        Trending.Pick youtube=new Trending.Pick();youtube.pkg=PACKAGES[2];youtube.title="Video";youtube.at=100;sessions.add(youtube);
        Trending.Pick ytShared=new Trending.Pick();ytShared.pkg=PACKAGES[2];ytShared.title="Shared video";ytShared.at=101;shared.add(ytShared);
        Trending.Pick netflix=new Trending.Pick();netflix.pkg=PACKAGES[1];netflix.title="Film";netflix.at=90;netflix.intentUri="netflix://resume/episode";netflix.progress=.35f;shared.add(netflix);
        activity.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.YOUTUBE,false).commit();
        List<Trending.Pick> out=(List<Trending.Pick>)call(activity,"continuePicks",new Class[]{List.class,List.class},sessions,shared);
        assertEquals(1,out.size());assertSame(netflix,out.get(0));assertEquals(.35f,out.get(0).progress,0);assertEquals("netflix://resume/episode",out.get(0).intentUri);
        assertEquals(1,sessions.size());assertEquals(2,shared.size());
        Trending.Pick saved=new Trending.Pick();saved.pkg=PACKAGES[0];saved.title="Saved Apple episode";saved.meta="Season 2 · Episode 1 · Apple TV";saved.savedResume=true;
        activity.getSharedPreferences("home",0).edit().putStringSet("hidden_continue",java.util.Collections.singleton(saved.pkg+"\n"+saved.title+"\n"+saved.meta)).commit();
        assertTrue(((List<?>)call(activity,"continuePicks",new Class[]{List.class,List.class},java.util.Collections.emptyList(),java.util.Arrays.asList(saved))).isEmpty());
        List<AppEntry> apps = (List<AppEntry>) field(activity, "apps");
        Set<String> observed = (Set<String>) field(activity, "recentSessionApps");
        List<String> hiddenPackages = List.of("com.google.android.videos", SpotifyPicks.PACKAGE, "com.google.android.youtube.tvmusic");
        for (String pkg : hiddenPackages) {
            ActivityInfo info = new ActivityInfo();
            info.packageName = pkg;
            info.name = pkg + ".MainActivity";
            apps.add(new AppEntry(info, pkg));
            observed.add(pkg);
        }
        call(activity, "openContinueSettings", new Class[]{});
        var appRows = (java.util.Map<String, View>) field(activity, "continueAppRows");
        assertTrue(appRows.containsKey(PACKAGES[1]));
        assertTrue(appRows.containsKey(PACKAGES[2]));
        assertTrue(hiddenPackages.stream().noneMatch(appRows::containsKey));
        ViewGroup group = (ViewGroup) appRows.get(PACKAGES[1]).getParent().getParent();
        assertSame(group, appRows.get(PACKAGES[2]).getParent().getParent());
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            assertFalse(child instanceof android.widget.TextView
                    && "Other apps".contentEquals(((android.widget.TextView) child).getText()));
        }
        List<View> settings = (List<View>) field(activity, "menuItems");
        assertTrue(settings.stream().anyMatch(row -> row.getParent() == group
                && "Show Continue Watching".contentEquals(((android.widget.TextView) row.findViewById(R.id.input_label)).getText())));
        assertFalse(settings.stream().map(row -> (android.widget.TextView) row.findViewById(R.id.input_label))
                .filter(java.util.Objects::nonNull).anyMatch(label ->
                        "Artwork".contentEquals(label.getText()) || "Hide spoilers".contentEquals(label.getText())));
        assertTrue(settings.stream().map(row -> (android.widget.TextView) row.findViewById(R.id.input_label))
                .filter(java.util.Objects::nonNull)
                .anyMatch(label -> "Notification access".contentEquals(label.getText())));
        selectSetting(activity, "YouTube");
        assertTrue(ContinueOptions.youtube(activity));
        selectSetting(activity, "Netflix");
        assertFalse(ContinueOptions.include(activity, netflix.pkg));
        assertEquals(1, ((List<?>) call(activity, "continuePicks", new Class[]{List.class, List.class}, sessions, shared)).size());
        assertEquals("netflix://resume/episode", netflix.intentUri);
        selectSetting(activity, "Netflix");
        assertTrue(ContinueOptions.include(activity, netflix.pkg));
    }

    @Test public void homeAppListsAlternateLaunchersAndOpensTheSelectedOne() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        ShadowPackageManager packages = Shadows.shadowOf(activity.getPackageManager());
        ComponentName alternate = new ComponentName(PACKAGES[12], PACKAGES[12] + ".MainActivity");
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        home.setPriority(2);
        packages.addIntentFilterForActivity(alternate, home);

        Field running = SleepService.class.getDeclaredField("running");
        running.setAccessible(true);
        Object previous = running.get(null);
        SleepService helper = Robolectric.buildService(SleepService.class).get();
        running.set(null, helper);
        try {
            ResolveInfo resolved = (ResolveInfo) call(activity, "resolveHome", new Class[]{});
            assertEquals(alternate.getPackageName(), resolved.activityInfo.packageName);
            assertFalse(SleepService.handlesHome(resolved));
            ((Set<String>) field(helper, "stockPackages")).add(alternate.getPackageName());
            call(activity, "openQuickSettings", new Class[]{int.class}, 3);
            List<String> labels = menuLabels(activity);
            assertEquals("Home app", labels.get(labels.size() - 1));
            assertEquals("Sweep TV", menuValue(activity, labels.size() - 1));
            selectSetting(activity, "Home app");
            int active = menuLabels(activity).indexOf("Sweep TV");
            assertEquals(active, field(activity, "menuIdx"));
            assertEquals("Home button helper", menuValue(activity, active));
            assertEquals("Android default", menuValue(activity, menuLabels(activity).indexOf(LABELS[12])));
            assertTrue(((List<View>) field(activity, "menuItems")).get(active).isSelected());
        } finally {
            running.set(null, previous);
        }
        call(activity, "openHomeSetup", new Class[]{});
        selectSetting(activity, LABELS[12]);
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(alternate, launched.getComponent());
        assertTrue(launched.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
    }

    @Test public void notificationAccessOpensAppsSettingsWhenTvHasNoDirectShortcut() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        ShadowPackageManager packages = Shadows.shadowOf(activity.getPackageManager());
        ComponentName settings = new ComponentName(PACKAGES[10], PACKAGES[10] + ".MainActivity");
        IntentFilter apps = new IntentFilter(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        apps.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(settings, apps);

        call(activity, "openNotificationAccess", new Class[]{});
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, launched.getAction());
        assertEquals("Open Special app access → Notification access",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());

        IntentFilter direct = new IntentFilter(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        direct.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(settings, direct);
        call(activity, "openNotificationAccess", new Class[]{});
        assertEquals(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Shadows.shadowOf(activity).getNextStartedActivity().getAction());
    }

    @Test public void continueEpisodeCaptionUsesKnownSeasonLengthAndOmitsTheProvider() throws Exception {
        Trending.Pick pick = new Trending.Pick();
        pick.tab = RecentStore.TAB;
        pick.meta = "Season 2 · Episode 3 · Apple TV";
        pick.service = "Apple TV";
        Trending.TitleMetadata details = new Trending.TitleMetadata("tv", new org.json.JSONObject(
                "{\"seasons\":[{\"season_number\":1,\"episode_count\":10},{\"season_number\":2,\"episode_count\":6}]}"));
        ContinueOptions.Episode episode = ContinueOptions.episode(pick);
        assertNotNull(episode);
        assertEquals("S2 · E3 · 3 Episodes Left", episode.caption(pick, details.seasonSize(episode.season)));
        assertEquals("S2 · E3 · 3 Episodes Left · Series", ContinueOptions.caption(pick, details));
        assertEquals("S2 · E3", episode.caption(pick, details.seasonSize(9)));
        pick.meta = "S2E6 · Apple TV";
        assertEquals("S2 · E6 · 0 Episodes Left", ContinueOptions.episode(pick).caption(pick, details.seasonSize(2)));
        pick.meta = "S2E5 · Apple TV";
        assertEquals("S2 · E5 · 1 Episode Left", ContinueOptions.episode(pick).caption(pick, details.seasonSize(2)));
        pick.meta = "Apple TV";
        assertNull(ContinueOptions.episode(pick));
        assertEquals("", ContinueOptions.captionMeta(pick));
    }

    @Test public void titleArtworkAndPlaybackStillUseDifferentProviderImagesAndKeepProgress() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        File still=new File(app.getCacheDir(),"provider-still.png"),poster=new File(app.getCacheDir(),"provider-title.png");
        for(int i=0;i<2;i++) {
            Bitmap image=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);image.eraseColor(i==0?Color.RED:Color.BLUE);
            try(FileOutputStream out=new FileOutputStream(i==0?still:poster)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();
        }
        android.content.ContentProvider provider=new android.content.ContentProvider() {
            @Override public boolean onCreate(){return true;}
            @Override public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] args,String sort) {
                android.database.MatrixCursor cursor=new android.database.MatrixCursor(projection);
                for (int i = 0; i < 20; i++) {
                    String pkg = i == 19 ? PACKAGES[1] : PACKAGES[2];
                    android.database.MatrixCursor.RowBuilder row=cursor.newRow();
                    row.add(pkg).add("Provider fixture title " + i).add("Episode 4").add("2").add("4")
                            .add(android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE).add(100L).add(250L)
                            .add(android.net.Uri.fromFile(still).toString()).add(android.net.Uri.fromFile(poster).toString())
                            .add("provider://episode/4").add(1).add(1000L).add(android.media.tv.TvContract.WatchNextPrograms.TYPE_TV_SERIES).add("COMEDY,DRAMA").add("2026");
                }
                return cursor;
            }
            @Override public String getType(android.net.Uri uri){return null;}
            @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues values){return null;}
            @Override public int update(android.net.Uri uri,android.content.ContentValues values,String selection,String[] args){return 0;}
            @Override public int delete(android.net.Uri uri,String selection,String[] args){return 0;}
        };
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(android.media.tv.TvContract.AUTHORITY,provider);
        List<Trending.Pick> playback = WatchNext.load(app);
        assertEquals(1, playback.size());
        assertEquals(PACKAGES[1], playback.get(0).pkg);
        assertEquals(Color.RED, android.graphics.BitmapFactory.decodeFile(playback.get(0).image.getPath()).getPixel(100, 100));
        ContinueOptions.setIncluded(app, PACKAGES[2], true);
        List<Trending.Pick> mixed = WatchNext.load(app);
        assertEquals(16, mixed.size());
        assertEquals(PACKAGES[1], mixed.get(15).pkg);
        app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.YOUTUBE,false).putBoolean(ContinueOptions.ARTWORK,true).commit();
        List<Trending.Pick> original=WatchNext.load(app);assertEquals(1,original.size());
        assertEquals("Comedy / Drama", original.get(0).providerGenres);
        assertEquals(PACKAGES[1],original.get(0).pkg);assertEquals("provider://episode/4",original.get(0).intentUri);assertEquals(.25f,original.get(0).progress,0);
        assertEquals(Color.BLUE,android.graphics.BitmapFactory.decodeFile(original.get(0).image.getPath()).getPixel(100,100));
        app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.ARTWORK,false).commit();
        List<Trending.Pick> again=WatchNext.load(app);assertEquals(Color.RED,android.graphics.BitmapFactory.decodeFile(again.get(0).image.getPath()).getPixel(100,100));
    }

    @Test public void homeShelfCyclesAndKeepsAnActivelySelectedContinueCard() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        activity.getSharedPreferences("home", 0).edit().putBoolean("show_trending_tab", true)
                .putBoolean("show_recently_released_tab", true).apply();
        Trending.Pick resume = new Trending.Pick();
        resume.id = 81;
        resume.kind = "watchnext";
        resume.tab = RecentStore.TAB;
        resume.pkg = PACKAGES[0];
        resume.title = "Resume title";
        Trending.Pick next = new Trending.Pick();
        next.id = 82;
        next.kind = "watchnext";
        next.tab = RecentStore.TAB;
        next.pkg = PACKAGES[1];
        next.title = "Another resume";
        Trending.Pick discovery = new Trending.Pick();
        discovery.title = "Discovery";
        for (String fieldName : List.of("recentPicks", "watchNextPicks", "trendPicks", "indiaPicks")) {
            Field list = HomeActivity.class.getDeclaredField(fieldName);
            list.setAccessible(true);
            list.set(activity, fieldName.equals("watchNextPicks") || fieldName.equals("recentPicks")
                    ? List.of(resume, next) : List.of(discovery));
        }
        call(activity, "buildPicksRow", new Class[]{int.class}, 0);
        assertEquals(0, field(activity, "tabIdx"));
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(1, field(activity, "tabIdx"));
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(2, field(activity, "tabIdx"));
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(0, field(activity, "tabIdx"));
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(1, field(activity, "tabIdx"));

        key(activity, KeyEvent.KEYCODE_DPAD_UP);
        key(activity, KeyEvent.KEYCODE_DPAD_UP);
        key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(0, field(activity, "tabIdx"));
        assertEquals(2, field(activity, "zone"));
        call(activity, "onNewIntent", new Class[]{Intent.class}, new Intent(Intent.ACTION_MAIN));
        assertEquals(2, field(activity, "zone"));

        call(activity, "openContinueMenu", new Class[]{});
        call(activity, "advanceHomeShelf", new Class[]{});
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(0, field(activity, "tabIdx"));
        assertSame(next, ((List<?>) field(activity, "rowPicks")).get(0));
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(0, field(activity, "tabIdx"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        activity.getSharedPreferences("home", 0).edit().putBoolean("show_trending_tab", false).apply();
        call(activity, "advanceHomeShelf", new Class[]{});
        assertEquals(2, field(activity, "tabIdx"));
    }

    @Test public void titleCardsOverlayOnlyAvailableImdbRatings() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        Trending.Pick pick = new Trending.Pick();
        pick.title = "Example Film";
        pick.kind = "movie";
        pick.pkg = PACKAGES[0];
        pick.service = LABELS[0];
        pick.tab = Recommendations.appKey(pick.pkg);
        pick.sweep = true;
        pick.rating = 7.8f;
        pick.ratingSource = "IMDb";
        Shadows.shadowOf(activity.getPackageManager()).setApplicationIcon(pick.pkg,
                new android.graphics.drawable.ColorDrawable(Color.BLUE));

        View rowCard = (View) call(activity, "channelCard", new Class[]{Trending.Pick.class}, pick);
        View shelfCard = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, pick);
        for (View card : List.of(rowCard, shelfCard)) {
            android.widget.TextView badge = card.findViewWithTag("imdb-rating");
            assertNotNull(badge);
            assertEquals("7.8", badge.getText().toString());
            assertEquals(android.view.Gravity.TOP | android.view.Gravity.START,
                    ((android.widget.FrameLayout.LayoutParams) badge.getLayoutParams()).gravity);
            assertTrue(((View) badge.getParent()).getContentDescription().toString().endsWith("Rating 7.8 out of 10"));
        }
        assertNull(rowCard.findViewWithTag("provider-badge"));
        assertNotNull(shelfCard.findViewWithTag("provider-badge"));

        pick.ratingSource = "TMDB";
        View tmdb = (View) call(activity, "channelCard", new Class[]{Trending.Pick.class}, pick);
        assertNull(tmdb.findViewWithTag("imdb-rating"));
        pick.ratingSource = "IMDb";
        pick.rating = 0f;
        View unrated = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, pick);
        assertNull(unrated.findViewWithTag("imdb-rating"));
    }

    @Test public void portraitOnlyContinueArtworkStaysIntactWhenTheCardExpands() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        File portrait=new File(activity.getCacheDir(),"portrait-only.png");Bitmap b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);try(FileOutputStream out=new FileOutputStream(portrait)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        Trending.Pick p=new Trending.Pick();p.pkg=PACKAGES[0];p.title="Portrait-only title";p.kind="watchnext";p.meta="Series";p.poster=portrait;
        View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p);((List<Trending.Pick>)field(activity,"rowPicks")).clear();((List<Trending.Pick>)field(activity,"rowPicks")).add(p);
        PicksRow row=(PicksRow)field(activity,"picksRow");row.setItems(java.util.Collections.singletonList(item),0);call(activity,"updatePickImages",new Class[]{int.class,boolean.class},0,false);settle();
        android.widget.ImageView poster=item.findViewWithTag("poster");assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,poster.getScaleType());assertEquals(1f,poster.getAlpha(),0f);
    }

    @Test public void continueAndDiscoveryCardsRejectLandscapeFilesLabelledAsPosters() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);File wide=new File(activity.getCacheDir(),"wrong-poster-role.png");Bitmap b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);try(FileOutputStream out=new FileOutputStream(wide)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        Trending.Pick p=new Trending.Pick();p.pkg=PACKAGES[0];p.title="Landscape source";p.kind="watchnext";p.meta="Series";p.image=wide;p.poster=wide;
        Trending.Pick next=new Trending.Pick();next.pkg=PACKAGES[1];next.title="Next title";next.kind="watchnext";next.meta="Series";next.image=wide;
        View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p),second=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},next);
        List<Trending.Pick> picks=(List<Trending.Pick>)field(activity,"rowPicks");picks.clear();picks.add(p);picks.add(next);((PicksRow)field(activity,"picksRow")).setItems(java.util.Arrays.asList(item,second),1);call(activity,"updatePickImages",new Class[]{int.class,boolean.class},1,false);settle();
        assertEquals(0f,item.findViewWithTag("poster").getAlpha(),0f);
        assertEquals(View.INVISIBLE,item.findViewWithTag("backdrop").getVisibility());
        assertEquals(View.VISIBLE,item.findViewWithTag("continue_title").getVisibility());
        assertNull(item.getTag(R.id.ambient_artwork));
        call(activity,"updatePickImages",new Class[]{int.class,boolean.class},0,false);
        settle();
        assertEquals(View.GONE,item.findViewWithTag("continue_title").getVisibility());
        assertEquals(View.VISIBLE,item.findViewWithTag("backdrop").getVisibility());
        assertEquals(wide,item.getTag(R.id.ambient_artwork));
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,((android.widget.ImageView)item.findViewWithTag("backdrop")).getScaleType());
        for(String tab:new String[]{Recommendations.KEY,Recommendations.NEW_KEY}){p.tab=tab;ViewGroup card=(ViewGroup)call(activity,"channelCard",new Class[]{Trending.Pick.class},p);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,((android.widget.ImageView)card.getChildAt(0)).getScaleType());}
    }

    @Test public void mixedRowsShowProviderBadgesAndAppRowsRelyOnTheirHeader() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        Trending.Pick apple = new Trending.Pick();
        apple.pkg = PACKAGES[0];
        apple.tab = apple.pkg + "|Recommendations";
        apple.kind = "channel";
        apple.title = "Monarch";
        apple.service = "Apple TV";

        ShadowPackageManager packages = Shadows.shadowOf(activity.getPackageManager());
        android.graphics.drawable.Drawable appIcon = new android.graphics.drawable.ColorDrawable(Color.BLUE);
        packages.setApplicationIcon(apple.pkg, appIcon);
        for (String tab : new String[]{apple.tab, Recommendations.appKey(apple.pkg), SpotifyPicks.KEY}) {
            apple.tab = tab;
            View card = (View) call(activity, "channelCard", new Class[]{Trending.Pick.class}, apple);
            assertNull(card.findViewWithTag("provider-badge"));
        }
        apple.kind = "tv";
        apple.tab = Recommendations.KEY;
        View mixed = (View) call(activity, "channelCard", new Class[]{Trending.Pick.class}, apple);
        assertNotNull(mixed.findViewWithTag("provider-badge"));
        apple.tab = Trending.TRENDING;
        View hero = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, apple);
        android.widget.ImageView badge = hero.findViewWithTag("provider-badge");
        assertSame(appIcon, badge.getDrawable());
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.END,
                ((android.widget.FrameLayout.LayoutParams) badge.getLayoutParams()).gravity);
    }

    @Test public void homeCardWithoutLinkShowsTheTitleBeforeOpeningTheApp() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        var launches = Shadows.shadowOf(activity);
        while (launches.getNextStartedActivity() != null) { /* Discard setup intents. */ }
        Trending.Pick pick = new Trending.Pick();
        pick.kind = "channel";
        pick.title = "An app-published show";
        pick.pkg = PACKAGES[0];
        pick.service = "Apple TV";
        call(activity, "openPick", new Class[]{Trending.Pick.class}, pick);
        TitleDetailsDialog dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        View action = dialog.getWindow().getDecorView().findViewWithTag("title-action");
        assertEquals("Open Apple TV, Opens the app’s home screen", action.getContentDescription());
        assertNull(launches.getNextStartedActivity());

        pick.intentUri = new Intent(Intent.ACTION_MAIN).setPackage(pick.pkg).toUri(Intent.URI_INTENT_SCHEME);
        call(activity, "openPick", new Class[]{Trending.Pick.class}, pick);
        dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        action = dialog.getWindow().getDecorView().findViewWithTag("title-action");
        assertEquals("Open Apple TV, Opens the app’s home screen", action.getContentDescription());
        assertNull(launches.getNextStartedActivity());

        pick.intentUri = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("fixture://show/123"))
                .setPackage(pick.pkg).toUri(Intent.URI_INTENT_SCHEME);
        call(activity, "openPick", new Class[]{Trending.Pick.class}, pick);
        dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        action = dialog.getWindow().getDecorView().findViewWithTag("title-action");
        assertEquals("Open Apple TV, Opens the app’s home screen", action.getContentDescription());

        IntentFilter handler = new IntentFilter(Intent.ACTION_VIEW);
        handler.addCategory(Intent.CATEGORY_DEFAULT);
        handler.addDataScheme("fixture");
        Shadows.shadowOf(activity.getPackageManager()).addIntentFilterForActivity(
                new ComponentName(pick.pkg, pick.pkg + ".MainActivity"), handler);
        call(activity, "openPick", new Class[]{Trending.Pick.class}, pick);
        dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        action = dialog.getWindow().getDecorView().findViewWithTag("title-action");
        assertEquals("Play on Apple TV", action.getContentDescription());
        assertNull(launches.getNextStartedActivity());
        action.performClick();
        Intent launched = launches.getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("fixture://show/123", launched.getDataString());
        assertEquals(pick.pkg, launched.getPackage());
        assertFalse(dialog.isShowing());
    }

    @Test public void appPublishedTitleLinksStayWithTheirProvider() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        Trending.Pick pick = new Trending.Pick();
        pick.pkg = PACKAGES[0];
        Class<?>[] types = {Trending.Pick.class};
        android.net.Uri title = android.net.Uri.parse("https://tv.apple.com/in/show/umc.cmc.fixture");

        Intent link = new Intent(Intent.ACTION_VIEW, title).putExtra("resume", true);
        pick.intentUri = link.toUri(Intent.URI_INTENT_SCHEME);
        Intent pinned = (Intent) call(activity, "publishedTitleIntent", types, pick);
        assertNotNull(pinned);
        assertEquals(pick.pkg, pinned.getPackage());
        assertEquals(title, pinned.getData());
        assertTrue(pinned.getBooleanExtra("resume", false));

        ComponentName component = new ComponentName(pick.pkg, pick.pkg + ".MainActivity");
        pick.intentUri = link.setComponent(component).toUri(Intent.URI_INTENT_SCHEME);
        Intent explicit = (Intent) call(activity, "publishedTitleIntent", types, pick);
        assertEquals(component, explicit.getComponent());
        assertTrue(explicit.getBooleanExtra("resume", false));

        pick.intentUri = link.setComponent(null).setPackage(PACKAGES[1]).toUri(Intent.URI_INTENT_SCHEME);
        assertNull(call(activity, "publishedTitleIntent", types, pick));
        pick.intentUri = link.setPackage(null).setComponent(
                new ComponentName(PACKAGES[1], PACKAGES[1] + ".MainActivity")).toUri(Intent.URI_INTENT_SCHEME);
        assertNull(call(activity, "publishedTitleIntent", types, pick));
    }

    @Test public void catalogLinksUseExactRegionalProviderIdsAndRejectAmbiguousClaims() throws Exception {
        Trending.Pick pick = new Trending.Pick();
        pick.id = 42;
        pick.kind = "tv";
        pick.market = "IN";
        pick.pkg = PACKAGES[0];
        org.json.JSONArray apple = new org.json.JSONArray("[{\"mainsnak\":{\"datavalue\":{\"value\":\"umc.cmc.1aatz9gwjhnpfqqt8noafagq\"}}}]");
        String url = TitleLinks.url(pick, "IN", apple);
        assertEquals("https://tv.apple.com/in/show/umc.cmc.1aatz9gwjhnpfqqt8noafagq", url);
        assertEquals(pick.pkg, TitleLinks.appIntent(pick, url).getPackage());

        pick.pkg = PACKAGES[1];
        assertEquals("", TitleLinks.url(pick, "IN", apple));
        org.json.JSONArray netflix = new org.json.JSONArray("[{\"mainsnak\":{\"datavalue\":{\"value\":\"12345\"}}},"
                + "{\"mainsnak\":{\"datavalue\":{\"value\":\"54321\"}}}]");
        assertEquals("", TitleLinks.url(pick, "IN", netflix));
        netflix.getJSONObject(1).put("rank", "preferred");
        assertEquals("https://www.netflix.com/title/54321", TitleLinks.url(pick, "IN", netflix));
        Intent netflixIntent = TitleLinks.appIntent(pick, TitleLinks.url(pick, "IN", netflix));
        assertEquals("30", netflixIntent.getStringExtra("source"));
        assertTrue((netflixIntent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertNull(TitleLinks.appIntent(pick, "https://example.test/title/54321").getStringExtra("source"));

        pick.pkg = "com.amazon.amazonvideo.livingroom";
        org.json.JSONArray prime = new org.json.JSONArray("[{\"mainsnak\":{\"datavalue\":{\"value\":\"B012345678\"}}}]");
        String primeUrl = "https://app.primevideo.com/detail?gti=B012345678";
        assertEquals(primeUrl, TitleLinks.url(pick, "IN", prime));
        assertEquals(primeUrl, TitleLinks.appIntent(pick,
                "https://www.primevideo.com/detail/Example/B012345678").getDataString());
    }

    @Test public void discoveryLandscapeCardsUseTitleCoversOrAnIntactPosterNeverTheRawScene() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);File scene=new File(activity.getCacheDir(),"scene.png"),posterFile=new File(activity.getCacheDir(),"title-poster.png"),coverFile=new File(activity.getCacheDir(),"title-cover.png");
        for(Object[] spec:new Object[][]{{scene,1280,720},{posterFile,800,1200},{coverFile,1920,1080}}){Bitmap image=Bitmap.createBitmap((Integer)spec[1],(Integer)spec[2],Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream((File)spec[0])){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
        for(String tab:new String[]{Trending.TRENDING,Trending.INDIA}){
            Trending.Pick p=new Trending.Pick();p.id=100;p.kind="movie";p.tab=tab;p.pkg=PACKAGES[0];p.title="Title";p.image=scene;p.poster=posterFile;p.intentUri="fixture://resume";p.progress=.4f;
            ((android.util.LruCache)field(activity,"titleCovers")).evictAll();List<Trending.Pick> picks=(List<Trending.Pick>)field(activity,"rowPicks");picks.clear();picks.add(p);View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p);((PicksRow)field(activity,"picksRow")).setItems(java.util.Collections.singletonList(item),0);
            call(activity,"updatePickImages",new Class[]{int.class,boolean.class},0,false);settle();android.widget.ImageView poster=item.findViewWithTag("poster"),back=item.findViewWithTag("backdrop");assertEquals(1f,poster.getAlpha(),0);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,poster.getScaleType());assertNull(back.getTag(R.id.img_state));
            ((android.util.LruCache)field(activity,"titleCovers")).put(TitleCovers.key(p),new TitleCovers.Result(coverFile,true));call(activity,"updatePickImages",new Class[]{int.class,boolean.class},0,false);settle();assertEquals(0f,poster.getAlpha(),0);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,back.getScaleType());assertTrue(back.getTag(R.id.img_state).toString().contains(coverFile.getAbsolutePath()));assertFalse(back.getTag(R.id.img_state).toString().contains(scene.getAbsolutePath()));assertEquals("fixture://resume",p.intentUri);assertEquals(.4f,p.progress,0);

            p.id = -100;
            p.sweep = true;
            PicksRow row = (PicksRow) field(activity, "picksRow");
            View managed = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, p);
            row.setItems(List.of(managed), 0);
            call(activity, "updatePickImages", new Class[]{int.class, boolean.class}, 0, false);
            settle();
            android.widget.ImageView managedPoster = managed.findViewWithTag("poster");
            assertEquals(1f, managedPoster.getAlpha(), 0f);
            assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, managedPoster.getScaleType());
            assertNull(managed.findViewWithTag("backdrop").getTag(R.id.img_state));
            assertEquals(posterFile, managed.getTag(R.id.ambient_artwork));

            p.poster = null;
            View fallback = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, p);
            row.setItems(List.of(fallback), 0);
            call(activity, "updatePickImages", new Class[]{int.class, boolean.class}, 0, false);
            settle();
            assertEquals(scene, fallback.getTag(R.id.ambient_artwork));
        }
    }

    @Test public void formulaWidgetAndRaceDetailsWorkWithTheRemote() throws Exception {
        SectionOptions.set(RuntimeEnvironment.getApplication(), Recommendations.EXPLORE, false);
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get(); drain(activity);
        activity.getSharedPreferences("home", 0).edit().putBoolean("formula_one", true).commit();
        Field f = HomeActivity.class.getDeclaredField("formulaState"); f.setAccessible(true); f.set(activity, FormulaOneTest.fixture(FormulaOneTest.NOW));
        call(activity, "showChannels", new Class[]{List.class}, java.util.Collections.emptyList()); drain(activity);
        List<Channels.Channel> rows = (List<Channels.Channel>) field(activity, "chanRows");
        assertEquals(1, rows.size()); assertEquals(FormulaOne.KEY, rows.get(0).key);
        assertEquals(6, rows.get(0).items.size());
        for (String asset : new String[]{"driver-antonelli", "driver-leclerc", "driver-norris", "team-mercedes", "team-ferrari", "team-mclaren", "circuit-mexico"})
            assertNotNull("Real widget asset: " + asset, F1Assets.image(activity, asset));
        assertNotNull(F1Assets.circuit(activity, "Mexico City Grand Prix"));
        assertNotNull(F1Assets.circuit(activity, "São Paulo Grand Prix · Interlagos"));
        call(activity, "focusDock", new Class[]{int.class}, 0);
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN); settle(); assertEquals(6, field(activity, "zone"));
        View root = activity.findViewById(R.id.root); layout(root); save(root, "f1-widget-native.png");
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER); settle(); layout(root); assertEquals(5, field(activity, "zone"));
        assertTrue(((List<?>) field(activity, "menuItems")).size() >= 4);
        save(root, "f1-race-details-native.png");
        key(activity, KeyEvent.KEYCODE_BACK); settle(); assertEquals(6, field(activity, "zone"));
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT); settle(); assertEquals(1, ((int[]) field(activity, "chanCol"))[0]);
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN); settle(); assertEquals(3, ((int[]) field(activity, "chanCol"))[0]);
        assertEquals(6, field(activity, "zone"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN); settle(); assertEquals(4, field(activity, "zone"));
        key(activity, KeyEvent.KEYCODE_DPAD_UP); settle(); assertEquals(6, field(activity, "zone"));
        key(activity, KeyEvent.KEYCODE_DPAD_UP); settle(); assertEquals(1, ((int[]) field(activity, "chanCol"))[0]);
        key(activity, KeyEvent.KEYCODE_DPAD_UP); settle(); assertEquals(3, field(activity, "zone"));
    }

    @Test public void cricketUsesOneFixedRowAndIgnoresSavedFootballSetting() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        activity.getSharedPreferences("home", 0).edit()
                .putBoolean("sport_cricket", true).putBoolean("sport_football", true).apply();
        call(activity, "showChannels", new Class[]{List.class}, List.of());
        drain(activity);

        var rows = (List<Channels.Channel>) field(activity, "chanRows");
        var cards = (List<List<View>>) field(activity, "chanCards");
        int cricket = -1;
        for (int i = 0; i < rows.size(); i++) {
            String key = rows.get(i).key;
            assertNotEquals("football", key);
            if ("cricket".equals(key)) cricket = i;
        }
        assertTrue(cricket >= 0);
        assertEquals(1, cards.get(cricket).size());
        assertTrue(cards.get(cricket).get(0).getContentDescription().toString().contains("match data"));
        Field feed = HomeActivity.class.getDeclaredField("cricketFeed");
        feed.setAccessible(true);
        List<Cricket.Match> fixtures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Cricket.Match match = new Cricket.Match();
            match.id = "fixture-" + i;
            match.name = "Bangladesh vs South Africa";
            match.league = "Bangladesh tour of South Africa";
            match.phase = i == 1 ? "Stumps" : "";
            match.live = i == 0;
            match.paused = i == 1;
            fixtures.add(match);
        }
        feed.set(activity, fixtures);
        call(activity, "showChannels", new Class[]{List.class}, List.of());
        drain(activity);
        List<View> row = cards.get(cricket);
        assertEquals(3, row.size());
        assertNull(((List<?>) field(activity, "chanScrolls")).get(cricket));
        var first = (android.widget.FrameLayout.LayoutParams) row.get(0).getLayoutParams();
        var last = (android.widget.FrameLayout.LayoutParams) row.get(2).getLayoutParams();
        assertEquals(first.topMargin, last.topMargin);
        assertEquals(first.width, last.width);
        assertTrue(last.leftMargin > first.leftMargin);
        call(activity, "focusChan", new Class[]{int.class, int.class}, cricket, 0);
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(2, ((int[]) field(activity, "chanCol"))[cricket]);
    }

    @Test public void recommendationRowFollowsFormulaAndOpensDetailsWithBackToCard() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        activity.getSharedPreferences("home",0).edit().putBoolean("formula_one",true).putBoolean("recommendations",true).commit();
        Field formula=HomeActivity.class.getDeclaredField("formulaState");formula.setAccessible(true);formula.set(activity,FormulaOneTest.fixture(FormulaOneTest.NOW));
        File art=new File(activity.getExternalFilesDir(null),"tiles/"+PACKAGES[0]+".jpg");
        Trending.Pick film=RecommendationsTest.pick(1,PACKAGES[0],art);film.service="Apple TV";
        File poster=new File(activity.getCacheDir(),"portrait-fixture.png");Bitmap portrait=Bitmap.createBitmap(300,450,Bitmap.Config.ARGB_8888);portrait.eraseColor(Color.BLUE);
        try(FileOutputStream out=new FileOutputStream(poster)){assertTrue(portrait.compress(Bitmap.CompressFormat.PNG,100,out));}portrait.recycle();film.poster=poster;film.lang="en";
        Trending.Pick fresh=RecommendationsTest.pick(2,PACKAGES[1],art);fresh.service="Netflix";fresh.poster=poster;fresh.lang="en";
        fresh.releaseDate=java.time.LocalDate.now().minusDays(2).toString();fresh.tab=Trending.RELEASES;
        Field feed=HomeActivity.class.getDeclaredField("recommendationCatalog");feed.setAccessible(true);feed.set(activity,java.util.Collections.singletonList(fresh));
        Field catalog=HomeActivity.class.getDeclaredField("trendPicks");catalog.setAccessible(true);catalog.set(activity,java.util.Collections.singletonList(film));
        call(activity,"showChannels",new Class[]{List.class},java.util.Collections.emptyList());drain(activity);
        List<Channels.Channel> rows=(List<Channels.Channel>)field(activity,"chanRows");
        assertEquals(3,rows.size());assertEquals(FormulaOne.KEY,rows.get(0).key);assertEquals(Recommendations.KEY,rows.get(1).key);assertEquals(Recommendations.NEW_KEY,rows.get(2).key);
        View root=activity.findViewById(R.id.root);layout(root);drain(activity);
        List<List<View>> cards=(List<List<View>>)field(activity,"chanCards");View tall=cards.get(1).get(0);
        assertTrue(tall.getHeight()>tall.getWidth());android.widget.ImageView image=(android.widget.ImageView)((ViewGroup)tall).getChildAt(0);
        assertNull("The off-screen row does not decode ahead of the visible cards",image.getDrawable());
        call(activity,"focusDock",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(0,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(1,field(activity,"chanIdx"));
        layout(root);
        CardArtwork artwork=(CardArtwork)field(activity,"cardArtwork");artwork.refreshVisible();
        ((ExecutorService)field(artwork,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);settle();
        assertNotNull(image.getDrawable());
        assertTrue(image.getDrawable().getIntrinsicHeight()>image.getDrawable().getIntrinsicWidth());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        TitleDetailsDialog dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        assertTrue(dialog.isShowing());
        dialog.onBackPressed();settle();assertEquals(6,field(activity,"zone"));assertEquals(1,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(2,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        dialog = (TitleDetailsDialog) field(activity, "titleDialog");
        assertTrue(dialog.isShowing());
        dialog.onBackPressed();settle();assertEquals(2,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(4,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(2,field(activity,"chanIdx"));
        call(activity,"openHomePageWidgets",new Class[]{});settle();
        List<?> menu=(List<?>)field(activity,"menuItems");boolean found=false;
        for(int i=0;i<menu.size();i++) {
            android.widget.TextView label=((View)menu.get(i)).findViewById(R.id.input_label);
            if(label!=null&&"Our Recommendations".contentEquals(label.getText())) {
                ((Runnable)((List<?>)field(activity,"menuActions")).get(i)).run();found=true;break;
            }
        }
        assertTrue(found);assertFalse(activity.getSharedPreferences("home",0).getBoolean("recommendations",true));drain(activity);
        assertEquals(2,((List<?>)field(activity,"chanRows")).size());
    }

    @Test public void catalogMetadataSurvivesSaveAndLegacyCacheStillLoads() throws Exception {
        Application app=RuntimeEnvironment.getApplication();File root=app.getExternalFilesDir(null);
        try(FileOutputStream key=new FileOutputStream(new File(root,"tmdb_key.txt"))){key.write("test-only-not-a-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        File dir=new File(root,"trending");assertTrue(dir.mkdirs()||dir.isDirectory());
        File art=new File(dir,"fixture.jpg");java.nio.file.Files.copy(new File(root,"tiles/"+PACKAGES[0]+".jpg").toPath(),art.toPath());
        Trending.Pick film=RecommendationsTest.pick(1,PACKAGES[0],art);Trending catalog=new Trending(app);
        film.lang="en";film.releaseDate="2026-10-31";film.weeklyTrending=true;
        call(catalog,"save",new Class[]{List.class},java.util.Collections.singletonList(film));
        Trending.Pick saved=catalog.cached().get(0);assertEquals(film.overview,saved.overview);assertEquals(film.year,saved.year);assertEquals(film.rating,saved.rating,0);
        assertEquals(film.releaseDate,saved.releaseDate);assertTrue(saved.weeklyTrending);
        File cache=new File(dir,"picks.json");org.json.JSONArray old=new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        for(String name:new String[]{"overview","year","rating","release_date","weekly_trending"})old.getJSONObject(0).remove(name);
        java.nio.file.Files.write(cache.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Trending.Pick legacy=catalog.cached().get(0);assertEquals("",legacy.overview);assertEquals("",legacy.year);assertEquals(0,legacy.rating,0);
        assertEquals("",legacy.releaseDate);assertFalse(legacy.weeklyTrending);
    }

    @Test public void unchangedSolarPaletteDoesNotRestyleTheWholeHomeOnResume() throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("home",0).edit().putBoolean(TimeOfDay.PREF,true).commit();
        org.robolectric.android.controller.ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class);HomeActivity activity=controller.get();Field source=HomeActivity.class.getDeclaredField("timeOfDay");source.setAccessible(true);source.set(activity,new TimeOfDay(java.time.Clock.fixed(java.time.Instant.parse("2026-11-10T08:30:00Z"),java.time.ZoneOffset.UTC)));
        controller.setup();drain(activity);call(activity,"refreshHomeTheme",new Class[]{});Object theme=field(activity,"theme");Object band=((View)field(activity,"dockBand")).getBackground();
        call(activity,"onResume",new Class[]{});settle();assertSame(theme,field(activity,"theme"));assertSame(band,((View)field(activity,"dockBand")).getBackground());
        Field cached=HomeActivity.class.getDeclaredField("cachedSky");cached.setAccessible(true);cached.set(activity,null);call(activity,"refreshHomeTheme",new Class[]{});assertNotSame(theme,field(activity,"theme"));
        controller.pause().stop().destroy();
    }

    @Test public void sunlightUpdatesThroughTheDayAndAppColoursIgnoresLegacyLightMode() throws Exception {
        java.util.TimeZone previous=java.util.TimeZone.getDefault();java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        try {
            Application app=RuntimeEnvironment.getApplication();
            app.getSharedPreferences("home",0).edit().putInt("appearance",1).putBoolean(TimeOfDay.PREF,true).commit();
            long midnight=java.time.Instant.parse("2026-11-02T00:00:00Z").toEpochMilli();
            java.util.concurrent.atomic.AtomicReference<java.time.Instant> now=new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.ofEpochMilli(midnight));
            java.time.Clock clock=new java.time.Clock() {
                @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
                @Override public java.time.Clock withZone(java.time.ZoneId zone){return this;}
                @Override public java.time.Instant instant(){return now.get();}
            };
            org.robolectric.android.controller.ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class);
            HomeActivity activity=controller.get();Field source=HomeActivity.class.getDeclaredField("timeOfDay");source.setAccessible(true);source.set(activity,new TimeOfDay(clock));
            controller.setup();drain(activity);
            assertEquals(false,field(activity,"day"));int nightMenu=(Integer)field(field(activity,"theme"),"menuBg");
            call(activity,"openAppearance",new Class[]{});settle();
            now.set(java.time.Instant.ofEpochMilli(midnight+12*3600_000L));
            call(activity,"onMinute",new Class[]{});settle();
            TimeOfDay.Palette noon=TimeOfDay.palette(720);assertEquals(false,field(activity,"day"));
            Object theme=field(activity,"theme");assertEquals(noon.accent,field(theme,"iconFocusBg"));
            assertEquals(noon.accent,field(theme,"menuFocusBg"));assertNotEquals(nightMenu,field(theme,"menuBg"));
            for(String surface:new String[]{"menuBg","band","iconBg","tileBg"})assertTrue(TimeOfDay.luminance((Integer)field(theme,surface))<.06);
            assertEquals(noon.text,((android.widget.TextView)activity.findViewById(R.id.big_time)).getCurrentTextColor());
            HighlightView ring=(HighlightView)field(activity,"ring");assertEquals(noon.accent,((Paint)field(ring,"ring")).getColor());
            call(activity, "openSweepSettings", new Class[]{int.class}, 0);
            assertEquals("Appearance", field(activity, "settingsHeading"));
            List<?> menu;
            now.set(java.time.Instant.ofEpochMilli(midnight + 21 * 3600_000L));
            call(activity, "onResume", new Class[]{});
            settle();
            assertEquals(TimeOfDay.palette(1260).accent, field(field(activity, "theme"), "menuFocusBg"));

            call(activity, "select", new Class[]{});
            assertEquals("Appearance", field(activity, "settingsHeading"));
            call(activity, "focusMenu", new Class[]{int.class}, HomeTheme.FOREST.ordinal());
            android.content.SharedPreferences prefs = app.getSharedPreferences("home", 0);
            assertEquals(HomeTheme.SUNLIGHT, HomeTheme.selected(prefs));
            int[] preview = (int[]) field(field(activity, "backdrop"), "previewColours");
            assertEquals(HomeTheme.FOREST.palette(Math.floorDiv(now.get().toEpochMilli(), HomeTheme.HOUR_MS)).mid, preview[1]);
            key(activity, KeyEvent.KEYCODE_BACK);
            assertNull(field(field(activity, "backdrop"), "previewColours"));
            assertEquals(HomeTheme.SUNLIGHT, HomeTheme.selected(prefs));
            key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            selectSetting(activity, "Forest");
            assertEquals(HomeTheme.FOREST, HomeTheme.selected(prefs));
            assertEquals(List.of("Forest", "Sunlight", "App colours", "Midnight", "Ember", "Graphite", "Black glow"),
                    menuLabels(activity).subList(0, HomeTheme.values().length));
            assertEquals(0, field(activity, "menuIdx"));
            assertEquals(HomeTheme.FOREST.preview.accent, ((Paint) field(ring, "ring")).getColor());
            call(activity, "onMinute", new Class[]{});
            assertEquals(HomeTheme.FOREST.preview.accent, ((Paint) field(ring, "ring")).getColor());
            call(activity, "back", new Class[]{});
            assertEquals(true, field(activity, "settingsSidebar"));
            assertEquals(HomeTheme.FOREST, HomeTheme.selected(prefs));

            call(activity, "select", new Class[]{});
            prefs.edit().putBoolean("app_glow", false).apply();
            selectSetting(activity, "App colours");
            assertEquals(HomeTheme.APP_COLOURS, HomeTheme.selected(prefs));
            assertFalse(prefs.getBoolean(TimeOfDay.PREF, true));
            assertEquals(false, field(activity, "day"));
            assertEquals(1, prefs.getInt("appearance", -1));
            assertEquals(0xFFFFFFFF, ((Paint) field(ring, "ring")).getColor());
            call(activity, "back", new Class[]{});
            call(activity, "openAppearance", new Class[]{});
            menu = (List<?>) field(activity, "menuItems");
            assertEquals(0, field(activity, "menuIdx"));
            assertEquals("App colours", menuLabels(activity).get(0));
            assertNull(field(field(activity, "backdrop"), "previewColours"));
            View appColours = (View) menu.get(0);
            assertEquals(View.VISIBLE, appColours.findViewWithTag("selection-tick").getVisibility());
            assertFalse(menuLabels(activity).contains("App colours mode"));
            assertFalse(menuLabels(activity).contains("Background glow"));
            call(activity, "updateAmbient", new Class[]{});
            Object backdrop = field(activity, "backdrop");
            int appGlow = (Integer) field(backdrop, "glow");
            assertNotEquals(0, appGlow);
            selectSetting(activity, "Black glow");
            call(activity, "updateAmbient", new Class[]{});
            assertEquals(HomeTheme.BLACK_GLOW, HomeTheme.selected(prefs));
            assertEquals("Black glow", menuLabels(activity).get(0));
            assertEquals(0xFF000000, field(backdrop, "bgBottom"));
            assertEquals(appGlow, field(backdrop, "glow"));
            assertEquals(true, field(backdrop, "subduedGlow"));
            assertFalse(menuLabels(activity).contains("Scan QR to customize"));
            assertFalse(menuLabels(activity).contains("App launch animation"));
            assertEquals(4, ((ViewGroup) appColours.getParent()).getChildCount());
            assertFalse(menuLabels(activity).contains("Choose from TV"));
            selectSetting(activity, "Show custom wallpaper");
            assertEquals("Custom wallpaper", field(activity, "settingsHeading"));
            assertTrue(menuLabels(activity).containsAll(List.of("Choose from TV", "Picture of the day")));
            selectSetting(activity, "Show custom wallpaper");
            assertFalse(menuLabels(activity).contains("Picture of the day"));

        } finally {java.util.TimeZone.setDefault(previous);}
    }

    @Test public void hourlyThemeRotationIncludesWallpaperAndRestoresManualChoice() throws Exception {
        var controller = Robolectric.buildActivity(HomeActivity.class);
        HomeActivity activity = controller.get();
        long started = 1_800_000_000_000L;
        var now = new java.util.concurrent.atomic.AtomicLong(started);
        java.time.Clock clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now.get()); }
        };
        Field time = HomeActivity.class.getDeclaredField("timeOfDay");
        time.setAccessible(true);
        time.set(activity, new TimeOfDay(clock));
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("home", 0);
        HomeTheme.FOREST.save(prefs);
        prefs.edit().putBoolean("wallpaper_paused", true).putBoolean("wallpaper_picture_of_day", false).apply();
        controller.setup();
        drain(activity);
        Bitmap picture = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
        picture.eraseColor(Color.YELLOW);
        try (FileOutputStream out = new FileOutputStream(new File(activity.getExternalFilesDir(null), "wallpaper.png"))) {
            assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            picture.recycle();
        }
        try {
            int forest = ((TimeOfDay.Palette) field(activity, "appliedPalette")).mid;
            now.addAndGet(HomeTheme.HOUR_MS);
            call(activity, "refreshHomeTheme", new Class[]{});
            assertEquals(HomeTheme.FOREST, field(activity, "activeTheme"));
            assertNotEquals(forest, ((TimeOfDay.Palette) field(activity, "appliedPalette")).mid);
            now.set(started);

            call(activity, "openAppearance", new Class[]{});
            assertFalse(prefs.contains(HomeTheme.RANDOMISE_SINCE));
            selectSetting(activity, "Randomise Themes");
            assertEquals(started, prefs.getLong(HomeTheme.RANDOMISE_SINCE, -1));
            HomeTheme.Rotation rotation = new HomeTheme.Rotation();
            Set<HomeTheme> seen = new HashSet<>();
            for (int hour = 0; hour <= HomeTheme.values().length; hour++) {
                now.set(started + hour * HomeTheme.HOUR_MS);
                HomeTheme expected = rotation.at(started, now.get());
                assertTrue(seen.add(expected));
                assertEquals(expected, new HomeTheme.Rotation().at(started, now.get() + 59 * 60_000L));
                call(activity, "refreshHomeTheme", new Class[]{});
                ((ExecutorService) field(activity, "artworkColours")).submit(() -> { }).get(10, TimeUnit.SECONDS);
                settle();
                assertEquals(expected == null, field(activity, "hasWallpaper"));
                if (expected != null) assertEquals(expected, field(activity, "activeTheme"));
                assertEquals(HomeTheme.FOREST, HomeTheme.selected(prefs));
            }
            selectSetting(activity, "Randomise Themes");
            assertFalse(prefs.contains(HomeTheme.RANDOMISE_SINCE));
            assertEquals(HomeTheme.FOREST, field(activity, "activeTheme"));
            assertEquals(false, field(activity, "hasWallpaper"));
            assertTrue(prefs.getBoolean("wallpaper_paused", false));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void settingsHubHasOwnBrandAndLongPressCanHideAndRestoreAnAppSection() throws Exception {
        SectionOptions.set(RuntimeEnvironment.getApplication(), Recommendations.EXPLORE, false);
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        call(activity,"openSweepSettings",new Class[]{int.class},0);
        assertEquals("Appearance", field(activity, "settingsHeading"));
        assertEquals(true, field(activity, "settingsSidebar"));
        key(activity, KeyEvent.KEYCODE_BACK);
        Channels.Channel section=new Channels.Channel();section.key="example.app|Featured";section.title="Featured";
        Trending.Pick pick=new Trending.Pick();pick.id=100;pick.title="Film";pick.lang="en";pick.pkg=PACKAGES[0];pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+pick.pkg+".jpg");section.items.add(pick);
        List<Channels.Channel> rows=new java.util.ArrayList<>();rows.add(section);call(activity,"showChannels",new Class[]{List.class},rows);drain(activity);call(activity,"focusChan",new Class[]{int.class,int.class},0,0);
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("Remove section from Home",((android.widget.TextView)((View)((List<?>)field(activity,"menuItems")).get(0)).findViewById(R.id.input_label)).getText().toString());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);drain(activity);assertTrue(SectionOptions.hidden(activity,section.key));assertEquals(0,((List<?>)field(activity,"chanRows")).size());assertEquals(1,section.items.size());
        drain(activity);SectionOptions.set(activity,section.key,true);call(activity,"showChannels",new Class[]{List.class},rows);drain(activity);assertEquals(1,((List<?>)field(activity,"chanRows")).size());
    }

    @Test public void appleResumeEntriesLoadAtColdStartBeforeTheProviderPublishes() throws Exception {
        Application app=RuntimeEnvironment.getApplication();Trending.Pick pick=new Trending.Pick();pick.pkg=PACKAGES[0];pick.id=42;pick.title="Apple TV episode";pick.meta="Season 1 · Episode 2 · Apple TV";pick.service="Apple TV";pick.intentUri="apple-tv://resume/42";pick.at=System.currentTimeMillis();
        AppleResumeCache.reconcile(app,java.util.Arrays.asList(pick),true,System.currentTimeMillis());
        app.getSharedPreferences("channels", 0).edit().putString("shown", "watchnext").putString("order", "watchnext").apply();
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        List<Trending.Pick> recent=(List<Trending.Pick>)field(activity,"recentPicks");assertEquals(1,recent.size());assertEquals("apple-tv://resume/42",recent.get(0).intentUri);assertTrue(recent.get(0).savedResume);
        List<Channels.Channel> homeRows = (List<Channels.Channel>) field(activity, "chanRows");
        assertFalse(homeRows.stream().anyMatch(row -> "watchnext".equals(row.key)));
    }

    @Test public void firstLaunchSkipsPermissionsAndFinishSetupCanRequestTvListings() throws Exception {
        String watchNext = "android.permission.READ_TV_LISTINGS";
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION, watchNext);
        var controller = Robolectric.buildActivity(HomeActivity.class).setup();
        try {
            HomeActivity activity = controller.get();
            drain(activity);
            var shadow = Shadows.shadowOf(activity);
            assertNull(shadow.getLastRequestedPermission());
            call(activity, "openFinishSetup", new Class[]{});
            selectSetting(activity, "Read TV listings");
            assertArrayEquals(new String[]{watchNext}, shadow.getLastRequestedPermission().requestedPermissions);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void finishSetupLetsUsersOptIntoEachSport() throws Exception {
        var prefs = HomeOptions.prefs(RuntimeEnvironment.getApplication());
        prefs.edit().remove(FormulaOne.KEY).remove("scores").apply();
        var controller = Robolectric.buildActivity(HomeActivity.class).setup();
        try {
            HomeActivity activity = controller.get();
            drain(activity);
            call(activity, "openSweepSettings", new Class[]{int.class}, 0);
            settle();
            enterCategory(activity, 6);
            settle();
            selectSetting(activity, "Finish setup");
            List<String> labels = menuLabels(activity);
            List<?> rows = (List<?>) field(activity, "menuItems");
            View formula = (View) rows.get(labels.indexOf("Formula 1"));
            View cricket = (View) rows.get(labels.indexOf("Cricket"));
            assertEquals("Formula 1, Off", formula.getContentDescription());
            assertEquals("Cricket, Off", cricket.getContentDescription());

            selectSetting(activity, "Formula 1");
            assertTrue(prefs.getBoolean(FormulaOne.KEY, false));
            assertEquals("Cricket, Off", cricket.getContentDescription());
            selectSetting(activity, "Cricket");
            assertTrue(prefs.getBoolean("sport_cricket", false));
            selectSetting(activity, "Formula 1");

            key(activity, KeyEvent.KEYCODE_BACK);
            selectSetting(activity, "Finish setup");
            rows = (List<?>) field(activity, "menuItems");
            assertEquals("Formula 1, Off", ((View) rows.get(labels.indexOf("Formula 1"))).getContentDescription());
            assertEquals("Cricket, On", ((View) rows.get(labels.indexOf("Cricket"))).getContentDescription());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void contentShowsTvListingsUntilAndroidGrantsIt() throws Exception {
        String permission = "android.permission.READ_TV_LISTINGS";
        var application = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        application.denyPermissions(permission);
        var controller = Robolectric.buildActivity(HomeActivity.class).setup();
        try {
            HomeActivity activity = controller.get();
            drain(activity);
            call(activity, "openContinueSettings", new Class[]{});
            assertTrue(menuLabels(activity).contains("Read TV listings"));
            selectSetting(activity, "Read TV listings");
            var request = Shadows.shadowOf(activity).getLastRequestedPermission();
            assertArrayEquals(new String[]{permission}, request.requestedPermissions);

            activity.onRequestPermissionsResult(request.requestCode, new String[]{permission},
                    new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
            var help = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(help);
            assertEquals("TV listings access is off", Shadows.shadowOf(help).getTitle());
            help.dismiss();
            assertTrue(menuLabels(activity).contains("Read TV listings"));

            application.grantPermissions(permission);
            activity.onRequestPermissionsResult(request.requestCode, new String[]{permission},
                    new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
            assertFalse(menuLabels(activity).contains("Read TV listings"));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void keylessHomeShowsAppsAndRevealsContentAsItBecomesAvailable() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("home", 0).edit().remove("for_you").apply();
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        assertNull(((Trending) field(activity, "trending")).key());
        assertEquals(View.GONE, activity.findViewById(R.id.for_you_head).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.picks).getVisibility());
        assertTrue(((ViewGroup) activity.findViewById(R.id.dock)).getChildCount() > 0);

        Channels.Channel published = new Channels.Channel();
        published.key = PACKAGES[0] + "|Featured";
        published.title = "Apple TV · Featured";
        Trending.Pick suggestion = new Trending.Pick();
        suggestion.id = 70;
        suggestion.pkg = PACKAGES[0];
        suggestion.title = "App recommendation";
        published.items.add(suggestion);
        call(activity, "showChannels", new Class[]{List.class}, List.of(published));
        drain(activity);
        List<Channels.Channel> homeRows = (List<Channels.Channel>) field(activity, "chanRows");
        assertTrue(homeRows.stream().anyMatch(row -> published.key.equals(row.key) && row.items.contains(suggestion)));
        ((Trending) field(activity, "trending")).setLanguages(java.util.Set.of("en"));
        call(activity, "showChannels", new Class[]{List.class}, List.of(published));
        drain(activity);
        assertFalse(((List<Channels.Channel>) field(activity, "chanRows")).stream()
                .anyMatch(row -> published.key.equals(row.key)));

        Trending.Pick resume = new Trending.Pick();
        resume.id = 71;
        resume.pkg = PACKAGES[0];
        resume.title = "Fixture episode";
        resume.kind = "watchnext";
        resume.tab = RecentStore.TAB;
        ((List<Trending.Pick>) field(activity, "recentPicks")).add(resume);
        call(activity, "openContentSettings", new Class[]{});
        List<String> labels = menuLabels(activity);
        assertEquals(List.of("Select Content Languages", "Recommendation Engine"), labels.subList(0, 2));
        assertFalse(labels.contains("Continue Watching"));
        call(activity, "openHomePageWidgets", new Class[]{});
        labels = menuLabels(activity);
        assertEquals(List.of("Header widgets", "Continue Watching", "Show Trending", "Show Recently Released"),
                labels.subList(0, 4));
        assertTrue(labels.containsAll(List.of("Sports", "Our Recommendations", "App sections")));
        int discovery = labels.indexOf("Show Trending");
        View switchRow = (View) ((List<?>) field(activity, "menuItems")).get(discovery);
        assertEquals("Show Trending, On", switchRow.getContentDescription());
        selectSetting(activity, "Show Trending");
        assertEquals("Show Trending, Off", switchRow.getContentDescription());
        selectSetting(activity, "Show Trending");
        assertEquals(View.VISIBLE, activity.findViewById(R.id.tab_recent).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.tab_trending).getVisibility());
        assertEquals(0, field(activity, "tabIdx"));
        selectSetting(activity, "Continue Watching");
        selectSetting(activity, "Show Continue Watching");
        assertEquals(View.GONE, activity.findViewById(R.id.for_you_head).getVisibility());
        assertTrue(((java.util.Map<?, ?>) field(activity, "continueAppRows")).isEmpty());
    }

    @Test public void appleResumeFallbackSurvivesExcludingTheApp() {
        Application app = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        Trending.Pick pick = new Trending.Pick();
        pick.pkg = AppleResumeCache.PACKAGE;
        pick.title = "Fixture episode";
        pick.intentUri = "apple-tv://resume/71";
        pick.progress = .45f;
        AppleResumeCache.reconcile(app, List.of(pick), true, now);
        ContinueOptions.setIncluded(app, pick.pkg, false);
        assertTrue(AppleResumeCache.reconcile(app, List.of(), true, now).isEmpty());
        ContinueOptions.setIncluded(app, pick.pkg, true);
        List<Trending.Pick> restored = AppleResumeCache.reconcile(app, List.of(), false, now);
        assertEquals(1, restored.size());
        assertEquals(pick.intentUri, restored.get(0).intentUri);
        assertEquals(pick.progress, restored.get(0).progress, .001f);
    }

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }
    private Channels.Channel simpleRow(HomeActivity activity,String key,int id,String title) {
        Channels.Channel row=new Channels.Channel();row.key=key;row.title=title;
        Trending.Pick pick=new Trending.Pick();pick.id=id;pick.title=title;pick.pkg=PACKAGES[0];pick.lang="en";
        pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+PACKAGES[0]+".jpg");row.items.add(pick);return row;
    }

    @Test public void refreshedRowsRetainUnchangedViewsAndReplaceChangedArtworkOrProgress() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        activity.getSharedPreferences("home",0).edit().putBoolean("recommendations",false).putBoolean("new_releases",false).commit();
        Channels.Channel first=simpleRow(activity,"first",1,"First"),second=simpleRow(activity,"second",2,"Second");
        call(activity,"showChannels",new Class[]{List.class},java.util.Arrays.asList(first,second));drain(activity);
        ViewGroup box=(ViewGroup)field(activity,"channelsBox");View firstView=box.getChildAt(0),secondView=box.getChildAt(1);
        Channels.Channel update=simpleRow(activity,"second",2,"Second");update.items.get(0).progress=.6f;
        call(activity,"showChannels",new Class[]{List.class},java.util.Arrays.asList(first,update));drain(activity);
        assertSame(firstView,box.getChildAt(0));assertNotSame(secondView,box.getChildAt(1));
        View updated=box.getChildAt(1);
        call(activity,"showChannels",new Class[]{List.class},java.util.Arrays.asList(first,update));drain(activity);
        assertSame(firstView,box.getChildAt(0));assertSame(updated,box.getChildAt(1));
    }

    @Test public void slowRowPreparationDoesNotBlockArrowsAndStoppedHomeDefersItsResult() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        activity.getSharedPreferences("home",0).edit().putBoolean("recommendations",false).putBoolean("new_releases",false).commit();
        Channels.Channel first=simpleRow(activity,"first",1,"First");call(activity,"showChannels",new Class[]{List.class},java.util.Arrays.asList(first));drain(activity);
        java.util.concurrent.CountDownLatch running=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        ExecutorService worker=(ExecutorService)field(activity,"channelWorker");worker.execute(()->{running.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(running.await(2,TimeUnit.SECONDS));
        try {
            Channels.Channel newer=simpleRow(activity,"second",2,"Second");call(activity,"showChannels",new Class[]{List.class},java.util.Arrays.asList(newer));
            call(activity,"focusDock",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(1,field(activity,"dockIdx"));assertEquals("first",((List<Channels.Channel>)field(activity,"chanRows")).get(0).key);
            Field front=HomeActivity.class.getDeclaredField("inFront");front.setAccessible(true);front.setBoolean(activity,false);
            release.countDown();drain(activity);assertEquals("first",((List<Channels.Channel>)field(activity,"chanRows")).get(0).key);
            front.setBoolean(activity,true);((Runnable)field(activity,"applyPreparedChannels")).run();settle();
            assertEquals("second",((List<Channels.Channel>)field(activity,"chanRows")).get(0).key);
        } finally {release.countDown();}
    }

    @Test public void f1ClassificationAndPictureChangesInvalidateOnlyTheirRowSignature() {
        Channels.Channel row=new Channels.Channel();row.key=FormulaOne.KEY;row.title="F1";Trending.Pick pick=new Trending.Pick();pick.id=1;
        FormulaOne.Card card=new FormulaOne.Card();card.key="latest";FormulaOne.Line leader=new FormulaOne.Line("1","Driver","25","Team",0xFFFF0000);card.table.add(leader);pick.extra=card;row.items.add(pick);
        String before=ChannelSnapshot.signature(row);leader.value="26";assertNotEquals(before,ChannelSnapshot.signature(row));
        before=ChannelSnapshot.signature(row);leader.portrait="driver-new";assertNotEquals(before,ChannelSnapshot.signature(row));
    }

    @Test public void focusedTitleRequestsTheArtworkAttachedToItsCard() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        File art = new File(activity.getCacheDir(), "glow.png");
        Bitmap poster = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888);
        poster.eraseColor(0xFFFF8811);
        try (FileOutputStream out = new FileOutputStream(art)) {
            assertTrue(poster.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        poster.recycle();
        Trending.Pick pick = new Trending.Pick();
        pick.title = "Amber title";
        pick.poster = art;
        pick.pkg = PACKAGES[0];
        View item = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, pick);
        PicksRow row = (PicksRow) field(activity, "picksRow");
        row.setItems(new java.util.ArrayList<>(List.of(item)), 0);
        Field rowPicks = HomeActivity.class.getDeclaredField("rowPicks");
        rowPicks.setAccessible(true);
        rowPicks.set(activity, new java.util.ArrayList<>(List.of(pick)));
        call(activity, "focusPicks", new Class[]{int.class}, 0);
        assertEquals(art, item.getTag(R.id.ambient_artwork));
        assertTrue(((String) field(activity, "requestedArtworkColour")).startsWith(art.getPath()));
    }

    @Test public void dominantColourComesFromTitleArtwork() {
        int[] pixels = new int[100];
        java.util.Arrays.fill(pixels, 0, 65, 0xFF38B984);
        java.util.Arrays.fill(pixels, 65, 85, 0xFFCC4411);
        int[] palette = ArtworkColour.palette(pixels);
        assertArrayEquals(new int[]{0xFF38B984, 0xFFCC4411}, palette);
        assertEquals(0xFF38B984, ArtworkColour.at(palette, 0, 0));
        assertEquals(0xFFCC4411, ArtworkColour.at(palette, 0, 1));
        assertEquals(0xFF38B984, ArtworkColour.at(palette, 0, 2));
        java.util.Arrays.fill(pixels, 0, 65, 0xFFB92A80);
        assertEquals(0xFFB92A80, ArtworkColour.palette(pixels)[0]);
        assertEquals(0, ArtworkColour.palette(new int[10]).length);
        assertNotEquals(ArtworkColour.at(null, 0xFF38B984, 0), ArtworkColour.at(null, 0xFF38B984, 1));
    }

    @Test public void quickSettingsOpensHomeAppAndStartsTheSupportedScreenSaver() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        Intent voice = new Intent("com.google.android.pano.action.SLEEP");
        ComponentName target = new ComponentName("com.android.tv.settings", "com.android.tv.settings.DaydreamVoiceAction");
        ShadowPackageManager packages = Shadows.shadowOf(activity.getPackageManager());
        android.content.pm.ActivityInfo info = new android.content.pm.ActivityInfo();
        info.packageName = target.getPackageName();
        info.name = target.getClassName();
        info.enabled = true;
        info.exported = true;
        packages.addOrUpdateActivity(info);
        IntentFilter startScreenSaver = new IntentFilter(voice.getAction());
        startScreenSaver.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(target, startScreenSaver);

        call(activity, "openQuickSettings", new Class[]{int.class}, 3);
        selectSetting(activity, "Home app");
        assertEquals("Home app", field(activity, "settingsHeading"));
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("Settings", field(activity, "settingsHeading"));
        selectSetting(activity, "Start screen saver");
        assertEquals(voice.getAction(), Shadows.shadowOf(activity).getNextStartedActivity().getAction());
    }

    @Test public void settingsSidebarShowsPageAndReturnsFromItsControls() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        call(activity, "focusTop", new Class[]{int.class}, 3);
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(menuLabels(activity).contains("Sound output"));
        selectSetting(activity, "Sweep TV settings");
        assertEquals("Appearance", field(activity, "settingsHeading"));
        assertEquals(true, field(activity, "settingsSidebar"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("Dock", field(activity, "settingsHeading"));
        layout(activity.findViewById(R.id.root));
        save(activity.findViewById(R.id.root), "sweep-split-settings.png");
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(false, field(activity, "settingsSidebar"));
        assertEquals(List.of("Dock size", "Icon shape", "Background", "Theme", "Show All Apps button", "Edit Dock apps"),
                menuLabels(activity).subList(0, 6));
        assertNull(field(activity, "allTile"));
        selectSetting(activity, "Show All Apps button");
        assertEquals("All apps", ((View) field(activity, "allTile")).getContentDescription());
        selectSetting(activity, "Show All Apps button");
        assertNull(field(activity, "allTile"));
        assertEquals("7 apps", menuValue(activity, 0));
        selectSetting(activity, "Dock size");
        assertEquals("8 apps", menuValue(activity, 0));
        assertFalse(menuLabels(activity).contains("Hidden apps"));
        assertFalse(menuLabels(activity).contains("All apps order"));
        assertFalse(menuLabels(activity).contains("Show other apps in drawer"));
        AppEntry app = (AppEntry) ((List<?>) field(activity, "apps")).get(0);
        ViewGroup appChoice = (ViewGroup) call(activity, "appChoiceCard",
                new Class[]{AppEntry.class, String.class}, app, app.label);
        assertNull(((android.widget.ImageView) appChoice.getChildAt(0)).getImageTintList());
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals(true, field(activity, "settingsSidebar"));
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("Settings", field(activity, "settingsHeading"));
        call(activity, "openRemoteKeys", new Class[]{});
        assertTrue(menuLabels(activity).contains("Double-press Home for app drawer"));
        call(activity, "openSystemSettings", new Class[]{});
        assertFalse(menuLabels(activity).contains("Double-press Home for app drawer"));
    }

    @Test public void automaticDockPrioritizesInstalledEntertainmentThenPlayStore() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = Shadows.shadowOf(app.getPackageManager());
        String playStore = "com.android.vending";
        String extraVideo = "example.extra.video";
        for (String pkg : List.of(playStore, extraVideo)) {
            ActivityInfo entry = new ActivityInfo();
            entry.packageName = pkg;
            entry.name = pkg + ".TvActivity";
            entry.enabled = entry.exported = true;
            entry.applicationInfo = new ApplicationInfo(app.getApplicationInfo());
            entry.applicationInfo.packageName = pkg;
            entry.applicationInfo.category = pkg.equals(extraVideo)
                    ? ApplicationInfo.CATEGORY_VIDEO : ApplicationInfo.CATEGORY_UNDEFINED;
            PackageInfo installed = new PackageInfo();
            installed.packageName = pkg;
            installed.applicationInfo = entry.applicationInfo;
            pm.installPackage(installed);
            pm.addOrUpdateActivity(entry);
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(new ComponentName(pkg, entry.name), filter);
        }
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        assertEquals(7, field(activity, "dockCount"));
        assertFalse((boolean) call(activity, "inDock", new Class[]{String.class}, playStore));
        assertTrue((boolean) call(activity, "inDock", new Class[]{String.class}, PACKAGES[6]));

        app.getSharedPreferences("home", 0).edit().putString("hidden_apps", String.join(",",
                PACKAGES[2], PACKAGES[4], PACKAGES[5], PACKAGES[6], PACKAGES[7])).apply();
        call(activity, "loadApps", new Class[]{});
        List<?> apps = (List<?>) field(activity, "apps");
        assertEquals(7, field(activity, "dockCount"));
        assertEquals(extraVideo, ((AppEntry) apps.get(3)).pkg);
        assertEquals(playStore, ((AppEntry) apps.get(4)).pkg);
        assertTrue(((AppEntry) apps.get(5)).pkg.startsWith("example."));
    }

    @Test public void dockEditorGrowsTheDockAndExplainsWhenItIsFull() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        var prefs = app.getSharedPreferences("home", 0);
        var startingDock = new java.util.ArrayList<>(List.of(PACKAGES).subList(0, 6));
        startingDock.add(PACKAGES[8]);
        prefs.edit().putBoolean("dock_custom", true).putInt("dock_size", 7)
                .putString("order_dock", String.join(",", startingDock)).apply();
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        List<?> initialTiles = (List<?>) field(activity, "dockTiles");
        int oldWidth = ((View) initialTiles.get(0)).getLayoutParams().width;
        call(activity, "openDockApps", new Class[]{});

        selectSetting(activity, "Sony LIV");
        assertEquals(8, field(activity, "dockCount"));
        assertEquals(8, prefs.getInt("dock_size", 0));
        assertEquals(String.join(",", startingDock) + "," + PACKAGES[6], prefs.getString("order_dock", ""));
        List<?> resizedTiles = (List<?>) field(activity, "dockTiles");
        assertTrue(((View) resizedTiles.get(0)).getLayoutParams().width < oldWidth);

        selectSetting(activity, "FanCode");
        assertEquals(8, field(activity, "dockCount"));
        assertEquals("Dock is full. Remove an app to add another.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        call(activity, "loadApps", new Class[]{});
        assertEquals(8, field(activity, "dockCount"));
        assertTrue((boolean) call(activity, "inDock", new Class[]{String.class}, PACKAGES[6]));
        assertFalse((boolean) call(activity, "inDock", new Class[]{String.class}, PACKAGES[7]));

        selectSetting(activity, "Sony LIV");
        assertEquals(7, field(activity, "dockCount"));
        assertEquals(7, prefs.getInt("dock_size", 0));
        assertEquals(String.join(",", startingDock), prefs.getString("order_dock", ""));
        List<?> restoredTiles = (List<?>) field(activity, "dockTiles");
        assertEquals(oldWidth, ((View) restoredTiles.get(0)).getLayoutParams().width);

        // A Dock saved by the old build might still have eight configured slots and seven actual apps.
        prefs.edit().putInt("dock_size", 8).apply();
        call(activity, "loadApps", new Class[]{});
        call(activity, "buildAppViews", new Class[]{});
        List<?> migratedTiles = (List<?>) field(activity, "dockTiles");
        assertEquals(7, migratedTiles.size());
        assertEquals(oldWidth, ((View) migratedTiles.get(0)).getLayoutParams().width);
    }

    @Test public void holdingOkOnHomeDockKeepsThePopupInPlaceAndOnTheSameApp() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        View root = activity.findViewById(R.id.root);
        layout(root);
        call(activity, "focusDock", new Class[]{int.class}, 6);
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(List.of("Dock size", "Icon shape", "Show background", "Theme", "Remove from dock", "App info"),
                menuLabels(activity));
        layout(root);
        View popup = (View) field(activity, "menuScroll");
        int left = popup.getLeft(), top = popup.getTop();
        String target = (String) field(activity, "dockMenuPackage");
        assertEquals("7 apps", menuValue(activity, 0));
        assertEquals("Rectangle", menuValue(activity, 1));
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("8 apps", menuValue(activity, 0));
        key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("7 apps", menuValue(activity, 0));
        selectSetting(activity, "Icon shape");
        assertEquals("Square", menuValue(activity, 1));
        layout(root);
        ViewGroup square = (ViewGroup) ((List<?>) field(activity, "dockTiles")).get(0);
        assertEquals(square.getWidth(), square.getHeight());
        assertEquals(square.getWidth(), square.getChildAt(0).getWidth());
        assertTrue(((View) field(activity, "dockBand")).getWidth() < root.getWidth());
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("Circle", menuValue(activity, 1));
        ViewGroup tile = (ViewGroup) ((List<?>) field(activity, "dockTiles")).get(0);
        assertEquals(tile.getLayoutParams().width, tile.getLayoutParams().height);
        selectSetting(activity, "Theme");
        assertNotNull(((android.widget.ImageView) tile.getChildAt(0)).getColorFilter());
        selectSetting(activity, "Show background");
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("Off", menuValue(activity, 2));
        assertNull(((View) field(activity, "dockBand")).getBackground());
        settle();
        layout(root);
        assertEquals(left, popup.getLeft());
        assertEquals(top, popup.getTop());
        save(root, "sweep-home-dock-menu.png");
        call(activity, "focusMenu", new Class[]{int.class}, 0);
        key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("6 apps", menuValue(activity, 0));
        assertEquals("Not in Dock", menuValue(activity, 4));
        assertEquals(target, field(activity, "dockMenuPackage"));
        layout(root);
        assertEquals(left, popup.getLeft());
        assertEquals(top, popup.getTop());
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals(3, field(activity, "zone"));
    }

    private static String menuValue(HomeActivity activity, int index) throws Exception {
        View row = (View) ((List<?>) field(activity, "menuItems")).get(index);
        return ((android.widget.TextView) row.findViewById(R.id.input_status)).getText().toString();
    }

    private static List<String> menuLabels(HomeActivity activity) throws Exception {
        return ((List<?>) field(activity, "menuItems")).stream().map(View.class::cast)
                .map(row -> ((android.widget.TextView) row.findViewById(R.id.input_label)).getText().toString())
                .collect(java.util.stream.Collectors.toList());
    }

    @Test public void recommendationEngineDefaultsToSweepAndPreservesTheSavedKey() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        String key = "fixture-tmdb-key";
        assertTrue(Keys.write(activity, Keys.TMDB, key));
        assertEquals("sweep", Keys.catalogEngine(activity));
        assertNull(Keys.activeTmdbKey(activity));
        call(activity, "openContentSettings", new Class[]{});
        if (!BuildConfig.CATALOG_COMPARISON) {
            assertFalse(menuLabels(activity).contains("Recommendation Engine"));
            Keys.setCatalogEngine(activity, "tmdb");
            assertNull(Keys.activeTmdbKey(activity));
        } else {
            selectSetting(activity, "Recommendation Engine");
            assertEquals(List.of("Sweep TV", "StreamRadar", "TMDB"), menuLabels(activity));
            selectSetting(activity, "TMDB");
            assertEquals("tmdb", Keys.catalogEngine(activity));
            assertTrue(menuLabels(activity).contains("TMDB key"));
            selectSetting(activity, "StreamRadar");
            assertEquals("streamradar", Keys.catalogEngine(activity));
            assertFalse(menuLabels(activity).contains("TMDB key"));
            selectSetting(activity, "Sweep TV");
            assertNull(Keys.activeTmdbKey(activity));
        }
        assertEquals(key, Keys.read(activity, Keys.TMDB));
    }

    @Test public void anOfflineCardShowsItsTitleWithoutTheLargeAppLogo() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        Trending.Pick pick = new Trending.Pick();
        pick.title = "Offline title";
        pick.pkg = PACKAGES[1];
        pick.service = "Netflix";
        for (String type : List.of("tv", "watchnext")) {
            pick.kind = type;
            pick.sweep = "tv".equals(type);
            View item = (View) call(activity, "pickView", new Class[]{Trending.Pick.class}, pick);
            android.widget.TextView fallback = item.findViewWithTag("catalog_placeholder");
            assertEquals("Offline title\nNetflix", fallback.getText().toString());
            assertNull(((android.widget.ImageView) item.findViewWithTag("backdrop")).getDrawable());
        }
    }

    @Test public void aSelectedDiscoveryTabBindsNewArtworkWithoutReplacingCardsOrMovingFocus() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("home",0).edit().putBoolean("for_you",true).commit();
        var controller=Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity activity=controller.get();drain(activity);
        Trending.Pick missing=new Trending.Pick();
        missing.id=-998;missing.kind="tv";missing.title="Cached title";missing.pkg=PACKAGES[1];
        missing.service="Netflix";missing.tab=Trending.TRENDING;missing.sweep=true;
        call(activity,"onPicks",new Class[]{List.class},List.of(missing));
        call(activity,"focusTabs",new Class[]{int.class},1);settle();
        PicksRow shelf=(PicksRow)field(activity,"picksRow");
        View original=shelf.getChildAt(0);assertNotNull(original.findViewWithTag("catalog_placeholder"));
        int selected=shelf.anchor();
        File poster=File.createTempFile("arriving-poster-",".png",activity.getCacheDir());
        Bitmap bitmap=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888);
        try(FileOutputStream output=new FileOutputStream(poster)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
        bitmap.recycle();
        Trending.Pick fresh=new Trending.Pick();
        fresh.id=missing.id;fresh.kind=missing.kind;fresh.title=missing.title;fresh.pkg=missing.pkg;
        fresh.service=missing.service;fresh.tab=missing.tab;fresh.sweep=true;fresh.poster=poster;
        call(activity,"onPicks",new Class[]{List.class},List.of(fresh));
        assertSame(original,shelf.getChildAt(0));assertEquals(selected,shelf.anchor());
        assertEquals(1,field(activity,"zone"));assertNull(original.findViewWithTag("catalog_placeholder"));
        assertTrue(original.findViewWithTag("poster").getTag(R.id.img_state).toString().contains(poster.getPath()));
        controller.pause().stop().destroy();
    }

    private static void enterCategory(HomeActivity activity, int category) throws Exception {
        call(activity, "focusSettingsSidebar", new Class[]{});
        for (int moves = 0; moves < 7 && (int) field(activity, "settingsCategory") != category; moves++) {
            boolean down = (int) field(activity, "settingsCategory") < category;
            call(activity, "moveSettingsSidebar", new Class[]{boolean.class}, down);
        }
        assertEquals(category, field(activity, "settingsCategory"));
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Test public void languageGridStartsUnfilteredAndKeepsAnExplicitSelection() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        assertTrue(ContentLanguages.selected(activity).isEmpty());
        call(activity, "openSweepSettings", new Class[]{int.class}, 3);
        enterCategory(activity, 2);
        assertEquals("All languages", menuValue(activity, 0));
        selectSetting(activity, "Select Content Languages");
        assertEquals(ContentLanguages.AVAILABLE.size() + 1, menuLabels(activity).size());
        selectSetting(activity, "English");
        selectSetting(activity, "Hindi");
        selectSetting(activity, "Japanese");
        assertEquals(java.util.Set.of("en", "hi", "ja"), ContentLanguages.selected(activity));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertEquals("Select Content Languages", field(activity, "settingsHeading"));
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("Content settings", field(activity, "settingsHeading"));
        assertEquals("English, Hindi, Japanese", menuValue(activity, 0));
        selectSetting(activity, "Select Content Languages");
        selectSetting(activity, "All languages");
        assertTrue(ContentLanguages.selected(activity).isEmpty());
    }

    @Test public void rotatingHeaderWidgetSitsBesideControlsAndPausesForFocusAndBackground() throws Exception {
        SectionOptions.set(RuntimeEnvironment.getApplication(), Recommendations.EXPLORE, false);
        RuntimeEnvironment.getApplication().getSharedPreferences("home", 0).edit().putBoolean("insights", true).apply();
        var controller=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true);
        HomeActivity activity = controller.get();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.top_air).getVisibility());
        drain(activity);
        HomeInsights.State insightState = (HomeInsights.State) field(activity, "insightState");
        insightState.locationKey = ((Weather) field(activity, "weather")).widgetLocationKey();
        insightState.place = new HomeInsights.Place("Delhi", "IN", "Delhi", "Asia/Kolkata", 28.61, 77.21, false);
        insightState.air = new HomeInsights.Air(320, 83, System.currentTimeMillis(), true, 10);
        call(activity,"showChannels",new Class[]{List.class},new java.util.ArrayList<Channels.Channel>());drain(activity);View root=activity.findViewById(R.id.root);layout(root);
        ViewGroup strip=activity.findViewById(R.id.top_insights);assertEquals(View.VISIBLE,strip.getVisibility());assertEquals(3,strip.getChildCount());assertTrue(((List<?>)field(activity,"chanRows")).isEmpty());
        float dp=activity.getResources().getDisplayMetrics().density;
        assertTrue(strip.getHeight()<=38*dp);
        assertEquals(Math.round(196 * dp), strip.getWidth());
        View air = activity.findViewById(R.id.top_air);
        assertEquals(Math.round(140 * dp), air.getWidth());
        View meters = activity.findViewById(R.id.meters);
        assertSame(strip, meters.getParent());
        assertEquals(strip.getHeight(), meters.getHeight());
        assertTrue(strip.getRight() < air.getLeft());
        assertTrue(air.getRight() < activity.findViewById(R.id.icon_inputs).getLeft());
        int airX = air.getLeft();
        int[] settingsLocation = new int[2];
        activity.findViewById(R.id.icon_settings).getLocationInWindow(settingsLocation);
        int settingsX = settingsLocation[0];
        assertEquals(Math.round(414*dp),activity.findViewById(R.id.dock_band).getTop());
        call(activity,"focusDock",new Class[]{int.class},0);int first=(int)field(activity,"insightIdx");
        // Invoke one timer tick directly: advancing the whole looper also runs unrelated Home refresh callbacks.
        call(activity,"rotateTopInsight",new Class[]{});
        int next=(first+1)%3;assertEquals(next,field(activity,"insightIdx"));
        android.animation.ValueAnimator fade=(android.animation.ValueAnimator)field(activity,"insightFade");
        assertNotNull(fade);assertEquals(220,fade.getDuration());
        assertEquals(View.VISIBLE,strip.getChildAt(first).getVisibility());
        assertEquals(View.VISIBLE,strip.getChildAt(next).getVisibility());
        fade.end();assertOneInsightVisible(strip,next);
        layout(root);
        assertEquals(Math.round(196*dp), strip.getWidth());
        assertEquals(airX, air.getLeft());
        activity.findViewById(R.id.icon_settings).getLocationInWindow(settingsLocation);
        assertEquals(settingsX, settingsLocation[0]);
        assertEquals(1f,strip.getChildAt(next).getAlpha(),0f);
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(8,field(activity,"zone"));int selected=(int)field(activity,"insightIdx");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertEquals(selected,field(activity,"insightIdx"));assertOneInsightVisible(strip,selected);
        key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(false, field(activity, "airFocused"));
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(true, field(activity, "airFocused"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);settle();assertEquals(0,field(activity,"zone"));assertEquals(1,field(activity,"iconIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_LEFT);settle();assertEquals(8,field(activity,"zone"));key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(3,field(activity,"zone"));
        controller.pause();selected=(int)field(activity,"insightIdx");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertEquals(selected,field(activity,"insightIdx"));
        controller.resume().windowFocusChanged(true);
        call(activity,"rotateTopInsight",new Class[]{});assertEquals((selected+1)%3,field(activity,"insightIdx"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        Field enabled=HomeActivity.class.getDeclaredField("forYou");enabled.setAccessible(true);enabled.setBoolean(activity,true);
        Trending.Pick pick=new Trending.Pick();pick.id=99;pick.kind="tv";pick.title="A title";pick.pkg=PACKAGES[0];pick.meta="Series";pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+pick.pkg+".jpg");
        for(String fieldName:new String[]{"recentPicks","trendPicks","indiaPicks"})((List<Trending.Pick>)field(activity,fieldName)).add(pick);
        call(activity,"applyForYou",new Class[]{});call(activity,"buildPicksRow",new Class[]{int.class},0);layout(root);
        call(activity,"focusTabs",new Class[]{int.class},1);key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(8,field(activity,"zone"));key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(1,field(activity,"zone"));
        call(activity,"focusInsights",new Class[]{int.class},0);call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},HomeInsights.KEY,false);drain(activity);assertEquals(View.GONE, air.getVisibility());assertEquals(View.VISIBLE,strip.getVisibility());assertEquals(1, strip.getChildCount());
        call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},HomeInsights.KEY,true);drain(activity);layout(root);assertEquals(View.VISIBLE,strip.getVisibility());assertOneInsightVisible(strip,(int)field(activity,"insightIdx"));
        HomeOptions.prefs(activity).edit().putBoolean("insight_news",false).putBoolean("insight_festivals",false).apply();
        call(activity,"showChannels",new Class[]{List.class},new java.util.ArrayList<Channels.Channel>());drain(activity);assertEquals(1,strip.getChildCount());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertOneInsightVisible(strip,0);
        controller.pause().stop().destroy();
    }
    private static void assertOneInsightVisible(ViewGroup strip,int selected){
        int visible=0;for(int i=0;i<strip.getChildCount();i++){if(strip.getChildAt(i).getVisibility()==View.VISIBLE){visible++;assertEquals(selected,i);}}assertEquals(1,visible);
    }

    @Test public void marketControlsAreInlineAndReturnToHeaderWidgets() throws Exception {
        HomeActivity activity = Robolectric.buildActivity(HomeActivity.class).setup().get();
        drain(activity);
        call(activity, "openSweepSettings", new Class[]{int.class}, 3);
        enterCategory(activity, 4);
        selectSetting(activity, "Header widgets");
        selectSetting(activity, "Market prices");
        assertEquals("Market prices", field(activity, "settingsHeading"));
        MarketQuotes quotes = (MarketQuotes) field(activity, "marketQuotes");
        List<String> assets = menuLabels(activity);
        assets.remove("Scan QR to customize");
        assertEquals(java.util.Arrays.stream(MarketQuotes.Asset.values()).map(asset -> asset.title).sorted()
                .collect(java.util.stream.Collectors.toList()), assets.stream().sorted()
                .collect(java.util.stream.Collectors.toList()));
        View root = activity.findViewById(R.id.root);
        layout(root);
        ViewGroup menu = activity.findViewById(R.id.menu);
        View viewport = activity.findViewById(R.id.menu_scroll);
        assertTrue("All market groups fit in the viewport", menu.getHeight() <= viewport.getHeight());
        List<View> cards = (List<View>) field(activity, "menuItems");
        View firstMagCard = cards.get(assets.indexOf("NVIDIA"));
        View secondMagRowCard = cards.get(assets.indexOf("Meta"));
        assertEquals(5, ((ViewGroup) firstMagCard.getParent()).getChildCount());
        assertNotSame(firstMagCard.getParent(), secondMagRowCard.getParent());
        call(activity, "focusMenu", new Class[]{int.class}, assets.indexOf("NVIDIA"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(assets.indexOf("Meta"), field(activity, "menuIdx"));
        assertFalse(quotes.isEnabled(MarketQuotes.Asset.RELIANCE));
        selectSetting(activity, "Reliance");
        assertTrue(quotes.isEnabled(MarketQuotes.Asset.RELIANCE));
        int card = menuLabels(activity).indexOf("Reliance");
        assertEquals("INR", menuValue(activity, card));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(MarketQuotes.Unit.USD, quotes.displayUnit(MarketQuotes.Asset.RELIANCE));
        assertEquals("USD", menuValue(activity, card));
        assertTrue(quotes.isEnabled(MarketQuotes.Asset.RELIANCE));
        assertEquals("Market prices", field(activity, "settingsHeading"));
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("Header widgets", field(activity, "settingsHeading"));
    }

    @Test public void sidebarQrFocusLeavesTheSettingsPageOpen() throws Exception {
        var controller = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity activity = controller.get();
        drain(activity);
        call(activity, "openSweepSettings", new Class[]{int.class}, 3);
        for (int category = 0; category < 6; category++) key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(menuLabels(activity).contains("Weather location"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(0, field(activity, "settingsSidebarAction"));
        key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("System", field(activity, "settingsHeading"));
        assertEquals(1, field(activity, "settingsSidebarAction"));
        View root = activity.findViewById(R.id.root);
        settle();
        layout(root);
        android.graphics.RectF panel = (android.graphics.RectF) field(field(activity, "settingsPopup"), "panel");
        assertEquals(.60, panel.width() * panel.height() / (root.getWidth() * root.getHeight()), .02);
        save(root, "settings-sidebar-scan-qr.png");

        android.widget.ImageView code = (android.widget.ImageView) call(activity, "qrCode",
                new Class[]{String.class}, "https://example.com/phone-preview");
        PhoneQrDialog preview = new PhoneQrDialog(activity, code, () -> { });
        preview.show();
        View decor = preview.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(preview.getWindow().getAttributes().width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        save(decor, "phone-qr-dialog-preview.png");
        preview.dismiss();

        key(activity, KeyEvent.KEYCODE_DPAD_UP);
        key(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(-1, field(activity, "settingsSidebarAction"));
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(false, field(activity, "settingsSidebar"));
        assertEquals("Weather location", menuLabels(activity).get(0));
        controller.pause().stop().destroy();
    }

    private static void selectSetting(HomeActivity activity, String title) throws Exception {
        List<?> items = (List<?>) field(activity, "menuItems");
        for (int i = 0; i < items.size(); i++) {
            android.widget.TextView label = ((View) items.get(i)).findViewById(R.id.input_label);
            if (label != null && title.contentEquals(label.getText())) {
                call(activity, "focusMenu", new Class[]{int.class}, i);
                key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
                return;
            }
        }
        throw new AssertionError("Missing setting " + title);
    }

    private static void settle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100)); }
    private static void key(HomeActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args);
    }
    private static void save(View root, String name) throws Exception {
        File dir = new File("build/native-previews"); assertTrue(dir.mkdirs() || dir.isDirectory());
        Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }
}
