package com.sweepmytv.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talkies. One screen: "For You" with tabs (Continue, Trending, New in India) and a row of picks, round icon
 * buttons and a small time · date · weather line at the top right, a dock of seven apps in one band, and every other
 * app below the fold. Light by day, dark at night. The remote is handled entirely here, so navigation is exact.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "GlassHome";

    // Where focus is.
    private static final int Z_TOP = 0, Z_TABS = 1, Z_PICKS = 2, Z_DOCK = 3, Z_GRID = 4, Z_MENU = 5, Z_CHAN = 6, Z_ALL = 7, Z_INSIGHTS = 8;
    private static final int P_CONTROL = 0, P_INPUTS = 1, P_WIFI = 2, P_SETTINGS = 3, P_BELL = 4;
    private static final String[] TAB_IDS = {RecentStore.TAB, Trending.TRENDING, Trending.INDIA};
    private static final int TAB_TRENDING = 1, MIN_CONTINUE = 10, MAX_CONTINUE = 16, MAX_YOUTUBE = 2;
    private static final int GRID_COLS = 6;
    /** How many apps the dock holds (Talkies settings → Dock size); the tiles shrink or grow to fit. */
    private static final int[] DOCK_SIZES = {5, 6, 7, 8, 9, 10};
    // Geometry (dp, on a 960 × 540 dp screen).
    private static final int DOCK_MAX_W = 146, DOCK_GAP = 12, ALL_BUTTON = 44, GRID_TILE_W = 133, GRID_TILE_H = 75, GRID_GAP_X = 16,
            GRID_GAP_Y = 18;
    // Channel rows, below the dock: header, a row of cards, the focused card's title and description.
    private static final int CHAN_TOP = 548, CHAN_ROW_H = 212, CH_W = 200, CH_H = 112, CH_GAP = 18, CRICKET_W = 280;
    private static final int POSTER_W=140,POSTER_H=210;
    private static final int MENU_PLAIN = 0, MENU_CHANNELS = 1;

    private float dp;
    private SharedPreferences prefs;
    private PackageManager pm;

    private View root;
    private Backdrop backdrop;
    private FrameLayout page, dockBand,topInsights;
    private final List<View> insightCards=new ArrayList<>();
    private Channels.Channel topInsightRow;
    private String topInsightSignature;
    private int insightIdx;
    private static final long INSIGHT_ROTATION_MS=12_000;
    private final Runnable rotateInsight=this::rotateTopInsight;
    private LinearLayout dock, grid, menu, channelsBox;
    private android.widget.ScrollView menuScroll;
    private TextView title, info, credit, allAppsLabel, moveHint;
    private TextView[] tabs;
    private ImageView[] icons;
    private PicksRow picksRow;
    private HighlightView ring;

    // Apps: the dock's seven first, then the rest in your order.
    private final List<AppEntry> apps = new ArrayList<>();
    private int dockCount;
    /** The tile after the dock: OK goes to All apps, rather than down past every channel row. */
    private View allTile;
    private final List<View> dockTiles = new ArrayList<>(), gridTiles = new ArrayList<>();

    // Channel rows.
    private List<Channels.Channel> chanRows = new ArrayList<>();
    private final List<android.widget.HorizontalScrollView> chanScrolls = new ArrayList<>();
    private final List<List<View>> chanCards = new ArrayList<>();
    private int[] chanCol = new int[0];
    private int chanIdx;
    private String chanSignature = null;
    private final List<Spring> chanSprings = new ArrayList<>();
    private boolean refocusChannels;
    /** When a remote key last moved focus, and whether a channel update is waiting for the remote to go still. */
    private long lastKeyAt;
    private final ExecutorService channelWorker=Executors.newSingleThreadExecutor(r->new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();
    },"home-rows"));
    private volatile int channelsGeneration;
    private ChannelSnapshot pendingChannels;
    private long homeInteractiveAfter;
    private static final long NAV_IDLE_MS=650;
    private static final class ChannelViews {
        String signature;LinearLayout row;android.widget.HorizontalScrollView scroll;List<View> cards;Spring spring;int col;
    }
    private final Map<String,ChannelViews> channelViews=new java.util.LinkedHashMap<>();
    /** The icon Talkies settings drops down from (Control Center, or the gear when you held OK on it). */
    private int settingsAt = P_CONTROL;
    private SettingsPopup settingsPopup;
    private boolean settingsPopupMode,settingsGrid,settingsOpening;
    private int settingsColumns=3;
    private String settingsHeading;

    // The Channels editor (Control Center → Channels).
    private int menuMode = MENU_PLAIN;
    private List<Channels.Channel> editList, beforeEdit;
    private boolean menuMoving, channelsEdited;
    /** The arrow being handled is a repeat from holding it down: menus then stop at their ends instead of wrapping. */
    private boolean heldKey;

    // Picks for the three tabs, and what the row shows now.
    private Trending trending;
    private Weather weather;
    private Cricket cricket;
    private HomeInsights insights;
    private HomeInsights.State insightState=HomeInsights.State.empty();
    private FormulaOne formula;
    private FormulaOne.State formulaState = FormulaOne.State.empty();
    private List<Cricket.Match> matches = new ArrayList<>();
    private List<Channels.Channel> appChannels = new ArrayList<>();  // the shown app channels, without Cricket
    private TextView score;
    private String weatherLine;
    private List<Trending.Pick> watchNextPicks = new ArrayList<>(), recentPicks = new ArrayList<>(),
            trendPicks = new ArrayList<>(), indiaPicks = new ArrayList<>(), rowPicks = new ArrayList<>();
    private List<Trending.Pick> recommendationCatalog=new ArrayList<>();
    private int tabIdx = TAB_TRENDING;
    private long pickHour = -1;
    private int continueGeneration;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final CardArtwork cardArtwork = new CardArtwork();
    private final ExecutorService titleCoverWorker=Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"title-covers"));
    private final android.util.LruCache<String,TitleCovers.Result> titleCovers=new android.util.LruCache<>(48);
    private volatile int titleCoverGeneration;

    // Focus.
    private int zone = Z_DOCK, iconIdx = P_CONTROL, dockIdx = 0, gridIdx = 0;  // Up starts on Control Center, then the icon used last
    private int menuIdx, menuAnchor = P_INPUTS;
    private View menuTile;          // a menu opened on an app or card sits by it (else under its top-bar icon)
    private Runnable menuClosed;    // and closing it puts focus back there
    // Back in a menu opened from another menu's row (Control Center → Talkies settings → Dock apps) goes up one
    // level: what reopens each menu above, with the row you came from.
    private Runnable menuOpener;
    private final java.util.ArrayDeque<Object[]> menuParents = new java.util.ArrayDeque<>();
    private boolean fromMenuRow;
    private int menuGen;
    private final List<View> menuItems = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<Boolean> menuKeepsOpen = new ArrayList<>();
    private View focusedView;

    // The ring and the page scroll.
    // Motion, all springs stepped on one frame callback (see doMotion): the page, the channel rows and each card's
    // scale. The ring is fixed to the focused card (it grows with it); a ring gliding on its own spring lagged behind
    // the cards when a row or the page moved. Springs keep their speed when a press changes the target.
    private final RectF ringNow = new RectF(), tmp = new RectF();
    private final Spring pageSpring = new Spring(0.34f, 1f, 0, 0.25f);
    private float ringRadius;
    private final Map<View, Spring> scaleSprings = new WeakHashMap<>();
    private boolean motionPosted;
    private long motionNanos, followUntil;

    // Theme.
    private boolean day;
    private Theme theme;
    private long wallpaperStamp = -1;
    private boolean hasWallpaper;
    private Bing bing;
    private Legibility wallFit;  // how much veil the wallpaper needs behind text (null: no wallpaper)
    // The accessibility service should be running and isn't (ServiceRepair.stopped): a note at the top says so.
    private TextView serviceNote;
    private boolean serviceStopped, rebindTried;
    // For You (title, tabs, picks) can be hidden in Control Center; a large clock, date and weather take its place.
    private boolean forYou;
    private TimeOfDay timeOfDay=new TimeOfDay();
    private Meters meters;
    /** Online at the last network change; null before the first. */
    private Boolean wasOnline;
    private View forYouHead, bigClock;
    private TextView picksHint;
    private TextView bigTime, bigAmPm, bigDate, bigWeather;
    private View bigRule;
    private boolean inFront;

    // Rearranging: hold OK on an app, arrows move it, OK places it, Back cancels.
    private static final long HOLD_MS = 550;
    private boolean moving, holdFired;
    private String movingPkg;
    private List<AppEntry> beforeMove;
    private final Runnable holdTimer = this::onHold;

    private ConnectivityManager.NetworkCallback netCallback;
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onMinute(); }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadApps(); }
    };

    // ---------------------------------------------------------------- setup

    /**
     * The layout is drawn for a screen 960 × 540 dp, which is what 1080p and 4K TVs report at their usual density.
     * Some TVs and boxes report another density (1080p at 240 dpi is 1280 dp wide), which would leave the layout
     * small in a corner or too big. So the density is set from the screen's width: it's always 960 dp across.
     */
    @Override
    protected void attachBaseContext(Context base) {
        android.util.DisplayMetrics dm = base.getResources().getDisplayMetrics();
        android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
        int wide = Math.max(dm.widthPixels, dm.heightPixels), narrow = Math.min(dm.widthPixels, dm.heightPixels);
        int dpi = Math.round(wide * 160f / 960f);
        if (wide > 0 && Math.abs(dpi - c.densityDpi) > 2) {
            c.densityDpi = dpi;
            c.screenWidthDp = 960;
            c.screenHeightDp = Math.round(narrow * 160f / dpi);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // restore-settings.sh pushed a backup: apply it before anything reads the settings.
        if (Backup.restoreIfPresent(this)) Toast.makeText(this, "Settings restored from the backup", Toast.LENGTH_LONG).show();
        else if (getIntent().getBooleanExtra("talkies_backup", false)) Backup.save(this);  // asked before Talkies was running
        setContentView(R.layout.activity_home);
        weigh(this::findViewById, R.id.title, 600, R.id.info, 400, R.id.score, 500, R.id.big_weather, 500, R.id.big_time, 400,
                R.id.big_ampm, 500, R.id.big_date, 500, R.id.all_apps_label, 600, R.id.move_hint, 450);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pm = getPackageManager();

        root = findViewById(R.id.root);
        backdrop = findViewById(R.id.backdrop);
        forYouHead = findViewById(R.id.for_you_head);
        bigClock = findViewById(R.id.big_clock);
        bigTime = findViewById(R.id.big_time);
        bigDate = findViewById(R.id.big_date);
        bigAmPm = findViewById(R.id.big_ampm);
        bigRule = findViewById(R.id.big_rule);
        bigWeather = findViewById(R.id.big_weather);
        forYou = prefs.getBoolean("for_you", true);
        meters = findViewById(R.id.meters);
        meters.update();
        getWindow().setBackgroundDrawable(null);   // the backdrop covers the screen: don't paint it black first
        page = findViewById(R.id.page);
        topInsights=findViewById(R.id.top_insights);
        dockBand = findViewById(R.id.dock_band);
        dock = findViewById(R.id.dock);
        grid = findViewById(R.id.grid);
        menu = findViewById(R.id.menu);
        menuScroll = findViewById(R.id.menu_scroll);
        settingsPopup=new SettingsPopup(this);settingsPopup.setVisibility(View.GONE);
        ((FrameLayout)root).addView(settingsPopup,((FrameLayout)root).indexOfChild(menuScroll),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        channelsBox = findViewById(R.id.channels);
        title = findViewById(R.id.title);
        info = findViewById(R.id.info);
        credit = findViewById(R.id.credit);
        allAppsLabel = findViewById(R.id.all_apps_label);
        moveHint = findViewById(R.id.move_hint);
        picksRow = findViewById(R.id.picks);
        picksHint = findViewById(R.id.picks_hint);
        ring = findViewById(R.id.ring);
        tabs = new TextView[]{findViewById(R.id.tab_recent), findViewById(R.id.tab_trending), findViewById(R.id.tab_india)};
        icons = new ImageView[]{findViewById(R.id.icon_cc), findViewById(R.id.icon_inputs), findViewById(R.id.icon_wifi),
                findViewById(R.id.icon_settings), findViewById(R.id.icon_bell)};
        Updates.setListener(this::onUpdates);
        onUpdates();
        picksRow.setOnFrame(this::snapRing);

        // Banners are prepared at the size of the largest focused tile: an All apps tile at 110%, or the dock's
        // largest (five apps) at 114%.
        AppEntry.setBannerSize(px(Math.max(GRID_TILE_W * 1.10f, DOCK_MAX_W * 1.14f)), px(Math.max(GRID_TILE_H * 1.10f, DOCK_MAX_W * 9 / 16f * 1.14f)));
        AppEntry.customDir = new File(getExternalFilesDir(null), "tiles");
        trending = new Trending(this);
        Profiles.active(this);
        weather = new Weather(this);
        insights=new HomeInsights(this,weather);insightState=insights.cached();
        bing = new Bing(this, background);
        cricket = new Cricket(this);
        formula = new FormulaOne(this);
        formulaState = formula.cached();
        score = findViewById(R.id.score);
        weatherLine = weather.cached();
        day = isDayNow();
        theme = Theme.of(day);

        loadApps();
        loadPicks();
        buildAppViews();
        buildPicksRow(0);
        applyTheme();
        applyForYou();
        dockIdx = Math.min(prefs.getInt("last_dock_col", 0), Math.max(0, dockCount - 1));
        root.post(() -> {
            focusDock(dockIdx);
            String open = getIntent().getStringExtra(RemoteKeys.EXTRA_OPEN);
            if (RemoteKeys.INPUTS.equals(open)) openInputs();
            else if (RemoteKeys.RECENTS.equals(open)) openRecents();
        });
    }

    private int px(float v) { return Math.round(v * dp); }

    // ---------------------------------------------------------------- theme

    /** Colours for the light (day) and dark (night) looks. */
    private static final class Theme {
        int bgTop, bgBottom, text, text2, iconBg, iconStroke, iconFocusBg, iconFocusFg, band, bandStroke,
                scrimTop, scrimMid, scrimBottom, menuBg, menuStroke, menuFocusBg, menuFocusText, tileBg;
        float ambientAlpha;

        static Theme of(boolean day) {
            Theme t = new Theme();
            if (day) {
                t.bgTop = 0xFFF5F6F8; t.bgBottom = 0xFFE2E5EA; t.text = 0xFF1D1D1F; t.text2 = 0xFF55555A;
                t.iconBg = 0xC0FFFFFF; t.iconStroke = 0x10000000; t.iconFocusBg = 0xFF1D1D1F; t.iconFocusFg = 0xFFFFFFFF;
                t.band = 0x94FFFFFF; t.bandStroke = 0xF2FFFFFF;
                t.scrimTop = 0x8CF5F6F8; t.scrimMid = 0x33EBEEF2; t.scrimBottom = 0xA6E2E5EA;
                t.menuBg = 0xFFFFFFFF; t.menuStroke = 0x14000000; t.menuFocusBg = 0xFF1D1D1F; t.menuFocusText = 0xFFFFFFFF;
                t.tileBg = 0xFFD9DCE1; t.ambientAlpha = 0.22f;
            } else {
                t.bgTop = 0xFF121419; t.bgBottom = 0xFF08090C; t.text = 0xFFF5F5F7; t.text2 = 0xFFA1A1A6;
                t.iconBg = 0x1AFFFFFF; t.iconStroke = 0x1FFFFFFF; t.iconFocusBg = 0xFFF5F5F7; t.iconFocusFg = 0xFF111113;
                t.band = 0x12FFFFFF; t.bandStroke = 0x1FFFFFFF;
                t.scrimTop = 0x5908090C; t.scrimMid = 0x2608090C; t.scrimBottom = 0xB308090C;
                t.menuBg = 0xFF1C1C1F; t.menuStroke = 0x2EFFFFFF; t.menuFocusBg = 0xFFF5F5F7; t.menuFocusText = 0xFF111113;
                t.tileBg = 0xFF232326; t.ambientAlpha = 0.30f;
            }
            return t;
        }

        static Theme fromSky(TimeOfDay.Palette p) {
            Theme t=of(p.light);
            t.bgTop=p.top;t.bgBottom=p.bottom;t.text=p.text;t.text2=p.muted;
            t.iconFocusBg=t.menuFocusBg=p.accent;
            t.iconFocusFg=t.menuFocusText=TimeOfDay.foreground(p.accent);
            t.iconBg=(TimeOfDay.blend(p.mid,0xFF0B1426,.55f)&0xFFFFFF)|0xDD000000;t.iconStroke=(p.accent&0xFFFFFF)|0x44000000;
            t.band=(TimeOfDay.blend(p.mid,0xFF101B30,.45f)&0xFFFFFF)|0xEB000000;t.bandStroke=(p.accent&0xFFFFFF)|0x77000000;
            t.menuBg=TimeOfDay.blend(p.mid,0xFF111A2C,.68f);t.menuStroke=(p.accent&0xFFFFFF)|0x55000000;
            t.tileBg=TimeOfDay.blend(p.mid,0xFF142038,.5f);
            t.scrimTop=(t.scrimTop&0xFF000000)|(p.top&0xFFFFFF);
            t.scrimMid=(t.scrimMid&0xFF000000)|(TimeOfDay.blend(p.top,p.bottom,.5f)&0xFFFFFF);
            t.scrimBottom=(t.scrimBottom&0xFF000000)|(p.bottom&0xFFFFFF);
            return t;
        }
    }

    private void applyTheme() {applyTheme(true);}

    private void applyTheme(boolean rebuildCards) {
        int oldText=theme.text,oldMuted=theme.text2;
        boolean timed=prefs.getBoolean(TimeOfDay.PREF,false);
        TimeOfDay.Palette sky=timed?skyPalette():null;
        appliedSky=sky;
        theme = timed?Theme.fromSky(sky):Theme.of(day);
        backdrop.setTheme(day, theme.bgTop, theme.bgBottom, theme.scrimTop, theme.scrimMid, theme.scrimBottom, theme.ambientAlpha);
        backdrop.setScrimStops(wallpaperScrim());
        title.setTextColor(theme.text);
        bigTime.setTextColor(theme.text);
        bigAmPm.setTextColor(theme.text);
        bigDate.setTextColor(theme.text);
        bigWeather.setTextColor(theme.text2);
        meters.setColor(theme.text);
        bigRule.setBackgroundColor((theme.text2 & 0x00FFFFFF) | 0x66000000);
        allAppsLabel.setTextColor(theme.text);
        picksHint.setTextColor(theme.text2);
        credit.setTextColor((theme.text2 & 0x00FFFFFF) | 0x99000000);
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(px(30));
        band.setColor(theme.band);
        band.setStroke(px(1), theme.bandStroke);
        dockBand.setBackground(band);
        moveHint.setTextColor(theme.text);
        moveHint.setBackground(rounded(theme.menuBg, theme.menuStroke, 14));
        ring.setDay(day);
        ring.setAccent(timed?sky.accent:0);
        for (int i = 0; i < picksRow.getChildCount(); i++) stylePickText(picksRow.getChildAt(i));
        styleChannels();
        // Card backgrounds and match cards are drawn in the theme's colours: redraw the rows.
        if (rebuildCards && cricket != null && zone != Z_CHAN && !chanRows.isEmpty()) {
            chanSignature = null;
            showChannels(appChannels);
        }
        for (View t : dockTiles) t.setBackground(rounded(theme.tileBg, 0, 11));
        for (View t : gridTiles) t.setBackground(rounded(theme.tileBg, 0, 11));
        styleTop();
        updateInfo();
        updateAmbient();
        if(zone==Z_MENU) {
            if(settingsPopupMode)settingsPopup.show(settingsHeading,HomeOptions.name(this),theme.iconFocusBg,"Sweep TV · Settings".equals(settingsHeading));
            menuScroll.setBackground(settingsPopupMode?new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT):rounded(theme.menuBg,theme.menuStroke,20));
            retintMenu(menu,oldText,oldMuted);
            if(!menuItems.isEmpty())focusMenu(menuIdx);
        }
    }

    private void retintMenu(View view,int oldText,int oldMuted) {
        if(view instanceof TextView) {
            TextView text=(TextView)view;int color=text.getCurrentTextColor();
            if(color==oldText)text.setTextColor(theme.text);
            else if(color==oldMuted)text.setTextColor(theme.text2);
        } else if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)retintMenu(((ViewGroup)view).getChildAt(i),oldText,oldMuted);
    }

    private void refreshTimeTheme() {
        boolean nowDay=isDayNow(),changed=nowDay!=day;
        if(homeHasResumed&&!changed&&appliedSky==skyPalette()){updateAmbient();return;}
        day=nowDay;applyTheme(changed);
    }

    /**
     * The veil over a wallpaper: at each stop the theme's usual scrim, or more where the photo needs it for the text
     * to stay readable (Legibility).
     */
    private int[] wallpaperScrim() {
        if (wallFit == null) return null;
        float[] need = day ? wallFit.forLight : wallFit.forDark;
        int n = Legibility.STOPS;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float y = i / (float) (n - 1);
            // The theme's own three-stop scrim at this height.
            int base = y <= 0.5f ? lerpColor(theme.scrimTop, theme.scrimMid, y * 2) : lerpColor(theme.scrimMid, theme.scrimBottom, (y - 0.5f) * 2);
            int a = Math.max(base >>> 24, Math.round(need[i] * 255));
            out[i] = (a << 24) | (base & 0x00FFFFFF);
        }
        return out;
    }

    private static int lerpColor(int a, int b, float t) {
        int out = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (a >>> sh) & 0xFF, y = (b >>> sh) & 0xFF;
            out |= Math.round(x + (y - x) * t) << sh;
        }
        return out;
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radiusDp));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(px(1), stroke);
        return g;
    }

    /** Icon buttons and tabs: their look depends on the theme and on whether they have focus. */
    private void styleTop() {
        for(int i=0;i<insightCards.size();i++)((HomeInsightCard)insightCards.get(i)).highlight(zone==Z_INSIGHTS&&i==insightIdx,theme.iconFocusBg);
        for (int i = 0; i < icons.length; i++) {
            boolean f = zone == Z_TOP && iconIdx == i;
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(f ? theme.iconFocusBg : theme.iconBg);
            if (!f) c.setStroke(px(1), theme.iconStroke);
            icons[i].setBackground(c);
            icons[i].setImageTintList(ColorStateList.valueOf(f ? theme.iconFocusFg : theme.text));
            icons[i].animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(150).start();
        }
        if (allTile != null) {
            boolean f = zone == Z_ALL;
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(f ? theme.iconFocusBg : theme.iconBg);
            if (!f) c.setStroke(px(1), theme.iconStroke);
            allTile.setBackground(c);
            ((ImageView) allTile).setImageTintList(ColorStateList.valueOf(f ? theme.iconFocusFg : theme.text));
            allTile.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(150).start();
        }
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == tabIdx, f = zone == Z_TABS && selected;
            tabs[i].setVisibility(picksFor(i).isEmpty() ? View.GONE : View.VISIBLE);
            tabs[i].setTextColor(f ? theme.iconFocusFg : selected ? theme.text : theme.text2);
            tabs[i].setFontVariationSettings(selected ? "'wght' 600" : "'wght' 400");
            tabs[i].setBackground(f ? rounded(theme.iconFocusBg, 0, 15) : null);
        }
        credit.setVisibility(!forYou || tabIdx == 0 || rowPicks.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * The weather, time and date always sit top left (the small line under the icons isn't used). For You shown: its
     * tabs and picks follow below them; hidden: just the clock, 1.6 times the size, with the dock below.
     */
    private void applyForYou() {
        forYouHead.setVisibility(forYou ? View.VISIBLE : View.GONE);
        info.setVisibility(View.GONE);
        float k = forYou || topInsights.getVisibility()==View.VISIBLE ? 1f : 1.6f;
        bigWeather.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12 * k);
        bigTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42 * k);
        bigAmPm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15 * k);
        bigDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14 * k);
        ViewGroup.MarginLayoutParams am = (ViewGroup.MarginLayoutParams) bigAmPm.getLayoutParams();
        am.bottomMargin = px(5 * k);
        ViewGroup.MarginLayoutParams rule = (ViewGroup.MarginLayoutParams) bigRule.getLayoutParams();
        rule.height = px(28 * k);
        rule.setMarginStart(px(16 * k));
        rule.setMarginEnd(px(16 * k));
        ((ViewGroup.MarginLayoutParams) bigClock.getLayoutParams()).topMargin = px(forYou ? 26 : 34);
        bigClock.requestLayout();
        styleTop();
    }

    /** "1:05 PM · Wed 21 Oct · 27° Showers", the time a little stronger. */
    private void updateInfo() {
        Calendar now = Calendar.getInstance();now.setTimeInMillis(timeOfDay.nowMillis());
        String time = DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", now).toString();
        SpannableStringBuilder b = new SpannableStringBuilder(time);
        b.setSpan(new ForegroundColorSpan(theme.text), 0, time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new StyleSpan(Typeface.BOLD), 0, time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append("  ·  ").append(DateFormat.format("EEE d MMM", now));
        if (weatherLine != null) b.append("  ·  ").append(weatherLine);
        info.setTextColor(theme.text2);
        info.setText(b);
        boolean h24 = DateFormat.is24HourFormat(this);
        bigTime.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        bigAmPm.setText(h24 ? "" : DateFormat.format("a", now));
        bigAmPm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        bigDate.setText(DateFormat.format("EEEE  d MMMM", now));
        String header=HomeOptions.header(this,timeOfDay.nowMillis(),weatherLine);
        bigWeather.setText(header);
        bigWeather.setCompoundDrawablePadding(px(7));
        bigWeather.setCompoundDrawables(HomeOptions.prefs(this).getBoolean("header_greeting",true)?Avatars.drawable(this,px(24)):null,null,null,null);meters.update();
        bigWeather.setVisibility(header.isEmpty()?View.GONE:View.VISIBLE);
        updateScore();
    }

    /** Control Center → Live scores: "both" (the default), "cricket", "football" or "off". */
    private String scores() { return prefs.getString("scores", "both"); }
    private List<Cricket.Match> cricketShown() { return scores().equals("both") || scores().equals("cricket") ? matches : new ArrayList<>(); }
    private List<Cricket.Match> footballShown() { return new ArrayList<>(); }

    /**
     * "● LIVE  IND 245/4 (42.3) v AUS" or "● LIVE  67'  ·  ARS 2–1 CHE", or today's next match, or today's result;
     * hidden when there's none. A live match wins, cricket before football.
     */
    private void updateScore() {
        List<Cricket.Match> c = cricketShown(), f = footballShown();
        Cricket.Match m = !c.isEmpty() && c.get(0).live ? c.get(0) : !f.isEmpty() && f.get(0).live ? f.get(0)
                : !c.isEmpty() ? c.get(0) : !f.isEmpty() ? f.get(0) : null;
        if (m == null) {
            score.setVisibility(View.GONE);
            credit.setAlpha(1f);
            return;
        }
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (m.football) {
            // League first, like cricket's match type: "● LIVE  Serie A · 52' · SAS 2–4 LAZ".
            String result = m.short1 + " " + m.s1 + "–" + m.s2 + " " + m.short2;
            if (m.live) {
                b.append("● LIVE  ");
                b.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.append(m.league).append("  ·  ").append(m.status).append("  ·  ").append(result);
            } else if (!m.ended) {
                b.append(m.league).append("  ·  ").append(m.short1).append(" v ").append(m.short2)
                        .append(m.start == null ? "" : "  ·  " + m.start);
            } else {
                b.append(m.league).append("  ·  ").append(result).append("  ·  ").append(m.status);
            }
        } else if (m.paused) {
            // "Test · AUS 329 v RSA · Day 1: Stumps": the scores, and why nobody's playing (before the " - " detail).
            String why = m.status.contains(" - ") ? m.status.substring(0, m.status.indexOf(" - ")) : m.status;
            if (!m.kind.isEmpty()) b.append(m.kind).append("  ·  ");
            b.append(m.t1).append(m.s1.isEmpty() ? "" : " " + m.s1).append("  v  ").append(m.t2).append(m.s2.isEmpty() ? "" : " " + m.s2)
                    .append("  ·  ").append(why);
        } else if (m.live) {
            b.append("● LIVE  ");
            b.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!m.kind.isEmpty()) b.append(m.kind).append("  ·  ");
            b.append(m.t1).append(m.s1.isEmpty() ? "" : " " + m.s1).append("  v  ").append(m.t2).append(m.s2.isEmpty() ? "" : " " + m.s2);
        } else if (!m.ended) {
            b.append(m.t1).append(" v ").append(m.t2).append(m.start == null ? "" : "  ·  " + m.start).append(m.kind.isEmpty() ? "" : "  ·  " + m.kind);
        } else {
            b.append(m.t1).append(" v ").append(m.t2).append("  ·  ").append(m.status);
        }
        score.setText(b);
        score.setTextColor(theme.text);
        score.setVisibility(View.VISIBLE);
        credit.setAlpha(0f);  // the score sits where the TMDB credit goes; TMDB is credited in the README too
    }

    private void onMatches(List<Cricket.Match> list) {
        matches = list;
        updateScore();
        showChannels(appChannels);  // while you're moving through the rows it waits for the remote to go still
    }

    private void onFormula(FormulaOne.State state) {
        if (isDestroyed()) return;
        formulaState = state;
        showChannels(appChannels);
    }

    private void onMinute() {
        updateInfo();
        refreshOnline();
        if (prefs.getBoolean("formula_one", true)||prefs.getBoolean("insights",true)) showChannels(appChannels);
        boolean nowDay = isDayNow();
        if(prefs.getBoolean(TimeOfDay.PREF,false))refreshTimeTheme();
        else if (nowDay != day) { day = nowDay; applyTheme(); }
        // New picks on the hour, but never while you're looking through them.
        if (Trending.localHour() != pickHour && zone != Z_PICKS && zone != Z_TABS && tabIdx != 0) buildPicksRow(0);
    }

    /** Appearance from Control Center: Auto (by sunrise and sunset), Light or Dark. */
    private static final String[] APPEARANCE = {"Auto", "Light", "Dark"};
    /** How All apps is arranged (Talkies settings → All apps order). The dock always keeps your order. */
    private static final String[] APPS_ORDER = {"Your order", "Recently opened", "A to Z"};
    private static final int ORDER_YOURS = 0, ORDER_RECENT = 1, ORDER_AZ = 2;
    /** RecentApps as of the last loadApps, so coming home re-sorts only when something new was opened. */
    private String recentSig = "";

    private TimeOfDay.Palette cachedSky,appliedSky;
    private boolean homeHasResumed;
    private long skyMinute=Long.MIN_VALUE;
    private String skyZone="";
    private TimeOfDay.Palette skyPalette() {
        long now=timeOfDay.nowMillis(),minute=Math.floorDiv(now,60_000);String zone=java.util.TimeZone.getDefault().getID();
        if(cachedSky==null||minute!=skyMinute||!zone.equals(skyZone)){
            cachedSky=TimeOfDay.palette(now,weather.sky(now));skyMinute=minute;skyZone=zone;
        }
        return cachedSky;
    }

    private boolean isDayNow() {
        if(prefs.getBoolean(TimeOfDay.PREF,false))return skyPalette().light;
        int mode = prefs.getInt("appearance", 0);
        // Auto with a wallpaper: the look the photo suits, so text stays readable on it.
        if (mode == 0 && wallFit != null) return wallFit.prefersLight();
        return mode == 1 || (mode == 0 && weather.isDay());
    }

    private void onWeather(String line) {
        cachedSky=null;
        weatherLine = line;
        if(prefs.getBoolean(TimeOfDay.PREF,false)){refreshTimeTheme();return;}
        boolean nowDay = isDayNow();
        if (nowDay != day) { day = nowDay; applyTheme(); } else updateInfo();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<AppEntry> all = new ArrayList<>();
        Set<String> seen = new HashSet<>(), hidden = hiddenApps();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || hidden.contains(p)) continue;
            Intent main = pm.getLeanbackLaunchIntentForPackage(p);
            // Some apps expose several TV entries (e.g. input shortcuts); keep the main one.
            if (main != null && main.getComponent() != null
                    && !main.getComponent().getClassName().equals(ri.activityInfo.name)) continue;
            if (!seen.add(p)) continue;
            all.add(new AppEntry(ri.activityInfo, String.valueOf(ri.loadLabel(pm)).trim()));
        }
        // Your arrangement (hold OK on an app to move it), else dock.xml; the dock fills up to its size.
        int dockSize = dockSize();
        List<String> dockPkgs = new ArrayList<>();
        String savedDock = prefs.getString("order_dock", null);
        if (savedDock != null) dockPkgs.addAll(Arrays.asList(savedDock.split(",")));
        for (String d : getResources().getStringArray(R.array.dock_packages)) if (!dockPkgs.contains(d)) dockPkgs.add(d);
        List<AppEntry> docked = new ArrayList<>();
        for (String p : dockPkgs) {
            if (docked.size() >= dockSize) break;
            for (AppEntry a : all) if (a.pkg.equals(p)) { docked.add(a); break; }
        }
        all.removeAll(docked);
        List<String> savedApps = Arrays.asList(prefs.getString("order_apps", "").split(","));
        Collections.sort(all, (a, b) -> {
            // Apps placed by you keep their spot; new ones go to the end, A–Z.
            int ia = savedApps.indexOf(a.pkg), ib = savedApps.indexOf(b.pkg);
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : a.label.compareToIgnoreCase(b.label);
        });
        while (docked.size() < dockSize && !all.isEmpty()) docked.add(all.remove(0));
        int order = appsOrder();
        List<String> recent = RecentApps.all(this);
        recentSig = String.join(",", recent);
        if (order == ORDER_RECENT) {
            // Apps you opened through Talkies, newest first; the ones you haven't opened yet follow, A–Z.
            Collections.sort(all, (a, b) -> {
                int ia = recent.indexOf(a.pkg), ib = recent.indexOf(b.pkg);
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                return ia != ib ? Integer.compare(ia, ib) : a.label.compareToIgnoreCase(b.label);
            });
        } else if (order == ORDER_AZ) {
            Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        }
        apps.clear();
        apps.addAll(docked);
        apps.addAll(all);
        dockCount = docked.size();
        Log.i(TAG, "apps=" + apps.size() + " dock=" + dockCount);
    }

    private int appsOrder() {
        int n = prefs.getInt("apps_order", ORDER_YOURS);
        return n < 0 || n >= APPS_ORDER.length ? ORDER_YOURS : n;
    }

    private int dockSize() {
        int n = prefs.getInt("dock_size", 7);
        return n < DOCK_SIZES[0] || n > DOCK_SIZES[DOCK_SIZES.length - 1] ? 7 : n;
    }

    private void reloadApps() {
        endMove(false);
        loadApps();
        buildAppViews();
        if (zone == Z_DOCK) focusDock(Math.min(dockIdx, dockCount - 1));
        else if (zone == Z_ALL) { if (allTile != null) focusAllTile(); else focusDock(dockCount - 1); }
        else if (zone == Z_GRID) focusGrid(Math.min(gridIdx, gridTiles.size() - 1));
    }

    /** One app tile: its banner (or icon) on a rounded card. */
    private View appTile(AppEntry app, float wDp, float hDp) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 11));
        final float r = px(11);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);
        Drawable banner = app.banner(pm);
        ImageView img = new ImageView(this);
        if (banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(banner);
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setImageDrawable(app.icon(pm));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(hDp * 0.6f), px(hDp * 0.6f));
            lp.gravity = android.view.Gravity.CENTER;
            card.addView(img, lp);
        }
        card.setContentDescription(app.label);
        card.setLayoutParams(new LinearLayout.LayoutParams(px(wDp), px(hDp)));
        return card;
    }

    /** After the dock: a round button like the icons at the top, a little larger. Its look is set in styleTop. */
    private View allAppsButton() {
        ImageView b = new ImageView(this);
        b.setImageResource(R.drawable.ic_apps);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription("All apps");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(ALL_BUTTON), px(ALL_BUTTON));
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    private void buildAppViews() {
        dock.removeAllViews();
        dockTiles.clear();
        // The dock's apps and, when any are left over, the round All apps button share the band's width: seven
        // apps are 106 dp wide, five 146, ten 73.
        boolean more = apps.size() > dockCount;
        int n = dockSize();
        float inner = getResources().getDisplayMetrics().widthPixels / dp - 40 - 36;
        float w = Math.min(DOCK_MAX_W, (inner - (more ? n * DOCK_GAP + ALL_BUTTON : (n - 1) * DOCK_GAP)) / n);
        float h = w * 9 / 16;
        for (int i = 0; i < dockCount; i++) {
            View t = appTile(apps.get(i), w, h);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.leftMargin = px(DOCK_GAP);
            dock.addView(t, lp);
            dockTiles.add(t);
        }
        allTile = more ? allAppsButton() : null;
        if (allTile != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) allTile.getLayoutParams();
            lp.leftMargin = px(DOCK_GAP);
            dock.addView(allTile, lp);
        }
        grid.removeAllViews();
        gridTiles.clear();
        LinearLayout row = null;
        for (int i = dockCount; i < apps.size(); i++) {
            int g = i - dockCount;
            if (g % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (g > 0) rl.topMargin = px(GRID_GAP_Y);
                grid.addView(row, rl);
            }
            View t = appTile(apps.get(i), GRID_TILE_W, GRID_TILE_H);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (g % GRID_COLS > 0) lp.leftMargin = px(GRID_GAP_X);
            row.addView(t, lp);
            gridTiles.add(t);
        }
        allAppsLabel.setVisibility(gridTiles.isEmpty() ? View.GONE : View.VISIBLE);
        layoutPage();
        focusedView = null;
    }

    /** Where All apps starts: after the channel rows. */
    private static boolean isFormula(Channels.Channel row) { return FormulaOne.KEY.equals(row.key); }
    private static boolean isInsights(Channels.Channel row){return HomeInsights.KEY.equals(row.key);}
    private static boolean isMosaic(Channels.Channel row){return isFormula(row);}
    private static int channelHeight(Channels.Channel row) { return isFormula(row) ? FormulaOneDashboard.SECTION_HEIGHT : Recommendations.isRow(row.key)?POSTER_H+100:CHAN_ROW_H; }
    private int channelOffset(int index) { int top=0;for(int i=0;i<index;i++)top+=channelHeight(chanRows.get(i));return top; }

    private int gridTopDp() { return CHAN_TOP + channelOffset(chanRows.size()) + 18 + 40; }

    /**
     * Places All apps under the channel rows and gives the page its full height (it is taller than the screen;
     * without this the parent caps it at the screen and everything below is clipped away).
     */
    private void layoutPage() {
        int gridTop = gridTopDp();
        ((ViewGroup.MarginLayoutParams) allAppsLabel.getLayoutParams()).topMargin = px(gridTop - 40);
        ((ViewGroup.MarginLayoutParams) grid.getLayoutParams()).topMargin = px(gridTop);
        int rows = (gridTiles.size() + GRID_COLS - 1) / GRID_COLS;
        ViewGroup.LayoutParams plp = page.getLayoutParams();
        plp.height = px(gridTop + rows * (GRID_TILE_H + GRID_GAP_Y) + 120);
        page.setLayoutParams(plp);
        allAppsLabel.requestLayout();
        grid.requestLayout();
    }

    // ---------------------------------------------------------------- channels

    /** Reloads the shown channels (and their artwork) in the background, then rebuilds the rows if anything changed. */
    private void refreshChannels() {
        List<Trending.Pick> watchNext = new ArrayList<>(watchNextPicks);
        background.execute(() -> {
            // The Watch Next row shows the same list as Continue: Watch Next plus anything played more recently.
            List<Channels.Channel> loaded = Channels.loadShown(this, continuePicks(RecentStore.load(this), watchNext));
            runOnUiThread(() -> {
                appChannels = loaded;
                showChannels(loaded);
            });
        });
    }

    /** The channel rows: Cricket and Football first while there are matches (Live scores), then your app channels. */
    private void showChannels(List<Channels.Channel> apps) {
        if(isDestroyed())return;
        final int generation=++channelsGeneration;
        pendingChannels=null;root.removeCallbacks(applyPreparedChannels);
        final boolean f1=prefs.getBoolean("formula_one",true),recommended=prefs.getBoolean("recommendations",true),releases=prefs.getBoolean("new_releases",true);
        final FormulaOne.State state=formulaState;
        final HomeInsights.State brief=insightState;final boolean showBrief=prefs.getBoolean("insights",true);
        final Set<String> contentLanguages=trending.languages();
        final List<Trending.Pick> catalog=ContentLanguages.filter(recommendationCatalog,contentLanguages);catalog.addAll(ContentLanguages.filter(trendPicks,contentLanguages));catalog.addAll(ContentLanguages.filter(indiaPicks,contentLanguages));
        final List<Trending.Pick> history=new ArrayList<>(recentPicks);
        final List<Channels.Channel> published=ContentLanguages.channels(apps,contentLanguages);
        final List<Cricket.Match> cricketRow=new ArrayList<>(cricketShown());
        channelWorker.execute(()->{
            if(generation!=channelsGeneration)return;
            List<Channels.Channel> list=new ArrayList<>();long now=System.currentTimeMillis();
            if(showBrief){Channels.Channel row=brief.forLocation(weather.widgetLocationKey()).row(this,now);if(!row.items.isEmpty())list.add(row);}
            if(f1)list.add(state.row(now,java.util.TimeZone.getDefault()));
            Channels.Channel suggestions=recommended||releases?Recommendations.build(catalog,history,now):new Channels.Channel();
            if(recommended&&!suggestions.items.isEmpty())list.add(suggestions);
            if(releases){Channels.Channel fresh=Recommendations.newReleases(catalog,recommended?suggestions:new Channels.Channel(),now);if(!fresh.items.isEmpty())list.add(fresh);}
            if(!cricketRow.isEmpty())list.add(scoresRow("cricket","Cricket",cricketRow));
            list.addAll(published);SectionOptions.filter(this,list);
            ChannelSnapshot prepared=new ChannelSnapshot(list);
            runOnUiThread(()->{
                if(isDestroyed()||generation!=channelsGeneration)return;
                pendingChannels=prepared;root.removeCallbacks(applyPreparedChannels);applyPreparedChannels.run();
            });
        });
    }

    /** Keep the current home usable while fresh data is ranked; install changed rows only after navigation rests. */
    private final Runnable applyPreparedChannels=()->{
        if(pendingChannels==null||!inFront)return;
        long now=android.os.SystemClock.uptimeMillis();
        long ready=Math.max(homeInteractiveAfter,lastKeyAt+NAV_IDLE_MS);
        if((!chanRows.isEmpty()||!insightCards.isEmpty())&&!refocusChannels&&now<ready){root.postDelayed(this.applyPreparedChannels,ready-now);return;}
        ChannelSnapshot prepared=pendingChannels;pendingChannels=null;
        if(prepared.signature.equals(chanSignature)){refocusChannels=false;return;}
        List<Channels.Channel> list=prepared.rows;
        boolean inRows=zone==Z_CHAN;
        String atRow=inRows&&chanIdx<chanRows.size()?chanRows.get(chanIdx).key:null,atCard=null;
        if(atRow!=null){List<Trending.Pick> items=chanRows.get(chanIdx).items;if(chanCol[chanIdx]<items.size())atCard=cardKey(items.get(chanCol[chanIdx]));}
        chanSignature=prepared.signature;buildChannelRows(list,prepared.signatures);
        if(inRows&&!chanRows.isEmpty()){
            int r=Math.min(chanIdx,chanRows.size()-1);
            for(int i=0;i<chanRows.size();i++)if(chanRows.get(i).key.equals(atRow))r=i;
            List<Trending.Pick> items=chanRows.get(r).items;int c=Math.min(chanCol[r],items.size()-1);
            for(int j=0;j<items.size();j++)if(cardKey(items.get(j)).equals(atCard))c=j;
            focusChan(r,c);
        }
        refocusChannels=false;
    };

    /** What a card shows, unchanged by a new score: the match, or the pick itself. */
    private static String cardKey(Trending.Pick p) {
        if(Recommendations.isRow(p.tab))return p.kind+":"+p.id;
        return p.extra instanceof Cricket.Match ? ((Cricket.Match) p.extra).id : String.valueOf(p.id);
    }

    private Channels.Channel scoresRow(String key, String title, List<Cricket.Match> list) {
        Channels.Channel c = new Channels.Channel();
        c.key = key;
        c.name = title;
        c.title = title;
        for (Cricket.Match m : list) {
            Trending.Pick p = new Trending.Pick();
            p.kind = key;
            p.extra = m;
            p.id = (m.id + m.s1 + m.s2 + m.status).hashCode();
            p.title = m.name;
            // A football match that hasn't started has no status yet: say when it starts instead.
            String st = m.status.isEmpty() && m.start != null ? "starts " + m.start : m.status;
            p.meta = m.kind.isEmpty() ? st : st.isEmpty() ? m.kind : m.kind + " · " + st;
            p.pkg = m.watchPkg;
            p.intentUri = m.watchUri;
            c.items.add(p);
        }
        return c;
    }

    private static boolean isScores(Channels.Channel ch) { return "cricket".equals(ch.key) || "football".equals(ch.key); }

    private void buildChannelRows(List<Channels.Channel> list,Map<String,String> signatures) {
        Channels.Channel widgets=null;List<Channels.Channel> below=new ArrayList<>();
        for(Channels.Channel row:list){if(isInsights(row))widgets=row;else below.add(row);}
        buildTopInsights(widgets,widgets==null?"":signatures.get(widgets.key));list=below;

        for(int i=0;i<chanRows.size()&&i<chanCol.length;i++){ChannelViews old=channelViews.get(chanRows.get(i).key);if(old!=null)old.col=chanCol[i];}
        Map<String,ChannelViews> next=new java.util.LinkedHashMap<>();
        chanScrolls.clear();chanSprings.clear();chanCards.clear();chanCol=new int[list.size()];
        for(int i=0;i<list.size();i++){
            Channels.Channel ch=list.get(i);String signature=signatures.get(ch.key)+(isScores(ch)?":"+theme.tileBg:"");
            ChannelViews old=channelViews.get(ch.key);int col=old==null?0:Math.max(0,Math.min(old.col,ch.items.size()-1));
            ChannelViews row=old!=null&&signature.equals(old.signature)?old:buildChannelRow(ch,col);
            row.signature=signature;row.col=col;next.put(ch.key,row);chanCol[i]=col;
            chanScrolls.add(row.scroll);chanSprings.add(row.spring);chanCards.add(row.cards);
        }
        for(Map.Entry<String,ChannelViews> entry:channelViews.entrySet())if(next.get(entry.getKey())!=entry.getValue()){
            cardArtwork.clear(entry.getValue().row);channelsBox.removeView(entry.getValue().row);
        }
        int index=0;
        for(ChannelViews row:next.values()){
            if(channelsBox.indexOfChild(row.row)!=index){if(row.row.getParent()==channelsBox)channelsBox.removeView(row.row);channelsBox.addView(row.row,index);}
            index++;
        }
        channelViews.clear();channelViews.putAll(next);chanRows=list;styleChannels();layoutPage();
        if(zone==Z_GRID)focusGrid(gridIdx);
    }

    private void buildTopInsights(Channels.Channel row,String signature){
        boolean visible=row!=null&&!row.items.isEmpty();String next=signature+":"+theme.tileBg;
        boolean wasVisible=topInsights.getVisibility()==View.VISIBLE;
        String selected=topInsightRow!=null&&insightIdx<topInsightRow.items.size()?((HomeInsights.Card)topInsightRow.items.get(insightIdx).extra).kind:"";
        topInsightRow=row;topInsights.setVisibility(visible?View.VISIBLE:View.GONE);
        if(wasVisible!=visible)applyForYou();
        if(!visible){root.removeCallbacks(rotateInsight);topInsights.removeAllViews();insightCards.clear();topInsightSignature=null;if(zone==Z_INSIGHTS)focusDock(dockIdx);return;}
        if(next.equals(topInsightSignature))return;topInsightSignature=next;topInsights.removeAllViews();insightCards.clear();
        for(int i=0;i<row.items.size();i++){
            HomeInsights.Card value=(HomeInsights.Card)row.items.get(i).extra;View card=channelCard(row.items.get(i));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px("air".equals(value.kind)?96:"news".equals(value.kind)?196:148),px("air".equals(value.kind)?32:36),Gravity.END|Gravity.CENTER_VERTICAL);topInsights.addView(card,lp);insightCards.add(card);
            if(selected.equals(((HomeInsights.Card)row.items.get(i).extra).kind))insightIdx=i;
        }
        showTopInsight(Math.min(insightIdx,insightCards.size()-1));if(zone==Z_INSIGHTS)focusInsights(insightIdx);scheduleInsightRotation();
    }
    private void showTopInsight(int index){
        if(insightCards.isEmpty())return;insightIdx=Math.floorMod(index,insightCards.size());
        for(int i=0;i<insightCards.size();i++)insightCards.get(i).setVisibility(i==insightIdx?View.VISIBLE:View.GONE);
    }
    private void scheduleInsightRotation(){
        root.removeCallbacks(rotateInsight);
        if(inFront&&insightCards.size()>1)root.postDelayed(rotateInsight,INSIGHT_ROTATION_MS);
    }
    private void rotateTopInsight(){
        if(inFront&&hasWindowFocus()&&zone!=Z_INSIGHTS&&zone!=Z_MENU&&!moving&&!insightCards.isEmpty())showTopInsight(insightIdx+1);
        scheduleInsightRotation();
    }
    private void focusInsights(int index){
        if(insightCards.isEmpty()){focusTop(iconIdx);return;}zone=Z_INSIGHTS;showTopInsight(index);scrollPage(0);setFocus(insightCards.get(insightIdx),1f,10);ring.animate().cancel();ring.setAlpha(0f);styleTop();updateAmbient();
    }
    private void openTopInsightsMenu(){
        final int index=insightIdx;beginMenu(P_CONTROL,"Header widgets");menuTile=insightCards.get(index);menuClosed=()->{if(!insightCards.isEmpty())focusInsights(Math.min(index,insightCards.size()-1));else focusDock(dockIdx);};
        addRow("Hide widgets from Home","Restore in Settings",()->{focusDock(dockIdx);setSectionVisibility(HomeInsights.KEY,false);},false);
        addRow("Widget settings",null,this::openInsightsSettings,false);showMenu(420);
    }

    private ChannelViews buildChannelRow(Channels.Channel ch,int col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(px(40), 0, px(40), 0);
        if (isScores(ch)) {
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextColor(0xFFFF453A);
            dot.setTextSize(12);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.rightMargin = px(8);
            if (!ch.items.isEmpty() && ((Cricket.Match) ch.items.get(0).extra).live) header.addView(dot, dl);
        } else if (ch.pkg != null) {
            ImageView icon = new ImageView(this);
            try {
                // App icons can be 512 px; keep a 20 dp copy.
                Drawable d = pm.getApplicationIcon(ch.pkg);
                Bitmap b = Bitmap.createBitmap(px(20), px(20), Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, px(20), px(20));
                d.draw(new android.graphics.Canvas(b));
                icon.setImageBitmap(b);
            } catch (PackageManager.NameNotFoundException ignored) { }
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(20), px(20));
            il.rightMargin = px(10);
            header.addView(icon, il);
        }
        TextView title = new TextView(this);
        title.setTag("chanTitle");
        title.setText(ch.title);
        title.setTextSize(16);
        title.setTypeface(getResources().getFont(R.font.outfit));
        title.setFontVariationSettings("'wght' 550");
        header.addView(title);
        if(Recommendations.isRow(ch.key)) {
            TextView source=new TextView(this);source.setTag("catalogSource");
            source.setText("TMDB · Availability: JustWatch");source.setTextSize(10);source.setTextColor(theme.text2);
            LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin=px(16);header.addView(source,sl);
        }
        row.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(28)));

        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setFocusable(false);
        hsv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(0, px(8), 0, px(8));
        List<View> cards = new ArrayList<>();
        int w = cardW(ch),h=Recommendations.isRow(ch.key)?POSTER_H:CH_H;
        if (isMosaic(ch)) {
            int mosaicHeight=FormulaOneDashboard.BODY_HEIGHT;
            FrameLayout mosaic = new FrameLayout(this);
            mosaic.setClipChildren(false); mosaic.setClipToPadding(false);
            mosaic.setLayoutParams(new FrameLayout.LayoutParams(px(960), px(mosaicHeight)));
            for (int i = 0; i < ch.items.size(); i++) {
                View card = channelCard(ch.items.get(i)); int[] cell = FormulaOneDashboard.cell(i);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(cell[2]), px(cell[3]));
                cl.leftMargin = px(cell[0]); cl.topMargin = px(cell[1]);
                mosaic.addView(card, cl); cards.add(card);
            }
            hsv.setPadding(0, 0, 0, 0); hsv.addView(mosaic);
            row.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(mosaicHeight)));
        } else {
            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL); track.setClipChildren(false);
            track.setPadding(px(40), 0, px(960), 0);
            for (int i = 0; i < ch.items.size(); i++) {
                View card = channelCard(ch.items.get(i));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(px(w), px(h));
                if (i > 0) cl.leftMargin = px(CH_GAP);
                track.addView(card, cl); cards.add(card);
            }
            hsv.addView(track);
            row.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(h + 16)));
        }

        LinearLayout caption = new LinearLayout(this);
        caption.setTag("caption");
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(px(42), px(6), px(40), 0);
        caption.setAlpha(0f);
        TextView name = new TextView(this);
        name.setTag("name");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(15);
        name.setTypeface(getResources().getFont(R.font.outfit));
        name.setFontVariationSettings("'wght' 600");
        caption.addView(name);
        TextView meta = new TextView(this);
        meta.setTag("meta");
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setTextSize(12.5f);
        meta.setTypeface(getResources().getFont(R.font.outfit));
        caption.addView(meta);
        row.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)));

        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(channelHeight(ch))));
        ChannelViews result=new ChannelViews();result.row=row;result.scroll=hsv;result.cards=cards;
        result.spring=new Spring(0.34f,1f,isMosaic(ch)?0:px(col*(w+CH_GAP)),0.25f);
        hsv.post(()->hsv.scrollTo(Math.round(result.spring.value),0));return result;
    }

    /** Cricket cards are wider: a Test's scores ("425/9d & 84/1 (25)") need the room. */
    private static int cardW(Channels.Channel ch) { return isScores(ch) ? CRICKET_W : Recommendations.isRow(ch.key)?POSTER_W:CH_W; }

    /** A card in a channel row: its artwork (loaded in the background), or the app's banner and the title. */
    private View channelCard(Trending.Pick p) {
        if(p.extra instanceof HomeInsights.Card)return new HomeInsightCard(this,(HomeInsights.Card)p.extra,theme.tileBg);
        if (p.extra instanceof FormulaOne.Card) return new FormulaOneCard(this,(FormulaOne.Card)p.extra);
        if (p.extra instanceof Cricket.Match) return cricketCard((Cricket.Match) p.extra);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 10));
        final float r = px(10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        boolean portrait=Recommendations.isRow(p.tab);
        File art=portrait&&ArtworkAssets.portrait(p.poster)?p.poster:p.image;
        boolean portraitFallback=art==null&&ArtworkAssets.portrait(p.poster);if(portraitFallback){art=p.poster;img.setScaleType(ImageView.ScaleType.FIT_CENTER);}
        if(portrait&&art==p.image)img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (art != null && art.isFile()) {
            cardArtwork.bind(img,art,px((portrait?POSTER_W:CH_W)*1.08f),
                    px((portrait?POSTER_H:CH_H)*1.08f),!portraitFallback&&!(portrait&&art==p.image),portrait||portraitFallback||"watchnext".equals(p.kind)||"recent".equals(p.kind));
        } else if (p.pkg != null) {
            showAppLogo(card, img, p.pkg);
        }
        if (p.progress >= 0) {
            View fill = new View(this);
            fill.setBackground(rounded(0xFFFFFFFF, 0, 2));
            FrameLayout track = new FrameLayout(this);
            track.setBackground(rounded(0x4DFFFFFF, 0, 2));
            track.addView(fill, new FrameLayout.LayoutParams(Math.round(px(CH_W - 24) * Math.max(0.02f, p.progress)), ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(px(CH_W - 24), px(3));
            tl.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            tl.bottomMargin = px(8);
            card.addView(track, tl);
        }
        if(Recommendations.isRow(p.tab)) {
            LinearLayout caption=new LinearLayout(this);caption.setOrientation(LinearLayout.VERTICAL);
            caption.setPadding(px(10),px(18),px(10),px(8));
            caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xF0000000}));
            TextView label=new TextView(this);label.setText(p.title);label.setTextColor(Color.WHITE);label.setTextSize(12);
            label.setTypeface(getResources().getFont(R.font.outfit));label.setFontVariationSettings("'wght' 600");
            label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);caption.addView(label);
            TextView service=new TextView(this);service.setText(p.service);service.setTextColor(0xDDFFFFFF);service.setTextSize(10);caption.addView(service);
            card.addView(caption,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,android.view.Gravity.BOTTOM));
        }
        card.setContentDescription(p.title);
        return card;
    }

    /** A match card: each team's flag, short name and score, then the status ("India need 38 off 24"). */
    private View cricketCard(Cricket.Match m) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 10));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(14), px(12), px(14), px(10));
        col.addView(teamLine(m.t1, m.s1, m.img1));
        col.addView(teamLine(m.t2, m.s2, m.img2));
        TextView st = new TextView(this);
        st.setTag("cricketText");
        String kind = m.kind.isEmpty() ? "" : m.kind + "  ·  ";
        String status = m.live ? "LIVE  ·  " + kind + m.status : !m.ended && m.start != null ? kind + "starts " + m.start : kind + m.status;
        SpannableStringBuilder sb = new SpannableStringBuilder(status);
        if (m.live) sb.setSpan(new ForegroundColorSpan(0xFFFF453A), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        st.setText(sb);
        st.setSingleLine(true);
        st.setEllipsize(android.text.TextUtils.TruncateAt.END);
        st.setTextSize(12);
        st.setTypeface(getResources().getFont(R.font.outfit));
        st.setTextColor(theme.text2);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        st.setGravity(android.view.Gravity.BOTTOM);
        col.addView(st, sl);
        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setContentDescription(m.name + ". " + m.status);
        return card;
    }

    private View teamLine(String team, String runs, String flagUrl) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView flag = new ImageView(this);
        flag.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (flagUrl != null && !flagUrl.isEmpty()) {
            background.execute(() -> {
                File f = WatchNext.cached(this, new File(getExternalFilesDir(null), "cricket/flags"), flagUrl, 64);
                Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
                if (b != null) runOnUiThread(() -> flag.setImageBitmap(b));
            });
        }
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(px(20), px(20));
        fl.rightMargin = px(8);
        line.addView(flag, fl);
        TextView name = new TextView(this);
        name.setTag("cricketText");
        name.setText(team);
        name.setTextSize(15);
        name.setTypeface(getResources().getFont(R.font.outfit));
        name.setFontVariationSettings("'wght' 600");
        name.setTextColor(theme.text);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView sc = new TextView(this);
        sc.setTag("cricketText");
        sc.setText(runs);
        sc.setSingleLine(true);
        sc.setTextSize(14);
        sc.setTypeface(getResources().getFont(R.font.outfit));
        sc.setFontVariationSettings("'wght' 500");
        sc.setTextColor(theme.text);
        line.addView(sc);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(26));
        line.setLayoutParams(ll);
        return line;
    }

    private void styleChannels() {
        for (int i = 0; i < channelsBox.getChildCount(); i++) {
            View row = channelsBox.getChildAt(i);
            TextView t = row.findViewWithTag("chanTitle"), n = row.findViewWithTag("name"), m = row.findViewWithTag("meta");
            TextView source=row.findViewWithTag("catalogSource");if(source!=null)source.setTextColor(theme.text2);
            if (t != null) t.setTextColor(theme.text);
            if (n != null) n.setTextColor(theme.text);
            if (m != null) m.setTextColor(theme.text2);
        }
    }

    // ---------------------------------------------------------------- picks

    private void loadPicks() {
        recommendationCatalog=trending.cached();
        if(watchNextPicks.isEmpty())watchNextPicks=AppleResumeCache.restore(this,System.currentTimeMillis());
        recentPicks = continuePicks(RecentStore.load(this), watchNextPicks);
        trendPicks = trending.cached(Trending.TRENDING);
        indiaPicks = trending.cached(Trending.INDIA);
    }

    private List<Trending.Pick> picksFor(int t) { return t == 0 ? recentPicks : t == 1 ? trendPicks : indiaPicks; }

    /**
     * Continue: what apps put on Android's Watch Next list (Netflix, Prime Video…, opening the right episode) plus what
     * other apps reported playing, newest first. First the newest title from each app (so one app can't take over the
     * front of the row), then the rest in order, until there are at least ten cards (up to sixteen).
     */
    private List<Trending.Pick> continuePicks(List<Trending.Pick> sessions, List<Trending.Pick> watchNext) {
        // Titles you removed (hold OK → Remove from Continue) stay out until the app reports a new episode.
        Set<String> hidden = prefs.getStringSet("hidden_continue", java.util.Collections.emptySet());
        List<Trending.Pick> wnShown = new ArrayList<>();
        for (Trending.Pick p : watchNext) if (ContinueOptions.include(this,p.pkg) && !hidden.contains(continueKey(p))) wnShown.add(p);
        watchNext = wnShown;
        // An app's Watch Next entries (they open the right episode) win over what we saw it play, unless it played
        // something more recently than its newest entry: JioHotstar, for one, doesn't always update Watch Next.
        Map<String, Long> newest = new java.util.HashMap<>();
        for (Trending.Pick p : watchNext) newest.merge(p.pkg, p.at, Math::max);
        List<Trending.Pick> all = new ArrayList<>(watchNext);
        for (Trending.Pick p : sessions) {
            if(!ContinueOptions.include(this,p.pkg))continue;
            Long wn = newest.get(p.pkg);
            if (wn == null || p.at > wn) all.add(p);
        }
        Collections.sort(all, (a, b) -> Long.compare(b.at, a.at));
        // YouTube fills up fastest (every video you play), so it gets at most two cards.
        List<Trending.Pick> capped = new ArrayList<>();
        int youtube = 0;
        for (Trending.Pick p : all) {
            if ("com.google.android.youtube.tv".equals(p.pkg) && ++youtube > MAX_YOUTUBE) continue;
            capped.add(p);
        }
        List<Trending.Pick> out = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Trending.Pick p : capped) (seen.add(p.pkg) ? out : rest).add(p);
        for (Trending.Pick p : rest) if (out.size() < MIN_CONTINUE) out.add(p);
        return out.size() > MAX_CONTINUE ? new ArrayList<>(out.subList(0, MAX_CONTINUE)) : out;
    }

    /**
     * The TMDB tabs start somewhere new each hour, so the large card changes through the day: 24 picks moving on by
     * 4 an hour come round every 6 hours (when the list is refreshed anyway). The row shows 12 of them, which keeps
     * the posters in memory the same as before.
     */
    private static List<Trending.Pick> rotated(List<Trending.Pick> all) {
        if (all.isEmpty()) return all;
        int start = (int) ((Trending.localHour() * Trending.SHOWN) % all.size());
        List<Trending.Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.min(ROW_PICKS, all.size()); i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    private static final int ROW_PICKS = 12;

    private void buildPicksRow(int anchor) {
        titleCoverGeneration++;
        cardArtwork.clear(picksRow);
        pickHour = Trending.localHour();
        if (!forYou) {
            // Hidden: no cards (so focus skips the section), no pick behind the background.
            rowPicks = new ArrayList<>();
            picksRow.setItems(new ArrayList<>(), 0);
            picksRow.setVisibility(View.GONE);
            showPicksHint();
            styleTop();
            updateAmbient();
            return;
        }
        if (picksFor(tabIdx).isEmpty()) {
            for (int t = 0; t < TAB_IDS.length; t++) if (!picksFor(t).isEmpty()) { tabIdx = t; break; }
        }
        rowPicks = tabIdx == 0 ? picksFor(0) : rotated(picksFor(tabIdx));
        StringBuilder from = new StringBuilder();
        if (tabIdx == 0) for (Trending.Pick p : rowPicks) from.append(' ').append(p.pkg.substring(p.pkg.lastIndexOf('.') + 1));
        Log.i(TAG, "row " + TAB_IDS[tabIdx] + ": " + rowPicks.size() + " cards" + from);
        List<View> views = new ArrayList<>();
        for (Trending.Pick p : rowPicks) views.add(pickView(p));
        picksRow.setItems(views, anchor);
        updatePickImages(picksRow.anchor());
        requestTitleCovers();
        picksRow.setVisibility(rowPicks.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        showPicksHint();
        styleTop();
        updateAmbient();
    }

    /** Nothing to show yet: say why, rather than leave a gap. Without a TMDB key only Continue ever fills. */
    private void showPicksHint() {
        boolean show = forYou && rowPicks.isEmpty();
        picksHint.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        picksHint.setText(Keys.read(this, Keys.TMDB) == null
                ? "Trending and new picks need a free TMDB key: Control Center → Sweep TV settings → Keys and location"
                : trending.matchedApps() == 0
                ? "Trending shows what's on your streaming apps, and none Sweep TV knows is installed. With YouTube installed, it shows trailers instead."
                : !trending.languages().isEmpty()
                ? "Nothing in " + languagesLabel() + " on your apps here right now. More languages: Sweep TV settings → Content languages."
                : "Picks appear here once Sweep TV is online. Shows you watch show up under Continue.");
    }

    /** A pick: the image card (with a progress bar when the app shares one), its title, and a description line. */
    private View pickView(Trending.Pick p) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setClipChildren(false);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 14));
        final float r = px(14);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        card.setClipToOutline(true);
        // Two layers: the landscape picture (large card) and the poster on top (small cards); see updatePickImages.
        ImageView back = new ImageView(this);
        back.setTag("backdrop");
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if ((p.image == null || !p.image.isFile())&&!ArtworkAssets.portrait(p.poster)) showAppLogo(card, back, p.pkg);
        card.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView poster = new ImageView(this);
        poster.setTag("poster");
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setAlpha(0f);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (p.progress >= 0) {
            FrameLayout track = new FrameLayout(this);
            track.setBackground(rounded(0x4DFFFFFF, 0, 2));
            View fill = new View(this);
            fill.setBackground(rounded(0xFFFFFFFF, 0, 2));
            fill.setPivotX(0f);
            fill.setScaleX(Math.max(0.02f,p.progress));
            track.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4));
            tl.gravity = android.view.Gravity.BOTTOM;
            tl.setMargins(px(14), 0, px(14), px(12));
            card.addView(track, tl);
        }
        item.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(PicksRow.CARD_H)));
        TextView name = new TextView(this);
        name.setTag("name");
        name.setText(p.title);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(16);
        name.setTypeface(getResources().getFont(R.font.outfit));
        name.setFontVariationSettings("'wght' 600");
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = px(9);
        nl.leftMargin = px(2);
        item.addView(name, nl);
        TextView meta = new TextView(this);
        meta.setTag("meta");
        meta.setText(p.displayMeta());
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setTextSize(12.5f);
        meta.setTypeface(getResources().getFont(R.font.outfit));
        meta.setFontVariationSettings("'wght' 400");
        meta.setAlpha(0f);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = px(2);
        ml.leftMargin = px(2);
        item.addView(meta, ml);
        stylePickText(item);
        return item;
    }

    /**
     * Loads each pick card's picture for its size: the large card gets the full landscape picture (TMDB's 1280 px
     * backdrop); small cards show the portrait poster, or a half-size landscape picture when there is no poster.
     * Pictures a card no longer needs are released, so only one full-size picture is ever in memory.
     */
    private void updatePickImages(int anchor) {
        for(int i=0;i<picksRow.getChildCount()&&i<rowPicks.size();i++)updatePickImage(i,anchor);
    }
    private void updatePickImage(int i,int anchor) {
            View item = picksRow.getChildAt(i);
            Trending.Pick p = rowPicks.get(i);
            ImageView back = item.findViewWithTag("backdrop"), poster = item.findViewWithTag("poster");
            if (back == null || poster == null) return;
            boolean discovery=TitleCovers.discovery(p);TitleCovers.Result cover=discovery?titleCovers.get(TitleCovers.key(p)):null;
            File landscape=discovery?(cover==null?null:cover.image):p.image;
            boolean large=i==anchor,hasLandscape=ArtworkAssets.landscape(landscape),hasPoster=ArtworkAssets.portrait(p.poster);
            boolean usePoster=hasPoster&&(!large||!hasLandscape);
            poster.setScaleType(large?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
            if(usePoster)cardArtwork.bind(poster,p.poster,px((large?PicksRow.LARGE_W:PicksRow.SMALL_W)*1.02f),px(PicksRow.CARD_H*1.02f),!large,true);
            poster.animate().alpha(usePoster?1f:0f).setDuration(250).start();
            if(!hasLandscape)return;
            boolean resume="watchnext".equals(p.kind)||"recent".equals(p.kind);
            boolean titleCover=discovery||resume&&ContinueOptions.titleArtwork(this);
            boolean crop=!titleCover&&(large||!resume);
            back.setScaleType(crop?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER);
            if(large||!usePoster)cardArtwork.bind(back,landscape,px((large?PicksRow.LARGE_W:PicksRow.SMALL_W)*1.02f),px(PicksRow.CARD_H*1.02f),crop,resume||discovery);
            else {
                // Covered by the poster: let it go once the fade is done.
                final ImageView b = back;
                back.postDelayed(() -> {
                    if (b.getAlpha() >= 0 && rowPicks.indexOf(p) != picksRow.anchor() && poster.getAlpha() > 0.99f) {
                        b.setImageDrawable(null);
                        b.setTag(R.id.img_state, null);
                    }
                }, 400);
            }
    }

    private void requestTitleCovers(){
        final int generation=++titleCoverGeneration;List<Trending.Pick> requested=new ArrayList<>(rowPicks);
        if(!inFront||requested.isEmpty()||!TitleCovers.discovery(requested.get(0))||Keys.read(this,Keys.TMDB)==null)return;
        titleCoverWorker.execute(()->{
            for(Trending.Pick p:requested){
                if(isDestroyed()||generation!=titleCoverGeneration||Thread.currentThread().isInterrupted())return;
                TitleCovers.Result cover=TitleCovers.resolve(this,p);
                runOnUiThread(()->{if(isDestroyed()||generation!=titleCoverGeneration)return;titleCovers.put(TitleCovers.key(p),cover);int i=rowPicks.indexOf(p);if(i>=0&&i<picksRow.getChildCount())updatePickImage(i,picksRow.anchor());});
            }
        });
    }

    /**
     * A card with no picture (JioHotstar shares none while playing): the app's colour, with its logo at its own sharp
     * size in the middle, instead of the small banner stretched across the card.
     */
    private void showAppLogo(View card, ImageView v, String pkg) {
        AppEntry a = appFor(pkg);
        Drawable logo = a != null ? a.banner(pm) : null;
        if (logo == null) {
            try { logo = pm.getApplicationBanner(pkg); } catch (PackageManager.NameNotFoundException e) { return; }
        }
        int c = a != null ? a.glowColor(pm) : 0xFF3A3A3C;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(c, 0xFF000000, 0.45f), blend(c, 0xFF000000, 0.8f)});
        g.setCornerRadius(px(14));
        card.setBackground(g);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImageDrawable(logo);
        // The logo takes about half the card's width, whatever the card's size.
        v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            int padX = Math.round((r - l) * 0.24f), padY = Math.round((b - t) * 0.22f);
            if (view.getPaddingLeft() != padX || view.getPaddingTop() != padY) view.post(() -> view.setPadding(padX, padY, padX, padY));
        });
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Decode at the card's display size without waiting for provider or network refreshes. */
    private void loadInto(ImageView v, File file, int sample) {
        cardArtwork.bind(v,file,px(sample==1?PicksRow.LARGE_W*1.02f:PicksRow.SMALL_W*1.02f),
                px(PicksRow.CARD_H*1.02f),true,false);
    }

    private void stylePickText(View item) {
        TextView name = item.findViewWithTag("name"), meta = item.findViewWithTag("meta");
        if (name != null) name.setTextColor(theme.text);
        if (meta != null) meta.setTextColor(theme.text2);
    }

    /**
     * The background glows in the colour of the focused item's app: the dock or All apps tile, the pick's app, or the
     * channel row's app (Netflix red, Prime Video blue…). It keeps the last colour on the icons and in menus, and for
     * picks from apps that aren't installed. Your own wallpaper replaces it.
     */
    private void updateAmbient() {
        if (hasWallpaper) return;
        if(prefs.getBoolean(TimeOfDay.PREF,false)) {
            TimeOfDay.Palette p=skyPalette();
            backdrop.setTimeColors(p.top,p.mid,p.bottom);return;
        }
        backdrop.clearTimeColors();
        AppEntry a = null;
        if (zone == Z_DOCK && dockIdx < apps.size()) a = apps.get(dockIdx);
        else if (zone == Z_GRID && dockCount + gridIdx < apps.size()) a = apps.get(dockCount + gridIdx);
        else if ((zone == Z_PICKS || zone == Z_TABS) && !rowPicks.isEmpty())
            a = appFor(rowPicks.get(Math.min(picksRow.anchor(), rowPicks.size() - 1)).pkg);
        else if (zone == Z_CHAN && chanIdx < chanRows.size()) {
            List<Trending.Pick> items = chanRows.get(chanIdx).items;
            if (!items.isEmpty()) a = appFor(items.get(Math.min(chanCol[chanIdx], items.size() - 1)).pkg);
        }
        if (a != null) backdrop.setGlow(a.glowColor(pm));
    }

    private AppEntry appFor(String pkg) {
        if (pkg != null) for (AppEntry a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void refreshWatchNext() {
        final int request=++continueGeneration;
        background.execute(() -> {
            List<Trending.Pick> list = WatchNext.load(this);
            List<Trending.Pick> sessions=RecentStore.load(this);
            TitleArtwork.resolveRecent(this,sessions);
            runOnUiThread(() -> {
                if(request!=continueGeneration||isDestroyed())return;
                watchNextPicks = list;
                refreshChannels();
                List<Trending.Pick> merged = continuePicks(sessions, watchNextPicks);
                if (!sameKeys(recentPicks, merged)) {
                    boolean updateFocused = tabIdx==0 && zone==Z_PICKS && sameOrder(recentPicks,merged);
                    int anchor=picksRow.anchor();
                    recentPicks = merged;
                    if(updateFocused) {buildPicksRow(anchor);focusPicks(anchor);return;}
                    // Also when the row is empty: on a new TV it waits on Trending, which may never fill (no key).
                    if ((tabIdx == 0 || rowPicks.isEmpty()) && zone != Z_PICKS) buildPicksRow(0);
                    else styleTop();
                }
            });
        });
    }

    private static boolean sameKeys(List<Trending.Pick> a, List<Trending.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Trending.Pick first=a.get(i),second=b.get(i);
            if (!first.key().equals(second.key()) || !java.util.Objects.equals(first.image,second.image)
                    || !java.util.Objects.equals(first.poster,second.poster) || first.progress!=second.progress||first.savedResume!=second.savedResume)return false;
        }
        return true;
    }

    private static boolean sameOrder(List<Trending.Pick> a,List<Trending.Pick> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).key().equals(b.get(i).key()))return false;
        return true;
    }

    private void onPicks(List<Trending.Pick> all) {
        recommendationCatalog=ContentLanguages.filter(all,trending.languages());
        trendPicks = trending.cached(Trending.TRENDING);
        indiaPicks = trending.cached(Trending.INDIA);
        showChannels(appChannels);
        if (zone != Z_PICKS && zone != Z_TABS) { tabIdx = TAB_TRENDING; buildPicksRow(0); } else styleTop();
    }

    /** The section opens on Trending; if it had to fall back (no picks yet), switch back once they exist. */
    private void backToTrending() {
        if (tabIdx != TAB_TRENDING && !trendPicks.isEmpty() && zone != Z_PICKS && zone != Z_TABS) {
            tabIdx = TAB_TRENDING;
            buildPicksRow(0);
        } else styleTop();
    }

    // ---------------------------------------------------------------- focus

    /** Grows the focused card or tile (and shrinks the previous one); the ring sits on it. */
    private void setFocus(View v, float scale, float radiusDp) {
        View old = focusedView;
        if (old != null && old != v) scaleTo(old, 1f);
        focusedView = v;
        ringRadius = px(radiusDp);
        ring.animate().alpha(v == null ? 0f : 1f).setDuration(150).start();
        if (v == null) return;
        scaleTo(v, scale);
        snapRing();
    }

    private void scaleTo(View v, float scale) {
        Spring sp = scaleSprings.get(v);
        if (sp == null) scaleSprings.put(v, sp = new Spring(0.34f, 0.74f, v.getScaleX()));
        sp.target = scale;
        startMotion();
    }

    private RectF rectOf(View v) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        ring.getLocationInWindow(b);
        float l = a[0] - b[0], t = a[1] - b[1];
        tmp.set(l, t, l + v.getWidth() * v.getScaleX(), t + v.getHeight() * v.getScaleY());
        return tmp;
    }

    /** Puts the ring on the focused card, at its current scale. Also called on PicksRow's frames. */
    private void snapRing() {
        if (focusedView == null) return;
        ringNow.set(rectOf(focusedView));
        ring.setRing(ringNow, ringRadius * focusedView.getScaleX());
    }

    /** Keeps the ring on its card for a while (PicksRow and the captions move on their own). */
    private void followRing(long ms) {
        followUntil = Math.max(followUntil, System.nanoTime() + ms * 1_000_000L);
        startMotion();
    }

    private void startMotion() {
        if (motionPosted) return;
        motionPosted = true;
        motionNanos = 0;
        android.view.Choreographer.getInstance().postFrameCallback(this::doMotion);
    }

    /** One frame: steps every spring and applies them. Stops posting itself once all have settled. */
    private void doMotion(long now) {
        float dt = motionNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - motionNanos) / 1e9f);
        motionNanos = now;
        boolean moving = pageSpring.step(dt);
        page.setTranslationY(-pageSpring.value);
        for (int i = 0; i < chanSprings.size() && i < chanScrolls.size(); i++) {
            Spring sp = chanSprings.get(i);
            if (!sp.step(dt) && chanScrolls.get(i).getScrollX() == Math.round(sp.value)) continue;
            moving = true;
            chanScrolls.get(i).scrollTo(Math.round(sp.value), 0);
        }
        for (java.util.Iterator<Map.Entry<View, Spring>> it = scaleSprings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, Spring> e = it.next();
            Spring sp = e.getValue();
            boolean m = sp.step(dt);
            e.getKey().setScaleX(sp.value);
            e.getKey().setScaleY(sp.value);
            if (m) moving = true;
            else if (sp.target == 1f) it.remove();
        }
        snapRing();
        if (moving || now < followUntil) android.view.Choreographer.getInstance().postFrameCallback(this::doMotion);
        else motionPosted = false;
    }

    /** Scrolls the page: the grid rows come up from below the fold; everything else sits at the top. */
    private void scrollPage(float y) {
        if (Math.abs(y - pageSpring.target) < 1) return;
        pageSpring.target = y;
        startMotion();
    }

    private void focusTop(int i) {
        zone = Z_TOP;
        iconIdx = i;
        scrollPage(0);
        setFocus(null, 1f, 0);
        styleTop();
    }

    private void focusTabs(int t) {
        zone = Z_TABS;
        scrollPage(0);
        if (t != tabIdx) {
            tabIdx = t;
            buildPicksRow(0);
        }
        setFocus(null, 1f, 0);
        styleTop();
    }

    private void focusPicks(int i) {
        if (rowPicks.isEmpty()) return;
        zone = Z_PICKS;
        scrollPage(0);
        boolean moved = i != picksRow.anchor();
        picksRow.setAnchor(i, moved);
        updatePickImages(i);
        updateAmbient();
        setFocus(picksRow.anchorCard(), 1.02f, 14);
        styleTop();
        followRing(moved ? 360 : 220);
    }

    private void focusDock(int i) {
        if (dockTiles.isEmpty()) return;
        zone = Z_DOCK;
        dockIdx = Math.max(0, Math.min(i, dockTiles.size() - 1));
        prefs.edit().putInt("last_dock_col", dockIdx).apply();
        scrollPage(0);
        setFocus(dockTiles.get(dockIdx), moving ? 1.22f : 1.14f, 11);
        updateAmbient();
        styleTop();
        followRing(260);
    }

    private void focusAllTile() {
        zone = Z_ALL;
        scrollPage(0);
        setFocus(null, 1f, 0);  // no ring: the button fills in, like the icons at the top (styleTop)
        updateAmbient();
        styleTop();
    }

    private void focusGrid(int i) {
        if (gridTiles.isEmpty()) return;
        zone = Z_GRID;
        gridIdx = Math.max(0, Math.min(i, gridTiles.size() - 1));
        int row = gridIdx / GRID_COLS;
        int gridTop = gridTopDp();
        float rowTop = gridTop + row * (GRID_TILE_H + GRID_GAP_Y);
        scrollPage(px(Math.max(gridTop - 96, rowTop - 190)));
        setFocus(gridTiles.get(gridIdx), moving ? 1.18f : 1.10f, 11);
        updateAmbient();
        styleTop();
        followRing(420);
    }

    private void focusChan(int r, int c) {
        if (chanRows.isEmpty()) return;
        zone = Z_CHAN;
        chanIdx = Math.max(0, Math.min(r, chanRows.size() - 1));
        List<Trending.Pick> items = chanRows.get(chanIdx).items;
        int col = Math.max(0, Math.min(c, items.size() - 1));
        chanCol[chanIdx] = col;
        scrollPage(px(CHAN_TOP + channelOffset(chanIdx) - 64));
        // Slide the row so the focused card sits at the left, like Projectivy.
        if (chanIdx < chanSprings.size()) chanSprings.get(chanIdx).target = isMosaic(chanRows.get(chanIdx)) ? 0 : px(col * (cardW(chanRows.get(chanIdx)) + CH_GAP));
        startMotion();
        for (int i = 0; i < channelsBox.getChildCount(); i++) {
            View caption = channelsBox.getChildAt(i).findViewWithTag("caption");
            if (caption == null) continue;
            if (i == chanIdx) {
                Trending.Pick p = items.get(col);
                ((TextView) caption.findViewWithTag("name")).setText(p.title);
                ((TextView) caption.findViewWithTag("meta")).setText(p.displayMeta());
            }
            caption.animate().alpha(i == chanIdx ? 1f : 0f).setDuration(160).start();
        }
        setFocus(chanCards.get(chanIdx).get(col), isMosaic(chanRows.get(chanIdx)) ? 1.025f : 1.06f, isMosaic(chanRows.get(chanIdx)) ? 15 : 10);
        updateAmbient();
        styleTop();
        followRing(440);
    }

    /** Dock column (0–6) → grid column (0–5) and back, by position across the screen. */
    private int dockToGridCol(int c) { return Math.round(c * (GRID_COLS - 1) / (float) Math.max(1, dockCount - 1)); }

    private int gridToDockCol(int c) { return Math.round(c * Math.max(0, dockCount - 1) / (float) (GRID_COLS - 1)); }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        if (!select && !nav && !back) return super.dispatchKeyEvent(e);
        lastKeyAt = android.os.SystemClock.uptimeMillis();
        // OK and Back act when let go, so a release whose press went to another app must do nothing here: an app
        // that closes as OK goes down (Netflix → Exit) would otherwise have its OK's release open the focused tile,
        // the same app, again.
        if ((select || back) && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) pressedHere = k;
        if ((select || back) && e.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = pressedHere == k;
            pressedHere = 0;
            if (!ours) return true;
        }
        boolean onApp = zone == Z_INSIGHTS || zone == Z_DOCK || zone == Z_GRID || zone == Z_CHAN || zone == Z_PICKS || zone == Z_TABS
                || (zone == Z_MENU && menuMode == MENU_CHANNELS)
                || continueCard() != null || (zone == Z_TOP && iconIdx == P_SETTINGS);
        // Holding OK on an app opens its menu, on a Continue card the card's menu, and in the Channels editor
        // picks the row up (a timer, or the remote's long-press signal, whichever comes first).
        if (select && e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                holdFired = false;
                if (!moving && !menuMoving && onApp) root.postDelayed(holdTimer, HOLD_MS);
            } else if (!holdFired && !moving && !menuMoving && onApp) {
                root.removeCallbacks(holdTimer);
                onHold();
            }
        }
        if (select && e.getAction() == KeyEvent.ACTION_UP) {
            root.removeCallbacks(holdTimer);
            if (holdFired) { holdFired = false; return true; }  // this release just ends the hold
        }
        if (nav && e.getAction() == KeyEvent.ACTION_DOWN) {
            heldKey = e.getRepeatCount() > 0;
            String before = focusSignature();
            if (moving) moveApp(k); else if (menuMoving) moveMenuRow(k); else move(k);
            if (!before.equals(focusSignature())) navigationSound(k);
        }
        if ((select || back) && e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            if (moving) endMove(select);
            else if (menuMoving) endMenuMove(select);
            else if (select) select();
            else back();
        }
        return true;
    }

    private int nextTab(int t, int step) {
        for (int i = t + step; i >= 0 && i < TAB_IDS.length; i += step) if (!picksFor(i).isEmpty()) return i;
        return -1;
    }

    private void move(int k) {
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT,
                up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean hasPicks = !rowPicks.isEmpty();
        switch (zone) {
            case Z_MENU:
                if(settingsGrid){
                    int next=menuIdx;
                    if(left&&menuIdx%settingsColumns>0)next--;
                    else if(right&&menuIdx%settingsColumns<settingsColumns-1&&menuIdx+1<menuItems.size())next++;
                    else if(up&&menuIdx>=settingsColumns)next-=settingsColumns;
                    else if(down&&menuIdx+settingsColumns<menuItems.size())next+=settingsColumns;
                    if(next!=menuIdx)focusMenu(next);
                    break;
                }
                if(settingsPopupMode&&!menuItems.isEmpty()&&menuScroll.getHeight()>0){
                    View row=menuItems.get(menuIdx);int y=menuScroll.getScrollY(),h=menuScroll.getHeight();
                    if(up&&(y>row.getTop()+px(8)||menuIdx==0&&y>0)){menuScroll.smoothScrollBy(0,-px(100));break;}
                    if(down&&row.getBottom()>y+h-px(8)){menuScroll.smoothScrollBy(0,px(100));break;}
                }
                // The ends wrap around on a fresh press: Up from the first row reaches the last one (About, Updates…)
                // at once. Holding the key stops at the end instead of racing round again.
                if (up && menuIdx > 0) focusMenu(menuIdx - 1);
                else if (up && !heldKey && menuItems.size() > 1) focusMenu(menuItems.size() - 1);
                else if (down && menuIdx < menuItems.size() - 1) focusMenu(menuIdx + 1);
                else if (down && !heldKey && menuItems.size() > 1) focusMenu(0);
                else if (left || right) {if(settingsPopupMode){if(left)back();}else closeMenu();}
                break;
            case Z_TOP:
                if (left && iconIdx > 0) focusTop(iconIdx - 1);
                else if(left&&!insightCards.isEmpty())focusInsights(insightIdx);
                else if (right && iconIdx < icons.length - 1 && icons[iconIdx + 1].getVisibility() == View.VISIBLE) focusTop(iconIdx + 1);
                else if (down) { if (hasPicks) focusTabs(tabIdx); else focusDock(dockIdx); }
                break;  // Up, and the ends of the row, go nowhere.
            case Z_INSIGHTS:
                if(right)focusTop(0);
                else if(down){if(hasPicks)focusTabs(tabIdx);else focusDock(dockIdx);}
                break;
            case Z_TABS:
                if (left && nextTab(tabIdx, -1) >= 0) focusTabs(nextTab(tabIdx, -1));
                else if (right && nextTab(tabIdx, 1) >= 0) focusTabs(nextTab(tabIdx, 1));
                else if (up) {if(!insightCards.isEmpty())focusInsights(insightIdx);else focusTop(iconIdx);}
                else if (down) focusPicks(picksRow.anchor());
                break;
            case Z_PICKS:
                if (left && picksRow.anchor() > 0) focusPicks(picksRow.anchor() - 1);
                else if (right && picksRow.anchor() < rowPicks.size() - 1) focusPicks(picksRow.anchor() + 1);
                else if (up) focusTabs(tabIdx);
                else if (down) focusDock(dockIdx);
                break;
            case Z_DOCK:
                if (left && dockIdx > 0) focusDock(dockIdx - 1);
                else if (right && dockIdx < dockTiles.size() - 1) focusDock(dockIdx + 1);
                else if (right && allTile != null) focusAllTile();
                else if (up) { if (hasPicks) focusPicks(picksRow.anchor()); else if(!insightCards.isEmpty())focusInsights(insightIdx);else focusTop(iconIdx); }
                else if (down && !chanRows.isEmpty()) focusChan(0, chanCol[0]);
                else if (down && !gridTiles.isEmpty()) focusGrid(Math.min(dockToGridCol(dockIdx), gridTiles.size() - 1));
                break;
            case Z_ALL:
                if (left) focusDock(dockTiles.size() - 1);
                else if (up) { if (hasPicks) focusPicks(picksRow.anchor()); else if(!insightCards.isEmpty())focusInsights(insightIdx);else focusTop(iconIdx); }
                else if (down && !chanRows.isEmpty()) focusChan(0, chanCol[0]);
                else if (down) focusGrid(GRID_COLS - 1);
                break;
            case Z_CHAN: {
                int n = chanRows.get(chanIdx).items.size(), c = chanCol[chanIdx];
                if (isFormula(chanRows.get(chanIdx))) {
                    int next = FormulaOneDashboard.neighbor(c,left ? -1 : right ? 1 : 0,up ? -1 : down ? 1 : 0);
                    if(next>=0) {focusChan(chanIdx,next);break;}
                    if(left||right)break;
                }
                if (left && c > 0) focusChan(chanIdx, c - 1);
                else if (right && c < n - 1) focusChan(chanIdx, c + 1);
                else if (up && chanIdx == 0) focusDock(dockIdx);
                else if (up) focusChan(chanIdx - 1, chanCol[chanIdx - 1]);
                else if (down && chanIdx < chanRows.size() - 1) focusChan(chanIdx + 1, chanCol[chanIdx + 1]);
                else if (down && !gridTiles.isEmpty()) focusGrid(0);
                break;
            }
            case Z_GRID: {
                int row = gridIdx / GRID_COLS, col = gridIdx % GRID_COLS;
                if (left && col > 0) focusGrid(gridIdx - 1);
                else if (right && col < GRID_COLS - 1 && gridIdx + 1 < gridTiles.size()) focusGrid(gridIdx + 1);
                else if (up && row == 0 && !chanRows.isEmpty()) focusChan(chanRows.size() - 1, chanCol[chanRows.size() - 1]);
                else if (up && row == 0) focusDock(gridToDockCol(col));
                else if (up) focusGrid(gridIdx - GRID_COLS);
                else if (down && (row + 1) * GRID_COLS < gridTiles.size()) focusGrid(Math.min(gridIdx + GRID_COLS, gridTiles.size() - 1));
                break;
            }
        }
    }

    /**
     * What starts the screensaver now: Google TV Settings' "start screensaver" voice action (what the Assistant uses;
     * also on devices without SystemUI's shortcut, like the Google TV Streamer), else SystemUI's Somnambulator.
     * Null where neither exists: Control Center leaves the row out. The voice action idea is from tvOS Launcher.
     */
    private Intent screensaverIntent() {
        // In a task of its own: it's part of the TV's Settings app, and in Settings' own task a settings page left open
        // in the background would be what waking the TV shows, instead of Talkies.
        Intent voice = new Intent("com.google.android.pano.action.SLEEP")
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ResolveInfo r = pm.resolveActivity(voice, 0);
        if (r != null && r.activityInfo != null && !r.activityInfo.name.contains("EmptyStub")) return voice;
        Intent dream = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
        return pm.resolveActivity(dream, 0) != null ? dream : null;
    }

    /** Where focus is: changes whenever a move took it somewhere, so a press at an end makes no sound. */
    private String focusSignature() {
        return zone + "," + iconIdx + "," + tabIdx + "," + picksRow.anchor() + "," + dockIdx + "," + gridIdx + "," + chanIdx
                + "," + (chanIdx < chanCol.length ? chanCol[chanIdx] : 0) + "," + menuIdx + "," + appIndex();
    }

    /**
     * Android's own navigation and click sounds. Talkies takes the remote's presses itself, so Android doesn't play
     * them; they follow the TV's System sounds switch (off: silent), as everywhere else on the TV.
     */
    private void navigationSound(int k) {
        root.playSoundEffect(k == KeyEvent.KEYCODE_DPAD_UP ? android.view.SoundEffectConstants.NAVIGATION_UP
                : k == KeyEvent.KEYCODE_DPAD_DOWN ? android.view.SoundEffectConstants.NAVIGATION_DOWN
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? android.view.SoundEffectConstants.NAVIGATION_LEFT
                : android.view.SoundEffectConstants.NAVIGATION_RIGHT);
    }

    private void select() {
        root.playSoundEffect(android.view.SoundEffectConstants.CLICK);
        switch (zone) {
            case Z_MENU:
                if (menuIdx < menuActions.size()) {
                    Runnable a = menuActions.get(menuIdx);
                    boolean keep = menuKeepsOpen.get(menuIdx);
                    Object[] parent = {menuOpener, menuIdx};
                    if (!keep) closeMenu();
                    int before = menuGen;
                    fromMenuRow = true;
                    try { a.run(); } finally { fromMenuRow = false; }
                    // A row that opened another menu: Back comes here. (A row that kept its menu open and rebuilt
                    // it is the same level.)
                    if (!keep && menuGen != before && zone == Z_MENU && parent[0] != null) {
                        menuParents.push(parent);
                        menuOpener = a;
                    }
                }
                break;
            case Z_TOP:
                if (iconIdx == P_CONTROL) { openControlCenter(); menuOpener = this::openControlCenter; }
                else if (iconIdx == P_INPUTS) openInputs();
                else if (iconIdx == P_WIFI) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else if (iconIdx == P_BELL) openUpdates();
                else openTvSettings();
                break;
            case Z_TABS:
                focusPicks(picksRow.anchor());
                break;
            case Z_PICKS:
                openPick(rowPicks.get(picksRow.anchor()));
                break;
            case Z_INSIGHTS:
                if(topInsightRow!=null&&insightIdx<topInsightRow.items.size())openInsight((HomeInsights.Card)topInsightRow.items.get(insightIdx).extra);
                break;
            case Z_CHAN:
                openPick(chanRows.get(chanIdx).items.get(chanCol[chanIdx]));
                break;
            case Z_DOCK:
                startFromTile(apps.get(dockIdx).launchIntent());
                break;
            case Z_GRID:
                startFromTile(apps.get(dockCount + gridIdx).launchIntent());
                break;
            case Z_ALL:
                focusGrid(0);
                break;
        }
    }

    /** Opens a pick or channel card: through the app's own link to that title when it gave one, else the app. */
    private void openRecommendation(Trending.Pick p) {
        final int row=chanIdx,col=row<chanCol.length?chanCol[row]:0;
        beginMenu(P_CONTROL,null);
        if(row<chanCards.size()&&col<chanCards.get(row).size())menuTile=chanCards.get(row).get(col);
        menuClosed=()->focusChan(row,col);
        recommendationText(p.title,22,theme.text);
        String details=p.year;
        if(p.rating>0)details+=(details.isEmpty()?"":" · ")+String.format(Locale.US,"TMDB %.1f/10",p.rating);
        if(!details.isEmpty())recommendationText(details,12,theme.text2);
        recommendationText(p.meta,12,theme.text2);
        if(!p.overview.isEmpty())recommendationText(p.overview,14,theme.text);
        recommendationText("TMDB · Streaming availability via JustWatch. Opens the app; select the title there.",11,theme.text2);
        addRow("Open "+p.service,null,()->{
            Intent app=pm.getLeanbackLaunchIntentForPackage(p.pkg);
            if(app!=null)startFromTile(app);
            else Toast.makeText(this,"This streaming app is no longer installed",Toast.LENGTH_SHORT).show();
        },false);
        showMenu(500);
    }

    private void recommendationText(String text,float size,int color) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);
        view.setTypeface(getResources().getFont(R.font.outfit));if(settingsPopupMode)view.setFontVariationSettings("'wght' 400");view.setPadding(px(14),px(7),px(14),px(7));
        view.setMaxLines(size==14?7:4);view.setEllipsize(android.text.TextUtils.TruncateAt.END);menu.addView(view);
    }

    private void openFormulaCard(FormulaOne.Card card) {
        final int row = chanIdx, col = row < chanCol.length ? chanCol[row] : 0;
        beginMenu(P_CONTROL, card.title + "\n" + card.foot);
        if (row < chanCards.size() && col < chanCards.get(row).size()) menuTile = chanCards.get(row).get(col);
        menuClosed = () -> focusChan(row, col);
        String body = card.body.isEmpty() ? card.metric + "\nConnect to load Formula 1 data" : card.body;
        if (card.target > System.currentTimeMillis()) body = "Starts in " + FormulaOne.countdown(card.target, System.currentTimeMillis()) + "\n" + body;
        for (String line : body.split("\n")) if (!line.trim().isEmpty()) {
            View entry = addRow(line, null, () -> { }, true);
            TextView label = entry.findViewById(R.id.input_label);
            label.setSingleLine(false); label.setMaxLines(3); label.setTextSize(14);
            entry.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            entry.setPadding(entry.getPaddingLeft(), px(9), entry.getPaddingRight(), px(9));
        }
        for (String pkg : new String[]{"com.formulaone.production", "com.fancode.tv"}) {
            Intent app = pm.getLeanbackLaunchIntentForPackage(pkg);
            String label = "com.formulaone.production".equals(pkg) ? "F1 TV" : "FanCode";
            if (app != null) addRow("Open " + label, null, () -> startFromTile(app), false);
        }
        showMenu(640);
    }

    private void openPick(Trending.Pick p) {
        if(p.extra instanceof HomeInsights.Card){openInsight((HomeInsights.Card)p.extra);return;}
        if (p.extra instanceof FormulaOne.Card) {openFormulaCard((FormulaOne.Card)p.extra);return;}
        if (Recommendations.isRow(p.tab)) {openRecommendation(p);return;}
        if (p.intentUri != null) {
            try {
                startActivity(Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fromTile());
                if (p.pkg != null) RecentApps.note(this, p.pkg);
                return;
            } catch (Exception ex) {
                Log.w(TAG, "Link failed, opening the app: " + ex.getClass().getSimpleName());
            }
        }
        // A match JioHotstar has no card for: open its search page and have SleepService type both teams in
        // (its search links ignore the text). With the service off, the empty search page still opens.
        if (p.extra instanceof Cricket.Match && Cricket.HOTSTAR.equals(p.pkg) && ((Cricket.Match) p.extra).search != null) {
            String text = ((Cricket.Match) p.extra).search;
            if (!SleepService.searchHotstar(text)) Log.i(TAG, "Accessibility off: opening JioHotstar search without " + text);
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hotstar.com/in/explore")).setPackage(Cricket.HOTSTAR));
            return;
        }
        Intent i = p.pkg == null ? null : pm.getLeanbackLaunchIntentForPackage(p.pkg);
        if (i != null) startFromTile(i);
        else if (p.extra instanceof Cricket.Match && ((Cricket.Match) p.extra).football) Toast.makeText(this, "Sweep TV doesn't know where to watch football yet", Toast.LENGTH_SHORT).show();
        else if (p.extra instanceof Cricket.Match) Toast.makeText(this, "None of your apps has a card for this match", Toast.LENGTH_SHORT).show();
    }

    private void back() {
        if (zone == Z_MENU && !menuParents.isEmpty()) {
            Object[] parent = menuParents.pop();
            Runnable opener = (Runnable) parent[0];
            closeMenu();
            fromMenuRow = true;
            try { opener.run(); } finally { fromMenuRow = false; }
            menuOpener = opener;
            if (zone == Z_MENU) focusMenu(Math.min((Integer) parent[1], menuItems.size() - 1));
        } else if (zone == Z_MENU) closeMenu();
        else if (zone != Z_DOCK) focusDock(dockIdx);
    }

    /**
     * The TV's settings. Some firmware hides the standard entry (some projectors); Android TV's own settings app is
     * usually still there. An idea from CleanYourTV (MIT).
     */
    private void openTvSettings() {
        Intent i = new Intent(Settings.ACTION_SETTINGS);
        if (pm.resolveActivity(i, 0) == null) {
            Intent tv = new Intent(Intent.ACTION_MAIN).setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
            if (pm.resolveActivity(tv, 0) != null) i = tv;
            else Toast.makeText(this, "This TV has no settings app Sweep TV can open", Toast.LENGTH_SHORT).show();
        }
        start(i);
    }

    /**
     * The app grows out of the focused tile or card, from the ring's rectangle. It's the window animation Android
     * draws anyway, only starting from the tile instead of the middle, so it costs nothing extra. Talkies settings
     * → Apps open from their tile turns it off. An idea from Plume (MIT).
     */
    private Bundle fromTile() {
        if (!prefs.getBoolean("open_animation", true) || focusedView == null || ring.rect.isEmpty()) return null;
        RectF r = ring.rect;
        return android.app.ActivityOptions.makeScaleUpAnimation(ring, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height())).toBundle();
    }

    private void startFromTile(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fromTile());
            String pkg = i.getPackage() != null ? i.getPackage() : i.getComponent() != null ? i.getComponent().getPackageName() : null;
            if (pkg != null && !pkg.equals(getPackageName())) RecentApps.note(this, pkg);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Log.w(TAG, "Cannot start " + i, ex);
        }
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Log.w(TAG, "Cannot start " + i, ex);
        }
    }

    // ---------------------------------------------------------------- rearranging

    private int appIndex() {
        if (zone == Z_DOCK) return dockIdx;
        if (zone == Z_GRID) return dockCount + gridIdx;
        return -1;
    }

    private void startMove() {
        if (zone == Z_MENU) { startMenuMove(); return; }
        int ai = appIndex();
        if (ai < 0 || ai >= apps.size()) return;
        holdFired = true;
        moving = true;
        movingPkg = apps.get(ai).pkg;
        beforeMove = new ArrayList<>(apps);
        ring.setMoving(true);
        refocusMoving();
        moveHint.setText("Moving " + apps.get(ai).label + "  ·  OK to place  ·  Back to cancel");
        moveHint.setAlpha(0f);
        moveHint.setVisibility(View.VISIBLE);
        moveHint.animate().alpha(1f).setDuration(150).start();
        showServiceNote();
    }

    /** Moves the picked-up app one step. In a row it swaps with its neighbour; between the dock and the grid it
     *  trades places with the app there (the dock keeps seven); within the grid the others shift along. */
    private void moveApp(int k) {
        int ai = -1;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(movingPkg)) ai = i;
        if (ai < 0) return;
        int gridSize = apps.size() - dockCount, target = -1;
        boolean swap = true;
        if (ai < dockCount) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && ai > 0) target = ai - 1;
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && ai < dockCount - 1) target = ai + 1;
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && gridSize > 0) target = dockCount + Math.min(dockToGridCol(ai), gridSize - 1);
        } else {
            int g = ai - dockCount, row = g / GRID_COLS, col = g % GRID_COLS;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) target = ai - 1;
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && col < GRID_COLS - 1 && ai + 1 < apps.size()) target = ai + 1;
            else if (k == KeyEvent.KEYCODE_DPAD_UP && row == 0) target = Math.min(gridToDockCol(col), dockCount - 1);
            else if (k == KeyEvent.KEYCODE_DPAD_UP) { target = ai - GRID_COLS; swap = false; }
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && (row + 1) * GRID_COLS < gridSize) {
                target = Math.min(ai + GRID_COLS, apps.size() - 1);
                swap = false;
            }
        }
        if (target < 0) return;
        if (swap) Collections.swap(apps, ai, target);
        else apps.add(target, apps.remove(ai));
        buildAppViews();
        refocusMoving();
    }

    /** Focus (and the lifted look) follows the app being moved to wherever it now is. */
    private void refocusMoving() {
        for (int i = 0; i < apps.size(); i++) {
            if (!apps.get(i).pkg.equals(movingPkg)) continue;
            final int at = i;
            root.post(() -> { if (at < dockCount) focusDock(at); else focusGrid(at - dockCount); });
            return;
        }
    }

    private void endMove(boolean keep) {
        if (!moving) return;
        moving = false;
        ring.setMoving(false);
        if (keep) saveOrder();
        else {
            apps.clear();
            apps.addAll(beforeMove);
            buildAppViews();
        }
        int at = 0;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(movingPkg)) at = i;
        final int pos = at;
        root.post(() -> { if (pos < dockCount) focusDock(pos); else focusGrid(pos - dockCount); });
        moveHint.animate().alpha(0f).setDuration(120).withEndAction(() -> { moveHint.setVisibility(View.GONE); showServiceNote(); }).start();
    }

    private void saveOrder() {
        StringBuilder d = new StringBuilder(), rest = new StringBuilder();
        for (int i = 0; i < apps.size(); i++) {
            StringBuilder b = i < dockCount ? d : rest;
            if (b.length() > 0) b.append(',');
            b.append(apps.get(i).pkg);
        }
        if (appsOrder() != ORDER_YOURS) {
            // All apps is sorted for you: only the dock's arrangement is yours to keep.
            prefs.edit().putString("order_dock", d.toString()).apply();
            loadApps();
            buildAppViews();
            return;
        }
        prefs.edit().putString("order_dock", d.toString()).putString("order_apps", rest.toString()).apply();
    }

    // ---------------------------------------------------------------- menus (Inputs, Control Center)

    private void beginSettingsMenu(int anchor,String heading){
        settingsOpening=true;
        try{beginMenu(anchor,heading);}finally{settingsOpening=false;}
    }

    private void beginMenu(int anchor, String heading) {
        boolean rootSettings="Sweep TV · Settings".equals(heading);
        if(settingsOpening||rootSettings)settingsPopupMode=true;
        else if(!fromMenuRow)settingsPopupMode=false;
        settingsGrid=rootSettings;settingsColumns=3;settingsHeading=heading;
        if(!settingsPopupMode)settingsPopup.hide(false);
        menuGen++;
        if (!fromMenuRow) {  // a menu of its own, not one level down
            menuParents.clear();
            menuOpener = null;
        }
        menuAnchor = anchor;
        menuTile = null;
        menuClosed = null;
        menuItems.clear();
        menuActions.clear();
        menuKeepsOpen.clear();
        menuMode = MENU_PLAIN;
        menuScroll.animate().cancel();
        menu.removeAllViews();
        menuScroll.setBackground(settingsPopupMode?new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT):rounded(theme.menuBg, theme.menuStroke, 20));
        menuScroll.scrollTo(0, 0);
        menu.setPadding(settingsPopupMode?0:px(8),settingsPopupMode?0:px(8),settingsPopupMode?0:px(8),settingsPopupMode?0:px(8));
        if (heading != null && !settingsPopupMode) {
            TextView h = new TextView(this);
            h.setText(heading);
            h.setTextSize(13);
            h.setTextColor(theme.text2);
            h.setTypeface(getResources().getFont(R.font.outfit));
            h.setFontVariationSettings("'wght' 500");
            h.setPadding(px(14), px(8), px(14), px(6));
            menu.addView(h);
        }
    }

    /**
     * Outfit's weights for the layouts' text, as (view id, weight) pairs. They're set here rather than in the layouts
     * because the layout attribute only works from Android 9, while this call works from Android 8.
     */
    private static void weigh(java.util.function.IntFunction<View> find, int... idWeight) {
        for (int i = 0; i < idWeight.length; i += 2)
            ((TextView) find.apply(idWeight[i])).setFontVariationSettings("'wght' " + idWeight[i + 1]);
    }

    /** A row with a label, an optional status on the right, and what OK does. */
    private View addRow(String label, String status, Runnable action, boolean keepOpen) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_input, menu, false);
        weigh(row::findViewById, R.id.input_label, 450, R.id.input_status, 400);
        ((TextView) row.findViewById(R.id.input_label)).setText(label);
        row.findViewById(R.id.input_dot).setVisibility(View.GONE);
        setStatus(row, status);
        if(settingsPopupMode){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(56));lp.bottomMargin=px(8);row.setLayoutParams(lp);
            row.setPadding(px(16),0,px(16),0);
            ((TextView)row.findViewById(R.id.input_label)).setTextSize(16.5f);
            ((TextView)row.findViewById(R.id.input_status)).setTextSize(12.5f);
            SettingsPopup.prepareRow(row,theme.iconFocusBg,false);
        }
        menu.addView(row);
        menuItems.add(row);
        menuActions.add(action);
        menuKeepsOpen.add(keepOpen);
        return row;
    }

    private static void setStatus(View row, String status) {
        TextView t = row.findViewById(R.id.input_status);
        t.setText(status);
        t.setVisibility(status == null ? View.GONE : View.VISIBLE);
        SettingsPopup.updateStatus(row,status);
    }

    private void showMenu(int widthDp) {
        if(settingsPopupMode){
            int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
            RectF content=settingsPopup.content(width,height);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)menuScroll.getLayoutParams();
            lp.width=Math.round(content.width());lp.height=Math.round(content.height());lp.leftMargin=Math.round(content.left);lp.topMargin=Math.round(content.top);
            menuScroll.setLayoutParams(lp);menuScroll.setBackgroundColor(Color.TRANSPARENT);
            settingsPopup.show(settingsHeading,HomeOptions.name(this),theme.iconFocusBg,"Sweep TV · Settings".equals(settingsHeading));
            menuScroll.setVisibility(View.VISIBLE);menuScroll.setAlpha(0f);menuScroll.setTranslationY(px(5));
            menuScroll.animate().alpha(1f).translationY(0).setDuration(130).start();zone=Z_MENU;
            if(!menuItems.isEmpty())focusMenu(0);return;
        }
        RectF a = new RectF(rectOf(menuTile != null ? menuTile : icons[menuAnchor]));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuScroll.getLayoutParams();
        lp.width = px(widthDp);
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        menu.measure(View.MeasureSpec.makeMeasureSpec(px(widthDp), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = menu.getMeasuredHeight();
        if (menuTile != null) {
            // By the app or card: left-aligned to it, below it if there's room, else above it.
            lp.leftMargin = Math.max(px(24), Math.min(Math.round(a.left), screenW - px(widthDp + 24)));
            lp.topMargin = a.bottom + px(10) + h <= screenH - px(16) ? Math.round(a.bottom) + px(10)
                    : Math.max(px(16), Math.round(a.top) - px(10) - h);
        } else {
            // Under its icon, right-aligned to it, kept on screen.
            int left = Math.round(a.right) - px(widthDp);
            lp.leftMargin = Math.max(px(24), Math.min(left, screenW - px(widthDp + 24)));
            lp.topMargin = Math.round(a.bottom) + px(10);
        }
        // Long menus (Channels) scroll instead of running off the screen.
        int room = screenH - lp.topMargin - px(24);
        lp.height = h > room ? room : ViewGroup.LayoutParams.WRAP_CONTENT;
        menuScroll.setLayoutParams(lp);
        menuScroll.setVisibility(View.VISIBLE);
        menuScroll.setAlpha(0f);
        menuScroll.setTranslationY(-px(6));
        menuScroll.animate().alpha(1f).translationY(0).setDuration(150).start();
        zone = Z_MENU;
        focusMenu(0);
    }

    private void focusMenu(int i) {
        menuIdx = i;
        for (int r = 0; r < menuItems.size(); r++) {
            View row = menuItems.get(r);
            boolean f = r == i;
            if(settingsPopupMode){SettingsPopup.focus(row,f,theme.iconFocusBg);continue;}
            row.setBackground(f ? rounded(theme.menuFocusBg, 0, 12) : null);
            ((TextView) row.findViewById(R.id.input_label)).setTextColor(f ? theme.menuFocusText : theme.text);
            ((TextView) row.findViewById(R.id.input_status)).setTextColor(f ? (theme.menuFocusText & 0x00FFFFFF) | 0x99000000 : theme.text2);
        }
        if(settingsGrid)return;
        View row = menuItems.get(i);
        menuScroll.post(() -> {
            int top = row.getTop(), bottom = row.getBottom(), y = menuScroll.getScrollY(), h = menuScroll.getHeight();
            if(settingsPopupMode&&bottom-top>h-px(16))menuScroll.smoothScrollTo(0,Math.max(0,top-px(8)));
            else if (top - px(8) < y) menuScroll.smoothScrollTo(0, Math.max(0, top - px(52)));
            else if (bottom + px(8) > y + h) menuScroll.smoothScrollTo(0, bottom + px(52) - h);
        });
    }

    private void closeMenu() {
        if(settingsPopupMode)settingsPopup.hide(true);
        settingsGrid=false;
        menuScroll.animate().alpha(0f).setDuration(110).withEndAction(() -> menuScroll.setVisibility(View.GONE)).start();
        if (menuMode == MENU_CHANNELS && channelsEdited) refreshChannels();
        menuMode = MENU_PLAIN;
        Runnable back = menuClosed;
        menuClosed = null;
        menuTile = null;
        if (back != null) back.run(); else focusTop(menuAnchor);
    }

    private void openInputs() { openInputs(!prefs.getBoolean("inputs_connected_only", false)); }

    /**
     * HDMI and AV ports, then Live TV. With Talkies settings → Empty inputs set to "Under More inputs", ports with
     * nothing plugged in wait behind a More inputs row ({@code all} false). An idea from CleanYourTV (MIT).
     */
    private void openInputs(boolean all) {
        beginMenu(P_INPUTS, null);
        TvInputManager tim = getSystemService(TvInputManager.class);
        List<TvInputInfo> hdmi = new ArrayList<>(), av = new ArrayList<>(), tuners = new ArrayList<>();
        TvInputInfo tuner = null;
        Map<String, TvInputInfo> cec = new java.util.HashMap<>();  // port id → the HDMI-CEC device behind it
        if (tim != null) {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.getParentId() != null) { cec.put(i.getParentId(), i); continue; } // devices behind a port: use the port
                switch (i.getType()) {
                    case TvInputInfo.TYPE_HDMI: hdmi.add(i); break;
                    case TvInputInfo.TYPE_COMPOSITE: case TvInputInfo.TYPE_COMPONENT:
                    case TvInputInfo.TYPE_SCART: case TvInputInfo.TYPE_SVIDEO: case TvInputInfo.TYPE_VGA: av.add(i); break;
                    case TvInputInfo.TYPE_TUNER: tuners.add(i); break;
                }
            }
        }
        // App channels (Zee5, Play Movies…) also register as tuners; prefer the TV's own one.
        String vendor = hdmi.isEmpty() ? null : hdmi.get(0).getServiceInfo().packageName;
        for (TvInputInfo i : tuners) {
            boolean system = (i.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (i.getServiceInfo().packageName.equals(vendor)) { tuner = i; break; }
            if (tuner == null && system) tuner = i;
        }
        Collections.sort(hdmi, (a, b) -> label(a).compareTo(label(b)));
        int n = 1, hidden = 0, firstEmpty = -1;
        boolean named = false;
        for (TvInputInfo i : hdmi) {
            String name = fixLabel(label(i), "HDMI " + n++);
            if (!connected(tim, i)) {
                if (!all) { hidden++; continue; }
                if (firstEmpty < 0) firstEmpty = menuItems.size();
            }
            View row = addInput(name, tim, i, passthrough(i));
            TvInputInfo behind = cec.get(i.getId());
            String device = cecStatus(tim, behind);
            if (device != null) {
                setStatus(row, device);
                named = true;
                ImageView dot = row.findViewById(R.id.input_dot);
                if (dot.getVisibility() == View.VISIBLE) dot.setColorFilter(identify(behind).color, PorterDuff.Mode.SRC_IN);
            }
        }
        n = 1;
        for (TvInputInfo i : av) {
            String name = av.size() == 1 ? "AV" : fixLabel(label(i), "AV " + n++);
            if (!connected(tim, i)) {
                if (!all) { hidden++; continue; }
                if (firstEmpty < 0) firstEmpty = menuItems.size();
            }
            addInput(name, tim, i, passthrough(i));
        }
        final TvInputInfo tunerInfo = tuner;
        addRow("Live TV", null, () -> {
            if (tunerInfo != null) {
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(TvContract.buildChannelsUriForInput(tunerInfo.getId()), "vnd.android.cursor.dir/channel");
                try { startActivity(v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                catch (ActivityNotFoundException ignored) { }
            }
            Intent tv = new Intent(Intent.ACTION_VIEW);
            tv.setDataAndType(TvContract.Channels.CONTENT_URI, "vnd.android.cursor.dir/channel");
            start(tv);
        }, false);
        if (hidden > 0) addRow("More inputs", String.valueOf(hidden), () -> openInputs(true), false);
        showMenu(named ? 340 : 220);
        // Opened from More inputs: start on the first port it brought back.
        if (all && firstEmpty > 0 && prefs.getBoolean("inputs_connected_only", false)) focusMenu(firstEmpty);
    }

    private static boolean connected(TvInputManager tim, TvInputInfo info) {
        return tim != null && info != null && tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED;
    }

    private String label(TvInputInfo i) { return String.valueOf(i.loadLabel(this)); }

    private static String fixLabel(String l, String fallback) {
        // Some vendors label ports by hardware ID; show a friendly name instead.
        return (l == null || l.isEmpty() || l.contains("HW") || l.contains("/")) ? fallback : l;
    }

    private Runnable passthrough(TvInputInfo i) {
        return () -> {
            Intent v = new Intent(Intent.ACTION_VIEW);
            v.setDataAndType(TvContract.buildChannelUriForPassthroughInput(i.getId()), "vnd.android.cursor.item/channel");
            start(v);
        };
    }

    private View addInput(String text, TvInputManager tim, TvInputInfo info, Runnable action) {
        boolean connected = connected(tim, info);
        View row = addRow(text, null, action, false);
        ImageView dot = row.findViewById(R.id.input_dot);
        dot.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        dot.setColorFilter(Color.rgb(48, 209, 88), PorterDuff.Mode.SRC_IN);
        return row;
    }

    /**
     * "Fire TV · Standby": the device and its power state, as it reports them over HDMI-CEC (the TV keeps them as an
     * input behind the port), under the name it's known by (Devices). Null when nothing CEC-capable is plugged in.
     * Idea from Matinee (MIT).
     */
    private String cecStatus(TvInputManager tim, TvInputInfo device) {
        if (tim == null || device == null) return null;
        String raw = label(device).trim();
        if (raw.isEmpty() || raw.contains("HW") || raw.contains("/")) return null;
        String name = identify(device).name;
        int state;
        try { state = tim.getInputState(device.getId()); } catch (Exception e) { return name; }
        if (state == TvInputManager.INPUT_STATE_CONNECTED) return name + "  ·  On";
        if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) return name + "  ·  Standby";
        return name;
    }

    // ---------------------------------------------------------------- weather and now playing (ideas from Luma, MIT)

    /** The next hours, or the next days; OK on any row switches. */
    private void openWeather(boolean days) {
        List<String[]> rows = weather.forecast(days);
        beginMenu(P_CONTROL, rows.isEmpty() ? "Weather  ·  the forecast arrives with the next update"
                : (days ? "Next days" : "Next hours") + "  ·  OK shows the " + (days ? "hours" : "days"));
        recommendationText(weather.locationLabel(),14,theme.text);
        recommendationText(weather.solarSummary(),12,theme.text2);
        SkyWeather.Sample sky=weather.sky(timeOfDay.nowMillis());
        recommendationText(skyPalette().phase+" sky · "+(sky.weatherKnown?"Hourly weather · Open-Meteo":"Local clock fallback"),11,theme.text2);
        for (String[] r : rows) addRow(r[0], r[1], () -> openWeather(!days), false);
        showMenu(340);
    }

    /** The song (or show), its artwork and app, then Play or Pause, Next, Previous and Open. Follows the player live. */
    private void openNowPlaying(MediaController s) {
        beginMenu(P_CONTROL, "Now playing  ·  " + appLabel(s.getPackageName()));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(14), px(4), px(14), px(10));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setBackground(rounded(theme.menuStroke, 0, 10));
        head.addView(art, new LinearLayout.LayoutParams(px(64), px(64)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(px(14), 0, 0, 0);
        TextView title = menuText(17, theme.text, 600), artist = menuText(14, theme.text2, 400);
        words.addView(title);
        words.addView(artist);
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        menu.addView(head);

        MediaController.TransportControls go = s.getTransportControls();
        View play = addRow("", null, () -> { if (NowPlaying.playing(s)) go.pause(); else go.play(); }, true);
        if (NowPlaying.can(s, PlaybackState.ACTION_SKIP_TO_NEXT)) addRow("Next", null, go::skipToNext, true);
        if (NowPlaying.can(s, PlaybackState.ACTION_SKIP_TO_PREVIOUS)) addRow("Previous", null, go::skipToPrevious, true);
        addRow("Open " + appLabel(s.getPackageName()), null, () -> {
            android.app.PendingIntent player = s.getSessionActivity();
            try {
                if (player != null) { player.send(); return; }
            } catch (android.app.PendingIntent.CanceledException ignored) { }
            Intent app = pm.getLeanbackLaunchIntentForPackage(s.getPackageName());
            if (app != null) start(app);
        }, false);

        Runnable fill = () -> {
            title.setText(NowPlaying.title(s));
            String a = NowPlaying.artist(s);
            artist.setText(a);
            artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
            art.setImageBitmap(NowPlaying.art(s));
            ((TextView) play.findViewById(R.id.input_label)).setText(NowPlaying.playing(s) ? "Pause" : "Play");
        };
        fill.run();
        MediaController.Callback follow = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { update(); }
            @Override public void onPlaybackStateChanged(PlaybackState p) { update(); }
            @Override public void onSessionDestroyed() { if (head.isAttachedToWindow() && zone == Z_MENU) closeMenu(); }
            private void update() {
                if (head.isAttachedToWindow()) fill.run(); else s.unregisterCallback(this);  // the menu moved on
            }
        };
        s.registerCallback(follow, new Handler(Looper.getMainLooper()));
        menuClosed = () -> { s.unregisterCallback(follow); focusTop(P_CONTROL); };
        showMenu(380);
    }

    private TextView menuText(int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private String appLabel(String pkg) {
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private Devices identify(TvInputInfo device) {
        int[] cec = Devices.cec(device);
        return Devices.identify(label(device), cec[0], cec[1]);
    }

    // ---------------------------------------------------------------- live scores

    private String scoresLabel() {
        return scores().equals("both") || scores().equals("cricket") ? "On" : "Off";
    }

    /** One row per league, On when it's shown; OK turns it on or off. Leaving the menu fetches the new set. */


    // ---------------------------------------------------------------- keys and location

    /**
     * Control Center → Keys and location: your own TMDB key (picks), CricketData key (scores) and weather area, typed
     * with the remote or pasted from a phone with the Google TV app's keyboard. Keys are tried before they're saved.
     * They go in the same files the set-*.sh scripts push over adb (see Keys).
     */
    private void openKeysAndLocation(int focus) {
        beginSettingsMenu(settingsAt, "Keys and location");
        String tmdb = Keys.read(this, Keys.TMDB), cricket = Keys.read(this, Keys.CRICKET), area = Keys.read(this, Keys.LOCATION);
        String paste = "\n\nTip: Send from your phone (above) is easier, or the Google TV app's keyboard on your phone can paste it.";
        addRow("Send from your phone", "QR code", this::openPhonePage, true);
        addRow("TMDB key", tmdb == null ? "Not set" : Keys.masked(tmdb), () -> askText("TMDB key",
                "For the Trending and New picks. Free at themoviedb.org → Settings → API: the API Key or the API Read "
                        + "Access Token." + paste, null, true, tmdb != null, text -> saveKey(Keys.TMDB, "TMDB", text, 1)), true);
        addRow("Cricket key", cricket == null ? "Not set" : Keys.masked(cricket), () -> askText("CricketData key",
                "For live cricket scores. Free at cricketdata.org (100 requests a day, enough for Sweep TV)." + paste,
                null, true, cricket != null, text -> saveKey(Keys.CRICKET, "CricketData", text, 2)), true);
        // Coordinates pinpoint a home, so the row (and any screenshot of it) just says so; place names show as typed.
        addRow("Weather location", weather.locationLabel(),
                () -> askText("Weather location", "Enter a city, such as Delhi, India, or latitude,longitude. "
                        + "A saved city overrides automatic detection. Remove it to use the automatic city when enabled.",
                        area, false, area != null, this::saveLocation), true);
        addRow("Automatic city", weather.automatic()?"On · approximate IP city":"Off", () -> {
            weather.automatic(!weather.automatic());weatherLine=weather.cached();refreshTimeTheme();
            openKeysAndLocation(4);refreshOnline();
        },false);
        recommendationText("Automatic city uses an IP lookup, without GPS access. VPNs and ISP routing can affect accuracy. "
                + "Your TV's clock is used when a city cannot be found.",11,theme.text2);
        showMenu(420);
        focusMenu(focus);
    }

    /** A one-line text box over the home screen. Save hands back the text; Remove (or saving it empty) hands back "". */
    private void askText(String title, String help, String current, boolean code, boolean canRemove,
                         java.util.function.Consumer<String> onSave) {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (code ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : 0));
        if (current != null) {
            field.setText(current);
            field.setSelection(current.length());
        }
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(20), px(4), px(20), 0);
        box.addView(field);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(help).setView(box)
                .setPositiveButton("Save", (d, w) -> onSave.accept(field.getText().toString().trim()))
                .setNegativeButton("Cancel", null);
        if (canRemove) b.setNeutralButton("Remove", (d, w) -> onSave.accept(""));
        android.app.AlertDialog dialog = b.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        field.requestFocus();
    }

    /** Tries the key with the service first: a key it turns down isn't saved; one that can't be checked offline is. */
    private void saveKey(String file, String service, String text, int row) {
        if (text.isEmpty()) {
            Keys.write(this, file, null);
            Toast.makeText(this, service + " key removed", Toast.LENGTH_SHORT).show();
            if (zone == Z_MENU) openKeysAndLocation(row);
            return;
        }
        String key = text.replaceAll("\\s", "");
        Toast.makeText(this, "Checking the key with " + service + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int result = file.equals(Keys.TMDB) ? Trending.check(key) : Cricket.check(key);
            runOnUiThread(() -> {
                if (result == Keys.REJECTED) {
                    Toast.makeText(this, service + " didn't accept that key, so it wasn't saved", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!Keys.write(this, file, key)) {
                    Toast.makeText(this, "Couldn't save the key", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, result == Keys.OK
                        ? "The key works. " + (file.equals(Keys.TMDB) ? "Picks arrive" : "Scores arrive") + " in a minute"
                        : "Couldn't reach " + service + " to check it. Saved; Sweep TV will try it later", Toast.LENGTH_LONG).show();
                if (zone == Z_MENU) openKeysAndLocation(row);
                refreshOnline();
            });
        }, "KeyCheck").start();
    }

    private void saveLocation(String text) {
        if (!Keys.write(this, Keys.LOCATION, text)) {
            Toast.makeText(this, "Couldn't save the location", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.isEmpty()) {
            weatherLine = null;
            updateInfo();
        }
        Toast.makeText(this, text.isEmpty() ? (weather.automatic()?"Using automatic city":"Weather location removed") : "Saved. The weather appears in a moment",
                Toast.LENGTH_SHORT).show();
        if (zone == Z_MENU) openKeysAndLocation(3);
        refreshOnline();
    }

    /**
     * Keys and location → Send from your phone: a QR code for a one-time page on the home network where the phone
     * pastes the keys and the weather area (PhonePage). Closing the dialog stops the page.
     */
    private void openPhonePage() {
        java.net.InetAddress lan = PhonePage.lanAddress(this);
        if (lan == null) {
            Toast.makeText(this, "The TV isn't on a home Wi-Fi or Ethernet network, so the phone can't reach it", Toast.LENGTH_LONG).show();
            return;
        }
        TextView status = new TextView(this);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        PhonePage page;
        try {
            page = new PhonePage(lan, new PhonePage.Listener() {
                @Override public void checking() {
                    runOnUiThread(() -> status.setText("Checking with TMDB and CricketData…"));
                }

                @Override public List<String> accept(Map<String, String> fields) {
                    List<String> lines = new ArrayList<>();
                    if (fields.containsKey("tmdb")) lines.add(acceptKey(Keys.TMDB, "TMDB", fields.get("tmdb")));
                    if (fields.containsKey("cricket")) lines.add(acceptKey(Keys.CRICKET, "CricketData", fields.get("cricket")));
                    if (fields.containsKey("location")) lines.add(Keys.write(HomeActivity.this, Keys.LOCATION, fields.get("location"))
                            ? "Weather location saved." : "Couldn't save the weather location.");
                    runOnUiThread(() -> {
                        if (dialog[0] != null) dialog[0].dismiss();
                        Toast.makeText(HomeActivity.this, String.join("\n", lines), Toast.LENGTH_LONG).show();
                        if (zone == Z_MENU) openKeysAndLocation(0);
                        refreshOnline();
                    });
                    return lines;
                }

                @Override public void closed(boolean expired) {
                    if (expired) runOnUiThread(() -> status.setText("This code has expired. Close it and open Send from your phone again."));
                }
            });
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Couldn't open the page for your phone", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView code = qrCode(page.url);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(24), px(8), px(24), 0);
        box.addView(code, new LinearLayout.LayoutParams(px(180), px(180)));
        TextView where = new TextView(this);
        where.setText(page.url);
        where.setTextSize(12);
        where.setPadding(0, px(8), 0, 0);
        box.addView(where);
        status.setText("Waiting for your phone… The page works once, for 5 minutes.");
        status.setPadding(0, px(6), 0, 0);
        box.addView(status);
        dialog[0] = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Send from your phone")
                .setMessage("Scan this with your phone's camera (on the same Wi-Fi as the TV), then paste the keys and your "
                        + "weather area there.")
                .setView(box)
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> page.close())
                .create();
        dialog[0].show();
    }

    /** A QR code for {@code text}, black on white with its quiet zone, sharp at any size. */
    private ImageView qrCode(String text) {
        boolean[][] modules = Qr.encode(text);
        int quiet = 4, n = modules.length + 2 * quiet, scale = 8;
        int[] pixels = new int[n * n];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < modules.length; y++)
            for (int x = 0; x < modules.length; x++) if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
        ImageView code = new ImageView(this);
        code.setImageBitmap(Bitmap.createScaledBitmap(Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888), n * scale, n * scale, false));
        return code;
    }

    private static final String SUPPORT_URL = "https://buymeacoffee.com/rajupp";

    /** About → Support Talkies: the Buy Me a Coffee page as a QR code, since a TV has no easy way to open a link. */
    private void showSupport() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(24), px(8), px(24), 0);
        box.addView(qrCode(SUPPORT_URL), new LinearLayout.LayoutParams(px(180), px(180)));
        TextView where = new TextView(this);
        where.setText("buymeacoffee.com/rajupp");
        where.setTextSize(12);
        where.setPadding(0, px(8), 0, 0);
        box.addView(where);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Support Sweep TV")
                .setMessage("Sweep TV is free, with no ads or tracking, and built in my spare time. If you enjoy it, "
                        + "scan this with your phone to buy me a popcorn. Thank you!")
                .setView(box)
                .setNegativeButton("Close", null)
                .show();
    }

    /** Checks a key from the phone page with its service and saves it unless turned down; the line the phone shows. */
    private String acceptKey(String file, String service, String text) {
        String key = text.replaceAll("\\s", "");
        int result = file.equals(Keys.TMDB) ? Trending.check(key) : Cricket.check(key);
        if (result == Keys.REJECTED) return service + " didn't accept that key, so it wasn't saved.";
        if (!Keys.write(this, file, key)) return "Couldn't save the " + service + " key.";
        return result == Keys.OK ? service + " key saved, and it works." : service + " key saved. The TV couldn't reach "
                + service + " to check it, so it'll try later.";
    }

    // ---------------------------------------------------------------- remote keys

    /** One row per button with a job ("Prog Red → Open JioHotstar"), then Add a key. */
    private String remoteKeysLabel() {
        int n = RemoteKeys.all(this).size();
        return !SleepService.watchButtons(this) ? "Off" : n == 0 ? "On" : "On · " + n + " set";
    }

    private void openRemoteKeys() {
        boolean on = SleepService.watchButtons(this);
        beginSettingsMenu(settingsAt, on ? "Remote keys · On" : "Remote keys · Off");
        addRow("Remote keys", on ? "On" : "Off", () -> {
            prefs.edit().putBoolean("remote_keys_on", !on).apply();
            SleepService.applyButtonWatching();
            openRemoteKeys();
        }, false);
        for (Map.Entry<Integer, String> e : RemoteKeys.all(this).entrySet()) {
            int code = e.getKey();
            String hold = RemoteKeys.holdAction(this, code);
            addRow(RemoteKeys.keyName(code), RemoteKeys.describe(this, e.getValue())
                    + (hold == null ? "" : "  ·  hold: " + RemoteKeys.describe(this, hold)), () -> openKeyActions(code), false);
        }
        toggleOption("Volume hold repair","volume_repeat_repair",true,()->{});
        addRow("Add a key", null, this::learnKey, false);
        showMenu(540);  // wide enough for a key's name beside "Open … · hold: Open …"
    }

    /** Waits for the next remote button (through the accessibility service, so the TV doesn't act on it). */
    private void learnKey() {
        if (!SleepService.isRunning()) { needsTurnOffSetup(); return; }
        if (!SleepService.watchButtons(this)) {
            prefs.edit().putBoolean("remote_keys_on", true).apply();
            SleepService.applyButtonWatching();
            Toast.makeText(this, "Remote keys are on. Volume hold repair keeps the Volume buttons repeating when enabled",
                    Toast.LENGTH_LONG).show();
        }
        beginMenu(settingsAt, "Press the button on your remote");
        addRow("Waiting for a button…", "Back cancels", () -> { }, true);
        menuClosed = () -> { SleepService.learn(null); focusTop(settingsAt); };
        showMenu(360);
        SleepService.learn(code -> {
            if (zone == Z_MENU) openKeyActions(code);
        });
    }

    /** What the button does: an app, Inputs, Talkies, nothing, or its normal job. */
    private void openKeyActions(int code) {
        menuClosed = null;
        beginMenu(settingsAt, RemoteKeys.keyName(code) + "  ·  now: " + RemoteKeys.describe(this, RemoteKeys.action(this, code)));
        addRow("Open an app", null, () -> openKeyApps(code), false);
        addRow("Inputs", null, () -> setKey(code, RemoteKeys.INPUTS), false);
        addRow("Recent apps", null, () -> setKey(code, RemoteKeys.RECENTS), false);
        addRow("Sweep TV", null, () -> setKey(code, RemoteKeys.HOME), false);
        addRow("Do nothing", null, () -> setKey(code, RemoteKeys.NONE), false);
        if (RemoteKeys.action(this, code) != null) {
            String hold = RemoteKeys.holdAction(this, code);
            addRow("When held", hold == null ? "same as a press" : RemoteKeys.describe(this, hold), () -> openHoldActions(code), false);
            addRow("Back to normal", null, () -> setKey(code, null), false);
        }
        showMenu(420);
    }

    /** A second job for holding the button (only for buttons with a press job: see RemoteKeys). */
    private void openHoldActions(int code) {
        String hold = RemoteKeys.holdAction(this, code);
        beginMenu(settingsAt, "Hold " + RemoteKeys.keyName(code) + "  ·  now: " + (hold == null ? "same as a press" : RemoteKeys.describe(this, hold)));
        addRow("Open an app", null, () -> openKeyApps(code, true), false);
        addRow("Inputs", null, () -> setHold(code, RemoteKeys.INPUTS), false);
        addRow("Recent apps", null, () -> setHold(code, RemoteKeys.RECENTS), false);
        addRow("Sweep TV", null, () -> setHold(code, RemoteKeys.HOME), false);
        if (hold != null) addRow("Same as a press", null, () -> setHold(code, null), false);
        showMenu(420);
    }

    private void openKeyApps(int code) { openKeyApps(code, false); }

    private void openKeyApps(int code, boolean hold) {
        beginMenu(settingsAt, "Open which app?");
        for (AppEntry a : apps) addRow(a.label, null, () -> {
            if (hold) setHold(code, RemoteKeys.APP + a.pkg); else setKey(code, RemoteKeys.APP + a.pkg);
        }, false);
        showMenu(360);
    }

    private void setKey(int code, String action) {
        RemoteKeys.set(this, code, action);
        SleepService.applyButtonWatching();  // the first key, or the last one gone, can change whether it's on
        Toast.makeText(this, RemoteKeys.keyName(code) + ": " + RemoteKeys.describe(this, action), Toast.LENGTH_SHORT).show();
        openRemoteKeys();
    }

    private void setHold(int code, String action) {
        RemoteKeys.setHold(this, code, action);
        Toast.makeText(this, "Hold " + RemoteKeys.keyName(code) + ": " + (action == null ? "same as a press"
                : RemoteKeys.describe(this, action)), Toast.LENGTH_SHORT).show();
        openRemoteKeys();
    }

    // ---------------------------------------------------------------- Bluetooth

    /** "Off", "Sony WH-1000XM4" (connected), "2 paired" or "None paired", for Control Center's Bluetooth row. */
    private void bluetoothStatus(java.util.function.Consumer<String> done) {
        if (!Bluetooth.allowed(this)) { done.accept(null); return; }
        if (!Bluetooth.on(this)) { done.accept("Off"); return; }
        List<android.bluetooth.BluetoothDevice> paired = Bluetooth.speakers(this);
        if (paired.isEmpty()) { done.accept("None paired"); return; }
        Bluetooth.audio(this, a2dp -> {
            for (android.bluetooth.BluetoothDevice d : paired) {
                if (Bluetooth.state(a2dp, d) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) { done.accept(Bluetooth.name(d)); return; }
            }
            done.accept(paired.size() + " paired");
        });
    }

    /** Paired speakers and headphones: OK connects or disconnects one. Pairing a new one is the TV's job. */
    private void openBluetooth() {
        if (!Bluetooth.allowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            return;
        }
        if (Bluetooth.adapter(this) == null) {
            Toast.makeText(this, "This TV has no Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Bluetooth.on(this)) {
            // Android's own "Turn on Bluetooth?" question.
            start(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        Bluetooth.audio(this, a2dp -> {
            List<android.bluetooth.BluetoothDevice> speakers = Bluetooth.speakers(this);
            beginMenu(P_CONTROL, speakers.isEmpty() ? "Bluetooth  ·  no speakers or headphones paired"
                    : "Bluetooth  ·  OK connects or disconnects");
            for (android.bluetooth.BluetoothDevice d : speakers) {
                final View[] row = new View[1];
                row[0] = addRow(Bluetooth.name(d), btLabel(Bluetooth.state(a2dp, d)), () -> {
                    boolean connect = Bluetooth.state(a2dp, d) != android.bluetooth.BluetoothProfile.STATE_CONNECTED;
                    if (!Bluetooth.connect(a2dp, d, connect)) {
                        // Android refused (some TVs): its own Bluetooth page can do it.
                        start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        return;
                    }
                    setStatus(row[0], connect ? "Connecting…" : "Disconnecting…");
                    watchBluetooth(row[0], a2dp, d, 0);
                }, true);
            }
            addRow("Pair a new device", null, () -> start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)), false);
            showMenu(400);
        });
    }

    /** Updates the row as the connection settles, for up to ~10 s. */
    private void watchBluetooth(View row, android.bluetooth.BluetoothA2dp a2dp, android.bluetooth.BluetoothDevice d, int tries) {
        root.postDelayed(() -> {
            if (!row.isAttachedToWindow()) return;
            int st = Bluetooth.state(a2dp, d);
            boolean settling = st == android.bluetooth.BluetoothProfile.STATE_CONNECTING
                    || st == android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
            if (settling && tries < 10) { watchBluetooth(row, a2dp, d, tries + 1); return; }
            if (tries < 3 && !settling) {
                // Android may not have started yet: look a little longer before showing the outcome.
                setStatus(row, btLabel(st));
                watchBluetooth(row, a2dp, d, tries + 1);
                return;
            }
            setStatus(row, btLabel(st));
        }, 1000);
    }

    private static String btLabel(int state) {
        switch (state) {
            case android.bluetooth.BluetoothProfile.STATE_CONNECTED: return "Connected";
            case android.bluetooth.BluetoothProfile.STATE_CONNECTING: return "Connecting…";
            case android.bluetooth.BluetoothProfile.STATE_DISCONNECTING: return "Disconnecting…";
            default: return "Not connected";
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_BLUETOOTH && Bluetooth.allowed(this)) openBluetooth();
        // The Wi-Fi name and Watch Next are read as Talkies starts: start again with them.
        if (req == REQ_SETUP && !permissionsMissing()) recreate();
        if (req == REQ_PICTURES && checkSelfPermission(picturesPermission()) == PackageManager.PERMISSION_GRANTED) openTvPictures();
    }

    // ---------------------------------------------------------------- finish setup

    private static final int REQ_SETUP = 8;
    /** Runtime permissions: the Wi-Fi name (Android shares it only with location) and Android TV's Watch Next list. */
    private static final String[] SETUP_PERMISSIONS = {android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.READ_TV_LISTINGS"};  // not in the public Manifest constants

    private boolean permissionsMissing() {
        for (String p : SETUP_PERMISSIONS) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    private boolean notificationAccess() {
        String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(getPackageName() + "/");
    }

    /** How many first-time steps are still to do: the Home button service, notification access, the permissions. */
    private int setupMissing() {
        return (SleepService.isRunning() ? 0 : 1) + (notificationAccess() ? 0 : 1) + (permissionsMissing() ? 1 : 0);
    }

    /**
     * Asked once, the first time Talkies starts without them (an APK installed without a computer): the two
     * permissions. Talkies settings → Finish setup asks again, and lists the switches only Settings can turn on.
     */
    private void askPermissionsOnce() {
        if (!permissionsMissing() || prefs.getBoolean("setup_asked", false)) return;
        prefs.edit().putBoolean("setup_asked", true).apply();
        requestPermissions(SETUP_PERMISSIONS, REQ_SETUP);
    }

    /** Talkies settings → Finish setup: one row per step still to do; OK opens the place it's done. */
    private void openFinishSetup() {
        beginMenu(settingsAt, "Finish setup");
        if (!SleepService.isRunning()) addRow("Home button", "Accessibility → Sweep TV", () -> {
            Toast.makeText(this, "Switch on Sweep TV here: it keeps the Home button on Sweep TV", Toast.LENGTH_LONG).show();
            Intent i = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.oemlink.AccessibilitySettingsActivity");
            if (pm.resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            start(i);
        }, false);
        // Android 13 and later grey out these switches for an app installed from an APK, until allowed in App info
        // (or with one adb command where the TV's App info doesn't offer it: README → Questions and fixes).
        if (Build.VERSION.SDK_INT >= 33 && (!SleepService.isRunning() || !notificationAccess()))
            addRow("Switch greyed out?", "App info → Allow restricted settings", () -> {
                Toast.makeText(this, "Look for Allow restricted settings here. Not there? The README's Questions and fixes "
                        + "has the one command that allows it", Toast.LENGTH_LONG).show();
                start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }, false);
        if (!notificationAccess()) addRow("Notification access", "Continue, Now playing, the bell", () -> {
            Toast.makeText(this, "Switch on Sweep TV here", Toast.LENGTH_LONG).show();
            start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }, false);
        if (permissionsMissing()) addRow("Wi-Fi name and Watch Next", "OK asks", () -> {
            closeMenu();
            requestPermissions(SETUP_PERMISSIONS, REQ_SETUP);
        }, false);
        if (menuItems.isEmpty()) addRow("All done", null, () -> { }, true);
        showMenu(420);
    }

    // ---------------------------------------------------------------- network debugging

    /**
     * Network debugging (ADB over Wi-Fi) lets a computer on your network control the TV; it's how this launcher was
     * installed. "On · port 5555" when the TV answers on that port, "USB only", or "Off". Idea from CleanYourTV's
     * Remote Access add-on.
     */
    private void checkNetworkDebugging(java.util.function.Consumer<String> done) {
        background.execute(() -> {
            String st;
            if (Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 0) {
                st = "Off";
            } else {
                boolean listening;
                try (java.net.Socket sock = new java.net.Socket()) {
                    sock.connect(new java.net.InetSocketAddress("127.0.0.1", 5555), 400);
                    listening = true;
                } catch (Exception e) {
                    listening = false;
                }
                st = listening ? "On · port 5555" : "USB only";
            }
            final String r = st;
            runOnUiThread(() -> done.accept(r));
        });
    }

    private void openNetworkDebugging() {
        beginMenu(settingsAt, "Network debugging lets a computer on your network control this TV");
        boolean on = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0;
        if (on && ServiceRepair.canRepair(this)) {
            addRow("Turn off", "computers lose access", () -> {
                try {
                    Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                    Toast.makeText(this, "Network debugging is off. Turn it on again in Developer options.", Toast.LENGTH_LONG).show();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Couldn't turn off debugging: " + e);
                    start(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                }
            }, false);
        }
        addRow("Developer options", null, () -> start(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)), false);
        showMenu(460);
    }

    // ---------------------------------------------------------------- hold OK: app and Continue menus

    /** What holding OK does where focus is. The release that follows only ends the hold (holdFired). */
    private void onHold() {
        if (zone == Z_MENU) { startMove(); return; }  // the Channels editor: pick the row up
        holdFired = true;
        if (zone == Z_DOCK || zone == Z_GRID) openAppMenu();
        else if (zone == Z_TOP && iconIdx == P_SETTINGS) {
            openTalkiesSettings(P_SETTINGS);
            menuOpener = () -> openTalkiesSettings(P_SETTINGS);
        }
        else if (zone == Z_INSIGHTS) openTopInsightsMenu();
        else if (zone == Z_CHAN) openSectionMenu();
        else if (continueCard() != null) openContinueMenu();
        else if (zone == Z_PICKS || zone == Z_TABS) openDiscoveryMenu();
    }

    /** Move, Uninstall (apps you installed only), App info. */
    private void openAppMenu() {
        int ai = appIndex();
        if (ai < 0 || ai >= apps.size()) return;
        AppEntry a = apps.get(ai);
        final int z = zone, di = dockIdx, gi = gridIdx;
        View tile = z == Z_DOCK ? dockTiles.get(di) : gridTiles.get(gi);
        beginMenu(P_CONTROL, a.label);
        menuTile = tile;
        menuClosed = () -> { if (z == Z_DOCK) focusDock(di); else focusGrid(gi); };
        boolean inDock = ai < dockCount, anyOutside = apps.size() > dockCount;
        if (!inDock) addRow("Add to dock", null, () -> addToDock(a.pkg), false);
        else if (anyOutside) addRow("Remove from dock", null, () -> removeFromDock(a.pkg), false);
        // A sorted All apps would put a moved app straight back, so there only the dock's apps move.
        if (inDock || appsOrder() == ORDER_YOURS) addRow("Move", null, this::startMove, false);
        File own = AppEntry.customImage(a.pkg);
        addRow("Change image", own != null && own.isFile() ? "your picture" : null, () -> openTileImage(a.pkg), false);
        addRow("Hide", null, () -> hideApp(a.pkg), false);
        if (canUninstall(a.pkg)) {
            addRow("Uninstall", null, () -> start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))), false);
        }
        addRow("App info", null, () -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg))), false);
        showMenu(240);
    }

    // ---------------------------------------------------------------- dock and hidden apps

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** From All apps into the dock's last spot; the app that was there goes to the front of All apps. */
    private void addToDock(String pkg) { addToDock(pkg, true); }

    private void addToDock(String pkg, boolean focus) {
        int ai = indexOf(pkg);
        if (ai < dockCount || dockCount == 0) return;
        AppEntry app = apps.remove(ai), last = apps.remove(dockCount - 1);
        apps.add(dockCount - 1, app);
        apps.add(dockCount, last);
        saveOrder();
        buildAppViews();
        if (focus) root.post(() -> focusDock(dockCount - 1));
        Toast.makeText(this, app.label + " is in the dock; " + last.label + " moved to All apps", Toast.LENGTH_SHORT).show();
    }

    /** Out of the dock to the front of All apps; the first app in All apps takes its spot. */
    private void removeFromDock(String pkg) { removeFromDock(pkg, true); }

    private void removeFromDock(String pkg, boolean focus) {
        int ai = indexOf(pkg);
        if (ai < 0 || ai >= dockCount || apps.size() <= dockCount) return;
        AppEntry app = apps.remove(ai), next = apps.remove(dockCount - 1);
        apps.add(ai, next);
        apps.add(dockCount, app);
        saveOrder();
        buildAppViews();
        if (focus) root.post(() -> focusDock(ai));
        Toast.makeText(this, app.label + " moved to All apps; " + next.label + " is in the dock", Toast.LENGTH_SHORT).show();
    }

    /** Apps you hid (Hide in an app's hold-OK menu): left out of the dock and All apps, still installed. */
    private Set<String> hiddenApps() {
        String s = prefs.getString("hidden_apps", "");
        return s.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(s.split(",")));
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> h = hiddenApps();
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs.edit().putString("hidden_apps", String.join(",", h)).apply();
    }

    /** Hides the app; in the dock, the first app in All apps takes its spot. */
    private void hideApp(String pkg) {
        int ai = indexOf(pkg);
        if (ai < 0) return;
        AppEntry app = apps.remove(ai);
        if (ai < dockCount && apps.size() >= dockCount) apps.add(ai, apps.remove(dockCount - 1));
        saveOrder();
        setHidden(pkg, true);
        reloadApps();
        Toast.makeText(this, app.label + " hidden. Sweep TV settings → Hidden apps brings it back", Toast.LENGTH_LONG).show();
    }

    /** Talkies settings → Hidden apps: OK on one shows it again (at the end of All apps). */
    private void openHiddenApps() {
        beginMenu(settingsAt, "Hidden apps  ·  OK shows it again");
        List<String> pkgs = new ArrayList<>(hiddenApps());
        List<String[]> rows = new ArrayList<>();
        for (String p : pkgs) {
            try {
                rows.add(new String[]{p, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)))});
            } catch (PackageManager.NameNotFoundException e) {
                setHidden(p, false);  // uninstalled since: forget it
            }
        }
        rows.sort((x, y) -> x[1].compareToIgnoreCase(y[1]));
        for (String[] r : rows) {
            final View[] row = new View[1];
            row[0] = addRow(r[1], "Hidden", () -> {
                boolean nowHidden = !hiddenApps().contains(r[0]);
                setHidden(r[0], nowHidden);
                setStatus(row[0], nowHidden ? "Hidden" : "Shown");
                reloadApps();
            }, true);
        }
        if (rows.isEmpty()) addRow("No hidden apps", null, () -> { }, true);
        showMenu(360);
    }

    /** System apps (and Talkies) can't be uninstalled from here; Android's own dialog asks before removing one. */
    private boolean canUninstall(String pkg) {
        if (pkg.equals(getPackageName())) return false;
        try {
            int f = pm.getApplicationInfo(pkg, 0).flags;
            return (f & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The focused Continue card (Continue tab or the Watch Next row), or null. */
    private Trending.Pick continueCard() {
        if (zone == Z_PICKS && tabIdx == 0 && !rowPicks.isEmpty()) return rowPicks.get(Math.min(picksRow.anchor(), rowPicks.size() - 1));
        if (zone == Z_CHAN && chanIdx < chanRows.size() && Channels.WATCH_NEXT.equals(chanRows.get(chanIdx).key)) {
            List<Trending.Pick> items = chanRows.get(chanIdx).items;
            if (!items.isEmpty()) return items.get(Math.min(chanCol[chanIdx], items.size() - 1));
        }
        return null;
    }

    /** Remove from Continue, or open the app itself rather than the episode. */
    private void openContinueMenu() {
        Trending.Pick p = continueCard();
        if (p == null) return;
        final int z = zone, anchor = picksRow.anchor(), r = chanIdx;
        final int c = z == Z_CHAN ? Math.min(chanCol[r], chanCards.get(r).size() - 1) : 0;
        beginMenu(P_CONTROL, p.title);
        menuTile = z == Z_PICKS ? picksRow.anchorCard() : chanCards.get(r).get(c);
        menuClosed = () -> { if (z == Z_PICKS) focusPicks(anchor); else focusChan(r, c); };
        addRow("Remove from Continue", null, () -> removeFromContinue(p), false);
        Intent app = p.pkg == null ? null : pm.getLeanbackLaunchIntentForPackage(p.pkg);
        if (app != null) addRow("Open " + Channels.label(pm, p.pkg), null, () -> start(app), false);
        showMenu(320);
    }

    /** "pkg / title / episode line": a new episode of the same show is a new key, so it comes back. */
    private static String continueKey(Trending.Pick p) { return p.pkg + "\n" + p.title + "\n" + p.meta; }

    private void removeFromContinue(Trending.Pick p) {
        if ("recent".equals(p.kind)) {
            RecentStore.remove(this, p.pkg, p.title);
        } else {
            Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden_continue", java.util.Collections.emptySet()));
            hidden.add(continueKey(p));
            prefs.edit().putStringSet("hidden_continue", hidden).apply();
        }
        Log.i(TAG, "Removed from Continue: " + p.title);
        recentPicks = continuePicks(RecentStore.load(this), watchNextPicks);
        if (zone == Z_PICKS && tabIdx == 0) {
            int a = Math.max(0, Math.min(picksRow.anchor(), recentPicks.size() - 1));
            buildPicksRow(a);
            if (!rowPicks.isEmpty()) focusPicks(Math.min(a, rowPicks.size() - 1)); else focusDock(dockIdx);
        } else if (tabIdx == 0) {
            buildPicksRow(0);
        }
        refocusChannels = zone == Z_CHAN;
        refreshChannels();
    }

    // ---------------------------------------------------------------- network

    private String lastSpeed = "Test now";
    private boolean speedTesting;

    /** "TPG 5G", "Ethernet", or "Offline". The Wi-Fi name needs the location permission (granted by the install script). */
    private String networkName() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return "Offline";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Connected";
        android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        String ssid = wm == null || wm.getConnectionInfo() == null ? null : wm.getConnectionInfo().getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>")) return "Wi-Fi";
        return ssid.replaceAll("^\"|\"$", "");
    }

    /** A few seconds' download from Cloudflare; the row shows "Testing…", then "87 Mbps". Idea from Matinee (MIT). */
    private void runSpeedTest(View row) {
        if (speedTesting) return;
        speedTesting = true;
        setStatus(row, "Testing…");
        new Thread(() -> {
            String result;
            try {
                double mbps = SpeedTest.downloadMbps();
                result = (mbps >= 100 ? String.valueOf(Math.round(mbps)) : String.format(Locale.US, "%.1f", mbps)) + " Mbps";
            } catch (Exception e) {
                Log.w(TAG, "Speed test failed: " + e);
                result = "Couldn't test";
            }
            final String r = result;
            runOnUiThread(() -> {
                speedTesting = false;
                lastSpeed = r;
                if (row.isAttachedToWindow()) setStatus(row, r);
            });
        }, "speed-test").start();
    }

    // ---------------------------------------------------------------- app updates

    /** A newer Sweep TV bundle, when a Sweep TV release channel is configured. */
    private String sweepUpdate() {
        return SelfUpdate.takesReleases(this) ? SelfUpdate.available(this) : null;
    }

    /** The bell shows while the Play Store has updates waiting (Updates, fed by WatchService) or a newer Sweep TV bundle is out. */
    private void onUpdates() {
        boolean any = !Updates.list().isEmpty() || sweepUpdate() != null;
        icons[P_BELL].setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any && zone == Z_TOP && iconIdx == P_BELL) focusTop(P_SETTINGS);
        if (!any && zone == Z_MENU && menuAnchor == P_BELL) closeMenu();
    }

    /** One row per Play Store notification ("3 updates available" · "YouTube, Netflix…"); OK opens it in the Play Store. */
    private void openUpdates() {
        List<Updates.Item> list = Updates.list();
        String mine = sweepUpdate();
        if (list.isEmpty() && mine == null) return;
        beginMenu(P_BELL, "App updates");
        if (mine != null) {
            // Only Sweep TV bundles belong here; individual upstream projects never install separately.
            final View[] row = new View[1];
            row[0] = addRow("Sweep TV " + mine, "OK installs", () -> SelfUpdate.checkAndInstall(this, st -> runOnUiThread(() -> {
                if (row[0].isAttachedToWindow()) setStatus(row[0], st);
            })), true);
        }
        for (Updates.Item it : list) {
            String label = it.title.isEmpty() ? it.text : it.title;
            addRow(label, it.title.isEmpty() ? null : shorten(it.text, 34), () -> {
                try {
                    if (it.open != null) { it.open.send(); return; }
                } catch (Exception e) {
                    Log.w(TAG, "Couldn't open the update: " + e);
                }
                Intent store = pm.getLeanbackLaunchIntentForPackage("com.android.vending");
                if (store != null) start(store);
            }, false);
        }
        showMenu(480);
    }

    private static String shorten(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    // Ported from tv-vibehack's launcher (pr/control-center, MIT); see THIRD_PARTY_NOTICES.md.
    private static final int REQ_BLUETOOTH = 7;

    private void openControlCenter() {
        settingsPopupMode=false;settingsPopup.hide(false);
        beginMenu(P_CONTROL, "Control Center");
        if (serviceStopped) addRow("Fix Home and Turn off", "switch Sweep TV off and on", () -> {
            Toast.makeText(this, "Sweep TV → off, then on again", Toast.LENGTH_LONG).show();
            start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }, false);
        addRow("Sweep TV settings", null, () -> openTalkiesSettings(P_CONTROL), false);
        MediaController playing = NowPlaying.current(this);
        if (playing != null) addRow("Now playing", NowPlaying.title(playing), () -> openNowPlaying(playing), false);
        if (weatherLine != null) addRow("Weather", weatherLine, () -> openWeather(false), false);
        if (Sleep.SUPPORTED) {
            final View[] timer = new View[1];
            timer[0] = addRow("Sleep timer", sleepStatus(), () -> {
                if (!Sleep.available()) { needsTurnOffSetup(); return; }
                Sleep.cycle(this);
                setStatus(timer[0], sleepStatus());
            }, true);
            addRow("Turn off now", null, () -> { if (!Sleep.now()) needsTurnOffSetup(); }, false);
        }
        final View[] mem = new View[1];
        mem[0] = addRow("Free up memory", availableMb() + " MB free", () -> freeMemory(mem[0]), true);
        addRow("Recent apps", null, this::openRecents, false);
        addRow("Storage", storageLine(), () -> { if (!openShortcut("storage")) start(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }, false);
        final View[] anim = new View[1];
        anim[0] = addRow("Animations", animationLabel(), () -> {
            if (!cycleAnimations()) {
                Toast.makeText(this, "Needs the WRITE_SECURE_SETTINGS grant (see the README)", Toast.LENGTH_LONG).show();
                return;
            }
            setStatus(anim[0], animationLabel());
        }, true);
        addRow("Network", networkName(), () -> { if (!openShortcut("network")) start(new Intent(Settings.ACTION_WIFI_SETTINGS)); }, false);
        final View[] speed = new View[1];
        speed[0] = addRow("Speed test", lastSpeed, () -> runSpeedTest(speed[0]), true);
        final View[] bt = new View[1];
        bt[0] = addRow("Bluetooth", null, () -> { if (!openShortcut("bluetooth")) openBluetooth(); }, false);
        bluetoothStatus(st -> { if (bt[0].isAttachedToWindow()) setStatus(bt[0], st); });
        // Android tells apps a soundbar is connected (HDMI ARC/eARC), not whether it's playing: say only what's known.
        addRow("Sound output", soundbarConnected() ? "Soundbar connected" : "TV speakers", () -> {
            if (openShortcut("sound")) return;
            // Where the speakers are chosen differs by maker: Sony keeps it under Display & sound → Audio output, and
            // its plain Sound page has only System sounds. Elsewhere the Sound page (sound mode, outputs).
            Intent i = new Intent();
            if ("sony".equalsIgnoreCase(Build.MANUFACTURER)) {
                i.setClassName("com.android.tv.settings", "com.android.tv.settings.device.displaysound.DisplaySoundActivity");
                if (pm.resolveActivity(i, 0) == null) i = new Intent();
            }
            if (i.getComponent() == null) i.setClassName("com.android.tv.settings", "com.android.tv.settings.device.sound.SoundActivity");
            if (pm.resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_SOUND_SETTINGS);
            start(i);
        }, false);
        Intent dream = screensaverIntent();
        if (dream != null) addRow("Screensaver", null, () -> start(dream), false);
        // The TV's own power menu, so it still asks before restarting.
        addRow("Restart…", null, () -> { if (!SleepService.powerMenu()) needsTurnOffSetup(); }, false);
        showMenu(350);
    }

    /**
     * Talkies settings: what you set once and rarely change (Control Center keeps what you reach for while watching).
     * From Control Center's last row, or by holding OK on the gear (a press opens the TV's own settings).
     */
    private void openTalkiesSettings(int anchor) {
        settingsAt=anchor;beginSettingsMenu(anchor,"Sweep TV · Settings");
        String[] labels={"Profile and greeting","Appearance","Home sections","Watching and discovery","Apps and dock","Status widget","Connections and controls","Updates","About and licences"};
        String[] descriptions={"Your name, greeting and weather","Sky colours, wallpaper and motion","Choose what appears on Home","Continue Watching and discovery","Order, size and hidden apps","RAM, storage and connected stats","Location, remote and TV controls","Release notes and included components","Version, sources and licences"};
        int[] icons={R.drawable.ic_settings_profile,R.drawable.ic_settings_palette,R.drawable.ic_settings_layers,R.drawable.ic_settings_play,R.drawable.ic_apps,R.drawable.ic_settings_meter,R.drawable.ic_settings_link,R.drawable.ic_bell,R.drawable.ic_settings_info};
        Runnable[] actions={this::openProfileSettings,()->openSettingsGroup(0),this::openSectionsSettings,()->openSettingsGroup(1),()->openSettingsGroup(2),this::openWidgetSettings,()->openSettingsGroup(3),this::openMaintenance,this::openAbout};
        LinearLayout track=null;
        for(int i=0;i<labels.length;i++){
            if(i%3==0){track=new LinearLayout(this);track.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(88));if(i>0)lp.topMargin=px(10);menu.addView(track,lp);}
            View category=settingsCategory(labels[i],descriptions[i],icons[i]);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1);if(i%3>0)lp.leftMargin=px(10);track.addView(category,lp);
            menuItems.add(category);menuActions.add(actions[i]);menuKeepsOpen.add(false);
        }
        showMenu(824);menuOpener=()->openTalkiesSettings(anchor);
    }

    private View settingsCategory(String label,String description,int iconId){
        FrameLayout card=new FrameLayout(this);
        ImageView icon=new ImageView(this);icon.setTag("settings-icon");icon.setImageResource(iconId);icon.setPadding(px(7),px(7),px(7),px(7));
        FrameLayout.LayoutParams il=new FrameLayout.LayoutParams(px(32),px(32));il.leftMargin=px(12);il.topMargin=px(13);card.addView(icon,il);
        TextView title=new TextView(this);title.setId(R.id.input_label);title.setText(label);title.setTypeface(getResources().getFont(R.font.outfit));title.setFontVariationSettings("'wght' 600");title.setTextSize(15);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);tl.leftMargin=px(55);tl.rightMargin=px(12);tl.topMargin=px(12);card.addView(title,tl);
        TextView detail=new TextView(this);detail.setId(R.id.input_status);detail.setText(description);detail.setTypeface(getResources().getFont(R.font.outfit));detail.setFontVariationSettings("'wght' 400");detail.setTextSize(10.5f);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams dl=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);dl.leftMargin=px(55);dl.rightMargin=px(12);dl.topMargin=px(37);card.addView(detail,dl);
        SettingsPopup.prepareRow(card,theme.iconFocusBg,true);return card;
    }

    private void openSettingsGroup(int group) {
        String[] names={"Appearance","Recommendations and watching","Apps and dock","Connections and controls"};
        beginSettingsMenu(settingsAt,names[group]);
        if(group==0) {
        final View[] look = new View[1];
        look[0] = addRow("Appearance", prefs.getBoolean(TimeOfDay.PREF,false)?"Time of day":APPEARANCE[prefs.getInt("appearance", 0)], () -> {
            if(prefs.getBoolean(TimeOfDay.PREF,false)) {
                Toast.makeText(this,"Choose App colours under Background to set a fixed appearance",Toast.LENGTH_SHORT).show();return;
            }
            int mode = (prefs.getInt("appearance", 0) + 1) % APPEARANCE.length;
            prefs.edit().putInt("appearance", mode).apply();
            setStatus(look[0], APPEARANCE[mode]);
            day = isDayNow();
            applyTheme();
            // Restyle the open menu too.
            menuScroll.setBackground(settingsPopupMode?new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT):rounded(theme.menuBg, theme.menuStroke, 20));
            View heading = menu.getChildAt(0);
            if (heading instanceof TextView && !menuItems.contains(heading)) ((TextView) heading).setTextColor(theme.text2);
            focusMenu(menuIdx);
        }, true);
        final View[] timeBackground = new View[1];
        timeBackground[0] = addRow("Background",prefs.getBoolean(TimeOfDay.PREF,false)?"Time of day":"App colours",()->{
            boolean enabled=!prefs.getBoolean(TimeOfDay.PREF,false);
            prefs.edit().putBoolean(TimeOfDay.PREF,enabled).apply();
            setStatus(timeBackground[0],enabled?"Time of day":"App colours");
            setStatus(look[0],enabled?"Time of day":APPEARANCE[prefs.getInt("appearance",0)]);
            day=isDayNow();applyTheme();
        },true);
        addRow("Wallpaper",wallpaperLabel(),this::openWallpaperMenu,false);
        final View[] anim = new View[1];
        anim[0] = addRow("Apps open from their tile", prefs.getBoolean("open_animation", true) ? "On" : "Off", () -> {
            boolean on = !prefs.getBoolean("open_animation", true);
            prefs.edit().putBoolean("open_animation", on).apply();
            setStatus(anim[0], on ? "On" : "Off");
        }, true);
        } else if(group==1) {
        final View[] fy = new View[1];
        fy[0] = addRow("For You", forYou ? "Shown" : "Hidden", () -> {
            forYou = !forYou;
            prefs.edit().putBoolean("for_you", forYou).apply();
            setStatus(fy[0], forYou ? "Shown" : "Hidden");
            applyForYou();
            tabIdx = TAB_TRENDING;
            buildPicksRow(0);
            if (forYou) refreshOnline();
        }, true);
        addRow("Content languages", languagesLabel(), this::openPicksLanguages, false);
        addRow("Continue Watching",ContinueOptions.summary(this),this::openContinueOptions,false);
        final View[] recommendations=new View[1];
        recommendations[0]=addRow("Recommendations",prefs.getBoolean("recommendations",true)?"Shown":"Hidden",()->{
            boolean shown=!prefs.getBoolean("recommendations",true);
            prefs.edit().putBoolean("recommendations",shown).apply();SectionOptions.set(this,Recommendations.KEY,shown);
            setStatus(recommendations[0],shown?"Shown":"Hidden");showChannels(appChannels);
            if(shown)refreshOnline();
        },true);
        final View[] releases=new View[1];
        releases[0]=addRow("New releases",prefs.getBoolean("new_releases",true)?"Shown":"Hidden",()->{
            boolean shown=!prefs.getBoolean("new_releases",true);prefs.edit().putBoolean("new_releases",shown).apply();SectionOptions.set(this,Recommendations.NEW_KEY,shown);
            setStatus(releases[0],shown?"Shown":"Hidden");showChannels(appChannels);if(shown)refreshOnline();
        },true);
        final View[] sp = new View[1];
        sp[0] = addRow("Hide spoilers", WatchNext.hideSpoilers(this) ? "On" : "Off", () -> {
            boolean on = !WatchNext.hideSpoilers(this);
            prefs.edit().putBoolean("hide_spoilers", on).apply();
            setStatus(sp[0], on ? "On" : "Off");
            recentPicks = new ArrayList<>();  // rebuild Continue with the other pictures
            refreshWatchNext();
        }, true);
        } else if(group==2) {
        addRow("Dock apps", dockCount + " in the dock", this::openDockApps, false);
        final View[] ds = new View[1];
        ds[0] = addRow("Dock size", dockSize() + " apps", () -> {
            int n = dockSize() + 1;
            if (n > DOCK_SIZES[DOCK_SIZES.length - 1]) n = DOCK_SIZES[0];
            prefs.edit().putInt("dock_size", n).apply();
            setStatus(ds[0], n + " apps");
            reloadApps();
        }, true);
        final View[] ao = new View[1];
        ao[0] = addRow("All apps order", APPS_ORDER[appsOrder()], () -> {
            int n = (appsOrder() + 1) % APPS_ORDER.length;
            prefs.edit().putInt("apps_order", n).apply();
            setStatus(ao[0], APPS_ORDER[n]);
            reloadApps();
        }, true);
        int hidden=hiddenApps().size();if(hidden>0)addRow("Hidden apps",String.valueOf(hidden),this::openHiddenApps,false);
        } else {
        int missing=setupMissing();if(missing>0)addRow("Finish setup",missing+" steps left",this::openFinishSetup,false);
        addRow("Keys and location",null,()->openKeysAndLocation(0),false);
        addRow("Remote keys", remoteKeysLabel(),
                this::openRemoteKeys, false);
        final View[] adb = new View[1];
        adb[0] = addRow("Network debugging", "Checking…", this::openNetworkDebugging, false);
        checkNetworkDebugging(st -> { if (adb[0].isAttachedToWindow()) setStatus(adb[0], st); });
        final View[] wake = new View[1];
        wake[0] = addRow("Sweep TV at power-on", wakeLabel(), () -> {
            if (!SleepService.isRunning()) { needsTurnOffSetup(); return; }
            prefs.edit().putString("home_at_power_on", SleepService.homeOnWake(this) ? "boot" : "always").apply();
            setStatus(wake[0], wakeLabel());
        }, true);
        final View[] ports = new View[1];
        ports[0] = addRow("Empty inputs", prefs.getBoolean("inputs_connected_only", false) ? "Under More inputs" : "Shown", () -> {
            boolean only = !prefs.getBoolean("inputs_connected_only", false);
            prefs.edit().putBoolean("inputs_connected_only", only).apply();
            setStatus(ports[0], only ? "Under More inputs" : "Shown");
        }, true);
        // Switch between Talkies and another launcher with Android's Home app chooser.
        // Google TV keeps its own launcher as the home app whatever is picked, so there Talkies has Home through its
        // Accessibility switch: that's what to turn off to use another home screen.
        ResolveInfo other = otherHome();
        if (other != null) {
            // Another launcher you installed has Home (CleanYourTV's, say), and Talkies steps aside for it. Removing
            // it is the way back; its App info page has Uninstall and Disable.
            String name = String.valueOf(other.loadLabel(pm));
            addRow("Home app", name, () -> {
                Toast.makeText(this, "Uninstall or disable " + name + " here, then press Home: Sweep TV takes over",
                        Toast.LENGTH_LONG).show();
                start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + other.activityInfo.packageName)));
            }, false);
        }
        else if (isGoogleTvLauncher()) addRow("Home app", SleepService.isRunning() ? "Sweep TV" : currentHomeName(), () -> {
            Toast.makeText(this, "To use another home screen, turn off Sweep TV here. Turn it back on to come back to Sweep TV",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.oemlink.AccessibilitySettingsActivity");
            if (pm.resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            start(i);
        }, false);
        else addRow("Home app", currentHomeName(), () -> start(new Intent(Settings.ACTION_HOME_SETTINGS)), false);
        addRow("Control Center shortcuts",null,this::openShortcuts,false);
        }
        showMenu(450);
    }

    private void rebuildSettings(Runnable opener) {boolean previous=fromMenuRow;fromMenuRow=true;try{opener.run();}finally{fromMenuRow=previous;}}
    private void openProfileSettings() {
        beginSettingsMenu(settingsAt,"Profile and greeting");
        addRow("Your name",HomeOptions.name(this).isEmpty()?"Not set":HomeOptions.name(this),()->askText("Your name","Shown only on this TV. Leave empty for a greeting without a name.",HomeOptions.name(this),false,!HomeOptions.name(this).isEmpty(),name->{
            if(!Keys.write(this,HomeOptions.NAME,HomeOptions.cleanName(name))){Toast.makeText(this,"Couldn't save name",Toast.LENGTH_SHORT).show();return;}
            Profiles.remember(this);updateInfo();rebuildSettings(this::openProfileSettings);
        }),true);
        addRow("Profile picture",Profiles.avatar(this)<0?"Your photo":Avatars.NAMES[Profiles.avatar(this)],this::openAvatarOptions,false);
        addRow("Switch or add profile",Profiles.ids(this).size()+" on this TV",this::openProfiles,false);
        addRow("Content languages",languagesLabel(),this::openPicksLanguages,false);
        toggleOption("Greeting","header_greeting",true,()->updateInfo());
        toggleOption("Weather beside greeting","header_weather",true,()->updateInfo());
        recommendationText("Names, avatars and language choices stay on this TV. Streaming accounts and Continue Watching are shared between profiles.",11,theme.text2);showMenu(460);
    }
    private void toggleOption(String title,String key,boolean fallback,Runnable changed) {
        final View[] row=new View[1];row[0]=addRow(title,prefs.getBoolean(key,fallback)?"On":"Off",()->{boolean on=!prefs.getBoolean(key,fallback);prefs.edit().putBoolean(key,on).apply();setStatus(row[0],on?"On":"Off");changed.run();},true);
    }
    private void openWidgetSettings() {
        beginSettingsMenu(settingsAt,"Status widget");
        addRow("AQI, festivals and news","Header widgets",this::openInsightsSettings,false);
        recommendationText("A small status pill below the controls. Each value can be hidden.",12,theme.text2);
        toggleOption("Show status widget","meters",true,()->meters.update());
        for(String[] metric:new String[][]{{"ram","Free RAM"},{"storage","Free storage"},{"uptime","TV uptime"},{"codex","Codex usage"}}) {
            final String key=metric[0];final View[] row=new View[1];row[0]=addRow(metric[1],HomeOptions.stat(this,key)?"On":"Off",()->{boolean on=!HomeOptions.stat(this,key);HomeOptions.stat(this,key,on);setStatus(row[0],on?"On":"Off");meters.update();},true);
        }
        CodexUsage usage=CodexUsage.read(this);
        recommendationText(usage==null?"Codex: connect the Mac companion to sync usage. Your account credentials stay on the Mac.":"Codex · "+usage.detail(System.currentTimeMillis()),11,theme.text2);
        recommendationText("RAM and disk show free capacity. Codex shows remaining quota; it asks for a sync when the snapshot is old.",11,theme.text2);showMenu(460);
    }
    private void openInsightsSettings(){
        beginSettingsMenu(settingsAt,"AQI, festivals and news");
        toggleOption("Show header widgets","insights",true,()->{SectionOptions.set(this,HomeInsights.KEY,prefs.getBoolean("insights",true));showChannels(appChannels);refreshOnline();});
        for(String[] item:new String[][]{{"Air quality","insight_air"},{"News headlines","insight_news"},{"Local holidays and festivals","insight_festivals"}})toggleOption(item[0],item[1],true,()->{showChannels(appChannels);insights.invalidate();refreshOnline();});
        addRow("Location",weather.locationLabel(),()->openKeysAndLocation(4),false);
        for(String[] item:new String[][]{{"WSJ","wsj"},{"CNN","cnn"},{"CNBC","cnbc"},{"TechCrunch AI","techcrunch"}})toggleOption(item[0],"insight_source_"+item[1],true,()->{showChannels(appChannels);insights.invalidate();refreshOnline();});
        recommendationText("AQI is a labelled Open-Meteo/CAMS estimate on the US scale. Holidays follow the weather location. News focuses on US companies, AI, technology and markets; source outages keep dated cached headlines.",12,theme.text2);showMenu(560);
    }
    private void openInsight(HomeInsights.Card card){
        if("news".equals(card.kind)){
            List<HomeInsights.Story> stories=card.stories;String[] labels=new String[stories.size()];for(int i=0;i<labels.length;i++){HomeInsights.Story story=stories.get(i);labels[i]=story.source+" · "+story.topic+" · "+HomeInsights.time(story.published)+"\n"+story.title;}
            android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("US AI, tech & markets").setNegativeButton("Close",null).setNeutralButton("Sources",(d,w)->new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Publisher feeds").setMessage(card.detail.isEmpty()?"Connect to refresh publisher feeds":card.detail).setPositiveButton("OK",null).show());
            if(labels.length>0)dialog.setItems(labels,(d,index)->{HomeInsights.Story story=stories.get(index);showInsightLink(story.title,story.source+" · "+HomeInsights.time(story.published)+(story.source.startsWith("WSJ")?"\nPublisher sign-in may be required.":""),story.url);});else dialog.setMessage("No fresh headlines are available. Check Sources for each feed's status.");dialog.show();return;
        }
        android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("air".equals(card.kind)?"Air quality estimate":card.title).setMessage(card.detail).setNegativeButton("Close",null);
        if(!card.url.isEmpty())dialog.setPositiveButton("Open on phone",(d,w)->showInsightLink(card.title,"air".equals(card.kind)?"Compare with AQI.in station readings":"Office Holidays regional calendar",card.url));
        if("air".equals(card.kind))dialog.setNeutralButton("Location settings",(d,w)->openKeysAndLocation(4));dialog.show();
    }
    private void showInsightLink(String title,String detail,String url){
        if(url==null||!url.startsWith("https://"))return;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(px(24),px(8),px(24),px(8));box.addView(qrCode(url),new LinearLayout.LayoutParams(px(180),px(180)));TextView note=new TextView(this);note.setText("Scan with your phone to read at the publisher. No streaming account is needed by Sweep TV.");note.setTextSize(12);box.addView(note);new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setMessage(detail).setView(box).setPositiveButton("Close",null).show();
    }

    private List<Channels.Channel> sectionDefinitions() {
        List<Channels.Channel> rows=new ArrayList<>();
        for(String[] value:new String[][]{{HomeInsights.KEY,"Header widgets · AQI, news and festivals"},{FormulaOne.KEY,"Formula 1"},{Recommendations.KEY,"Recommended for you"},{Recommendations.NEW_KEY,"New releases"},{"cricket","Cricket scores"},{Channels.WATCH_NEXT,"Continue Watching row"}}) {
            Channels.Channel row=new Channels.Channel();row.key=value[0];row.title=value[1];row.shown=sectionEnabled(row.key);rows.add(row);
        }
        for(Channels.Channel row:Channels.list(this,watchNextPicks.size()))if(!Channels.WATCH_NEXT.equals(row.key))rows.add(row);
        return rows;
    }
    private boolean sectionEnabled(String key) {
        if(SectionOptions.hidden(this,key))return false;
        if(HomeInsights.KEY.equals(key))return prefs.getBoolean("insights",true);
        if(FormulaOne.KEY.equals(key))return prefs.getBoolean("formula_one",true);
        if(Recommendations.KEY.equals(key))return prefs.getBoolean("recommendations",true);
        if(Recommendations.NEW_KEY.equals(key))return prefs.getBoolean("new_releases",true);
        if("cricket".equals(key))return !scores().equals("off");
        for(Channels.Channel row:Channels.list(this,watchNextPicks.size()))if(row.key.equals(key))return row.shown;
        return Channels.WATCH_NEXT.equals(key)&&Channels.isShown(this,key,true);
    }
    private void setSectionVisibility(String key,boolean shown) {
        SectionOptions.set(this,key,shown);
        if(HomeInsights.KEY.equals(key))prefs.edit().putBoolean("insights",shown).apply();
        else if(FormulaOne.KEY.equals(key))prefs.edit().putBoolean("formula_one",shown).apply();
        else if(Recommendations.KEY.equals(key))prefs.edit().putBoolean("recommendations",shown).apply();
        else if(Recommendations.NEW_KEY.equals(key))prefs.edit().putBoolean("new_releases",shown).apply();
        else if("cricket".equals(key))prefs.edit().putString("scores",shown?"cricket":"off").apply();
        else {List<Channels.Channel> rows=Channels.list(this,watchNextPicks.size());boolean found=false;for(Channels.Channel row:rows)if(key.equals(row.key)){row.shown=shown;found=true;}if(!found&&Channels.WATCH_NEXT.equals(key)){Channels.Channel row=new Channels.Channel();row.key=key;row.title="Continue Watching";row.shown=shown;rows.add(row);}Channels.save(this,rows);}
        chanSignature=null;showChannels(appChannels);refreshChannels();if(shown)refreshOnline();
    }
    private void openSectionsSettings() {
        beginSettingsMenu(settingsAt,"Home sections");
        recommendationText("Hold OK on any card in a row to remove that entire section. Restore it here at any time.",12,theme.text2);
        for(Channels.Channel section:sectionDefinitions()) {
            final View[] row=new View[1];row[0]=addRow(section.title,sectionEnabled(section.key)?"Shown":"Hidden",()->{boolean show=!sectionEnabled(section.key);setSectionVisibility(section.key,show);setStatus(row[0],show?"Shown":"Hidden");},true);
        }
        addRow("App section order","Hold OK to move",this::openChannelsEditor,false);
        recommendationText("Empty rows appear when their app publishes content. Removing a section does not uninstall its app or erase watch history.",11,theme.text2);showMenu(470);
    }
    private void openSectionMenu() {
        if(chanIdx>=chanRows.size())return;
        final int row=chanIdx,col=chanCol[row];final Channels.Channel section=chanRows.get(row);final Trending.Pick recent=continueCard();
        beginMenu(P_CONTROL,section.title);menuTile=chanCards.get(row).get(col);menuClosed=()->{if(row<chanRows.size())focusChan(row,col);else focusDock(dockIdx);};
        addRow("Remove section from Home","Restore in Settings",()->{focusDock(dockIdx);setSectionVisibility(section.key,false);Toast.makeText(this,section.title+" hidden",Toast.LENGTH_SHORT).show();},false);
        if(recent!=null)addRow("Remove this title from Continue",null,()->removeFromContinue(recent),false);
        addRow("Customise Home sections",null,this::openSectionsSettings,false);showMenu(430);
    }
    private void openDiscoveryMenu() {
        beginMenu(P_CONTROL,"Discovery shelf");
        addRow("Remove discovery shelf",null,()->{forYou=false;prefs.edit().putBoolean("for_you",false).apply();applyForYou();focusDock(dockIdx);},false);
        addRow("Discovery settings",null,()->openSettingsGroup(1),false);showMenu(400);
    }
    private void openMaintenance() {
        beginSettingsMenu(settingsAt,"Sweep TV updates");
        recommendationText("Sweep TV "+SelfUpdate.installed(this),19,theme.text);
        recommendationText("One release for your entire launcher.",14,theme.text2);
        addRow("What's new","This Sweep TV release",this::openReleaseNotes,false);
        addRow("Included components","Versions and source credits",this::openReleaseComponents,false);
        recommendationText("Verified component changes are bundled into Sweep TV updates. Updates are currently managed from your computer.",12,theme.text2);
        showMenu(470);
    }
    private void openReleaseNotes() {
        beginSettingsMenu(settingsAt,"What's new in Sweep TV");
        recommendationText(SelfUpdate.installed(this),18,theme.text);
        for(String note:ReleaseInfo.notes(this))recommendationText(note,14,theme.text);
        showMenu(560);
    }
    private void openReleaseComponents() {
        beginSettingsMenu(settingsAt,"Included in Sweep TV");
        for(ReleaseInfo.Component component:ReleaseInfo.components(this)) {
            addRow(component.name,shorten(component.detail,48),()->openReleaseComponent(component),false);
        }
        addRow("Open-source licences","Full notices",()->openLicence("THIRD_PARTY_NOTICES.md"),false);
        showMenu(560);
    }

    private void openReleaseComponent(ReleaseInfo.Component component) {
        beginSettingsMenu(settingsAt,component.name);
        recommendationText(component.detail,14,theme.text);
        showMenu(560);
    }

    private String languagesLabel() {
        Set<String> chosen = trending.languages();
        if (chosen.isEmpty()) return "All";
        List<String> names = new ArrayList<>();
        for (String c : chosen) names.add(Trending.languageName(c));
        return String.join(", ", names);
    }

    /**
     * Talkies settings → Picks languages: the languages Trending and New in … show, from those the country's lists
     * bring up. OK on one shows only the ones you choose; OK again leaves it out; All languages goes back to all.
     */
    private void openContinueOptions() {
        beginSettingsMenu(settingsAt,"Continue Watching");
        final View[] youtube=new View[1],artwork=new View[1];
        youtube[0]=addRow("Include YouTube",ContinueOptions.youtube(this)?"On":"Off",()->{
            boolean enabled=!ContinueOptions.youtube(this);
            prefs.edit().putBoolean(ContinueOptions.YOUTUBE,enabled).apply();
            setStatus(youtube[0],enabled?"On":"Off");refreshContinueChoices();
        },true);
        artwork[0]=addRow("Artwork",ContinueOptions.titleArtwork(this)?"Title artwork":"Playback still",()->{
            boolean original=!ContinueOptions.titleArtwork(this);
            prefs.edit().putBoolean(ContinueOptions.ARTWORK,original).apply();
            setStatus(artwork[0],original?"Title artwork":"Playback still");refreshContinueChoices();
        },true);
        showMenu(460);
    }

    private void refreshContinueChoices() {
        if(ContinueOptions.titleArtwork(this))for(Trending.Pick p:watchNextPicks) {
            TitleArtwork.applyCached(this,p);
        }
        recentPicks=continuePicks(RecentStore.load(this),watchNextPicks);
        if(tabIdx==0)buildPicksRow(picksRow.anchor());
        refreshChannels();refreshWatchNext();
    }

    private void contentLanguagesChanged() {
        Profiles.remember(this);
        Set<String> chosen=trending.languages();
        recommendationCatalog=ContentLanguages.filter(recommendationCatalog,chosen);
        trendPicks=ContentLanguages.filter(trendPicks,chosen);indiaPicks=ContentLanguages.filter(indiaPicks,chosen);
        tabs[2].setText(chosen.isEmpty()?"New in "+Region.name(Region.country(this)):"New releases");
        // Scrub the old rows immediately, rather than waiting for the usual navigation-idle window.
        refocusChannels=true;showChannels(appChannels);if(tabIdx!=0)buildPicksRow(0);
        background.execute(()->{List<Trending.Pick> saved=trending.cached();runOnUiThread(()->{
            recommendationCatalog=ContentLanguages.filter(saved,trending.languages());
            trendPicks=new ArrayList<>();indiaPicks=new ArrayList<>();
            for(Trending.Pick pick:recommendationCatalog){if(Trending.TRENDING.equals(pick.tab))trendPicks.add(pick);if(Trending.INDIA.equals(pick.tab))indiaPicks.add(pick);}
            showChannels(appChannels);if(tabIdx!=0)buildPicksRow(0);refreshOnline();
        });});
    }

    private void openPicksLanguages() {
        List<String> codes=new ArrayList<>(ContentLanguages.AVAILABLE);
        for(String code:trending.seenLanguages())if(!codes.contains(code))codes.add(code);
        for(String code:trending.languages())if(!codes.contains(code))codes.add(code);
        beginSettingsMenu(settingsAt,"Content languages");
        recommendationText("Choose original languages for every recommendation shelf. Region still controls where titles stream. Continue Watching stays unfiltered. App recommendations with an unknown language are hidden.",12,theme.text2);
        List<View> rows=new ArrayList<>();
        Runnable mark=()->{Set<String> chosen=trending.languages();setStatus(rows.get(0),chosen.isEmpty()?"On":"Off");for(int i=0;i<codes.size();i++)setStatus(rows.get(i+1),chosen.contains(codes.get(i))?"On":"Off");};
        rows.add(addRow("All languages",null,()->{trending.setLanguages(new java.util.LinkedHashSet<>());mark.run();contentLanguagesChanged();},true));
        for(String code:codes)rows.add(addRow(Trending.languageName(code),null,()->{
            Set<String> chosen=trending.languages();
            if(chosen.contains(code)&&chosen.size()==1){Toast.makeText(this,"Keep one language, or choose All languages",Toast.LENGTH_SHORT).show();return;}
            if(!chosen.remove(code))chosen.add(code);trending.setLanguages(chosen);mark.run();contentLanguagesChanged();
        },true));
        mark.run();showMenu(460);
    }

    private void openProfiles(){
        beginSettingsMenu(settingsAt,"Local profiles");
        recommendationText("Each profile has its own name, picture and content languages. Streaming accounts and Continue Watching are shared.",12,theme.text2);
        String active=Profiles.active(this);
        for(String id:Profiles.ids(this))addRow(Profiles.name(this,id).isEmpty()?"Guest":Profiles.name(this,id),id.equals(active)?"Active":null,()->{
            if(Profiles.switchTo(this,id)){updateInfo();contentLanguagesChanged();rebuildSettings(this::openProfiles);}
        },true);
        if(Profiles.ids(this).size()<6)addRow("Add profile",null,()->askText("New profile","Enter a name for this TV profile.","",false,false,name->{
            if(HomeOptions.cleanName(name).isEmpty())return;
            String id=Profiles.add(this,name);if(Profiles.switchTo(this,id)){updateInfo();contentLanguagesChanged();rebuildSettings(this::openProfileSettings);}
        }),true);
        showMenu(460);
    }

    private void openAvatarOptions(){
        beginSettingsMenu(settingsAt,"Profile picture");
        addRow("Choose an avatar","36 avatars · 3 styles",this::openAvatarPicker,false);
        addRow("Surprise me","Random avatar",()->{
            Profiles.randomizeAvatar(this);updateInfo();rebuildSettings(this::openAvatarOptions);
        },true);
        addRow("Photo from your phone",Profiles.avatar(this)<0?"Your current picture":"Scan a QR code",this::openProfilePhoto,true);
        recommendationText("Avatars are bundled offline. To use your YouTube picture, save that picture on your phone and send it here. YouTube sign-in does not grant Sweep TV access to your account.",12,theme.text2);showMenu(460);
    }

    private void openAvatarPicker(){
        beginSettingsMenu(settingsAt,"Choose avatar style");
        for(int group=0;group<Avatars.GROUPS.length;group++){
            final int start=Avatars.GROUP_STARTS[group];
            String description=Avatars.GROUP_DETAILS[group]+(Profiles.avatar(this)>=start&&Profiles.avatar(this)<start+12?" · Current style":"");
            View card=settingsCategory(Avatars.GROUPS[group],description,R.drawable.ic_settings_profile);
            ImageView preview=card.findViewWithTag("settings-icon");preview.setTag(null);preview.setImageTintList(null);preview.setBackground(null);preview.setPadding(0,0,0,0);preview.setImageBitmap(Avatars.preset(this,start));preview.setClipToOutline(true);preview.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(View view,android.graphics.Outline outline){outline.setOval(0,0,view.getWidth(),view.getHeight());}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(88));if(group>0)lp.topMargin=px(10);menu.addView(card,lp);menuItems.add(card);menuActions.add(()->openAvatarGrid(start));menuKeepsOpen.add(false);
        }
        showMenu(824);
    }

    private void openAvatarGrid(int start){
        int group=start/12;String heading=group==1?"Animals":group==2?"Little things":"Simple faces";
        beginSettingsMenu(settingsAt,heading);settingsGrid=true;settingsColumns=4;
        LinearLayout track=null;
        for(int cell=0;cell<12;cell++){
            int i=start+cell;
            if(cell%4==0){track=new LinearLayout(this);track.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(88));if(cell>0)lp.topMargin=px(10);menu.addView(track,lp);}
            final int index=i,position=cell;FrameLayout card=new FrameLayout(this);
            ImageView image=new ImageView(this);image.setImageBitmap(Avatars.preset(this,i));image.setClipToOutline(true);image.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(View view,android.graphics.Outline outline){outline.setOval(0,0,view.getWidth(),view.getHeight());}});FrameLayout.LayoutParams il=new FrameLayout.LayoutParams(px(58),px(58));il.leftMargin=px(10);il.topMargin=px(15);card.addView(image,il);
            TextView title=new TextView(this);title.setId(R.id.input_label);title.setText(Avatars.NAMES[i]);title.setTextSize(13);title.setTypeface(getResources().getFont(R.font.outfit));FrameLayout.LayoutParams tl=new FrameLayout.LayoutParams(-1,-2);tl.leftMargin=px(78);tl.topMargin=px(24);card.addView(title,tl);
            TextView value=new TextView(this);value.setId(R.id.input_status);value.setText(Profiles.avatar(this)==i?"Selected":"Choose");value.setTextSize(10);FrameLayout.LayoutParams vl=new FrameLayout.LayoutParams(-1,-2);vl.leftMargin=px(78);vl.topMargin=px(44);card.addView(value,vl);
            SettingsPopup.prepareRow(card,theme.iconFocusBg,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(cell%4>0)lp.leftMargin=px(10);track.addView(card,lp);
            menuItems.add(card);menuKeepsOpen.add(true);menuActions.add(()->{Profiles.avatar(this,index);updateInfo();rebuildSettings(()->openAvatarGrid(start));focusMenu(position);});
        }
        showMenu(824);int selected=Profiles.avatar(this)-start;focusMenu(selected>=0&&selected<12?selected:0);
    }

    private void openProfilePhoto(){
        java.net.InetAddress lan=PhonePage.lanAddress(this);if(lan==null){Toast.makeText(this,"Connect your phone and TV to the same Wi-Fi",Toast.LENGTH_LONG).show();return;}
        final String profile=Profiles.active(this);TextView status=new TextView(this);android.app.AlertDialog[] dialog=new android.app.AlertDialog[1];PhonePage page;
        try{page=new PhonePage(lan,true,"your profile picture",512,512,new PhonePage.Listener(){
            public void checking(){runOnUiThread(()->status.setText("Saving your picture…"));}
            public List<String> accept(Map<String,String> fields){return new ArrayList<>();}
            public List<String> acceptWallpaper(byte[] jpeg){boolean ok=Avatars.savePhoto(HomeActivity.this,profile,jpeg);runOnUiThread(()->{if(dialog[0]!=null)dialog[0].dismiss();if(ok){updateInfo();rebuildSettings(HomeActivity.this::openAvatarOptions);}else Toast.makeText(HomeActivity.this,"Couldn't save that picture",Toast.LENGTH_SHORT).show();});return java.util.Collections.singletonList(ok?"Your profile picture is saved.":"Try a different picture.");}
            public void closed(boolean expired){if(expired)runOnUiThread(()->status.setText("This code expired. Close and reopen Photo from your phone."));}
        });}catch(java.io.IOException e){Toast.makeText(this,"Couldn't open the phone page",Toast.LENGTH_SHORT).show();return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(px(24),px(8),px(24),0);box.addView(qrCode(page.url),new LinearLayout.LayoutParams(px(180),px(180)));TextView address=new TextView(this);address.setText(page.url);address.setTextSize(12);box.addView(address);status.setText("Choose a photo on your phone. This code works once, for 5 minutes.");box.addView(status);
        dialog[0]=new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Profile picture from your phone").setMessage("Scan this code on the same Wi-Fi. The photo is cropped to a square and saved only on this TV.").setView(box).setNegativeButton("Close",null).setOnDismissListener(d->page.close()).create();dialog[0].show();
    }

    private boolean ownWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return false;
        for (String n : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) if (new File(dir, n).isFile()) return true;
        return false;
    }

    private String wallpaperLabel() {
        String folder = prefs.getString("wall_folder_name", null);
        if (ownWallpaper()) return folder != null ? "A picture a day from " + folder : "Your picture";
        return prefs.getBoolean("bing_wallpaper", false) ? "Picture of the day" : "Off";
    }

    // ---------------------------------------------------------------- wallpaper

    /** Talkies settings → Wallpaper: a picture of your own (from your phone or this TV), Bing's, or none. */
    private void openWallpaperMenu() {
        beginMenu(settingsAt, "Wallpaper  ·  now: " + wallpaperLabel());
        addRow("From your phone", "scan a QR code", () -> { pictureFor = null; openPhoneWallpaper(); }, false);
        addRow("From this TV", "Pictures, Downloads, USB drives", () -> { pictureFor = null; openTvPictures(); }, false);
        final View[] bing = new View[1];
        bing[0] = addRow("Picture of the day", prefs.getBoolean("bing_wallpaper", false) ? "On" : "Off", () -> {
            boolean on = !prefs.getBoolean("bing_wallpaper", false);
            prefs.edit().putBoolean("bing_wallpaper", on).apply();
            if (on) this.bing.refreshIfStale(this::loadWallpaper); else { this.bing.clear(); loadWallpaper(); }
            setStatus(bing[0], on ? (ownWallpaper() ? "On · after your picture" : "On") : "Off");
        }, true);
        if (ownWallpaper()) addRow("Remove your picture", prefs.getBoolean("bing_wallpaper", false) ? "back to Bing's" : null, () -> {
            stopFolder();
            removeOwnWallpaper();
            loadWallpaper();
            Toast.makeText(this, "Your picture is removed", Toast.LENGTH_SHORT).show();
        }, false);
        showMenu(420);
    }

    // ---------------------------------------------------------------- your own app images

    /** The app whose image the picture pickers set (hold OK → Change image), or null for the wallpaper. */
    private String pictureFor;

    /** The app's tile on screen, for a menu about it; null if it isn't showing. */
    private View tileOf(String pkg) {
        int i = indexOf(pkg);
        if (i < 0) return null;
        if (i < dockCount) return i < dockTiles.size() ? dockTiles.get(i) : null;
        return i - dockCount < gridTiles.size() ? gridTiles.get(i - dockCount) : null;
    }

    private String labelOf(String pkg) {
        AppEntry a = appFor(pkg);
        return a != null ? a.label : pkg;
    }

    /** Puts a menu about an app by its tile; closing it puts focus back on the app. */
    private void anchorToApp(String pkg) {
        menuTile = tileOf(pkg);
        menuClosed = () -> {
            int i = indexOf(pkg);
            if (i >= 0 && i < dockCount) focusDock(i);
            else if (i >= dockCount) focusGrid(i - dockCount);
            else focusDock(dockIdx);
        };
    }

    /** Hold OK on an app → Change image: a picture of your own for its tile, from your phone or this TV. */
    private void openTileImage(String pkg) {
        beginMenu(P_CONTROL, labelOf(pkg) + "  ·  image");
        anchorToApp(pkg);
        addRow("From your phone", "scan a QR code", () -> { pictureFor = pkg; openPhoneWallpaper(); }, false);
        addRow("From this TV", "Pictures, Downloads, USB drives", () -> { pictureFor = pkg; openTvPictures(); }, false);
        File own = AppEntry.customImage(pkg);
        if (own != null && own.isFile()) addRow("Original image", null, () -> {
            own.delete();
            reloadApps();
            Toast.makeText(this, labelOf(pkg) + " has its own image again", Toast.LENGTH_SHORT).show();
        }, false);
        showMenu(380);
    }

    /** After a picture was saved for {@code pkg} (null: the wallpaper): show it, and say so. */
    private void pictureSaved(String pkg, boolean ok) {
        if (pkg == null) {
            if (ok) stopFolder();
            Toast.makeText(this, ok ? "Your picture is the wallpaper now" : "Couldn't use that picture", Toast.LENGTH_SHORT).show();
            if (ok) loadWallpaper();
            return;
        }
        Toast.makeText(this, ok ? "Your picture is on " + labelOf(pkg) + " now" : "Couldn't use that picture", Toast.LENGTH_SHORT).show();
        if (ok) reloadApps();
    }

    /** Back to one picture: the folder no longer changes it. */
    private void stopFolder() {
        prefs.edit().remove("wall_folder").remove("wall_folder_name").remove("wall_folder_last").remove("wall_folder_day").apply();
    }

    private void removeOwnWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        for (String n : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) new File(dir, n).delete();
    }

    /**
     * Saves a picture as files/wallpaper.jpg: cropped to fill the screen's 16:9 at 1920 × 1080 and re-encoded, so a
     * 50 MB photo becomes a small file and keeps nothing but the picture. Off the UI thread; false if it isn't a picture.
     */
    private boolean saveWallpaper(java.util.function.Supplier<java.io.InputStream> open) { return savePicture(open, null); }

    /** The same for an app's tile (files/tiles/<package>.jpg, 640 × 360) when {@code pkg} isn't null. */
    private boolean savePicture(java.util.function.Supplier<java.io.InputStream> open, String pkg) {
        final int w = pkg == null ? 1920 : 640, h = pkg == null ? 1080 : 360;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = open.get()) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap src;
            try (java.io.InputStream in = open.get()) { src = BitmapFactory.decodeStream(in, null, o); }
            if (src == null) return false;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            float k = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            float sw = src.getWidth() * k, sh = src.getHeight() * k;
            new android.graphics.Canvas(out).drawBitmap(src, null,
                    new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            File dir = pkg == null ? getExternalFilesDir(null) : AppEntry.customDir;
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
            File tmp = new File(dir, pkg == null ? "wallpaper.tmp" : pkg + ".tmp");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, f);
            }
            out.recycle();
            if (pkg != null) return tmp.renameTo(AppEntry.customImage(pkg));
            removeOwnWallpaper();
            return tmp.renameTo(new File(dir, "wallpaper.jpg"));
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Couldn't save the wallpaper: " + e);
            return false;
        }
    }

    /** Wallpaper → From your phone: the phone page's photo picker, behind a QR code. */
    private void openPhoneWallpaper() {
        java.net.InetAddress lan = PhonePage.lanAddress(this);
        if (lan == null) {
            Toast.makeText(this, "The TV isn't on a home Wi-Fi or Ethernet network, so the phone can't reach it", Toast.LENGTH_LONG).show();
            return;
        }
        final String target = pictureFor;
        TextView status = new TextView(this);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        PhonePage page;
        try {
            page = new PhonePage(lan, true, target == null ? "the TV screen" : labelOf(target) + "'s tile", new PhonePage.Listener() {
                @Override public void checking() {
                    runOnUiThread(() -> status.setText("Saving your picture…"));
                }

                @Override public List<String> accept(Map<String, String> fields) { return new ArrayList<>(); }

                @Override public List<String> acceptWallpaper(byte[] jpeg) {
                    boolean ok = savePicture(() -> new java.io.ByteArrayInputStream(jpeg), target);
                    runOnUiThread(() -> {
                        if (dialog[0] != null) dialog[0].dismiss();
                        pictureSaved(target, ok);
                    });
                    return java.util.Collections.singletonList(ok ? "Done: it's on your TV now." : "The TV couldn't use that picture. Try another one.");
                }

                @Override public void closed(boolean expired) {
                    if (expired) runOnUiThread(() -> status.setText("This code has expired. Close it and open From your phone again."));
                }
            });
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Couldn't open the page for your phone", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(24), px(8), px(24), 0);
        box.addView(qrCode(page.url), new LinearLayout.LayoutParams(px(180), px(180)));
        TextView where = new TextView(this);
        where.setText(page.url);
        where.setTextSize(12);
        where.setPadding(0, px(8), 0, 0);
        box.addView(where);
        status.setText("Waiting for your phone… The page works once, for 5 minutes.");
        status.setPadding(0, px(6), 0, 0);
        box.addView(status);
        dialog[0] = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(target == null ? "Wallpaper from your phone" : "A picture for " + labelOf(target))
                .setMessage("Scan this with your phone's camera (on the same Wi-Fi as the TV), then choose a photo.")
                .setView(box)
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> page.close())
                .create();
        dialog[0].show();
    }

    private static final int REQ_PICTURES = 9;
    private static final int MAX_PICTURES = 60;
    private static final int MAX_FOLDERS = 6;

    /** Reading pictures: Android 13 and later ask for photos only, earlier versions for all of storage. */
    private String picturesPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /**
     * Wallpaper → From this TV: the newest pictures Android knows of, on the TV and any USB drive, each with a small
     * preview; OK makes one the wallpaper. Asks for the permission first.
     */
    private void openTvPictures() {
        if (checkSelfPermission(picturesPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{picturesPermission()}, REQ_PICTURES);
            return;
        }
        background.execute(() -> {
            List<String[]> found = new ArrayList<>();  // uri, name, folder, volume, folder id
            java.util.Set<String> volumes = Build.VERSION.SDK_INT >= 29
                    ? android.provider.MediaStore.getExternalVolumeNames(this) : java.util.Collections.singleton("external");
            List<long[]> order = new ArrayList<>();
            for (String vol : volumes) {
                Uri base = android.provider.MediaStore.Images.Media.getContentUri(vol);
                String[] cols = {android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME, android.provider.MediaStore.Images.Media.DATE_MODIFIED,
                        android.provider.MediaStore.Images.Media.BUCKET_ID};
                try (android.database.Cursor c = getContentResolver().query(base, cols, null, null,
                        android.provider.MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
                    while (c != null && c.moveToNext() && found.size() < MAX_PICTURES * 4) {
                        found.add(new String[]{android.content.ContentUris.withAppendedId(base, c.getLong(0)).toString(),
                                c.getString(1), c.getString(2) == null ? "" : c.getString(2), vol, c.getString(4)});
                        order.add(new long[]{c.getLong(3), found.size() - 1});
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Couldn't list pictures on " + vol + ": " + e);
                }
            }
            order.sort((a, b) -> Long.compare(b[0], a[0]));  // newest first across the TV and USB drives
            List<String[]> pics = new ArrayList<>();
            for (long[] o : order) if (pics.size() < MAX_PICTURES) pics.add(found.get((int) o[1]));
            runOnUiThread(() -> showTvPictures(pics));
        });
    }

    private void showTvPictures(List<String[]> pics) {
        final String target = pictureFor;
        beginMenu(settingsAt, pics.isEmpty() ? "No pictures on this TV"
                : target == null ? "Wallpaper  ·  OK on a folder or a picture" : labelOf(target) + "  ·  OK on a picture to use it");
        if (target != null) anchorToApp(target);
        if (pics.isEmpty()) {
            addRow("Copy pictures to Pictures or Downloads, or plug in a USB drive", null, () -> { }, true);
            showMenu(460);
            return;
        }
        // The wallpaper can also come from a folder: a new picture from it each day. Each folder shows its newest.
        java.util.Set<String> folders = new java.util.HashSet<>();
        if (target == null) for (String[] pic : pics) {
            if (pic[4] == null || folders.size() >= MAX_FOLDERS || !folders.add(pic[3] + "/" + pic[4])) continue;
            String name = pic[2].isEmpty() ? "Pictures" : pic[2];
            addThumb(addRow(name, "a new picture each day", () -> useFolder(pic[3], pic[4], name), false), Uri.parse(pic[0]));
        }
        for (String[] pic : pics) {
            Uri uri = Uri.parse(pic[0]);
            View row = addRow(pic[1], pic[2], () -> {
                if (target == null) Toast.makeText(this, "Setting the wallpaper…", Toast.LENGTH_SHORT).show();
                background.execute(() -> {
                    boolean ok = savePicture(() -> {
                        try { return getContentResolver().openInputStream(uri); }
                        catch (java.io.FileNotFoundException e) { throw new RuntimeException(e); }
                    }, target);
                    runOnUiThread(() -> pictureSaved(target, ok));
                });
            }, false);
            addThumb(row, uri);
        }
        showMenu(520);
    }

    /** A small preview of the picture at the start of its row. */
    private void addThumb(View row, Uri uri) {
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(rounded(theme.tileBg, 0, 4));
        thumb.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(36));
        lp.setMarginEnd(px(12));
        lp.gravity = Gravity.CENTER_VERTICAL;
        ((LinearLayout) row).addView(thumb, 0, lp);
        background.execute(() -> {
            Bitmap t = thumbnail(uri);
            if (t != null) runOnUiThread(() -> thumb.setImageBitmap(t));
        });
    }

    /** Wallpaper → From this TV → a folder: its first picture now, then the next one each day. */
    private void useFolder(String volume, String id, String name) {
        prefs.edit().putString("wall_folder", volume + "/" + id).putString("wall_folder_name", name)
                .remove("wall_folder_last").remove("wall_folder_day").apply();
        Toast.makeText(this, "Setting the wallpaper…", Toast.LENGTH_SHORT).show();
        nextFromFolder(() -> Toast.makeText(this, ownWallpaper() ? "A new picture from " + name + " each day"
                : "Couldn't use a picture from " + name, Toast.LENGTH_SHORT).show());
    }

    private boolean folderBusy;

    /**
     * Once a day, the next picture from the wallpaper folder (in name order, round again after the last), saved like
     * one you picked. A folder that's gone (a USB drive unplugged) leaves the last picture up.
     */
    private void nextFromFolder(Runnable then) {
        String folder = prefs.getString("wall_folder", null);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int today = now.get(java.util.Calendar.YEAR) * 1000 + now.get(java.util.Calendar.DAY_OF_YEAR);
        if (folder == null || folderBusy || prefs.getInt("wall_folder_day", 0) == today) return;
        folderBusy = true;
        String last = prefs.getString("wall_folder_last", null);
        background.execute(() -> {
            String next = null;
            try {
                int cut = folder.lastIndexOf('/');
                Uri base = android.provider.MediaStore.Images.Media.getContentUri(folder.substring(0, cut));
                List<String> uris = new ArrayList<>();
                try (android.database.Cursor c = getContentResolver().query(base, new String[]{android.provider.MediaStore.Images.Media._ID},
                        android.provider.MediaStore.Images.Media.BUCKET_ID + "=?", new String[]{folder.substring(cut + 1)},
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME + " ASC")) {
                    while (c != null && c.moveToNext()) uris.add(android.content.ContentUris.withAppendedId(base, c.getLong(0)).toString());
                }
                if (!uris.isEmpty()) next = uris.get((uris.indexOf(last) + 1) % uris.size());
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't read the wallpaper folder: " + e);
            }
            final String pick = next;
            boolean ok = pick != null && savePicture(() -> {
                try { return getContentResolver().openInputStream(Uri.parse(pick)); }
                catch (java.io.FileNotFoundException e) { throw new RuntimeException(e); }
            }, null);
            runOnUiThread(() -> {
                folderBusy = false;
                // Tried today either way: a folder that's gone is tried again tomorrow, not every minute.
                if (prefs.getString("wall_folder", "").equals(folder)) {
                    SharedPreferences.Editor e = prefs.edit().putInt("wall_folder_day", today);
                    if (ok) e.putString("wall_folder_last", pick);
                    e.apply();
                }
                if (ok) loadWallpaper();
                if (then != null) then.run();
            });
        });
    }

    private Bitmap thumbnail(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return getContentResolver().loadThumbnail(uri, new android.util.Size(px(128), px(72)), null);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= px(128)) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void openLicence(String file) {
        beginMenu(settingsAt,"Open-source licences");
        try(java.io.InputStream in=getAssets().open("licenses/"+file)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] data=new byte[4096];int n;while((n=in.read(data))!=-1)out.write(data,0,n);
            for(String paragraph:out.toString("UTF-8").split("\n\n")) {
                View row=addRow(paragraph.trim(),null,()->{},true);TextView label=row.findViewById(R.id.input_label);label.setSingleLine(false);label.setMaxLines(40);label.setTextSize(12);row.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;row.setPadding(px(14),px(10),px(14),px(10));
            }
        }catch(Exception e){recommendationText("Licence unavailable",12,theme.text2);}
        showMenu(650);
    }

    /** After a restart only (the default), or every time the TV wakes from standby too. */
    private String wakeLabel() { return SleepService.homeOnWake(this) ? "Every time" : "After a restart"; }

    /** Credits for the data Talkies shows; TMDB's wording is what its terms ask for. */
    private void openAbout() {
        String version = "";
        try { version = " " + pm.getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { }
        beginSettingsMenu(settingsAt, "Sweep TV" + version + "  ·  MIT License");
        String[][] credits = {
                {"Weather", "Open-Meteo.com (CC BY 4.0)"},
                {"Airport temperatures", "aviationweather.gov (NOAA)"},
                {"Cricket scores", "CricketData.org"},
                {"Formula 1 results & standings", "Jolpica F1"},
                {"Formula 1 session status", "ESPN (public scoreboard)"},
                {"Formula 1 completed sessions", "OpenF1 (free historical data)"},
                {"Speed test", "Cloudflare"},
                {"Code from", "tv-vibehack, CleanYourTV, Free My TV, Matinee, Luma, GlassLauncher"},
                {"Font", "Outfit (SIL OFL)"},
        };
        addRow("Sweep TV", "Your personalised home", () -> { }, true);
        addRow("Launcher core", "Talkies · Raju / TechPP · MIT", () -> openLicence("LICENSE"), false);
        addRow("Original source", "github.com/rajupp/talkies", () -> { }, true);
        addRow("Open-source licences", "Full notices", () -> openLicence("THIRD_PARTY_NOTICES.md"), false);
        addRow("Profile illustrations", "Twemoji · CC BY 4.0; DiceBear Thumbs · CC0", () -> openLicence("TWEMOJI-CC-BY-4.0.txt"), false);

        // TMDB's terms ask for its logo with the credit: smaller than anything identifying Talkies.
        LinearLayout picks = (LinearLayout) addRow("Picks", null, () -> { }, true);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tmdb_logo);
        logo.setContentDescription("TMDB");
        picks.addView(logo, picks.getChildCount() - 1, new LinearLayout.LayoutParams(px(123), px(16)));
        // TMDB's notice, in full: a row of its own that wraps.
        View notice = addRow("This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise "
                + "approved by TMDB.", null, () -> { }, true);
        TextView nt = notice.findViewById(R.id.input_label);
        nt.setSingleLine(false);
        nt.setMaxLines(3);
        nt.setTextSize(14);
        notice.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        notice.setPadding(notice.getPaddingLeft(), px(8), notice.getPaddingRight(), px(8));
        for (String[] c : credits) addRow(c[0], c[1], () -> { }, true);
        String caption = bing.caption();
        if (prefs.getBoolean("bing_wallpaper", false) && caption != null) addRow("Wallpaper", "Bing: " + caption, () -> { }, true);
        showMenu(720);
    }

    // ---------------------------------------------------------------- recent apps

    /**
     * Control Center → Recent apps (or a remote button set to it): the apps you opened last, newest first, each with
     * its banner. OK opens one. At the end, close them all (apps in the background only, never system apps).
     */
    private void openRecents() {
        if (menuScroll.getVisibility() == View.VISIBLE) closeMenu();
        List<String> recent = RecentApps.list(this);
        beginMenu(P_CONTROL, recent.isEmpty() ? "Recent apps" : "Recent apps  ·  newest first");
        int shown = 0;
        for (String pkg : recent) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;  // uninstalled since
            final Intent open = launch;
            AppEntry a = appFor(pkg);
            String label = a != null ? a.label : Channels.label(pm, pkg);
            LinearLayout row = (LinearLayout) addRow(label, shown == 0 ? "last used" : null, () -> startFromTile(open), false);
            ImageView thumb = new ImageView(this);
            Drawable d = a != null ? a.banner(pm) : null;
            if (d == null) {
                try { d = pm.getApplicationBanner(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
            }
            if (d != null) {
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setImageDrawable(d);
            } else {
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                try { thumb.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (PackageManager.NameNotFoundException ignored) { }
            }
            thumb.setBackground(rounded(theme.tileBg, 0, 4));
            thumb.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(36));
            lp.setMarginEnd(px(12));
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(thumb, 0, lp);
            shown++;
        }
        if (shown == 0) {
            addRow("Apps you open show up here", null, () -> { }, true);
        } else {
            final View[] close = new View[1];
            close[0] = addRow("Close them all", "frees memory", () -> {
                ActivityManager am = getSystemService(ActivityManager.class);
                long before = availableMb();
                for (String pkg : RecentApps.list(this)) {
                    try {
                        if ((pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0) am.killBackgroundProcesses(pkg);
                    } catch (PackageManager.NameNotFoundException ignored) { }
                }
                close[0].postDelayed(() -> setStatus(close[0], "Freed " + Math.max(0, availableMb() - before) + " MB"), 800);
            }, true);
        }
        showMenu(460);
    }

    // ---------------------------------------------------------------- control center shortcuts

    /** Control Center rows whose screen you can choose (Talkies settings → Control Center shortcuts): key → row name. */
    private static final String[][] SHORTCUTS = {{"network", "Network"}, {"bluetooth", "Bluetooth"},
            {"sound", "Sound output"}, {"storage", "Storage"}};
    /** Words that make a settings screen a likely match for each row, shown first in the list. */
    private static final String[][] SHORTCUT_WORDS = {{"wifi", "network", "internet", "ethernet"},
            {"bluetooth", "accessor", "remote"}, {"sound", "audio", "speaker", "display"}, {"storage", "memory"}};

    /** The screen you chose for a row, if any: true when it opened. A choice that no longer opens is forgotten. */
    private boolean openShortcut(String key) {
        String c = prefs.getString("shortcut_" + key, null);
        if (c == null) return false;
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(c);
        try {
            if (cn == null) throw new ActivityNotFoundException();
            startActivity(new Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            prefs.edit().remove("shortcut_" + key).apply();
            Toast.makeText(this, "The screen you chose isn't there any more, so the TV's own opens", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Talkies settings → Control Center shortcuts: for Network, Bluetooth, Sound output and Storage, which screen of the
     * TV's settings OK opens (some TVs keep the useful one elsewhere, like Sony's speaker choice). An idea from tvOS
     * Launcher's activity picker.
     */
    private void openShortcuts() {
        beginMenu(settingsAt, "Control Center shortcuts  ·  what each row opens");
        for (int i = 0; i < SHORTCUTS.length; i++) {
            final int k = i;
            addRow(SHORTCUTS[i][1], shortcutLabel(SHORTCUTS[i][0]), () -> openShortcutPicker(k, false), false);
        }
        showMenu(460);
    }

    private String shortcutLabel(String key) {
        String c = prefs.getString("shortcut_" + key, null);
        android.content.ComponentName cn = c == null ? null : android.content.ComponentName.unflattenFromString(c);
        if (cn == null) return "TV default";
        try {
            return screenName(pm.getActivityInfo(cn, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "TV default";
        }
    }

    /** "Display & sound" from the screen's label, or from its class name when the label is just the app's name. */
    private String screenName(android.content.pm.ActivityInfo ai) {
        String label = String.valueOf(ai.loadLabel(pm)).trim();
        String app = String.valueOf(ai.applicationInfo.loadLabel(pm)).trim();
        if (!label.isEmpty() && !label.equals(app)) return label;
        String cls = ai.name.substring(ai.name.lastIndexOf('.') + 1).replace("Activity", "");
        return cls.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    /**
     * The screens the TV's settings apps let others open, likely matches for the row first; with {@code all}, every
     * one. OK on one makes it the row's; TV default puts the row back.
     */
    private void openShortcutPicker(int k, boolean all) {
        String key = SHORTCUTS[k][0];
        background.execute(() -> {
            List<android.content.pm.ActivityInfo> found = new ArrayList<>();
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                if (p.activities == null || p.applicationInfo == null || (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                if (!p.packageName.toLowerCase(Locale.ROOT).contains("settings")) continue;
                for (android.content.pm.ActivityInfo ai : p.activities) {
                    if (ai.exported && ai.enabled && ai.permission == null) found.add(ai);
                }
            }
            List<android.content.pm.ActivityInfo> likely = new ArrayList<>(), rest = new ArrayList<>();
            for (android.content.pm.ActivityInfo ai : found) {
                String n = ai.name.toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String w : SHORTCUT_WORDS[k]) if (n.contains(w)) { match = true; break; }
                (match ? likely : rest).add(ai);
            }
            List<String[]> rows = new ArrayList<>();  // name, component
            for (android.content.pm.ActivityInfo ai : likely) rows.add(new String[]{screenName(ai), ai.packageName + "/" + ai.name});
            if (all) for (android.content.pm.ActivityInfo ai : rest) rows.add(new String[]{screenName(ai), ai.packageName + "/" + ai.name});
            final int more = all ? 0 : rest.size();
            runOnUiThread(() -> {
                beginMenu(settingsAt, SHORTCUTS[k][1] + " opens…");
                addRow("TV default", null, () -> {
                    prefs.edit().remove("shortcut_" + key).apply();
                    openShortcuts();
                }, false);
                for (String[] r : rows) addRow(r[0], r[1].substring(0, r[1].indexOf('/')), () -> {
                    prefs.edit().putString("shortcut_" + key, r[1]).apply();
                    Toast.makeText(this, SHORTCUTS[k][1] + " opens " + r[0] + ". Try it from Control Center", Toast.LENGTH_LONG).show();
                    openShortcuts();
                }, false);
                if (more > 0) addRow("Show every screen", String.valueOf(more), () -> openShortcutPicker(k, true), false);
                showMenu(520);
            });
        });
    }

    // ---------------------------------------------------------------- dock apps

    /**
     * Talkies settings → Dock apps: every app, the dock's first; OK puts one in the dock or takes it out (the same as
     * Add to dock and Remove from dock in an app's hold-OK menu). The rows stay where they are while you change them.
     */
    private void openDockApps() {
        beginMenu(settingsAt, "Dock apps  ·  OK: in or out of the dock");
        final List<String> pkgs = new ArrayList<>();
        for (AppEntry a : apps) pkgs.add(a.pkg);
        for (AppEntry a : new ArrayList<>(apps)) {
            addRow(a.label, null, () -> {
                int at = menuIdx;
                if (indexOf(a.pkg) < dockCount) removeFromDock(a.pkg, false); else addToDock(a.pkg, false);
                dockAppStatuses(pkgs);
                focusMenu(at);  // the dock was rebuilt: put the ring back on this row
            }, true);
        }
        dockAppStatuses(pkgs);
        showMenu(440);
    }

    private void dockAppStatuses(List<String> pkgs) {
        for (int i = 0; i < pkgs.size() && i < menuItems.size(); i++) {
            int ai = indexOf(pkgs.get(i));
            setStatus(menuItems.get(i), ai >= 0 && ai < dockCount ? "In dock · " + (ai + 1) : null);
        }
    }

    // ---------------------------------------------------------------- channels editor

    /** Every channel your apps publish: OK shows or hides it; hold OK, then Up/Down, to move it. */
    private void openChannelsEditor() {
        editList = Channels.list(this, watchNextPicks.size());
        channelsEdited = false;
        fillChannelsMenu();
        showMenu(440);
    }

    private void fillChannelsMenu() {
        beginMenu(settingsAt, "Channels  ·  OK: show or hide  ·  hold OK: move");
        menuMode = MENU_CHANNELS;
        for (Channels.Channel ch : editList) {
            final Channels.Channel c = ch;
            addRow(ch.title, null, () -> {
                c.shown = !c.shown;SectionOptions.set(this,c.key,c.shown);
                channelsEdited = true;
                Channels.save(this, editList);
                channelStatuses();
            }, true);
        }
        channelStatuses();
    }

    private void channelStatuses() {
        boolean first = true;
        for (int i = 0; i < editList.size() && i < menuItems.size(); i++) {
            Channels.Channel ch = editList.get(i);
            String status = ch.shown ? (first ? "Shown · first" : "Shown") : "Hidden";
            if (ch.shown) first = false;
            if (menuMoving && i == menuIdx) status = "Moving…";
            setStatus(menuItems.get(i), status);
        }
    }

    private void startMenuMove() {
        if (menuMode != MENU_CHANNELS || menuIdx >= editList.size()) return;
        holdFired = true;
        menuMoving = true;
        beforeEdit = new ArrayList<>(editList);
        channelStatuses();
        moveHint.setText("Moving " + editList.get(menuIdx).title + "  ·  OK to place  ·  Back to cancel");
        moveHint.setAlpha(0f);
        moveHint.setVisibility(View.VISIBLE);
        moveHint.animate().alpha(1f).setDuration(150).start();
        showServiceNote();
    }

    /** Swaps the picked-up channel with the one above or below. */
    private void moveMenuRow(int k) {
        int to = k == KeyEvent.KEYCODE_DPAD_UP ? menuIdx - 1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? menuIdx + 1 : -1;
        if (to < 0 || to >= editList.size()) return;
        View a = menuItems.get(menuIdx), b = menuItems.get(to);
        int ia = menu.indexOfChild(a), ib = menu.indexOfChild(b);
        menu.removeView(a);
        menu.addView(a, ib);
        if (ia != ib) {
            menu.removeView(b);
            menu.addView(b, ia);
        }
        Collections.swap(menuItems, menuIdx, to);
        Collections.swap(menuActions, menuIdx, to);
        Collections.swap(menuKeepsOpen, menuIdx, to);
        Collections.swap(editList, menuIdx, to);
        focusMenu(to);
        channelStatuses();
    }

    private void endMenuMove(boolean keep) {
        if (!menuMoving) return;
        menuMoving = false;
        if (keep) {
            channelsEdited = true;
            Channels.save(this, editList);
            channelStatuses();
        } else {
            editList = beforeEdit;
            int at = menuIdx;
            fillChannelsMenu();
            focusMenu(Math.min(at, menuItems.size() - 1));
        }
        moveHint.animate().alpha(0f).setDuration(120).withEndAction(() -> { moveHint.setVisibility(View.GONE); showServiceNote(); }).start();
    }

    /** Google TV's own launcher is installed (visible through the HOME query in the manifest). */
    private boolean isGoogleTvLauncher() {
        try {
            pm.getApplicationInfo("com.google.android.apps.tv.launcherx", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The launcher that has Home when it's one you installed (not Talkies, not the TV's own), else null. */
    private ResolveInfo otherHome() {
        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String p = ri.activityInfo.packageName;
        boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        return p.equals(getPackageName()) || p.equals("android") || system ? null : ri;
    }

    private String currentHomeName() {
        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null ? null : String.valueOf(ri.loadLabel(pm));
    }

    private String sleepStatus() {
        int left = Sleep.minutesLeft(this);
        return left > 0 ? left + " min left" : "Off";
    }

    /** Turning off needs the accessibility helper, which only you can switch on. Take you there. */
    private void needsTurnOffSetup() {
        Toast.makeText(this, "To turn the TV off, switch on \"" + getString(R.string.sleep_service_label)
                + "\" in Accessibility", Toast.LENGTH_LONG).show();
        closeMenu();
        Intent i = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.oemlink.AccessibilitySettingsActivity");
        if (pm.resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        start(i);
    }

    private long availableMb() {
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    /** "12.4 GB free of 32 GB": the TV's own storage, where apps and their data live. */
    private String storageLine() {
        try {
            android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            return String.format(java.util.Locale.getDefault(), "%.1f GB free of %d GB",
                    fs.getAvailableBytes() / 1e9, Math.round(fs.getTotalBytes() / 1e9));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Control Center → Animations: the speed of every animation on the TV, 1× → 0.5× → Off. Faster makes menus and
     * app switches feel snappier; Off also stills Talkies' own motion. Needs the WRITE_SECURE_SETTINGS grant (the same
     * one that repairs a stopped service). An idea from GlassLauncher (MIT).
     */
    private static final String[] ANIM_KEYS = {Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE};

    private float animationScale() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    private String animationLabel() {
        float s = animationScale();
        return s == 0f ? "Off" : s == 0.5f ? "0.5×" : s == 1f ? "1×" : s + "×";
    }

    private boolean cycleAnimations() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        float s = animationScale(), next = s >= 1f ? 0.5f : s > 0f ? 0f : 1f;
        try {
            for (String k : ANIM_KEYS) Settings.Global.putFloat(getContentResolver(), k, next);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** A soundbar or receiver on HDMI ARC/eARC takes over the TV's sound while connected. */
    private boolean soundbarConnected() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_HDMI_ARC
                    || (Build.VERSION.SDK_INT >= 31 && d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC)) return true;
        }
        return false;
    }

    /** Stops cached background apps (never system apps) and shows how much memory came back. */
    private void freeMemory(View row) {
        ActivityManager am = getSystemService(ActivityManager.class);
        long before = availableMb();
        setStatus(row, "Freeing…");
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            am.killBackgroundProcesses(ai.packageName);
        }
        row.postDelayed(() -> {
            long after = availableMb();
            setStatus(row, "Freed " + Math.max(0, after - before) + " MB · " + after + " MB free");
        }, 800);
    }

    // ---------------------------------------------------------------- network, wallpaper

    private void updateNetwork(NetworkCapabilities caps) {
        boolean online = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        icons[P_WIFI].setAlpha(online ? 1f : 0.45f);
        icons[P_WIFI].setContentDescription(online ? "Wi-Fi" : "Wi-Fi: offline");
        // Back online after being offline: whatever failed meanwhile is fetched now, not at its next retry.
        if (online && Boolean.FALSE.equals(wasOnline)) {
            weather.networkBack();
            trending.networkBack();
            cricket.networkBack();
            formula.networkBack();
            bing.networkBack();
            refreshOnline();
        }
        wasOnline = online;
    }

    /** Your own wallpaper (files/wallpaper.jpg|png|webp) replaces the soft backdrop. */
    private void loadWallpaper() {
        File dir = getExternalFilesDir(null);
        File f = null;
        if (dir != null) {
            for (String name : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
                File c = new File(dir, name);
                if (c.isFile()) { f = c; break; }
            }
            // No picture of your own: Bing's, when that's on.
            File b = new File(dir, Bing.FILE);
            if (f == null && prefs.getBoolean("bing_wallpaper", false) && b.isFile()) f = b;
        }
        long stamp = f == null ? 0 : f.lastModified() ^ f.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        hasWallpaper = f != null;
        if (f == null) {
            wallFit = null;
            backdrop.setWallpaper(null);
            day = isDayNow();
            applyTheme();
            updateAmbient();
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        // Full size and full colour: at half size, or in 16-bit colour, a photo looks soft and skies band.
        int screen = getResources().getDisplayMetrics().widthPixels;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= screen) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp != null) {
            wallFit = Legibility.of(bmp, Theme.of(false).scrimTop & 0x00FFFFFF, Theme.of(true).scrimTop & 0x00FFFFFF);
            // Kept only where it's drawn, on the graphics side, once its veil is worked out: half the memory.
            Bitmap gpu = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) { bmp.recycle(); bmp = gpu; }
            backdrop.setWallpaper(bmp);
            // A new photo may suit the other look (Auto), and always needs its own veil.
            day = isDayNow();
            applyTheme();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ServiceRepair.restore(this);  // a force stop switched the accessibility service off: put it back
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, t);
        IntentFilter p = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_CHANGED);
        p.addDataScheme("package");
        register(packageReceiver, p);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { runOnUiThread(() -> updateNetwork(c)); }
            @Override public void onLost(Network n) { runOnUiThread(() -> updateNetwork(null)); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
        Network active = cm.getActiveNetwork();
        updateNetwork(active == null ? null : cm.getNetworkCapabilities(active));

        askPermissionsOnce();
        loadWallpaper();
        boolean nowDay = isDayNow();
        if (nowDay != day) { day = nowDay; applyTheme(); }
        weatherLine = weather.cached();
        updateInfo();
        refreshOnline();
        background.execute(() -> {
            List<Cricket.Match> saved = cricket.cached();
            runOnUiThread(() -> {
                if (matches.isEmpty() && !saved.isEmpty()) onMatches(saved);
                refreshOnline();
            });
        });
        // Right after the TV starts, its storage can be late: the saved picks may only now be readable.
        if (trendPicks.isEmpty() || indiaPicks.isEmpty()) {
            trendPicks = trending.cached(Trending.TRENDING);
            indiaPicks = trending.cached(Trending.INDIA);
            backToTrending();
        }
        // Something new may have been watched while we were away.
        refreshWatchNext();
        root.postDelayed(rereadWatchNext,5000);root.postDelayed(rereadWatchNext,20000);
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, watchNextChanged);
    }

    /** Apps update Watch Next a few seconds after you leave them (Netflix does): reread it then, once things settle. */
    private final android.database.ContentObserver watchNextChanged = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            root.removeCallbacks(rereadWatchNext);
            root.postDelayed(rereadWatchNext, 1500);
        }
    };
    private final Runnable rereadWatchNext = this::refreshWatchNext;

    @Override protected void onDestroy() {
        root.removeCallbacks(rotateInsight);if(insights!=null)insights.close();
        titleCoverGeneration++;titleCoverWorker.shutdownNow();cardArtwork.close();channelWorker.shutdownNow();root.removeCallbacks(applyPreparedChannels);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(watchNextChanged);
        root.removeCallbacks(rereadWatchNext);
        root.removeCallbacks(applyPreparedChannels);
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tabs[2].setText(trending.languages().isEmpty()?"New in " + Region.name(Region.country(this)):"New releases");  // the country can change (Region)
        inFront = true;
        requestTitleCovers();
        scheduleInsightRotation();
        homeInteractiveAfter=android.os.SystemClock.uptimeMillis()+1200;
        root.removeCallbacks(applyPreparedChannels);root.postDelayed(applyPreparedChannels,1200);
        if(prefs.getBoolean(TimeOfDay.PREF,false))refreshTimeTheme();else updateAmbient();
        homeHasResumed=true;
        root.post(this::snapRing);
        // Give Android time to bind the service after Talkies (re)starts before calling it stopped.
        root.removeCallbacks(checkService);
        root.postDelayed(checkService, 6000);
        refreshOnline();   // back on the home screen: catch up on anything that went stale meanwhile
        if (appsOrder() == ORDER_RECENT && !moving && !recentSig.equals(String.join(",", RecentApps.all(this)))) {
            String focused = zone == Z_GRID && dockCount + gridIdx < apps.size() ? apps.get(dockCount + gridIdx).pkg : null;
            reloadApps();
            int at = focused == null ? -1 : indexOf(focused);
            if (at >= dockCount) focusGrid(at - dockCount);
        }
    }

    /** Is the accessibility service down when it should be up? Switch it off and on if we may; else say so. */
    private final Runnable checkService = () -> {
        boolean stopped = ServiceRepair.stopped(this, SleepService.class);
        if (stopped && !rebindTried && ServiceRepair.canRepair(this)) {
            rebindTried = true;
            ServiceRepair.rebind(this, SleepService.class);
            root.postDelayed(this.checkService, 5000);
            return;
        }
        if (!stopped) rebindTried = false;
        serviceStopped = stopped;
        showServiceNote();
    };

    private void showServiceNote() {
        boolean show = serviceStopped && moveHint.getVisibility() != View.VISIBLE;
        if (serviceNote == null) {
            if (!show) return;
            serviceNote = new TextView(this);
            serviceNote.setText("Home, Turn off and Remote keys stopped working  ·  Control Center → Fix");
            serviceNote.setTextSize(13);
            serviceNote.setTypeface(getResources().getFont(R.font.outfit));
            serviceNote.setGravity(android.view.Gravity.CENTER);
            serviceNote.setPadding(px(16), 0, px(16), 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(28),
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.topMargin = px(6);
            ViewGroup parent = (ViewGroup) moveHint.getParent();
            parent.addView(serviceNote, parent.indexOfChild(moveHint), lp);
        }
        serviceNote.setTextColor(theme.text);
        serviceNote.setBackground(rounded(theme.menuBg, theme.menuStroke, 14));
        serviceNote.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The OK or Back button that went down while Talkies was in front (0: none), so only its release counts. */
    private int pressedHere;

    @Override
    protected void onPause() {
        super.onPause();
        pressedHere = 0;
        SleepService.learn(null);
        inFront = false;
        titleCoverGeneration++;
        root.removeCallbacks(rotateInsight);
    }

    /**
     * Network refreshes (cricket, weather, picks) run only while the home screen is the app in front and the TV's
     * display is on: never behind another app, under the screensaver, or in standby. Each also has its own interval.
     */
    private void refreshOnline() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (!inFront || !hasWindowFocus() || power == null || !power.isInteractive()) {
            Log.i(TAG, "Refresh skipped: " + (!inFront ? "another app in front" : !hasWindowFocus() ? "screensaver or dialog on top" : "display off"));
            return;
        }
        if (!scores().equals("off") && !scores().equals("football")) cricket.refreshIfStale(this::onMatches);
        if (prefs.getBoolean("formula_one",true)) formula.refreshIfStale(this::onFormula);
        weather.refreshIfStale(this::onWeather);
        insights.refresh(state->{if(!isDestroyed()){insightState=state;showChannels(appChannels);}});
        if (prefs.getBoolean("bing_wallpaper", false)) bing.refreshIfStale(this::loadWallpaper);
        nextFromFolder(null);  // no network needed, but the same moments: a new day's picture while you can see it
        if (forYou || prefs.getBoolean("recommendations",true)||prefs.getBoolean("new_releases",true)) trending.refreshIfStale(this::onPicks);
        // Only the Sweep TV release channel may notify here; dependency checks run during maintenance.
        background.execute(() -> {
            boolean news = SelfUpdate.checkIfStale(this);
            runOnUiThread(() -> {
                onUpdates();
                if (news) Toast.makeText(this, "Sweep TV " + SelfUpdate.available(this) + " is out: the bell, top right, installs it",
                        Toast.LENGTH_LONG).show();
            });
        });
        if (prefs.getBoolean("youtube_art", false)) RecentStore.upgradeArtwork(this, () -> {
            // Sharper YouTube pictures arrived: redraw Continue and the Watch Next row.
            recentPicks = continuePicks(RecentStore.load(this), watchNextPicks);
            if (tabIdx == 0 && zone != Z_PICKS) buildPicksRow(0);
            refreshChannels();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The screensaver or a system dialog took the screen, or gave it back.
        if (hasFocus) refreshOnline();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // backup-settings.sh: write files/talkies-backup.json for it to pull. restore-settings.sh: start again, so
        // onCreate applies the file it pushed.
        if (intent.getBooleanExtra("talkies_backup", false)) {
            Toast.makeText(this, Backup.save(this) ? "Settings backed up" : "Couldn't back up the settings", Toast.LENGTH_SHORT).show();
            return;
        }
        if (intent.getBooleanExtra("talkies_restore", false)) {
            recreate();
            return;
        }
        SleepService.learn(null);  // Home while "Waiting for a button…": stop listening
        // Home: back to the dock at the top, on the Trending tab.
        if (menuMoving) endMenuMove(false);
        if (zone == Z_MENU) {
            menuScroll.setVisibility(View.GONE);settingsPopup.hide(false);settingsPopupMode=false;settingsGrid=false;
            if (menuMode == MENU_CHANNELS && channelsEdited) refreshChannels();
            menuMode = MENU_PLAIN;
            zone = Z_TOP;
        }
        endMove(false);
        loadWallpaper();
        if (tabIdx != TAB_TRENDING && !picksFor(TAB_TRENDING).isEmpty()) {
            tabIdx = TAB_TRENDING;
            buildPicksRow(0);
        }
        focusDock(dockIdx);
        // A remote button set to "Inputs" (Control Center → Remote keys).
        String open = intent.getStringExtra(RemoteKeys.EXTRA_OPEN);
        if (RemoteKeys.INPUTS.equals(open)) root.post(this::openInputs);
        else if (RemoteKeys.RECENTS.equals(open)) root.post(this::openRecents);
    }
}
