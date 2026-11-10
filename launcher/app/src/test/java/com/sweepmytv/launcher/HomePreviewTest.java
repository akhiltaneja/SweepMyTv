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
import java.util.List;
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

    @Test public void originalDockGridAndSettingsRemainReachable() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        View root=activity.findViewById(R.id.root);layout(root);
        assertEquals(7,field(activity,"dockCount"));assertEquals(6,((List<?>)field(activity,"gridTiles")).size());
        call(activity,"focusDock",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();
        assertEquals(4,field(activity,"zone"));assertEquals(0,field(activity,"gridIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(3,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(0,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(5,field(activity,"zone"));
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);settle();
        for(Object row:(List<?>)field(activity,"menuItems")) {
            android.widget.TextView label=((View)row).findViewById(R.id.input_label);
            if(label!=null)assertNotEquals("Banner quality",label.getText().toString());
        }
        assertFalse(SelfUpdate.takesReleases(activity));assertNull(SelfUpdate.available(activity));
        save(root,"talkies-base-settings.png");
    }

    @Test public void unifiedUpdatesShowOnlySweepReleaseNotesAndCreditsAndBackWorks() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        activity.getSharedPreferences("upstream_updates",0).edit().putString("latest","v99.0.0").commit();
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);settle();call(activity,"focusMenu",new Class[]{int.class},7);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        List<View> rows=(List<View>)field(activity,"menuItems");assertEquals(2,rows.size());
        assertEquals("What's new",((android.widget.TextView)rows.get(0).findViewById(R.id.input_label)).getText().toString());
        assertEquals("Included components",((android.widget.TextView)rows.get(1).findViewById(R.id.input_label)).getText().toString());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(2,((List<?>)field(activity,"menuItems")).size());assertEquals(0,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();rows=(List<View>)field(activity,"menuItems");assertEquals(11,rows.size());
        assertEquals("CleanYourTV v3.4.0",((android.widget.TextView)rows.get(0).findViewById(R.id.input_label)).getText().toString());
        assertEquals("Open-source licences",((android.widget.TextView)rows.get(10).findViewById(R.id.input_label)).getText().toString());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(11,((List<?>)field(activity,"menuItems")).size());
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(2,((List<?>)field(activity,"menuItems")).size());assertEquals(1,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(9,((List<?>)field(activity,"menuItems")).size());assertEquals(7,field(activity,"menuIdx"));
        call(activity,"openControlCenter",new Class[]{});settle();
        for(View row:(List<View>)field(activity,"menuItems"))assertFalse(((android.widget.TextView)row.findViewById(R.id.input_label)).getText().toString().contains("CleanYourTV"));
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
        call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));settle();
        assertEquals(3,field(activity,"zone"));assertEquals(0,field(activity,"dockIdx"));
        View root=activity.findViewById(R.id.root);layout(root);save(root,"talkies-original-home.png");
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
        call(activity,"openContinueOptions",new Class[]{});settle();
        assertEquals(2,((List<?>)field(activity,"menuItems")).size());
        ((Runnable)((List<?>)field(activity,"menuActions")).get(0)).run();assertTrue(ContinueOptions.youtube(activity));
        ((Runnable)((List<?>)field(activity,"menuActions")).get(1)).run();assertTrue(ContinueOptions.titleArtwork(activity));
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
                for(String pkg:new String[]{PACKAGES[2],PACKAGES[1]}) {
                    android.database.MatrixCursor.RowBuilder row=cursor.newRow();
                    row.add(pkg).add("Provider fixture title").add("Episode 4").add("2").add("4")
                            .add(android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE).add(100L).add(250L)
                            .add(android.net.Uri.fromFile(still).toString()).add(android.net.Uri.fromFile(poster).toString())
                            .add("provider://episode/4").add(1).add(1000L).add(android.media.tv.TvContract.WatchNextPrograms.TYPE_TV_SERIES);
                }
                return cursor;
            }
            @Override public String getType(android.net.Uri uri){return null;}
            @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues values){return null;}
            @Override public int update(android.net.Uri uri,android.content.ContentValues values,String selection,String[] args){return 0;}
            @Override public int delete(android.net.Uri uri,String selection,String[] args){return 0;}
        };
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(android.media.tv.TvContract.AUTHORITY,provider);
        List<Trending.Pick> playback=WatchNext.load(app);assertEquals(2,playback.size());
        assertEquals(Color.RED,android.graphics.BitmapFactory.decodeFile(playback.get(1).image.getPath()).getPixel(100,100));
        app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.YOUTUBE,false).putBoolean(ContinueOptions.ARTWORK,true).commit();
        List<Trending.Pick> original=WatchNext.load(app);assertEquals(1,original.size());
        assertEquals(PACKAGES[1],original.get(0).pkg);assertEquals("provider://episode/4",original.get(0).intentUri);assertEquals(.25f,original.get(0).progress,0);
        assertEquals(Color.BLUE,android.graphics.BitmapFactory.decodeFile(original.get(0).image.getPath()).getPixel(100,100));
        app.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.ARTWORK,false).commit();
        List<Trending.Pick> again=WatchNext.load(app);assertEquals(Color.RED,android.graphics.BitmapFactory.decodeFile(again.get(0).image.getPath()).getPixel(100,100));
    }

    @Test public void portraitOnlyContinueArtworkStaysIntactWhenTheCardExpands() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        File portrait=new File(activity.getCacheDir(),"portrait-only.png");Bitmap b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);try(FileOutputStream out=new FileOutputStream(portrait)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        Trending.Pick p=new Trending.Pick();p.pkg=PACKAGES[0];p.title="Portrait-only title";p.kind="watchnext";p.meta="Series";p.poster=portrait;
        View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p);((List<Trending.Pick>)field(activity,"rowPicks")).clear();((List<Trending.Pick>)field(activity,"rowPicks")).add(p);
        PicksRow row=(PicksRow)field(activity,"picksRow");row.setItems(java.util.Collections.singletonList(item),0);call(activity,"updatePickImages",new Class[]{int.class},0);settle();
        android.widget.ImageView poster=item.findViewWithTag("poster");assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,poster.getScaleType());assertEquals(1f,poster.getAlpha(),0f);
    }

    @Test public void continueAndDiscoveryCardsRejectLandscapeFilesLabelledAsPosters() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);File wide=new File(activity.getCacheDir(),"wrong-poster-role.png");Bitmap b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);try(FileOutputStream out=new FileOutputStream(wide)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        Trending.Pick p=new Trending.Pick();p.pkg=PACKAGES[0];p.title="Landscape source";p.kind="watchnext";p.meta="Series";p.image=wide;p.poster=wide;
        Trending.Pick next=new Trending.Pick();next.pkg=PACKAGES[1];next.title="Next title";next.kind="watchnext";next.meta="Series";next.image=wide;
        View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p),second=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},next);
        List<Trending.Pick> picks=(List<Trending.Pick>)field(activity,"rowPicks");picks.clear();picks.add(p);picks.add(next);((PicksRow)field(activity,"picksRow")).setItems(java.util.Arrays.asList(item,second),1);call(activity,"updatePickImages",new Class[]{int.class},1);settle();
        assertEquals(0f,item.findViewWithTag("poster").getAlpha(),0f);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,((android.widget.ImageView)item.findViewWithTag("backdrop")).getScaleType());
        for(String tab:new String[]{Recommendations.KEY,Recommendations.NEW_KEY}){p.tab=tab;ViewGroup card=(ViewGroup)call(activity,"channelCard",new Class[]{Trending.Pick.class},p);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,((android.widget.ImageView)card.getChildAt(0)).getScaleType());}
    }

    @Test public void discoveryLandscapeCardsUseTitleCoversOrAnIntactPosterNeverTheRawScene() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);File scene=new File(activity.getCacheDir(),"scene.png"),posterFile=new File(activity.getCacheDir(),"title-poster.png"),coverFile=new File(activity.getCacheDir(),"title-cover.png");
        for(Object[] spec:new Object[][]{{scene,1280,720},{posterFile,800,1200},{coverFile,1920,1080}}){Bitmap image=Bitmap.createBitmap((Integer)spec[1],(Integer)spec[2],Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream((File)spec[0])){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
        for(String tab:new String[]{Trending.TRENDING,Trending.INDIA}){
            Trending.Pick p=new Trending.Pick();p.id=100;p.kind="movie";p.tab=tab;p.pkg=PACKAGES[0];p.title="Title";p.image=scene;p.poster=posterFile;p.intentUri="fixture://resume";p.progress=.4f;
            ((android.util.LruCache)field(activity,"titleCovers")).evictAll();List<Trending.Pick> picks=(List<Trending.Pick>)field(activity,"rowPicks");picks.clear();picks.add(p);View item=(View)call(activity,"pickView",new Class[]{Trending.Pick.class},p);((PicksRow)field(activity,"picksRow")).setItems(java.util.Collections.singletonList(item),0);
            call(activity,"updatePickImages",new Class[]{int.class},0);settle();android.widget.ImageView poster=item.findViewWithTag("poster"),back=item.findViewWithTag("backdrop");assertEquals(1f,poster.getAlpha(),0);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,poster.getScaleType());assertNull(back.getTag(R.id.img_state));
            ((android.util.LruCache)field(activity,"titleCovers")).put(TitleCovers.key(p),new TitleCovers.Result(coverFile,true));call(activity,"updatePickImages",new Class[]{int.class},0);settle();assertEquals(0f,poster.getAlpha(),0);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,back.getScaleType());assertTrue(back.getTag(R.id.img_state).toString().contains(coverFile.getAbsolutePath()));assertFalse(back.getTag(R.id.img_state).toString().contains(scene.getAbsolutePath()));assertEquals("fixture://resume",p.intentUri);assertEquals(.4f,p.progress,0);
        }
    }

    @Test public void formulaWidgetAndRaceDetailsWorkWithTheRemote() throws Exception {
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
        assertTrue(image.getDrawable().getIntrinsicHeight()>image.getDrawable().getIntrinsicWidth());
        call(activity,"focusDock",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(0,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(1,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(5,field(activity,"zone"));
        assertEquals(1,((List<?>)field(activity,"menuItems")).size());
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(6,field(activity,"zone"));assertEquals(1,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(2,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(5,field(activity,"zone"));key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(2,field(activity,"chanIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(4,field(activity,"zone"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(2,field(activity,"chanIdx"));
        call(activity,"openSettingsGroup",new Class[]{int.class},1);settle();
        List<?> menu=(List<?>)field(activity,"menuItems");boolean found=false;
        for(int i=0;i<menu.size();i++) {
            android.widget.TextView label=((View)menu.get(i)).findViewById(R.id.input_label);
            if(label!=null&&"Recommendations".contentEquals(label.getText())) {
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
        film.releaseDate="2026-10-31";film.weeklyTrending=true;
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
        controller.setup();drain(activity);call(activity,"refreshTimeTheme",new Class[]{});Object theme=field(activity,"theme");Object band=((View)field(activity,"dockBand")).getBackground();
        call(activity,"onResume",new Class[]{});settle();assertSame(theme,field(activity,"theme"));assertSame(band,((View)field(activity,"dockBand")).getBackground());
        Field cached=HomeActivity.class.getDeclaredField("cachedSky");cached.setAccessible(true);cached.set(activity,null);call(activity,"refreshTimeTheme",new Class[]{});assertNotSame(theme,field(activity,"theme"));
        controller.pause().stop().destroy();
    }

    @Test public void timeModeUpdatesWholeThemeFromNightToDayAndRestoresSavedManualAppearance() throws Exception {
        java.util.TimeZone previous=java.util.TimeZone.getDefault();java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        try {
            Application app=RuntimeEnvironment.getApplication();
            app.getSharedPreferences("home",0).edit().putInt("appearance",2).putBoolean(TimeOfDay.PREF,true).commit();
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
            call(activity,"openSettingsGroup",new Class[]{int.class},0);settle();
            now.set(java.time.Instant.ofEpochMilli(midnight+12*3600_000L));
            call(activity,"onMinute",new Class[]{});settle();
            TimeOfDay.Palette noon=TimeOfDay.palette(720);assertEquals(false,field(activity,"day"));
            Object theme=field(activity,"theme");assertEquals(noon.accent,field(theme,"iconFocusBg"));
            assertEquals(noon.accent,field(theme,"menuFocusBg"));assertNotEquals(nightMenu,field(theme,"menuBg"));
            for(String surface:new String[]{"menuBg","band","iconBg","tileBg"})assertTrue(TimeOfDay.luminance((Integer)field(theme,surface))<.06);
            assertEquals(noon.text,((android.widget.TextView)activity.findViewById(R.id.big_time)).getCurrentTextColor());
            HighlightView ring=(HighlightView)field(activity,"ring");assertEquals(noon.accent,((Paint)field(ring,"ring")).getColor());
            List<?> menu=(List<?>)field(activity,"menuItems");int appearance=-1,background=-1;
            for(int i=0;i<menu.size();i++) {
                android.widget.TextView label=((View)menu.get(i)).findViewById(R.id.input_label);
                if("Appearance".contentEquals(label.getText()))appearance=i;
                if("Background".contentEquals(label.getText()))background=i;
            }
            assertTrue(appearance>=0&&background>=0);
            assertEquals("Time of day",((android.widget.TextView)((View)menu.get(appearance)).findViewById(R.id.input_status)).getText().toString());
            assertEquals(2,app.getSharedPreferences("home",0).getInt("appearance",-1));
            now.set(java.time.Instant.ofEpochMilli(midnight+21*3600_000L));
            call(activity,"onResume",new Class[]{});settle();assertEquals(false,field(activity,"day"));
            assertEquals(TimeOfDay.palette(1260).accent,field(field(activity,"theme"),"menuFocusBg"));
            ((Runnable)((List<?>)field(activity,"menuActions")).get(background)).run();settle();
            assertFalse(app.getSharedPreferences("home",0).getBoolean(TimeOfDay.PREF,true));
            assertEquals(false,field(activity,"day"));assertEquals(2,app.getSharedPreferences("home",0).getInt("appearance",-1));
            assertEquals(0xFFFFFFFF,((Paint)field(ring,"ring")).getColor());
            assertEquals("Dark",((android.widget.TextView)((View)menu.get(appearance)).findViewById(R.id.input_status)).getText().toString());
        } finally {java.util.TimeZone.setDefault(previous);}
    }

    @Test public void settingsHubHasOwnBrandAndLongPressCanHideAndRestoreAnAppSection() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);settle();boolean profile=false,sections=false,widgets=false;
        for(Object row:(List<?>)field(activity,"menuItems")) {
            String label=((android.widget.TextView)((View)row).findViewById(R.id.input_label)).getText().toString();
            assertFalse(label.contains("Talkies"));profile|=label.equals("Profile and greeting");sections|=label.equals("Home sections");widgets|=label.equals("Status widget");
        }
        assertTrue(profile&&sections&&widgets);
        Channels.Channel section=new Channels.Channel();section.key="example.app|Featured";section.title="Featured";
        Trending.Pick pick=new Trending.Pick();pick.id=100;pick.title="Film";pick.pkg=PACKAGES[0];pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+pick.pkg+".jpg");section.items.add(pick);
        List<Channels.Channel> rows=new java.util.ArrayList<>();rows.add(section);call(activity,"showChannels",new Class[]{List.class},rows);drain(activity);call(activity,"focusChan",new Class[]{int.class,int.class},0,0);
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("Remove section from Home",((android.widget.TextView)((View)((List<?>)field(activity,"menuItems")).get(0)).findViewById(R.id.input_label)).getText().toString());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);drain(activity);assertTrue(SectionOptions.hidden(activity,section.key));assertEquals(0,((List<?>)field(activity,"chanRows")).size());assertEquals(1,section.items.size());
        drain(activity);SectionOptions.set(activity,section.key,true);call(activity,"showChannels",new Class[]{List.class},rows);drain(activity);assertEquals(1,((List<?>)field(activity,"chanRows")).size());
        call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},Channels.WATCH_NEXT,false);assertFalse(Channels.isShown(activity,Channels.WATCH_NEXT,true));
        call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},Channels.WATCH_NEXT,true);assertTrue(Channels.isShown(activity,Channels.WATCH_NEXT,false));
    }

    @Test public void appleResumeEntriesLoadAtColdStartBeforeTheProviderPublishes() throws Exception {
        Application app=RuntimeEnvironment.getApplication();Trending.Pick pick=new Trending.Pick();pick.pkg=PACKAGES[0];pick.id=42;pick.title="Apple TV episode";pick.meta="Season 1 · Episode 2 · Apple TV";pick.service="Apple TV";pick.intentUri="apple-tv://resume/42";pick.at=System.currentTimeMillis();
        AppleResumeCache.reconcile(app,java.util.Arrays.asList(pick),true,System.currentTimeMillis());
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        List<Trending.Pick> recent=(List<Trending.Pick>)field(activity,"recentPicks");assertEquals(1,recent.size());assertEquals("apple-tv://resume/42",recent.get(0).intentUri);assertTrue(recent.get(0).savedResume);
    }

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }
    private Channels.Channel simpleRow(HomeActivity activity,String key,int id,String title) {
        Channels.Channel row=new Channels.Channel();row.key=key;row.title=title;
        Trending.Pick pick=new Trending.Pick();pick.id=id;pick.title=title;pick.pkg=PACKAGES[0];
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

    @Test public void settingsPopupGridNavigatesAndBackReturnsToTheSelectedCategory() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);settle();layout(activity.findViewById(R.id.root));settle();
        assertTrue((Boolean)field(activity,"settingsPopupMode"));assertTrue((Boolean)field(activity,"settingsGrid"));
        assertEquals(9,((List<?>)field(activity,"menuItems")).size());assertEquals(View.VISIBLE,((View)field(activity,"settingsPopup")).getVisibility());
        // The modal surface must be behind its controls, not just logically navigable.
        View root=activity.findViewById(R.id.root),selected=(View)((List<?>)field(activity,"menuItems")).get(0);
        android.graphics.Rect bounds=new android.graphics.Rect();selected.getDrawingRect(bounds);((ViewGroup)root).offsetDescendantRectToMyCoords(selected,bounds);
        Bitmap rendered=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(rendered));
        int expected=((android.graphics.drawable.GradientDrawable)selected.getBackground()).getColor().getDefaultColor(),visiblePixels=0;
        for(int y=Math.max(0,bounds.top);y<Math.min(rendered.getHeight(),bounds.bottom);y++)for(int x=Math.max(0,bounds.left);x<Math.min(rendered.getWidth(),bounds.right);x++)if(rendered.getPixel(x,y)==expected)visiblePixels++;
        assertTrue("Selected category must be visible above the popup surface",visiblePixels>100);rendered.recycle();
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(1,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(4,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(7,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(8,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(8,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertFalse((Boolean)field(activity,"settingsGrid"));
        assertTrue((Boolean)field(activity,"settingsPopupMode"));assertTrue(((List<?>)field(activity,"menuItems")).size()>9);
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertTrue((Boolean)field(activity,"settingsGrid"));assertEquals(8,field(activity,"menuIdx"));
        call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));settle();
        assertEquals(View.GONE,((View)field(activity,"settingsPopup")).getVisibility());assertEquals(3,field(activity,"zone"));
    }

    @Test public void popupToggleShowsItsSavedValueAndDoesNotStyleOtherMenus() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        assertEquals("Profile and greeting",field(activity,"settingsHeading"));
        for(int i=0;i<4;i++)key(activity,KeyEvent.KEYCODE_DPAD_DOWN);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        assertFalse(activity.getSharedPreferences("home",0).getBoolean("header_greeting",true));
        View row=(View)((List<?>)field(activity,"menuItems")).get(4);android.widget.TextView value=row.findViewById(R.id.input_status);
        assertEquals("Off",value.getText().toString());assertNotNull(value.getCompoundDrawablesRelative()[2]);assertTrue(row.getContentDescription().toString().contains("Off"));
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(0,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();row=(View)((List<?>)field(activity,"menuItems")).get(4);
        assertEquals("Off",((android.widget.TextView)row.findViewById(R.id.input_status)).getText().toString());
        key(activity,KeyEvent.KEYCODE_BACK);key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(View.GONE,((View)field(activity,"settingsPopup")).getVisibility());
        call(activity,"openControlCenter",new Class[]{});settle();assertFalse((Boolean)field(activity,"settingsPopupMode"));assertFalse((Boolean)field(activity,"settingsGrid"));
    }

    @Test public void avatarGridUsesFourColumnsAndLanguagePickerKeepsOneSelection() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);
        call(activity,"openAvatarGrid",new Class[]{int.class},0);assertEquals(12,((List<?>)field(activity,"menuItems")).size());
        call(activity,"focusMenu",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);assertEquals(4,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(5,Profiles.avatar(activity));assertEquals(5,field(activity,"menuIdx"));
        new Trending(activity).setLanguages(java.util.Set.of("en"));call(activity,"openPicksLanguages",new Class[]{});call(activity,"focusMenu",new Class[]{int.class},1);
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(java.util.Set.of("en"),ContentLanguages.selected(activity));
        call(activity,"focusMenu",new Class[]{int.class},2);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(java.util.Set.of("en","hi"),ContentLanguages.selected(activity));
        call(activity,"focusMenu",new Class[]{int.class},1);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(java.util.Set.of("hi"),ContentLanguages.selected(activity));
    }

    @Test public void avatarStylesKeepStableChoicesAndReturnToTheirCategory() throws Exception {
        HomeActivity activity=Robolectric.buildActivity(HomeActivity.class).setup().get();drain(activity);Profiles.avatar(activity,11);
        call(activity,"openTalkiesSettings",new Class[]{int.class},0);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);key(activity,KeyEvent.KEYCODE_DPAD_DOWN);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();
        assertEquals("Choose avatar style",field(activity,"settingsHeading"));assertEquals(3,((List<?>)field(activity,"menuItems")).size());
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals("Animals",field(activity,"settingsHeading"));assertEquals(12,((List<?>)field(activity,"menuItems")).size());assertEquals(11,Profiles.avatar(activity));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(17,Profiles.avatar(activity));assertEquals(5,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals("Choose avatar style",field(activity,"settingsHeading"));assertEquals(0,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_DOWN);key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals("Little things",field(activity,"settingsHeading"));assertEquals(0,field(activity,"menuIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_CENTER);settle();assertEquals(24,Profiles.avatar(activity));
        key(activity,KeyEvent.KEYCODE_BACK);settle();assertEquals(1,field(activity,"menuIdx"));
    }

    @Test public void rotatingHeaderWidgetSitsBesideControlsAndPausesForFocusAndBackground() throws Exception {
        var controller=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true);
        HomeActivity activity=controller.get();drain(activity);activity.getSharedPreferences("home",0).edit().putBoolean("insights",true).apply();
        call(activity,"showChannels",new Class[]{List.class},new java.util.ArrayList<Channels.Channel>());drain(activity);View root=activity.findViewById(R.id.root);layout(root);
        ViewGroup strip=activity.findViewById(R.id.top_insights);assertEquals(View.VISIBLE,strip.getVisibility());assertEquals(3,strip.getChildCount());assertTrue(((List<?>)field(activity,"chanRows")).isEmpty());
        float dp=activity.getResources().getDisplayMetrics().density;assertTrue(strip.getHeight()<=38*dp);assertTrue(strip.getWidth()<=200*dp);assertTrue(strip.getChildAt(0).getWidth()<=100*dp);assertTrue(strip.getChildAt(0).getHeight()<=34*dp);
        assertSame(strip.getParent(),activity.findViewById(R.id.icon_cc).getParent());assertTrue(strip.getRight()<activity.findViewById(R.id.icon_cc).getLeft());assertEquals(Math.round(414*dp),activity.findViewById(R.id.dock_band).getTop());
        call(activity,"focusDock",new Class[]{int.class},0);int first=(int)field(activity,"insightIdx");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(12_100));assertEquals((first+1)%3,field(activity,"insightIdx"));assertOneInsightVisible(strip,(int)field(activity,"insightIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(8,field(activity,"zone"));int selected=(int)field(activity,"insightIdx");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertEquals(selected,field(activity,"insightIdx"));assertOneInsightVisible(strip,selected);
        key(activity,KeyEvent.KEYCODE_DPAD_RIGHT);settle();assertEquals(0,field(activity,"zone"));assertEquals(0,field(activity,"iconIdx"));
        key(activity,KeyEvent.KEYCODE_DPAD_LEFT);settle();assertEquals(8,field(activity,"zone"));key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(3,field(activity,"zone"));
        controller.pause();selected=(int)field(activity,"insightIdx");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertEquals(selected,field(activity,"insightIdx"));
        controller.resume().windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(12_100));assertEquals((selected+1)%3,field(activity,"insightIdx"));
        Field enabled=HomeActivity.class.getDeclaredField("forYou");enabled.setAccessible(true);enabled.setBoolean(activity,true);
        Trending.Pick pick=new Trending.Pick();pick.id=99;pick.kind="tv";pick.title="A title";pick.pkg=PACKAGES[0];pick.meta="Series";pick.image=new File(activity.getExternalFilesDir(null),"tiles/"+pick.pkg+".jpg");
        for(String fieldName:new String[]{"recentPicks","trendPicks","indiaPicks"})((List<Trending.Pick>)field(activity,fieldName)).add(pick);
        call(activity,"applyForYou",new Class[]{});call(activity,"buildPicksRow",new Class[]{int.class},0);layout(root);
        call(activity,"focusTabs",new Class[]{int.class},1);key(activity,KeyEvent.KEYCODE_DPAD_UP);settle();assertEquals(8,field(activity,"zone"));key(activity,KeyEvent.KEYCODE_DPAD_DOWN);settle();assertEquals(1,field(activity,"zone"));
        call(activity,"focusInsights",new Class[]{int.class},0);call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},HomeInsights.KEY,false);drain(activity);assertEquals(View.GONE,strip.getVisibility());assertEquals(3,field(activity,"zone"));
        call(activity,"setSectionVisibility",new Class[]{String.class,boolean.class},HomeInsights.KEY,true);drain(activity);layout(root);assertEquals(View.VISIBLE,strip.getVisibility());assertOneInsightVisible(strip,(int)field(activity,"insightIdx"));
        HomeOptions.prefs(activity).edit().putBoolean("insight_news",false).putBoolean("insight_festivals",false).apply();
        call(activity,"showChannels",new Class[]{List.class},new java.util.ArrayList<Channels.Channel>());drain(activity);assertEquals(1,strip.getChildCount());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertOneInsightVisible(strip,0);
        controller.pause().stop().destroy();
    }
    private static void assertOneInsightVisible(ViewGroup strip,int selected){
        int visible=0;for(int i=0;i<strip.getChildCount();i++){if(strip.getChildAt(i).getVisibility()==View.VISIBLE){visible++;assertEquals(selected,i);}}assertEquals(1,visible);
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
