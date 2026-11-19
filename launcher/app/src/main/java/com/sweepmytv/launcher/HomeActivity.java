package com.sweepmytv.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
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
 * Sweep TV. One screen: "For You" with tabs (Continue, Trending, New in India) and a row of picks, round icon
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
    /** How many apps the dock holds (Sweep TV settings → Dock size); the tiles shrink or grow to fit. */
    private static final int[] DOCK_SIZES = {5, 6, 7, 8};
    private static final int DEFAULT_DOCK_SIZE = 7;
    private static final String PLAY_STORE = "com.android.vending";
    // Geometry (dp, on a 960 × 540 dp screen).
    private static final String[] DOCK_BACKGROUNDS = {"Tinted", "Soft", "Off", "Frosted white", "Rotate", "Inset", "Outline"};
    private static final String[] DOCK_SHAPES = {"Rectangle", "Square", "Circle"};
    private static final String DOCK_ICON_SHAPE = "dock_icon_shape", DOCK_GRAYSCALE = "dock_grayscale";
    private static final String DOCK_ALL_APPS_BUTTON = "dock_all_apps_button";
    private static final android.graphics.ColorMatrixColorFilter GRAYSCALE = new android.graphics.ColorMatrixColorFilter(
            new android.graphics.ColorMatrix(new float[]{.213f,.715f,.072f,0,0,.213f,.715f,.072f,0,0,
                    .213f,.715f,.072f,0,0,0,0,0,1,0}));
    private static final int DOCK_MAX_W = 146, DOCK_ICON_MAX = 82, DOCK_GAP = 12, ALL_BUTTON = 44, GRID_TILE_W = 133, GRID_TILE_H = 75, GRID_GAP_X = 16,
            GRID_GAP_Y = 18;
    // Channel rows, below the dock: header, a row of cards, the focused card's title and description.
    private static final int CHAN_TOP = 548, CHAN_ROW_H = 212, CH_W = 200, CH_H = 112, CH_GAP = 18;
    private static final int POSTER_W=140,POSTER_H=210, MUSIC_SIZE=132;
    private static final int MENU_PLAIN = 0, MENU_CHANNELS = 1;

    private float dp;
    private SharedPreferences prefs;
    private PackageManager pm;

    private View root;
    private Backdrop backdrop;
    private FrameLayout page, dockBand, topInsights, topAir;
    private final List<View> insightCards = new ArrayList<>();
    private HomeInsightCard airView;
    private HomeInsights.Card airValue;
    private boolean airFocused;
    private GradientDrawable insightSurface;
    private android.animation.ValueAnimator insightFade;
    private final android.os.Handler insightTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private Channels.Channel topInsightRow;
    private boolean hadMeters;
    private String topInsightSignature;
    private int insightIdx;
    private static final long INSIGHT_ROTATION_MS=12_000;
    private final Runnable rotateInsight=this::rotateTopInsight;
    private LinearLayout dock, grid, menu, channelsBox;
    private android.widget.ScrollView menuScroll;
    private TextView allAppsLabel, moveHint, drawerHint;
    private TextView[] tabs;
    private ImageView[] icons;
    private PicksRow picksRow;
    private View pickCaption;
    private TextView pickCaptionTitle, pickCaptionMeta;
    private final android.util.LruCache<String, Trending.TitleMetadata> continueMetadata = new android.util.LruCache<>(40);
    private final android.util.LruCache<String, Long> continueMetadataRetry = new android.util.LruCache<>(40);
    private Runnable pendingContinueMetadata;
    private boolean continueMetadataRunning;
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
    private View activeChannelCaption;
    private boolean refocusChannels;
    /** When a remote key last moved focus, and whether a channel update is waiting for the remote to go still. */
    private long lastKeyAt;
    private final java.util.concurrent.ThreadPoolExecutor channelWorker = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "home-rows"));
    private Runnable queuedChannelPreparation;
    private final ExecutorService appChannelWorker = Executors.newSingleThreadExecutor();
    private boolean appChannelLoadRunning, appChannelReloadPending;
    private volatile int channelsGeneration;
    private List<Object> channelScope = List.of();
    private ChannelSnapshot pendingChannels;
    private long homeInteractiveAfter;
    private static final long NAV_IDLE_MS=650;
    private static final class ChannelViews {
        String signature;LinearLayout row;android.widget.HorizontalScrollView scroll;List<View> cards;Spring spring;int col;
        PicksRow reel;
        View caption;
        TextView captionTitle, captionMeta;
    }
    private final Map<String,ChannelViews> channelViews=new java.util.LinkedHashMap<>();
    /** The icon Sweep TV settings drops down from (Control Center, or the gear when you held OK on it). */
    private int settingsAt = P_CONTROL;
    private SettingsPopup settingsPopup;
    private boolean settingsPopupMode,settingsGrid,settingsOpening;
    private int settingsColumns=3;
    private final Object settingsCardRow = new Object();
    private String settingsHeading;
    private View focusedSettingsRow;
    private View lastSettingsSection;
    private final Map<String, View> continueAppRows = new java.util.HashMap<>();
    private final Map<View, View> settingsSections = new java.util.IdentityHashMap<>();
    private final Map<View, HomeTheme> themeChoices = new java.util.IdentityHashMap<>();
    private View appearanceWallpaperRow;
    private boolean appDrawer;

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
    private SportsScores sportsScores;
    private List<Cricket.Match> cricketFeed = Collections.emptyList();
    private HomeInsights insights;
    private HomeInsights.State insightState=HomeInsights.State.empty();
    private FormulaOne formula;
    private FormulaOne.State formulaState = FormulaOne.State.empty();
    private List<Cricket.Match> matches = new ArrayList<>();
    private List<Channels.Channel> appChannels = new ArrayList<>();  // the shown app channels, without Cricket
    private List<Trending.Pick> watchNextPicks = new ArrayList<>(), recentPicks = new ArrayList<>(),
            trendPicks = new ArrayList<>(), indiaPicks = new ArrayList<>(), rowPicks = new ArrayList<>();
    private List<Trending.Pick> recommendationCatalog=new ArrayList<>();
    private long recommendationWindow = Long.MIN_VALUE;
    private RottenTomatoesPicks rottenTomatoesPicks;
    private SpotifyPicks spotify;
    private Channels.Channel musicRow = SpotifyPicks.row();
    private static final long SHELF_ROTATION_MS = 20_000;
    private int tabIdx;
    private boolean continueShelfPinned;
    private final Runnable rotateHomeShelf = this::rotateHomeShelf;
    private final long firstShelfRotationAt = android.os.SystemClock.uptimeMillis() + 60_000L;
    private int appArtworkGeneration;
    private long pickHour = -1;
    private int continueGeneration;
    private final ExecutorService artworkColours = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, int[]> colourCache = new android.util.LruCache<>(48);
    private Runnable pendingArtworkColour;
    private String requestedArtworkColour, decodingArtworkColour;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final TitleLinks titleLinks = new TitleLinks();
    private final ExecutorService titleLinkWorker = Executors.newSingleThreadExecutor();
    private TitleDetailsDialog titleDialog;
    private final CardArtwork cardArtwork = new CardArtwork();
    private final ExecutorService titleCoverWorker=Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"title-covers"));
    private final android.util.LruCache<String,TitleCovers.Result> titleCovers=new android.util.LruCache<>(48);
    private volatile int titleCoverGeneration;

    // Focus.
    private int zone = Z_DOCK, iconIdx = P_SETTINGS, dockIdx = 0, gridIdx = 0;  // Up returns to the last header control
    private int menuIdx, menuAnchor = P_INPUTS;
    private static final int DOCK_MENU_WIDTH = 296;
    private final Map<View, java.util.function.IntConsumer> dockMenuCycles = new java.util.HashMap<>();
    private final Map<View, MarketQuotes.Asset> marketCurrencyCards = new java.util.HashMap<>();
    private boolean dockMenu;
    private String dockMenuPackage;
    private View dockRemoveRow;
    private static final String[] SETTINGS_CATEGORIES = {
            "Appearance", "Dock", "Content settings", "Screen saver", "Home Page Widgets", "Remote buttons", "System"
    };
    private final Runnable[] settingsPages = {this::openAppearance, this::openDockSettings, this::openContentSettings,
            this::openScreenSavers, this::openHomePageWidgets, this::openRemoteKeys, this::openSystemSettings};
    private static final int SIDEBAR_TV_SETTINGS = 0, SIDEBAR_SCAN_QR = 1;
    private int settingsCategory;
    private int settingsSidebarAction = -1;
    private boolean splitSettings, settingsSidebar, openingSettingsPage;
    private View menuTile;          // a menu opened on an app or card sits by it (else under its top-bar icon)
    private Runnable menuClosed;    // and closing it puts focus back there
    // Back in a menu opened from another menu's row (Control Center → Sweep TV settings → Dock apps) goes up one
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
    private MarketCard marketCard;
    private MarketQuotes marketQuotes;
    private int marketIndex;
    private final Runnable rotateMarket = this::advanceMarket;
    /** Online at the last network change; null before the first. */
    private Boolean wasOnline;
    private View forYouHead, bigClock;
    private TextView bigTime, bigAmPm, bigDate, bigWeather;
    private boolean inFront;

    // Rearranging: hold OK on an app, arrows move it, OK places it, Back cancels.
    private static final long HOLD_MS = 550;
    private boolean moving, holdFired;
    private String movingPkg;
    private List<AppEntry> beforeMove;
    private final Runnable holdTimer = this::onHold;

    private ConnectivityManager.NetworkCallback netCallback;
    private boolean homeStarted, appsStale;
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onMinute(); }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (homeStarted) reloadApps();
            else appsStale = true;
            if ((Intent.ACTION_PACKAGE_ADDED.equals(i.getAction()) || Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()))
                    && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                trending.appsChanged();
                if (inFront) trending.refreshIfStale(HomeActivity.this::onPicks);
            }
        }
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
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        register(packageReceiver, packages);
        // restore-settings.sh pushed a backup: apply it before anything reads the settings.
        if (Backup.restoreIfPresent(this)) Toast.makeText(this, "Settings restored from the backup", Toast.LENGTH_LONG).show();
        else if (getIntent().getBooleanExtra(Backup.EXTRA_BACKUP, false)) Backup.save(this);  // asked before Sweep TV was running
        setContentView(R.layout.activity_home);
        weigh(this::findViewById, R.id.big_weather, 500, R.id.big_time, 400,
                R.id.big_ampm, 500, R.id.big_date, 500, R.id.all_apps_label, 600, R.id.move_hint, 450);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        HomeOptions.migrateDeviceMetrics(prefs);
        pm = getPackageManager();

        root = findViewById(R.id.root);
        backdrop = findViewById(R.id.backdrop);
        forYouHead = findViewById(R.id.for_you_head);
        bigClock = findViewById(R.id.big_clock);
        bigTime = findViewById(R.id.big_time);
        bigDate = findViewById(R.id.big_date);
        bigAmPm = findViewById(R.id.big_ampm);
        bigWeather = findViewById(R.id.big_weather);
        View headerControls = findViewById(R.id.header_controls);
        headerControls.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                bigWeather.setMaxWidth(Math.max(0, root.getWidth() - view.getWidth() - px(96))));
        forYou = prefs.getBoolean("for_you", true);
        meters = findViewById(R.id.meters);
        marketCard = findViewById(R.id.market_card);
        marketQuotes = new MarketQuotes(this);
        showMarket();
        meters.update();
        getWindow().setBackgroundDrawable(null);   // the backdrop covers the screen: don't paint it black first
        page = findViewById(R.id.page);
        topInsights = findViewById(R.id.top_insights);
        topAir = findViewById(R.id.top_air);
        insightSurface = rounded(Color.TRANSPARENT, 0, HeaderWidgetStyle.RADIUS);
        topInsights.setBackground(insightSurface);
        dockBand = findViewById(R.id.dock_band);
        dock = findViewById(R.id.dock);
        grid = findViewById(R.id.grid);
        menu = findViewById(R.id.menu);
        menuScroll = findViewById(R.id.menu_scroll);
        settingsPopup=new SettingsPopup(this);settingsPopup.setVisibility(View.GONE);
        ((FrameLayout)root).addView(settingsPopup,((FrameLayout)root).indexOfChild(menuScroll),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        channelsBox = findViewById(R.id.channels);
        allAppsLabel = findViewById(R.id.all_apps_label);
        moveHint = findViewById(R.id.move_hint);
        drawerHint = findViewById(R.id.drawer_hint);
        picksRow = findViewById(R.id.picks);
        ring = findViewById(R.id.ring);
        tabs = new TextView[]{findViewById(R.id.tab_recent), findViewById(R.id.tab_trending), findViewById(R.id.tab_india)};
        icons = new ImageView[]{findViewById(R.id.icon_cc), findViewById(R.id.icon_inputs), findViewById(R.id.icon_wifi),
                findViewById(R.id.icon_settings), findViewById(R.id.icon_bell)};
        icons[P_CONTROL].setVisibility(View.GONE);
        Updates.setListener(this::onUpdates);
        onUpdates();
        picksRow.setOnFrame(this::snapRing);
        pickCaption = findViewById(R.id.pick_caption);
        pickCaptionTitle = findViewById(R.id.pick_caption_title);
        pickCaptionMeta = findViewById(R.id.pick_caption_meta);
        pickCaptionTitle.setFontVariationSettings("'wght' 600");
        pickCaptionMeta.setFontVariationSettings("'wght' 400");

        // Banners are prepared at the size of the largest focused tile: an All apps tile at 110%, or the dock's
        // largest (five apps) at 114%.
        AppEntry.setBannerSize(px(Math.max(GRID_TILE_W * 1.10f, DOCK_MAX_W * 1.14f)), px(Math.max(GRID_TILE_H * 1.10f, DOCK_MAX_W * 9 / 16f * 1.14f)));
        AppEntry.customDir = new File(getExternalFilesDir(null), "tiles");
        trending = new Trending(this);
        rottenTomatoesPicks = RottenTomatoesPicks.load(this);
        spotify = new SpotifyPicks(this);
        Profiles.active(this);
        weather = new Weather(this);
        insights=new HomeInsights(this,weather);insightState=insights.cached();
        bing = new Bing(this, background);
        cricket = new Cricket(this);
        sportsScores = new SportsScores(this);
        cricketFeed = sportsScores.cached(false, System.currentTimeMillis());
        formula = new FormulaOne(this);
        formulaState = formula.cached();
        updateThemeSelection();
        day = isDayNow();
        theme = Theme.of(day);

        loadApps();
        loadPicks();
        buildAppViews(true);
        buildPicksRow(0);
        applyTheme();
        showStartupSections();
        showChannels(appChannels);
        trending.readCached(this::onPicks);
        // Reserve the enabled header slots from cached data before recommendation rows finish loading.
        Channels.Channel header = HomeInsights.groupShown(this)
                ? insightState.forLocation(weather.widgetLocationKey()).row(this, timeOfDay.nowMillis()) : null;
        buildTopInsights(header, header == null || header.items.isEmpty() ? "" : ChannelSnapshot.signature(header));
        dockIdx = Math.min(prefs.getInt("last_dock_col", 0), Math.max(0, dockCount - 1));
        String panel = getIntent().getStringExtra(RemoteKeys.EXTRA_OPEN);
        if (isDrawerGesture(panel)) openRemotePanel(panel);
        else root.post(() -> {
            focusDock(dockIdx);
            openRemotePanel(panel);
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

        static Theme fromPalette(TimeOfDay.Palette p, boolean sunlight) {
            Theme t=of(p.light);
            t.bgTop=p.top;t.bgBottom=p.bottom;t.text=p.text;t.text2=p.muted;
            t.iconFocusBg=t.menuFocusBg=p.accent;
            t.iconFocusFg=t.menuFocusText=TimeOfDay.foreground(p.accent);
            t.iconBg=(TimeOfDay.blend(p.mid,sunlight?0xFF0B1426:0xFF111215,.55f)&0xFFFFFF)|0xDD000000;t.iconStroke=(p.accent&0xFFFFFF)|0x44000000;
            t.band=(TimeOfDay.blend(p.mid,sunlight?0xFF101B30:0xFF16171A,.45f)&0xFFFFFF)|0xEB000000;t.bandStroke=(p.accent&0xFFFFFF)|0x77000000;
            t.menuBg=TimeOfDay.blend(p.mid,sunlight?0xFF111A2C:0xFF15161A,.68f);t.menuStroke=(p.accent&0xFFFFFF)|0x55000000;
            t.tileBg=TimeOfDay.blend(p.mid,sunlight?0xFF142038:0xFF191A1E,.5f);
            t.scrimTop=(t.scrimTop&0xFF000000)|(p.top&0xFFFFFF);
            t.scrimMid=(t.scrimMid&0xFF000000)|(TimeOfDay.blend(p.top,p.bottom,.5f)&0xFFFFFF);
            t.scrimBottom=(t.scrimBottom&0xFF000000)|(p.bottom&0xFFFFFF);
            return t;
        }
    }

    private void applyTheme() {applyTheme(true);}

    private void applyTheme(boolean rebuildCards) {
        int oldText=theme.text,oldMuted=theme.text2;
        appliedPalette = themePalette(activeTheme);
        theme = appliedPalette == null ? Theme.of(day) : Theme.fromPalette(appliedPalette, activeTheme == HomeTheme.SUNLIGHT);
        configureBackdrop(backdrop, theme, day, activeTheme);
        backdrop.setScrimStops(wallpaperScrim());
        bigTime.setTextColor(theme.text);
        bigAmPm.setTextColor(theme.text2);
        bigDate.setTextColor(theme.text2);
        bigWeather.setTextColor(theme.text2);
        meters.setColors(theme.text, theme.text2);
        marketCard.setColors(theme.text, theme.text2);
        marketCard.setBackground(rounded(theme.iconBg, 0, HeaderWidgetStyle.RADIUS));
        for (View card : insightCards) {
            if (card instanceof HomeInsightCard) ((HomeInsightCard) card).setColors(theme.text, theme.text2);
        }
        if (airView != null) airView.setColors(theme.text, theme.text2);
        allAppsLabel.setTextColor(theme.text);
        styleDockSurface();
        moveHint.setTextColor(theme.text);
        drawerHint.setTextColor(theme.text);
        drawerHint.setBackground(rounded(theme.menuBg, theme.menuStroke, 10));
        moveHint.setBackground(rounded(theme.menuBg, theme.menuStroke, 14));
        ring.setDay(day);
        updateFocusAccent();
        pickCaptionTitle.setTextColor(theme.text);
        pickCaptionMeta.setTextColor(theme.text2);
        styleChannels();
        // Card backgrounds and match cards are drawn in the theme's colours: redraw the rows.
        if (rebuildCards && cricket != null && zone != Z_CHAN && !chanRows.isEmpty()) {
            chanSignature = null;
            showChannels(appChannels);
        }
        for (View t : dockTiles) styleDockTile(t);
        for (View t : gridTiles) t.setBackground(rounded(theme.tileBg, 0, 11));
        styleTop();
        updateInfo();
        updateAmbient();
        if(zone==Z_MENU) {
            if (settingsPopupMode) showSettingsHeading();
            menuScroll.setBackground(settingsPopupMode ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    : dockMenu ? rounded(0xFF1C1C1E, 0xFF3D3E45, 15) : rounded(theme.menuBg, theme.menuStroke, 20));
            retintMenu(menu,oldText,oldMuted);
            if(!menuItems.isEmpty())focusMenu(menuIdx);
        }
    }

    private static void configureBackdrop(Backdrop backdrop, Theme theme, boolean day, HomeTheme choice) {
        backdrop.setTheme(day, theme.bgTop, theme.bgBottom, theme.scrimTop, theme.scrimMid,
                theme.scrimBottom, theme.ambientAlpha, choice == HomeTheme.BLACK_GLOW);
    }

    private int dockSurface() {
        int saved = prefs.getInt("dock_background", -1);
        if (saved >= 0 && saved < DOCK_BACKGROUNDS.length) return saved;
        if (!prefs.getBoolean(HomeTheme.DOCK_SURFACE, true)) return 2;
        return prefs.getBoolean("dock_surface_soft", false) ? 1 : 0;
    }

    private void styleDockSurface() {
        int style = dockSurface();
        dockBand.setBackground(dockSurfaceDrawable(style, 30));
    }

    private Drawable dockSurfaceDrawable(int style, int radius) {
        switch (style) {
            case 1: return rounded((theme.band & 0xFFFFFF) | 0x46000000, 0, radius);
            case 2: return null;
            case 3: return rounded(0x38FFFFFF, 0x32FFFFFF, radius);
            case 4: {
                TimeOfDay.Palette sky = skyPalette();
                return rounded((sky.mid & 0xFFFFFF) | 0xB3000000, (sky.accent & 0xFFFFFF) | 0x45000000, radius);
            }
            case 5: {
                Drawable fill = rounded(theme.band, theme.bandStroke, Math.max(5, radius - 5));
                return new android.graphics.drawable.InsetDrawable(fill, px(5));
            }
            case 6: return rounded(Color.TRANSPARENT, theme.bandStroke, radius);
            default: return rounded(theme.band, theme.bandStroke, radius);
        }
    }

    private float dockTileRadius() {
        int shape = dockIconShape();
        return shape == 2 ? DOCK_ICON_MAX / 2f : shape == 1 ? 16 : 11;
    }

    private void styleDockTile(View tile) {
        int fill = dockIconShape() == 0 ? theme.tileBg : lerpColor(theme.tileBg, theme.text, .14f);
        tile.setBackground(rounded(fill, 0, dockTileRadius()));
        ImageView art = (ImageView) ((FrameLayout) tile).getChildAt(0);
        if (prefs.getBoolean(DOCK_GRAYSCALE, false)) art.setColorFilter(GRAYSCALE);
        else art.clearColorFilter();
    }

    private void retintMenu(View view,int oldText,int oldMuted) {
        if(view instanceof TextView) {
            TextView text=(TextView)view;int color=text.getCurrentTextColor();
            if(color==oldText)text.setTextColor(theme.text);
            else if(color==oldMuted)text.setTextColor(theme.text2);
        } else if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)retintMenu(((ViewGroup)view).getChildAt(i),oldText,oldMuted);
    }

    private void refreshHomeTheme() {
        HomeTheme previous = activeTheme;
        boolean previousWallpaper = rotatingWallpaper;
        long previousHour = themeHour, previousGlow = glowStep;
        updateThemeSelection();
        boolean themeChanged = previous != activeTheme || previousWallpaper != rotatingWallpaper;
        if (themeChanged) updateAppearanceSelection();
        boolean solarChanged = activeTheme == HomeTheme.SUNLIGHT && appliedPalette != skyPalette();
        boolean fixedChanged = activeTheme.fixed() && !activeTheme.usesGlow() && previousHour != themeHour;
        day = isDayNow();
        if (themeChanged || solarChanged || fixedChanged) applyTheme(false);
        else if (activeTheme.usesGlow() && previousGlow != glowStep) updateAmbient();
        if (previousWallpaper != rotatingWallpaper) loadWallpaper();
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
    private void updateDrawerHint() {
        boolean show = zone == Z_DOCK && !moving && inFront && hasWindowFocus()
                && RemoteKeys.doubleHomeDrawer(this) && SleepService.isRunning()
                && SleepService.watchButtons(this)
                && !prefs.getBoolean(RemoteKeys.DRAWER_GESTURE_USED, false);
        drawerHint.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void styleTop() {
        updateDrawerHint();
        insightSurface.setColor(insightColor());
        if (airView != null) topAir.setBackground(rounded(airView.surfaceColor(
                headerColor(zone == Z_INSIGHTS && airFocused)), 0, HeaderWidgetStyle.RADIUS));
        for (int i = 0; i < icons.length; i++) {
            boolean f = zone == Z_TOP && iconIdx == i;
            int fill = f ? theme.iconFocusBg : i == P_SETTINGS
                    ? lerpColor(theme.iconBg, theme.iconFocusBg, .14f) : theme.iconBg;
            icons[i].setBackground(rounded(fill, 0, 9));
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
        View tabBar = findViewById(R.id.tabs);
        tabBar.setBackground(rounded(theme.iconBg, 0, 12));
        tabBar.setVisibility(rowPicks.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == tabIdx, f = zone == Z_TABS && selected;
            tabs[i].setVisibility(picksFor(i).isEmpty() ? View.GONE : View.VISIBLE);
            tabs[i].setTextColor(f ? theme.iconFocusFg : selected ? theme.text : theme.text2);
            tabs[i].setFontVariationSettings(selected ? "'wght' 600" : "'wght' 400");
            int selectionFill = (theme.text & 0x00FFFFFF) | 0x22000000;
            tabs[i].setBackground(selected ? rounded(f ? theme.iconFocusBg : selectionFill, 0, 9) : null);
            tabs[i].setSelected(selected);
        }
    }

    private void applyForYou() {
        forYouHead.setVisibility(showTopContent() ? View.VISIBLE : View.GONE);
        float scale = showTopContent() || (topInsights.getVisibility() == View.VISIBLE || airView != null) ? 1f : 1.6f;
        bigWeather.setTextSize(12 * scale);
        bigTime.setTextSize(36 * scale);
        bigAmPm.setTextSize(12 * scale);
        bigDate.setTextSize(12 * scale);
        ((ViewGroup.MarginLayoutParams) bigClock.getLayoutParams()).topMargin = px(showTopContent() ? 24 : 34);
        bigClock.requestLayout();
        styleTop();
    }

    private void updateInfo() {
        long nowMillis = timeOfDay.nowMillis();
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);
        boolean h24 = DateFormat.is24HourFormat(this);
        bigTime.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        bigAmPm.setText(h24 ? "" : DateFormat.format("a", now));
        bigAmPm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        bigDate.setText(DateFormat.format("EEE, d MMM", now));
        String header = HomeOptions.header(this, nowMillis);
        bigWeather.setText(header);
        bigWeather.setCompoundDrawablePadding(px(7));
        bigWeather.setCompoundDrawables(header.isEmpty() ? null : Avatars.drawable(this, px(24)), null, null, null);
        refreshMeters();
        bigWeather.setVisibility(header.isEmpty()?View.GONE:View.VISIBLE);
    }

    private String scores() { return prefs.getString("scores", "off"); }

    private boolean sportEnabled(String key) {
        if (FormulaOne.KEY.equals(key)) return sectionEnabled(key);
        boolean fallback = "both".equals(scores()) || "cricket".equals(scores());
        return prefs.getBoolean("sport_" + key, fallback) && !SectionOptions.hidden(this, key);
    }

    private List<Cricket.Match> cricketShown() {
        if (!sportEnabled("cricket")) return Collections.emptyList();
        List<Cricket.Match> fixtures = SportsDashboard.fixtures(cricketFeed);
        return fixtures.isEmpty() ? SportsDashboard.fixtures(matches) : fixtures;
    }

    private String sportsSummary() {
        int count = (sportEnabled(FormulaOne.KEY) ? 1 : 0) + (sportEnabled("cricket") ? 1 : 0);
        return count == 0 ? "Off" : count == 2 ? "All sports" : "1 enabled";
    }

    private void updateSports() {
        showChannels(appChannels);
        refreshOnline();
    }

    private void setSports(String key, boolean enabled) {
        SectionOptions.set(this, key, enabled);
        prefs.edit().putBoolean(FormulaOne.KEY.equals(key) ? key : "sport_" + key, enabled).apply();
    }

    private void toggleSport(String key) {
        setSports(key, !sportEnabled(key));
        updateSports();
    }

    private void openSportsSettings() {
        beginSettingsMenu(settingsAt, "Sports");
        String[] keys = {FormulaOne.KEY, "cricket"};
        String[] labels = {"Formula 1", "Cricket"};
        addContentSwitch("All sports", () -> "All sports".equals(sportsSummary()), () -> {
            boolean enabled = !"All sports".equals(sportsSummary());
            for (String key : keys) setSports(key, enabled);
            updateSports();
            rebuildSettings(this::openSportsSettings);
            focusMenu(0);
        });
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            addContentSwitch(labels[i], () -> sportEnabled(key), () -> {
                toggleSport(key);
                int focus = menuIdx;
                rebuildSettings(this::openSportsSettings);
                focusMenu(focus);
            });
        }
        showMenu(480);
    }

    private void onMatches(List<Cricket.Match> list) {
        matches = list;
        showChannels(appChannels);  // while you're moving through the rows it waits for the remote to go still
    }

    private void onFormula(FormulaOne.State state) {
        if (isDestroyed()) return;
        formulaState = state;
        showChannels(appChannels);
    }

    private void onMinute() {
        refreshHomeTheme();
        updateInfo();
        if (dockSurface() == 4) styleDockSurface();
        if (sportEnabled(FormulaOne.KEY)||prefs.getBoolean("insights",true)
                || recommendationWindow != Recommendations.window(System.currentTimeMillis())) showChannels(appChannels);
        refreshOnline();
        // New picks on the hour, but never while you're looking through them.
        if (Trending.localHour() != pickHour && zone != Z_PICKS && zone != Z_TABS && tabIdx != 0) buildPicksRow(0);
    }

    /** How All apps is arranged (Sweep TV settings → All apps order). The dock always keeps your order. */
    private static final String[] APPS_ORDER = {"Your order", "Recently opened", "A to Z"};
    private static final int ORDER_YOURS = 0, ORDER_RECENT = 1, ORDER_AZ = 2;
    /** RecentApps as of the last loadApps, so coming home re-sorts only when something new was opened. */
    private String recentSig = "";

    private TimeOfDay.Palette cachedSky, appliedPalette;
    private final HomeTheme.Rotation themeRotation = new HomeTheme.Rotation();
    private HomeTheme activeTheme;
    private boolean rotatingWallpaper;
    private long themeHour, glowStep;
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
        return activeTheme == HomeTheme.SUNLIGHT && skyPalette().light;
    }

    private boolean randomisingThemes() {
        return prefs.contains(HomeTheme.RANDOMISE_SINCE);
    }

    private void updateThemeSelection() {
        long now = timeOfDay.nowMillis();
        HomeTheme manual = HomeTheme.selected(prefs);
        HomeTheme slot = randomisingThemes() ? themeRotation.at(prefs.getLong(HomeTheme.RANDOMISE_SINCE, now), now) : manual;
        rotatingWallpaper = slot == null;
        activeTheme = rotatingWallpaper ? manual : slot;
        themeHour = Math.floorDiv(now, HomeTheme.HOUR_MS);
        glowStep = Math.floorDiv(now, ArtworkColour.STEP_MS);
    }

    private TimeOfDay.Palette themePalette(HomeTheme choice) {
        return choice == HomeTheme.SUNLIGHT ? skyPalette() : choice.palette(themeHour);
    }

    private void onWeather(String ignored) {
        cachedSky = null;
        refreshHomeTheme();
        updateInfo();
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
        // Start with your arrangement, then fill the automatic dock with installed apps.
        int dockSize = dockSize();
        List<String> dockPkgs = new ArrayList<>();
        String savedDock = prefs.getString("order_dock", null);
        if (savedDock != null) dockPkgs.addAll(Arrays.asList(savedDock.split(",")));
        boolean customDock = prefs.getBoolean("dock_custom", false);
        if (!customDock) {
            for (String pkg : getResources().getStringArray(R.array.dock_packages)) {
                if (AppEntry.XIAOMI_SONY_LIV.equals(pkg) && seen.contains(Cricket.SONYLIV)) continue;
                if (!dockPkgs.contains(pkg)) dockPkgs.add(pkg);
            }
        }
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
        if (!customDock) {
            for (java.util.Iterator<AppEntry> candidates = all.iterator();
                 docked.size() < dockSize && candidates.hasNext();) {
                AppEntry app = candidates.next();
                if (!isEntertainmentApp(app) || PLAY_STORE.equals(app.pkg)
                        || AppEntry.XIAOMI_SONY_LIV.equals(app.pkg) && seen.contains(Cricket.SONYLIV)) continue;
                docked.add(app);
                candidates.remove();
            }
            if (docked.size() < dockSize) {
                for (int i = 0; i < all.size(); i++) {
                    if (PLAY_STORE.equals(all.get(i).pkg)) {
                        docked.add(all.remove(i));
                        break;
                    }
                }
            }
            while (docked.size() < dockSize && !all.isEmpty()) docked.add(all.remove(0));
        }
        int order = appsOrder();
        List<String> recent = RecentApps.all(this);
        recentSig = String.join(",", recent);
        if (order == ORDER_RECENT) {
            // Apps you opened through Sweep TV, newest first; the ones you haven't opened yet follow, A–Z.
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
        int n = prefs.getInt("dock_size", DEFAULT_DOCK_SIZE);
        return n >= DOCK_SIZES[0] && n <= 10 ? n : DEFAULT_DOCK_SIZE;
    }

    private int dockLimit() {
        return Math.max(dockSize(), DOCK_SIZES[DOCK_SIZES.length - 1]);
    }

    private void reloadApps() {
        boolean hadSpotify = indexOf(SpotifyPicks.PACKAGE) >= 0;
        endMove(false);
        loadApps();
        buildAppViews();
        if (hadSpotify != (indexOf(SpotifyPicks.PACKAGE) >= 0)) {
            showChannels(appChannels);
            if (!hadSpotify && inFront && hasWindowFocus()) refreshMusic();
        }
        if (zone == Z_DOCK) focusDock(Math.min(dockIdx, dockCount - 1));
        else if (zone == Z_ALL) { if (allTile != null) focusAllTile(); else focusDock(dockCount - 1); }
        else if (zone == Z_GRID) focusGrid(Math.min(gridIdx, gridTiles.size() - 1));
    }

    /** One app tile: its banner (or icon) on a rounded card. */
    private View appTile(AppEntry app, float wDp, float hDp, boolean inDock) {
        return appTile(app, wDp, hDp, inDock, true);
    }

    private View appTile(AppEntry app, float wDp, float hDp, boolean inDock, boolean artNow) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 11));
        card.setClipToOutline(true);
        if (artNow) bindAppArtwork(card, app, hDp, inDock);
        else {
            TextView label = new TextView(this);
            label.setText(app.label);
            label.setTextColor(theme.text);
            label.setGravity(Gravity.CENTER);
            card.addView(label, new FrameLayout.LayoutParams(-1, -1));
        }
        if (inDock) styleDockTile(card);
        card.setContentDescription(app.label);
        card.setLayoutParams(new LinearLayout.LayoutParams(px(wDp), px(hDp)));
        return card;
    }

    private void bindAppArtwork(FrameLayout card, AppEntry app, float hDp, boolean inDock) {
        card.removeAllViews();
        boolean useIcon = inDock && dockIconShape() != 0;
        Drawable banner = useIcon ? null : app.banner(pm);
        ImageView img = new ImageView(this);
        if (useIcon) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(app.dockIcon(pm, px(hDp)));
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(banner);
            Drawable sonyLogo = inDock ? app.sonyLivDockLogo(pm) : null;
            if (sonyLogo == null && inDock && app.isSonyLiv() && !app.usesCustomBanner()) {
                // The legacy TV banner places the logo inside wide margins.
                img.setScaleX(1.45f);
                img.setScaleY(1.45f);
            }
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (sonyLogo != null) {
                ImageView logo = new ImageView(this);
                logo.setImageDrawable(sonyLogo);
                int size = px(hDp * .94f);
                card.addView(logo, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
        } else {
            img.setImageDrawable(app.icon(pm));
            int size = px(hDp * .6f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = android.view.Gravity.CENTER;
            card.addView(img, lp);
        }
    }

    /** After the dock: a round button like the icons at the top, a little larger. Its look is set in styleTop. */
    private ImageView allAppsButton() {
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
        buildAppViews(false);
    }

    private void buildAppViews(boolean deferGridArtwork) {
        int generation = ++appArtworkGeneration;
        dock.removeAllViews();
        dockTiles.clear();
        boolean showAllAppsButton = prefs.getBoolean(DOCK_ALL_APPS_BUTTON, false) && apps.size() > dockCount;
        int n = Math.max(1, dockCount);
        float inner = getResources().getDisplayMetrics().widthPixels / dp - 40 - 36;
        float w = Math.min(DOCK_MAX_W, (inner - (showAllAppsButton ? n * DOCK_GAP + ALL_BUTTON : (n - 1) * DOCK_GAP)) / n);
        boolean iconTiles = dockIconShape() != 0;
        float h = iconTiles ? Math.min(DOCK_ICON_MAX, w) : w * 9 / 16;
        float width = iconTiles ? h : w;
        FrameLayout.LayoutParams surface = (FrameLayout.LayoutParams) dockBand.getLayoutParams();
        if (iconTiles && dockCount > 0) {
            int gaps = showAllAppsButton ? dockCount : dockCount - 1;
            surface.width = dockCount * px(width) + gaps * px(DOCK_GAP) + (showAllAppsButton ? px(ALL_BUTTON) : 0) + px(36);
        } else {
            surface.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        surface.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        dockBand.setLayoutParams(surface);
        for (int i = 0; i < dockCount; i++) {
            View t = appTile(apps.get(i), width, h, true);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.leftMargin = px(DOCK_GAP);
            dock.addView(t, lp);
            dockTiles.add(t);
        }
        allTile = showAllAppsButton ? allAppsButton() : null;
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
            View t = appTile(apps.get(i), GRID_TILE_W, GRID_TILE_H, false, !deferGridArtwork);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (g % GRID_COLS > 0) lp.leftMargin = px(GRID_GAP_X);
            row.addView(t, lp);
            gridTiles.add(t);
        }
        allAppsLabel.setVisibility(gridTiles.isEmpty() ? View.GONE : View.VISIBLE);
        layoutPage();
        focusedView = null;
        if (deferGridArtwork && !gridTiles.isEmpty()) {
            root.postOnAnimation(() -> loadGridArtwork(generation, 0));
        }
    }

    private void loadGridArtwork(int generation, int index) {
        if (isDestroyed() || generation != appArtworkGeneration || index >= gridTiles.size()) return;
        bindAppArtwork((FrameLayout) gridTiles.get(index), apps.get(dockCount + index), GRID_TILE_H, false);
        root.postOnAnimation(() -> loadGridArtwork(generation, index + 1));
    }

    /** Where All apps starts: after the channel rows. */
    private static boolean isFormula(Channels.Channel row) { return FormulaOne.KEY.equals(row.key); }
    private static boolean isInsights(Channels.Channel row){return HomeInsights.KEY.equals(row.key);}
    private static boolean isMosaic(Channels.Channel row){return isFormula(row) || isScores(row);}
    private static int channelHeight(Channels.Channel row) {
        if (isFormula(row)) return FormulaOneDashboard.SECTION_HEIGHT;
        if (isScores(row)) return SportsDashboard.SECTION_HEIGHT;
        if (SpotifyPicks.KEY.equals(row.key)) return MUSIC_SIZE + 100;
        return Recommendations.isRow(row.key) ? POSTER_H + 112 : CHAN_ROW_H;
    }
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

    private void showStartupSections() {
        List<Channels.Channel> rows = new ArrayList<>();
        if (sportEnabled(FormulaOne.KEY)) rows.add(formulaState.row(System.currentTimeMillis(), java.util.TimeZone.getDefault()));
        if (sportEnabled("cricket")) rows.add(cricketRow(cricketShown()));
        SectionOptions.filter(this, rows);
        ChannelSnapshot saved = new ChannelSnapshot(rows);
        chanSignature = saved.signature;
        buildChannelRows(saved.rows, saved.signatures);
    }

    /** Reloads the shown channels (and their artwork) in the background, then rebuilds the rows if anything changed. */
    private void refreshChannels() {
        if (isDestroyed()) return;
        if (appChannelLoadRunning) {
            appChannelReloadPending = true;
            return;
        }
        appChannelLoadRunning = true;
        boolean firstLoad = appChannels.isEmpty();
        appChannelWorker.execute(() -> {
            List<Channels.Channel> loaded = Channels.loadShown(this, rows -> {
                if (firstLoad) runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    appChannels = rows;
                    showChannels(rows);
                });
            });
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                appChannelLoadRunning = false;
                appChannels = loaded;
                showChannels(loaded);
                if (appChannelReloadPending) {
                    appChannelReloadPending = false;
                    refreshChannels();
                }
            });
        });
    }

    /** Prepare Home sections and app channels off the UI thread. */
    private void showChannels(List<Channels.Channel> apps) {
        if(isDestroyed())return;
        final long now = System.currentTimeMillis();
        recommendationWindow = Recommendations.window(now);
        final boolean f1 = sportEnabled(FormulaOne.KEY), showCricket = sportEnabled("cricket"),
                recommended = prefs.getBoolean("recommendations",true),
                releases = prefs.getBoolean("new_releases",true);
        final FormulaOne.State state=formulaState;
        final HomeInsights.State brief=insightState;final boolean showBrief=HomeInsights.groupShown(this);
        final Set<String> contentLanguages=trending.languages();
        final String catalogEngine=trending.catalogSource();
        final List<Trending.Pick> catalog=ContentLanguages.filter(recommendationCatalog,contentLanguages);catalog.addAll(ContentLanguages.filter(trendPicks,contentLanguages));catalog.addAll(ContentLanguages.filter(indiaPicks,contentLanguages));
        final List<Trending.Pick> history=new ArrayList<>(recentPicks);
        final Set<String> visibleApps=visibleAppPackages();
        final String reviewRegion = sectionEnabled(RottenTomatoesPicks.KEY) ? Region.country(this) : null;
        List<Object> scope = List.of(catalogEngine, Region.country(this), Profiles.active(this),
                weather.widgetLocationKey(), new HashSet<>(contentLanguages), new HashSet<>(visibleApps),
                f1, showCricket, recommended, releases, showBrief, reviewRegion == null ? "" : reviewRegion,
                new HashSet<>(prefs.getStringSet(SectionOptions.HIDDEN, Collections.emptySet())));
        if (!scope.equals(channelScope)) {
            channelScope = scope;
            channelsGeneration++;
            pendingChannels = null;
            root.removeCallbacks(applyPreparedChannels);
        }
        final int generation = channelsGeneration;
        final Channels.Channel music=musicRow;
        final List<Channels.Channel> published=ContentLanguages.channels(apps,contentLanguages);
        final List<Cricket.Match> cricketFixtures = cricketShown();
        if (queuedChannelPreparation != null) channelWorker.remove(queuedChannelPreparation);
        queuedChannelPreparation = () -> {
            if(generation!=channelsGeneration)return;
            List<Channels.Channel> list=new ArrayList<>();
            if(showBrief){Channels.Channel row=brief.forLocation(weather.widgetLocationKey()).row(this,now);if(!row.items.isEmpty())list.add(row);}
            if(f1)list.add(state.row(now,java.util.TimeZone.getDefault()));
            List<Trending.Pick> suggestionsCatalog = catalog;
            if (recommended && BuildConfig.CATALOG_COMPARISON && !"sweep".equals(catalogEngine)) {
                suggestionsCatalog = new ArrayList<>(catalog);
                for (Trending.Pick pick : trending.cachedSweepRecommendations()) {
                    if (visibleApps.contains(pick.pkg)) suggestionsCatalog.add(pick);
                }
            }
            Channels.Channel suggestions=recommended||releases?Recommendations.build(suggestionsCatalog,history,now):new Channels.Channel();
            if(recommended&&!suggestions.items.isEmpty())list.add(suggestions);
            if(releases){Channels.Channel fresh=Recommendations.newReleases(catalog,recommended?suggestions:new Channels.Channel(),now);if(!fresh.items.isEmpty())list.add(fresh);}
            if (reviewRegion != null) {
                Channels.Channel reviewed = rottenTomatoesPicks.row(catalog, reviewRegion, visibleApps);
                if (!reviewed.items.isEmpty()) list.add(reviewed);
            }
            if (showCricket) list.add(cricketRow(cricketFixtures));
            list.addAll(Recommendations.appRows(catalog,visibleApps,appsToFill -> trending.cachedProviderFallback(catalogEngine, appsToFill)));
            if ((recommended || releases) && catalog.isEmpty() && published.isEmpty()
                    && !SectionOptions.hidden(this, Recommendations.EXPLORE)) {
                Channels.Channel explore = new Channels.Channel();
                explore.key = Recommendations.EXPLORE;
                explore.name = explore.title = "More to explore";
                explore.items.addAll(trending.explore());
                explore.count = explore.items.size();
                if (!explore.items.isEmpty()) list.add(explore);
            }
            if (visibleApps.contains(SpotifyPicks.PACKAGE) && !music.items.isEmpty()) list.add(music);
            list.addAll(published);SectionOptions.filter(this,list);
            ChannelSnapshot prepared=new ChannelSnapshot(list);
            runOnUiThread(()->{
                if(isDestroyed()||generation!=channelsGeneration)return;
                pendingChannels=prepared;root.removeCallbacks(applyPreparedChannels);applyPreparedChannels.run();
            });
        };
        channelWorker.execute(queuedChannelPreparation);
    }

    /** Apply completed rows while newer snapshots prepare. Defer changes only while navigating those rows. */
    private final Runnable applyPreparedChannels=()->{
        if(pendingChannels==null||!inFront)return;
        long now=android.os.SystemClock.uptimeMillis();
        long ready=Math.max(homeInteractiveAfter,lastKeyAt+NAV_IDLE_MS);
        if(!chanRows.isEmpty()&&zone==Z_CHAN&&!refocusChannels&&now<ready){root.postDelayed(this.applyPreparedChannels,ready-now);return;}
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
        if(Recommendations.isRow(p.tab))return p.catalogIdentity();
        return p.extra instanceof Cricket.Match ? ((Cricket.Match) p.extra).id : String.valueOf(p.id);
    }

    private Channels.Channel cricketRow(List<Cricket.Match> list) {
        Channels.Channel c = new Channels.Channel();
        c.key = "cricket";
        c.name = c.title = "Cricket";
        if (list.isEmpty()) {
            Trending.Pick empty = new Trending.Pick();
            empty.kind = "scores-empty";
            empty.tab = c.key;
            empty.title = "No matches to show";
            empty.meta = SportsDashboard.detail(null);
            c.items.add(empty);
        }
        for (Cricket.Match m : list) {
            Trending.Pick p = new Trending.Pick();
            p.kind = p.tab = c.key;
            p.extra = m;
            p.id = java.util.Objects.hash(m.id, m.s1, m.s2, m.status, m.start, m.live, m.paused,
                    m.full1, m.full2, m.img1, m.img2, m.league, m.kind, m.venue, m.session, m.phase);
            p.title = m.name;
            p.meta = SportsDashboard.detail(m);
            p.pkg = m.watchPkg;
            p.intentUri = m.watchUri;
            c.items.add(p);
        }
        return c;
    }

    private static boolean isScores(Channels.Channel ch) { return "cricket".equals(ch.key); }

    private void buildChannelRows(List<Channels.Channel> list,Map<String,String> signatures) {
        Channels.Channel widgets=null;List<Channels.Channel> below=new ArrayList<>();
        for(Channels.Channel row:list){if(isInsights(row))widgets=row;else below.add(row);}
        buildTopInsights(widgets,widgets==null?"":signatures.get(widgets.key));list=below;

        for(int i=0;i<chanRows.size()&&i<chanCol.length;i++){ChannelViews old=channelViews.get(chanRows.get(i).key);if(old!=null)old.col=chanCol[i];}
        Map<String,ChannelViews> next=new java.util.LinkedHashMap<>();
        chanScrolls.clear();chanSprings.clear();chanCards.clear();chanCol=new int[list.size()];
        for(int i=0;i<list.size();i++){
            Channels.Channel ch=list.get(i);String signature=signatures.get(ch.key);
            ChannelViews old=channelViews.get(ch.key);int col=old==null?0:Math.max(0,Math.min(old.col,ch.items.size()-1));
            ChannelViews row=old!=null&&signature.equals(old.signature)?old:buildChannelRow(ch,col);
            row.signature=signature;row.col=col;next.put(ch.key,row);chanCol[i]=col;
            chanScrolls.add(row.scroll);chanSprings.add(row.spring);chanCards.add(row.cards);
        }
        for(Map.Entry<String,ChannelViews> entry:channelViews.entrySet())if(next.get(entry.getKey())!=entry.getValue()){
            ChannelViews removed = entry.getValue();
            if (activeChannelCaption == removed.caption) activeChannelCaption = null;
            cardArtwork.clear(removed.row);channelsBox.removeView(removed.row);
        }
        int index=0;
        for(ChannelViews row:next.values()){
            if(channelsBox.indexOfChild(row.row)!=index){if(row.row.getParent()==channelsBox)channelsBox.removeView(row.row);channelsBox.addView(row.row,index);}
            index++;
        }
        channelViews.clear();channelViews.putAll(next);chanRows=list;styleChannels();layoutPage();
        if(zone==Z_GRID)focusGrid(gridIdx);
    }

    private void refreshMeters() {
        meters.update();
        if (hadMeters != !meters.content().isEmpty()) buildTopInsights(topInsightRow, topInsightSignature);
    }

    private void buildTopInsights(Channels.Channel row, String signature) {
        boolean showMeters = !meters.content().isEmpty();
        if (java.util.Objects.equals(signature, topInsightSignature) && showMeters == hadMeters) return;
        String selected = insightCards.isEmpty() ? "" : (String) insightCards.get(insightIdx).getTag();
        boolean restoreFocus = zone == Z_INSIGHTS;
        boolean restoreAir = airFocused;
        if (insightFade != null) insightFade.cancel();
        topInsightRow = row;
        topInsightSignature = signature;
        hadMeters = showMeters;
        airView = null;
        airValue = null;
        topAir.removeAllViews();
        topInsights.removeAllViews();
        insightCards.clear();
        if (showMeters) {
            meters.setTag("meters");
            topInsights.addView(meters, new FrameLayout.LayoutParams(-1, -1));
            insightCards.add(meters);
        }
        if (row != null) for (Trending.Pick item : row.items) {
            HomeInsights.Card value = (HomeInsights.Card) item.extra;
            HomeInsightCard card = new HomeInsightCard(this, value);
            card.setTag(value.kind);
            card.setColors(theme.text, theme.text2);
            if ("air".equals(value.kind)) {
                airValue = value;
                airView = card;
                topAir.addView(card, new FrameLayout.LayoutParams(-1, -1));
            } else {
                topInsights.addView(card, new FrameLayout.LayoutParams(-1, -1));
                insightCards.add(card);
            }
        }
        topInsights.setVisibility(insightCards.isEmpty() ? View.GONE : View.VISIBLE);
        topAir.setVisibility(airView == null ? View.GONE : View.VISIBLE);
        insightIdx = 0;
        for (int i = 0; i < insightCards.size(); i++) {
            if (selected.equals(insightCards.get(i).getTag())) insightIdx = i;
        }
        showTopInsight(insightIdx);
        if (restoreFocus) {
            if (restoreAir && airView != null) focusAir();
            else if (hasRotatingDetails()) focusInsights(insightIdx);
            else if (airView != null) focusAir();
            else focusDock(dockIdx);
        }
        applyForYou();
        scheduleInsightRotation();
    }

    private void showTopInsight(int index) {
        if (insightCards.isEmpty()) return;
        insightIdx = Math.floorMod(index, insightCards.size());
        if (insightFade != null) insightFade.cancel();
        insightSurface.setColor(insightColor());
        for (int i = 0; i < insightCards.size(); i++) {
            View card = insightCards.get(i);
            card.setAlpha(1f);
            card.setTranslationY(0);
            card.setVisibility(i == insightIdx ? View.VISIBLE : View.INVISIBLE);
            card.setImportantForAccessibility(i == insightIdx ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private int insightColor() {
        return headerColor(zone == Z_INSIGHTS && !airFocused);
    }

    private int headerColor(boolean focused) {
        return focused ? lerpColor(theme.iconBg, theme.iconFocusBg, .24f) : theme.iconBg;
    }

    private void scheduleInsightRotation(){
        insightTimer.removeCallbacks(rotateInsight);
        if(inFront&&hasWindowFocus()&&insightCards.size()>1)insightTimer.postDelayed(rotateInsight,INSIGHT_ROTATION_MS);
    }
    private void rotateTopInsight(){
        if(inFront&&hasWindowFocus()&&zone!=Z_INSIGHTS&&zone!=Z_MENU&&!moving&&pageSpring.target==0f&&insightCards.size()>1){
            int next = (insightIdx + 1) % insightCards.size();
            if (animationScale() <= 0f || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                showTopInsight(next);
            } else {
                View outgoing = insightCards.get(insightIdx);
                View incoming = insightCards.get(next);
                insightIdx = next;
                outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                incoming.setAlpha(0f);
                incoming.setTranslationY(px(4));
                incoming.setVisibility(View.VISIBLE);
                insightFade = android.animation.ValueAnimator.ofFloat(0f, 1f);
                insightFade.setDuration(220);
                int distance = px(4);
                insightFade.addUpdateListener(animation -> {
                    float progress = (float) animation.getAnimatedValue();
                    outgoing.setAlpha(1f - progress);
                    outgoing.setTranslationY(-distance * progress);
                    incoming.setAlpha(progress);
                    incoming.setTranslationY(distance * (1f - progress));
                });
                insightFade.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        outgoing.setVisibility(View.INVISIBLE);
                        outgoing.setAlpha(1f);
                        outgoing.setTranslationY(0);
                        incoming.setAlpha(1f);
                        incoming.setTranslationY(0);
                        insightFade = null;
                    }
                });
                insightFade.start();
            }
        }
        scheduleInsightRotation();
    }
    private boolean hasRotatingDetails() {
        return insightCards.size() > (hadMeters ? 1 : 0);
    }

    private void focusInsights(int index) {
        if (!hasRotatingDetails()) {
            if (airView != null) focusAir();
            else focusTop(iconIdx);
            return;
        }
        zone = Z_INSIGHTS;
        airFocused = false;
        showTopInsight(index);
        focusHeaderWidget(insightCards.get(insightIdx));
    }

    private void focusAir() {
        zone = Z_INSIGHTS;
        airFocused = true;
        focusHeaderWidget(airView);
    }

    private void focusHeaderWidget(View card) {
        scrollPage(0);
        setFocus(card, 1f, 10);
        ring.animate().cancel();
        ring.setAlpha(0f);
        styleTop();
        updateAmbient();
    }

    private void focusRightmostWidget() {
        if (airView != null) focusAir();
        else focusInsights(insightIdx);
    }

    private void openTopInsightsMenu() {
        final int index = insightIdx;
        final boolean returnToAir = airFocused;
        beginMenu(P_CONTROL, "Header widgets");
        menuTile = returnToAir ? airView : insightCards.get(index);
        menuClosed = () -> {
            if (returnToAir) focusRightmostWidget();
            else focusInsights(index);
        };
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
        if (ch.pkg != null) {
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
        int w = cardW(ch), h = SpotifyPicks.KEY.equals(ch.key) ? MUSIC_SIZE : Recommendations.isRow(ch.key) ? POSTER_H : CH_H;
        if (isMosaic(ch)) {
            int mosaicHeight = isFormula(ch) ? FormulaOneDashboard.BODY_HEIGHT : SportsDashboard.BODY_HEIGHT;
            FrameLayout mosaic = new FrameLayout(this);
            mosaic.setClipChildren(false); mosaic.setClipToPadding(false);
            int width = isScores(ch) ? (int) (getResources().getDisplayMetrics().widthPixels / dp) : 960;
            mosaic.setLayoutParams(new FrameLayout.LayoutParams(px(width), px(mosaicHeight)));
            for (int i = 0; i < ch.items.size(); i++) {
                View card = channelCard(ch.items.get(i));
                int[] cell = isFormula(ch) ? FormulaOneDashboard.cell(i) : SportsDashboard.cell(i, width);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(cell[2]), px(cell[3]));
                cl.leftMargin = px(cell[0]); cl.topMargin = px(cell[1]);
                mosaic.addView(card, cl); cards.add(card);
            }
            if (isScores(ch)) {
                row.addView(mosaic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(mosaicHeight)));
            } else {
                hsv.setPadding(0, 0, 0, 0);
                hsv.addView(mosaic);
                row.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(mosaicHeight)));
            }
        } else {
            PicksRow track = new PicksRow(this, null);
            track.fixedCards(w, h);
            track.setOnFrame(this::snapRing);
            int viewport = Math.round(getResources().getDisplayMetrics().widthPixels / dp);
            int count = PicksRow.viewCount(ch.items.size(), viewport, w, CH_GAP);
            for (int i = 0; i < count; i++) cards.add(channelCard(ch.items.get(i % ch.items.size())));
            track.setItems(cards, ch.items.size(), col);
            hsv.setFillViewport(true);
            hsv.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(h)));
            row.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(h + 16)));
        }

        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(px(42), px(6), px(40), 0);
        caption.setAlpha(0f);
        if (isScores(ch)) caption.setVisibility(View.GONE);
        TextView name = new TextView(this);
        name.setTag("name");
        name.setMaxLines(Recommendations.isRow(ch.key) ? 2 : 1);
        name.setIncludeFontPadding(false);
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
        row.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                px(Recommendations.isRow(ch.key) ? 64 : 52)));

        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(channelHeight(ch))));
        ChannelViews result=new ChannelViews();result.row=row;result.scroll=isScores(ch)?null:hsv;result.cards=cards;
        result.caption = caption;
        result.captionTitle = name;
        result.captionMeta = meta;
        result.reel = !isMosaic(ch) ? (PicksRow) hsv.getChildAt(0) : null;
        result.spring = result.reel == null ? new Spring(0.34f,1f,0,0.25f) : result.reel.camera();
        if (result.reel == null && result.scroll != null) hsv.post(()->hsv.scrollTo(Math.round(result.spring.value),0));
        return result;
    }

    private static int cardW(Channels.Channel ch) {
        if (SpotifyPicks.KEY.equals(ch.key)) return MUSIC_SIZE;
        return Recommendations.isRow(ch.key) ? POSTER_W : CH_W;
    }

    /** A card in a channel row: its artwork (loaded in the background), or the app's banner and the title. */
    private View channelCard(Trending.Pick p) {
        if (p.extra instanceof FormulaOne.Card) return new FormulaOneCard(this,(FormulaOne.Card)p.extra);
        if (p.extra instanceof Cricket.Match match) {
            SportsDashboard.MatchCard card = new SportsDashboard.MatchCard(this, match, dp);
            loadScoreLogo(card, true, match.img1);
            loadScoreLogo(card, false, match.img2);
            return card;
        }
        if ("scores-empty".equals(p.kind)) return new SportsDashboard.MatchCard(this, null, dp);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(theme.tileBg, 0, 10));
        final float r = px(10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        boolean music=SpotifyPicks.KEY.equals(p.tab);
        card.setClipToOutline(!music);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (music) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (p.image != null && p.image.isFile()) {
                card.setTag(R.id.ambient_artwork, p.image);
                cardArtwork.bind(img, p.image, px(MUSIC_SIZE * 1.08f), px(MUSIC_SIZE * 1.08f), false, true);
            } else {
                TextView title = new TextView(this);
                title.setText(p.title);
                title.setTextColor(theme.text);
                title.setTextSize(14);
                title.setGravity(Gravity.CENTER);
                title.setPadding(px(12), px(8), px(12), px(8));
                card.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            decorateTitleCard(card, p, false);
            return card;
        }
        boolean portrait=Recommendations.isRow(p.tab);
        boolean hasPoster=ArtworkAssets.portrait(p.poster);
        boolean posterRequired=Recommendations.isAppRow(p.tab)||TitleArtwork.prefersCatalog(p.pkg);
        File art=portrait?(hasPoster?p.poster:posterRequired?null:p.image):p.image;
        boolean portraitFallback=!portrait&&art==null&&hasPoster;
        if(portraitFallback)art=p.poster;
        boolean resume = "watchnext".equals(p.kind) || "recent".equals(p.kind);
        boolean fit=resume||portraitFallback||portrait&&!hasPoster;
        if(fit)img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (art != null && art.isFile()) {
            card.setTag(R.id.ambient_artwork, art);
            cardArtwork.bind(img,art,px((portrait?POSTER_W:CH_W)*1.08f),
                    px((portrait?POSTER_H:CH_H)*1.08f),!fit,portrait||portraitFallback||resume);
        } else if (p.sweep) {
            addCatalogPlaceholder(card, p);
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
        boolean appRow = Recommendations.isAppRow(p.tab)
                || p.tab != null && p.pkg != null && p.tab.startsWith(p.pkg + "|");
        decorateTitleCard(card, p, !appRow);
        return card;
    }

    private void decorateTitleCard(FrameLayout card, Trending.Pick pick, boolean showProviderIcon) {
        String description = pick.service == null || pick.service.isEmpty()
                ? pick.title : pick.title + " · " + pick.service;
        if ("IMDb".equalsIgnoreCase(pick.ratingSource) && pick.rating > 0f && pick.rating <= 10f) {
            TextView score = new TextView(this);
            score.setTag("imdb-rating");
            score.setText(getString(R.string.imdb_card_score, pick.rating));
            score.setTextColor(0xFFE9D6A9);
            score.setTextSize(10);
            score.setTypeface(getResources().getFont(R.font.outfit));
            score.setFontVariationSettings("'wght' 550");
            score.setSingleLine(true);
            score.setIncludeFontPadding(false);
            score.setPadding(px(4), px(2), px(4), px(2));
            score.setBackground(rounded(0xB4171717, 0, 4));
            score.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            position.topMargin = px(8);
            position.setMarginStart(px(8));
            card.addView(score, position);
            description += ", " + getString(R.string.imdb_card_score_accessibility, pick.rating);
        }
        card.setContentDescription(description);
        if (!showProviderIcon || pick.pkg == null) return;
        try {
            ImageView badge = new ImageView(this);
            badge.setTag("provider-badge");
            badge.setImageDrawable(pm.getApplicationIcon(pick.pkg));
            badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(px(20), px(20), Gravity.TOP | Gravity.END);
            position.setMargins(px(9), px(9), px(9), 0);
            card.addView(badge, position);
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private void loadScoreLogo(SportsDashboard.MatchCard card, boolean first, String url) {
        if (url == null || url.isEmpty()) return;
        background.execute(() -> {
            File file = WatchNext.cached(this, new File(getExternalFilesDir(null), "cricket/flags"), url, 64);
            Bitmap bitmap = file == null ? null : BitmapFactory.decodeFile(file.getPath());
            if (bitmap != null) runOnUiThread(() -> card.setLogo(first, bitmap));
        });
    }

    private void styleChannels() {
        for (int i = 0; i < channelsBox.getChildCount(); i++) {
            View row = channelsBox.getChildAt(i);
            TextView t = row.findViewWithTag("chanTitle"), n = row.findViewWithTag("name"), m = row.findViewWithTag("meta");
            if (t != null) t.setTextColor(theme.text);
            if (n != null) n.setTextColor(theme.text);
            if (m != null) m.setTextColor(theme.text2);

        }
    }

    // ---------------------------------------------------------------- picks

    private void loadPicks() {
        if(watchNextPicks.isEmpty() && !watchNextPermissionMissing())
            watchNextPicks=AppleResumeCache.restoreHome(this,System.currentTimeMillis());
        recentPicks = continuePicks(rememberSessionApps(RecentStore.load(this)), watchNextPicks);
    }

    private boolean showTopContent() {
        return !picksFor(0).isEmpty() || !picksFor(TAB_TRENDING).isEmpty() || !picksFor(2).isEmpty();
    }

    private List<Trending.Pick> picksFor(int t) {
        if (t == 0) return ContinueOptions.showTab(this) ? recentPicks : Collections.emptyList();
        if (!discoveryTabEnabled(t)) return Collections.emptyList();
        return t == TAB_TRENDING ? trendPicks : indiaPicks;
    }

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
        if (picksFor(tabIdx).isEmpty()) {
            for (int t = 0; t < TAB_IDS.length; t++) if (!picksFor(t).isEmpty()) { tabIdx = t; break; }
        }
        rowPicks = tabIdx == 0 ? new ArrayList<>(picksFor(0)) : new ArrayList<>(rotated(picksFor(tabIdx)));
        List<View> views = new ArrayList<>();
        int viewport = Math.round(getResources().getDisplayMetrics().widthPixels / dp);
        int count = PicksRow.viewCount(rowPicks.size(), viewport, PicksRow.SMALL_W, PicksRow.GAP);
        for (int i = 0; i < count; i++) views.add(pickView(rowPicks.get(i % rowPicks.size())));
        picksRow.setItems(views, rowPicks.size(), anchor);
        updatePickImages(picksRow.anchor(), false);
        requestTitleCovers();
        boolean visible = !rowPicks.isEmpty();
        picksRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible != (forYouHead.getVisibility() == View.VISIBLE)) applyForYou();
        else styleTop();
        updateAmbient();
    }

    /** A pick: the artwork and a progress bar when the app shares one. */
    private void addCatalogPlaceholder(FrameLayout card, Trending.Pick pick) {
        TextView label = new TextView(this);
        label.setTag("catalog_placeholder");
        String provider = pick.service == null || pick.service.isEmpty()
                ? pick.pkg == null ? "" : Channels.label(pm, pick.pkg) : pick.service;
        label.setText(provider.isEmpty() ? pick.title : pick.title + "\n" + provider);
        label.setTextColor(theme.text);
        label.setTextSize(15);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(6);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(px(10), px(18), px(10), px(12));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

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
        boolean resume = "watchnext".equals(p.kind) || "recent".equals(p.kind);
        boolean noArtwork = !ArtworkAssets.landscape(p.image) && !ArtworkAssets.portrait(p.image)
                && !ArtworkAssets.portrait(p.poster);
        if (noArtwork && !p.sweep && !resume) showAppLogo(card, back, p.pkg);
        card.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView poster = new ImageView(this);
        poster.setTag("poster");
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setAlpha(0f);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (noArtwork && (p.sweep || resume)) addCatalogPlaceholder(card, p);
        if (resume) {
            TextView title = new TextView(this);
            title.setTag("continue_title");
            title.setText(p.title);
            title.setTextColor(theme.text);
            title.setTypeface(getResources().getFont(R.font.outfit));
            title.setTextSize(15);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(4);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(px(12), px(30), px(12), px(18));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            title.setVisibility(View.GONE);
            card.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
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
        decorateTitleCard(card, p, true);
        item.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(PicksRow.CARD_H)));
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return item;
    }

    /** Keep the focused card at the cover’s own aspect ratio and release hidden landscape bitmaps. */
    private void updatePickImages(int anchor, boolean animateWidth) {
        updatePickCaption(anchor);
        for (int i = 0; i < picksRow.getChildCount() && !rowPicks.isEmpty(); i++) {
            updatePickImage(i, anchor, animateWidth);
        }
    }

    private void updatePickImage(int i, int anchor, boolean animateWidth) {
        View item = picksRow.getChildAt(i);
        Trending.Pick p = rowPicks.get(i % rowPicks.size());
        ImageView back = item.findViewWithTag("backdrop"), poster = item.findViewWithTag("poster");
        if (back == null || poster == null) return;
        boolean discovery = TitleCovers.discovery(p);
        TitleCovers.Result cover = discovery ? titleCovers.get(TitleCovers.key(p)) : null;
        File portrait = ArtworkAssets.portrait(p.poster) ? p.poster : null;
        File landscape = p.image;
        if (discovery) landscape = cover == null ? null : cover.image;
        // A managed feed's background has no title-cover label. Prefer the poster when the feed supplies both.
        else if (p.sweep && portrait != null) landscape = null;
        boolean resume = "watchnext".equals(p.kind) || "recent".equals(p.kind);
        boolean large = i == picksRow.selectedIndex();
        if (portrait == null && resume && ArtworkAssets.portrait(landscape)) portrait = landscape;
        boolean hasPoster = portrait != null;
        boolean hasLandscape = ArtworkAssets.landscape(landscape)
                || resume && !hasPoster && landscape != null && landscape.isFile();
        boolean usePoster = hasPoster && (!large || !hasLandscape);
        boolean showBackdrop = !resume || large;
        if (usePoster || showBackdrop && hasLandscape) {
            View placeholder = item.findViewWithTag("catalog_placeholder");
            if (placeholder != null) ((ViewGroup) placeholder.getParent()).removeView(placeholder);
        }
        if (resume) {
            item.findViewWithTag("continue_title").setVisibility(!large && !usePoster && hasLandscape ? View.VISIBLE : View.GONE);
        }
        back.setVisibility(showBackdrop ? View.VISIBLE : View.INVISIBLE);
        int width = PicksRow.SMALL_W;
        if (large) {
            int[] size = ArtworkAssets.size(usePoster ? portrait : landscape);
            width = PicksRow.expandedWidthFor(size[0], size[1]);
            picksRow.setExpandedWidth(width, animateWidth);
        }
        item.setTag(R.id.ambient_artwork, usePoster ? portrait : showBackdrop && hasLandscape ? landscape : null);
        int targetWidth = px(width * 1.02f), targetHeight = px(PicksRow.CARD_H * 1.02f);
        boolean fitPoster = large || resume;
        poster.setScaleType(fitPoster ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (usePoster) cardArtwork.bind(poster, portrait, targetWidth, targetHeight, !fitPoster, true, true);
        poster.animate().alpha(usePoster ? 1f : 0f).setDuration(250).start();
        if (!showBackdrop) {
            if (back.getTag(R.id.img_state) != null) cardArtwork.clear(back);
            return;
        }
        if (!hasLandscape) return;
        boolean crop = !large && !discovery && !resume;
        back.setScaleType(crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (large || !usePoster) {
            cardArtwork.bind(back, landscape, targetWidth, targetHeight, crop, resume || discovery, true);
        } else {
            back.postDelayed(() -> {
                if (rowPicks.indexOf(p) != picksRow.anchor() && poster.getAlpha() > 0.99f) {
                    back.setImageDrawable(null);
                    back.setTag(R.id.img_state, null);
                }
            }, 400);
        }
    }

    private void requestTitleCovers(){
        final int generation=++titleCoverGeneration;List<Trending.Pick> requested=new ArrayList<>(rowPicks);
        if(!inFront||requested.isEmpty()||!TitleCovers.discovery(requested.get(0))||Keys.activeTmdbKey(this)==null)return;
        titleCoverWorker.execute(()->{
            for(Trending.Pick p:requested){
                if(isDestroyed()||generation!=titleCoverGeneration||Thread.currentThread().isInterrupted())return;
                TitleCovers.Result cover=TitleCovers.resolve(this,p);
                runOnUiThread(()->{if(isDestroyed()||generation!=titleCoverGeneration)return;titleCovers.put(TitleCovers.key(p),cover);int i=rowPicks.indexOf(p);if(i>=0&&i<picksRow.getChildCount()){updatePickImages(picksRow.anchor(),true);if(i==picksRow.anchor()&&(zone==Z_PICKS||zone==Z_TABS))updateAmbient();}});
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

    private void updatePickCaption(int anchor) {
        if (pendingContinueMetadata != null) {
            root.removeCallbacks(pendingContinueMetadata);
            pendingContinueMetadata = null;
        }
        if (!showTopContent() || anchor < 0 || anchor >= rowPicks.size()) {
            pickCaption.setVisibility(View.GONE);
            return;
        }
        Trending.Pick pick = rowPicks.get(anchor);
        pickCaptionTitle.setText(pick.title);
        String details = pick.displayMeta();
        if (RecentStore.TAB.equals(pick.tab)) {
            ContinueOptions.Episode episode = ContinueOptions.episode(pick);
            String key = pick.pkg + "\n" + TitleArtwork.queryTitle(pick.title)
                    + "\n" + (episode == null ? "" : episode.season);
            Trending.TitleMetadata metadata = continueMetadata.get(key);
            details = ContinueOptions.caption(pick, metadata);
            Long retryAt = continueMetadataRetry.get(key);
            if (metadata == null && !continueMetadataRunning
                    && (retryAt == null || SystemClock.elapsedRealtime() >= retryAt)) {
                pendingContinueMetadata = () -> loadContinueMetadata(pick, key);
                root.postDelayed(pendingContinueMetadata, 500);
            }
        }
        pickCaptionMeta.setText(details);
        pickCaptionMeta.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        pickCaption.setVisibility(View.VISIBLE);
    }

    private void loadContinueMetadata(Trending.Pick pick, String key) {
        pendingContinueMetadata = null;
        if (!inFront || isDestroyed()) return;
        continueMetadataRunning = true;
        titleLinkWorker.execute(() -> {
            Trending.TitleMetadata metadata = null;
            try {
                metadata = trending.details(pick);
            } catch (Exception unavailable) {
                // Keep the publisher's episode information when title metadata is unavailable.
            }
            Trending.TitleMetadata result = metadata;
            runOnUiThread(() -> {
                continueMetadataRunning = false;
                if (isDestroyed()) return;
                if (result == null) continueMetadataRetry.put(key, SystemClock.elapsedRealtime() + 60_000L);
                else continueMetadata.put(key, result);
                if (inFront) updatePickCaption(picksRow.anchor());
            });
        });
    }

    private void updateAmbient() {
        if (pendingArtworkColour != null) root.removeCallbacks(pendingArtworkColour);
        pendingArtworkColour = null;
        String previousRequest = requestedArtworkColour;
        requestedArtworkColour = null;
        if (hasWallpaper) return;
        if (!activeTheme.usesGlow()) {
            TimeOfDay.Palette palette = appliedPalette == null ? themePalette(activeTheme) : appliedPalette;
            backdrop.setTimeColors(palette.top, palette.mid, palette.bottom);
            return;
        }
        backdrop.clearTimeColors();

        AppEntry app = null;
        View card = null;
        if (zone == Z_DOCK && dockIdx < apps.size()) app = apps.get(dockIdx);
        else if (zone == Z_GRID && dockCount + gridIdx < apps.size()) app = apps.get(dockCount + gridIdx);
        else if ((zone == Z_PICKS || zone == Z_TABS) && !rowPicks.isEmpty()) {
            app = appFor(rowPicks.get(picksRow.anchor()).pkg);
            card = picksRow.anchorItem();
        } else if (zone == Z_CHAN && chanIdx < chanRows.size()) {
            List<Trending.Pick> items = chanRows.get(chanIdx).items;
            if (!items.isEmpty()) app = appFor(items.get(Math.min(chanCol[chanIdx], items.size() - 1)).pkg);
            card = focusedView;
        }
        if (app == null && !apps.isEmpty()) app = apps.get(Math.min(dockIdx, apps.size() - 1));
        AppEntry source = app;
        File artwork = card == null ? null : (File) card.getTag(R.id.ambient_artwork);
        if (artwork == null || !artwork.isFile()) {
            backdrop.setGlow(source == null ? 0 : source.ambientColour(pm, glowStep));
            return;
        }

        String key = artwork.getPath() + ':' + artwork.lastModified() + ':' + artwork.length();
        requestedArtworkColour = key;
        int[] cached = colourCache.get(key);
        if (cached != null) {
            showArtworkGlow(cached, source);
            return;
        }
        if (key.equals(previousRequest) && key.equals(decodingArtworkColour)) return;
        pendingArtworkColour = () -> {
            pendingArtworkColour = null;
            decodingArtworkColour = key;
            artworkColours.execute(() -> {
                int[] colours = ArtworkColour.from(artwork);
                colourCache.put(key, colours);
                root.post(() -> {
                    if (key.equals(decodingArtworkColour)) decodingArtworkColour = null;
                    if (!isDestroyed() && (zone == Z_PICKS || zone == Z_TABS || zone == Z_CHAN)
                            && key.equals(requestedArtworkColour)) {
                        showArtworkGlow(colours, source);
                    }
                });
            });
        };
        root.postDelayed(pendingArtworkColour, 120);
    }

    private void showArtworkGlow(int[] colours, AppEntry source) {
        backdrop.setGlow(colours.length == 0 && source != null ? source.ambientColour(pm, glowStep)
                : ArtworkColour.at(colours, 0, glowStep));
    }

    private AppEntry appFor(String pkg) {
        if (pkg != null) for (AppEntry a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private final Set<String> recentSessionApps = new HashSet<>();

    private List<Trending.Pick> rememberSessionApps(List<Trending.Pick> sessions) {
        recentSessionApps.clear();
        for (Trending.Pick pick : sessions) recentSessionApps.add(pick.pkg);
        return sessions;
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
                List<Trending.Pick> merged = continuePicks(rememberSessionApps(sessions), watchNextPicks);
                if (!sameKeys(recentPicks, merged)) {
                    boolean updateFocused = tabIdx==0 && zone==Z_PICKS && sameOrder(recentPicks,merged);
                    int anchor=picksRow.anchor();
                    recentPicks = merged;
                    if(updateFocused) {buildPicksRow(anchor);focusPicks(anchor);return;}
                    // A new TV can reveal Continue Watching as soon as an app shares an entry.
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
                    || !java.util.Objects.equals(first.poster,second.poster) || first.progress!=second.progress
                    || first.savedResume!=second.savedResume || !first.mediaKind.equals(second.mediaKind)
                    || !first.providerGenres.equals(second.providerGenres)) return false;
        }
        return true;
    }

    private static boolean sameOrder(List<Trending.Pick> a,List<Trending.Pick> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).key().equals(b.get(i).key()))return false;
        return true;
    }

    private void onPicks(List<Trending.Pick> all) {
        if (isDestroyed()) return;
        recommendationCatalog=ContentLanguages.filter(all,trending.languages());
        trendPicks = Trending.shelf(recommendationCatalog, Trending.TRENDING);
        indiaPicks = Trending.shelf(recommendationCatalog, Trending.INDIA);
        showChannels(appChannels);
        refreshDiscoveryShelf();
    }

    private boolean shelfFocused() { return zone == Z_PICKS || zone == Z_TABS; }

    private void refreshDiscoveryShelf() {
        if (rowPicks.isEmpty() || (tabIdx != 0 && !shelfFocused() && zone != Z_MENU)) buildPicksRow(0);
        else {
            // Keep focus and ordering stable, but bind newly cached art even while the tab is selected.
            if (tabIdx != 0) {
                List<Trending.Pick> latest = picksFor(tabIdx);
                for (int i = 0; i < rowPicks.size(); i++) {
                    Trending.Pick shown = rowPicks.get(i);
                    for (Trending.Pick fresh : latest) if (shown.key().equals(fresh.key())
                            && java.util.Objects.equals(shown.pkg, fresh.pkg)) { rowPicks.set(i, fresh); break; }
                }
            }
            updatePickImages(picksRow.anchor(), false);
            styleTop();
        }
    }

    private void scheduleHomeShelfRotation() {
        root.removeCallbacks(rotateHomeShelf);
        if (inFront && hasWindowFocus()) root.postDelayed(rotateHomeShelf,
                Math.max(SHELF_ROTATION_MS, firstShelfRotationAt - android.os.SystemClock.uptimeMillis()));
    }

    private void rotateHomeShelf() {
        if (inFront && hasWindowFocus() && pageSpring.target == 0f) advanceHomeShelf();
        scheduleHomeShelfRotation();
    }

    private void advanceHomeShelf() {
        if (zone == Z_MENU || moving || (continueShelfPinned && shelfFocused())) return;
        int next = nextTab(tabIdx, 1);
        if (next < 0) next = nextTab(-1, 1);
        if (next < 0 || next == tabIdx) return;
        if (zone == Z_TABS) {
            focusTabs(next);
        } else {
            tabIdx = next;
            buildPicksRow(0);
            if (zone == Z_PICKS) focusPicks(0);
        }
    }

    // ---------------------------------------------------------------- focus

    /** Grows the focused card or tile (and shrinks the previous one); the ring sits on it. */
    private void setFocus(View v, float scale, float radiusDp) {
        View old = focusedView;
        if (old != null && old != v) scaleTo(old, 1f);
        focusedView = v;
        ring.setCompact(dockIconShape() != 0 && (zone == Z_DOCK || (zone == Z_MENU && dockMenu)));
        if (old instanceof FormulaOneCard || v instanceof FormulaOneCard) updateFocusAccent();
        ringRadius = px(radiusDp);
        ring.animate().alpha(v == null ? 0f : 1f).setDuration(150).start();
        if (v == null) return;
        scaleTo(v, scale);
        snapRing();
    }

    private void updateFocusAccent() {
        int accent = activeTheme.usesGlow() ? 0 : theme.iconFocusBg;
        ring.setAccent(focusedView instanceof FormulaOneCard card ? card.focusAccent() : accent);
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
            View track = chanScrolls.get(i);
            if (track == null || ((android.widget.HorizontalScrollView) track).getChildAt(0) instanceof PicksRow) continue;
            Spring sp = chanSprings.get(i);
            if (!sp.step(dt) && track.getScrollX() == Math.round(sp.value)) continue;
            moving = true;
            track.scrollTo(Math.round(sp.value), 0);
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
        iconIdx = i == P_CONTROL ? P_SETTINGS : i;
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
        focusPicks(i, true);
    }

    private void focusPicks(int i, boolean animate) { focusPicks(i, animate, 0); }

    private void focusPicks(int i, boolean animate, int direction) {
        if (rowPicks.isEmpty()) return;
        zone = Z_PICKS;
        scrollPage(0);
        boolean moved = animate && i != picksRow.anchor();
        picksRow.setAnchor(i, moved, direction);
        updatePickImages(i, moved);
        updateAmbient();
        setFocus(picksRow.anchorCard(), 1.02f, 14);
        styleTop();
        followRing(moved ? 360 : 220);
    }

    private void focusDock(int i) {
        if (dockTiles.isEmpty()) {
            if (allTile != null) focusAllTile();
            else { zone = Z_DOCK; setFocus(null, 1f, 0); }
            return;
        }
        zone = Z_DOCK;
        dockIdx = Math.max(0, Math.min(i, dockTiles.size() - 1));
        prefs.edit().putInt("last_dock_col", dockIdx).apply();
        scrollPage(0);
        setFocus(dockTiles.get(dockIdx), moving ? 1.22f : 1.14f, dockTileRadius());
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

    private View channelCardAt(int row, int col) {
        ChannelViews views = channelViews.get(chanRows.get(row).key);
        return views.reel != null && views.reel.anchor() == col
                ? views.reel.anchorItem() : chanCards.get(row).get(col);
    }

    private void focusChan(int r, int c) {
        focusChan(r, c, true);
    }

    private void focusChan(int r, int c, boolean animate) { focusChan(r, c, animate, 0); }

    private void focusChan(int r, int c, boolean animate, int direction) {
        if (chanRows.isEmpty()) return;
        boolean enteringChannels = zone != Z_CHAN;
        zone = Z_CHAN;
        chanIdx = Math.max(0, Math.min(r, chanRows.size() - 1));
        List<Trending.Pick> items = chanRows.get(chanIdx).items;
        int col = Math.max(0, Math.min(c, items.size() - 1));
        chanCol[chanIdx] = col;
        scrollPage(px(CHAN_TOP + channelOffset(chanIdx) - 64));
        ChannelViews row = channelViews.get(chanRows.get(chanIdx).key);
        if (row.reel != null) row.reel.setAnchor(col, animate, direction);
        startMotion();
        Trending.Pick pick = items.get(col);
        if (!android.text.TextUtils.equals(row.captionTitle.getText(), pick.title)) row.captionTitle.setText(pick.title);
        String meta = pick.displayMeta();
        if (!android.text.TextUtils.equals(row.captionMeta.getText(), meta)) row.captionMeta.setText(meta);
        if (row.caption != activeChannelCaption) {
            if (activeChannelCaption != null) activeChannelCaption.animate().alpha(0f).setDuration(160).start();
            activeChannelCaption = row.caption;
            activeChannelCaption.animate().alpha(1f).setDuration(160).start();
        }
        setFocus(channelCardAt(chanIdx, col), isMosaic(chanRows.get(chanIdx)) ? 1.025f : 1.06f, 10);
        updateAmbient();
        if (enteringChannels) styleTop();
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
        scheduleHomeShelfRotation();
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
                || (zone == Z_MENU && menuMode == MENU_CHANNELS) || focusedMarketCurrencyCard() != null
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
        // Rotation can bring Continue into view by itself. Only remote interaction pins it.
        if (zone != Z_MENU) continueShelfPinned = shelfFocused() && tabIdx == 0;
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
                if (splitSettings && settingsSidebar) {
                    if (up || down) {
                        moveSettingsSidebar(down);
                    } else if (right && settingsSidebarAction < 0 && !menuItems.isEmpty()) {
                        settingsSidebar = false;
                        showSettingsHeading();
                        focusMenu(menuIdx);
                    }
                    break;
                }
                if (splitSettings && left && (menuItems.isEmpty() || !settingsGrid || menuItems.get(menuIdx).getParent() == menu
                        || menuIdx == 0 || menuItems.get(menuIdx - 1).getParent() != menuItems.get(menuIdx).getParent())) {
                    focusSettingsSidebar();
                    break;
                }
                if (dockMenu && (left || right)) {
                    java.util.function.IntConsumer cycle = dockMenuCycles.get(menuItems.get(menuIdx));
                    if (cycle != null) cycle.accept(left ? -1 : 1);
                    break;
                }
                if (appDrawer) {
                    moveAppDrawer(left, right, up, down);
                    break;
                }
                if (settingsGrid) {
                    moveSettingsGrid(left, right, up, down);
                    break;
                }
                if (up && menuIdx > 0) focusMenu(menuIdx - 1);
                else if (down && menuIdx < menuItems.size() - 1) focusMenu(menuIdx + 1);
                else if (left && settingsPopupMode) back();
                else if ((left || right) && !settingsPopupMode) closeMenu();
                break;
            case Z_TOP:
                if (left || right) {
                    int step = left ? -1 : 1;
                    int next = iconIdx + step;
                    while (next >= 0 && next < icons.length && icons[next].getVisibility() != View.VISIBLE) next += step;
                    if (next >= 0 && next < icons.length) focusTop(next);
                    else if (left && (airView != null || hasRotatingDetails())) focusRightmostWidget();
                }
                else if (down) { if (hasPicks) focusTabs(tabIdx); else focusDock(dockIdx); }
                break;  // Up, and the ends of the row, go nowhere.
            case Z_INSIGHTS:
                if (left && airFocused && hasRotatingDetails()) focusInsights(insightIdx);
                else if (right && !airFocused && airView != null) focusAir();
                else if (right) focusTop(P_INPUTS);
                else if(down){if(hasPicks)focusTabs(tabIdx);else focusDock(dockIdx);}
                break;
            case Z_TABS:
                if (left && nextTab(tabIdx, -1) >= 0) focusTabs(nextTab(tabIdx, -1));
                else if (right && nextTab(tabIdx, 1) >= 0) focusTabs(nextTab(tabIdx, 1));
                else if (up) {focusRightmostWidget();}
                else if (down) focusPicks(picksRow.anchor());
                break;
            case Z_PICKS:
                if ((left || right) && rowPicks.size() > 1) {
                    int current = picksRow.anchor(), count = rowPicks.size();
                    int next = Math.floorMod(current + (right ? 1 : -1), count);
                    focusPicks(next, true, right ? 1 : -1);
                }
                else if (up) focusTabs(tabIdx);
                else if (down) focusDock(dockIdx);
                break;
            case Z_DOCK:
                if (left && dockIdx > 0) focusDock(dockIdx - 1);
                else if (right && dockIdx < dockTiles.size() - 1) focusDock(dockIdx + 1);
                else if (right && allTile != null) focusAllTile();
                else if (up) { if (hasPicks) focusPicks(picksRow.anchor()); else focusRightmostWidget(); }
                else if (down && !chanRows.isEmpty()) focusChan(0, chanCol[0]);
                else if (down && !gridTiles.isEmpty()) focusGrid(Math.min(dockToGridCol(dockIdx), gridTiles.size() - 1));
                break;
            case Z_ALL:
                if (left) focusDock(dockTiles.size() - 1);
                else if (up) { if (hasPicks) focusPicks(picksRow.anchor()); else focusRightmostWidget(); }
                else if (down && !chanRows.isEmpty()) focusChan(0, chanCol[0]);
                else if (down) focusGrid(GRID_COLS - 1);
                break;
            case Z_CHAN: {
                int n = chanRows.get(chanIdx).items.size(), c = chanCol[chanIdx];
                Channels.Channel channel = chanRows.get(chanIdx);
                if (isFormula(channel)) {
                    int next = FormulaOneDashboard.neighbor(c, left ? -1 : right ? 1 : 0, up ? -1 : down ? 1 : 0);
                    if(next>=0) {focusChan(chanIdx,next);break;}
                    if(left||right)break;
                }
                if ((left || right) && !isMosaic(channel) && n > 1) {
                    int next = Math.floorMod(c + (right ? 1 : -1), n);
                    focusChan(chanIdx, next, true, right ? 1 : -1);
                }
                else if (left && c > 0) focusChan(chanIdx, c - 1);
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
        // in the background would be what waking the TV shows, instead of Sweep TV.
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
                + "," + (chanIdx < chanCol.length ? chanCol[chanIdx] : 0) + "," + menuIdx + "," + appIndex()
                + (splitSettings ? "," + settingsCategory + "," + settingsSidebar + "," + settingsSidebarAction : "");
    }

    /**
     * Android's own navigation and click sounds. Sweep TV takes the remote's presses itself, so Android doesn't play
     * them; they follow the TV's System sounds switch (off: silent), as everywhere else on the TV.
     */
    private void navigationSound(int k) {
        root.playSoundEffect(k == KeyEvent.KEYCODE_DPAD_UP ? android.view.SoundEffectConstants.NAVIGATION_UP
                : k == KeyEvent.KEYCODE_DPAD_DOWN ? android.view.SoundEffectConstants.NAVIGATION_DOWN
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? android.view.SoundEffectConstants.NAVIGATION_LEFT
                : android.view.SoundEffectConstants.NAVIGATION_RIGHT);
    }

    private void moveSettingsGrid(boolean left, boolean right, boolean up, boolean down) {
        if (menuItems.isEmpty()) return;
        View selected = menuItems.get(menuIdx);
        if (!up && !down) {
            int next = menuIdx + (left ? -1 : 1);
            if ((left || right) && next >= 0 && next < menuItems.size()
                    && selected.getParent() != menu && selected.getParent() == menuItems.get(next).getParent()) {
                focusMenu(next);
            }
            return;
        }
        int column = 0;
        for (int i = menuIdx - 1; i >= 0 && menuItems.get(i).getParent() == selected.getParent()
                && selected.getParent() != menu; i--) column++;
        int step = up ? -1 : 1;
        for (int i = menuIdx + step; i >= 0 && i < menuItems.size(); i += step) {
            View candidate = menuItems.get(i);
            if (candidate.getParent() == menu) {
                focusMenu(i);
                return;
            }
            if (candidate.getParent() != selected.getParent()) {
                int first = i, last = i;
                while (first > 0 && menuItems.get(first - 1).getParent() == candidate.getParent()) first--;
                while (last + 1 < menuItems.size() && menuItems.get(last + 1).getParent() == candidate.getParent()) last++;
                focusMenu(Math.min(first + column, last));
                return;
            }
        }
    }

    private void select() {
        root.playSoundEffect(android.view.SoundEffectConstants.CLICK);
        switch (zone) {
            case Z_MENU:
                if (splitSettings && settingsSidebar) {
                    if (settingsSidebarAction == SIDEBAR_SCAN_QR) {
                        openHomeDashboard();
                    } else if (settingsSidebarAction == SIDEBAR_TV_SETTINGS) {
                        openTvSettings();
                    } else if (!menuItems.isEmpty()) {
                        settingsSidebar = false;
                        showSettingsHeading();
                        focusMenu(menuIdx);
                    }
                    break;
                }
                if (menuIdx < menuActions.size()) {
                    Runnable a = menuActions.get(menuIdx);
                    boolean keep = menuKeepsOpen.get(menuIdx);
                    Object[] parent = {menuOpener, menuIdx, settingsHeading};
                    if (!keep) closeMenu();
                    int before = menuGen;
                    fromMenuRow = true;
                    try { a.run(); } finally { fromMenuRow = false; }
                    // A row that opened another menu: Back comes here. (A row that kept its menu open and rebuilt
                    // it is the same level.)
                    if (!keep && menuGen != before && zone == Z_MENU && parent[0] != null) {
                        menuParents.push(parent);
                        menuOpener = a;
                        showSettingsHeading();
                    }
                }
                break;
            case Z_TOP:
                if (iconIdx == P_INPUTS) openInputs();
                else if (iconIdx == P_WIFI) { if (!openShortcut("network")) start(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
                else if (iconIdx == P_BELL) openUpdates();
                else openQuickSettings(P_SETTINGS);
                break;
            case Z_TABS:
                focusPicks(picksRow.anchor());
                break;
            case Z_PICKS:
                openPick(rowPicks.get(picksRow.anchor()));
                break;
            case Z_INSIGHTS:
                if (airFocused && airValue != null) openInsight(airValue);
                else if (!insightCards.isEmpty()) {
                    View card = insightCards.get(insightIdx);
                    if (card instanceof HomeInsightCard) openInsight(((HomeInsightCard) card).value());
                    else openInsightsSettings();
                }
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

    private void openTitleDetails(Trending.Pick pick) {
        if (titleDialog != null) titleDialog.dismiss();
        Intent app = pick.pkg == null ? null : pm.getLeanbackLaunchIntentForPackage(pick.pkg);
        String service = pick.service;
        Drawable icon = null;
        if (pick.pkg != null) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pick.pkg, 0);
                icon = pm.getApplicationIcon(info);
                if (service == null || service.isEmpty()) service = pm.getApplicationLabel(info).toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        String label = service == null || service.isEmpty() ? "app" : service;
        TitleDetailsDialog dialog = new TitleDetailsDialog(this, pick, icon, cardArtwork);
        titleDialog = dialog;
        if (pick.extra instanceof RottenTomatoesPicks.Entry review) {
            dialog.reviewLink(review.label(), () -> {
                Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse(review.reviewUrl));
                if (!tryOpenTvSettings(page)) {
                    showInsightLink(pick.title, review.label(), review.reviewUrl);
                }
            });
        }
        dialog.setOnDismissListener(ignored -> {
            cardArtwork.clear(dialog.artwork());
            if (titleDialog == dialog) titleDialog = null;
        });

        Intent published = supportedTitleIntent(publishedTitleIntent(pick));
        String market = published == null && app != null && TitleLinks.supports(pick) ? Region.country(this) : null;
        String cached = market == null ? null : titleLinks.cached(pick, market);
        if (market != null && cached == null) {
            boolean[] requested = {false};
            dialog.action("Finding title link", "", () -> {
                requested[0] = true;
                dialog.action("Finding title link", "Opens when ready", () -> { });
            });
            titleLinkWorker.execute(() -> {
                String found;
                try { found = titleLinks.resolve(trending, pick, market); }
                catch (Exception unavailable) { found = ""; }
                Intent target = found.isEmpty() ? null : TitleLinks.appIntent(pick, found);
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    Intent supported = supportedTitleIntent(target);
                    Runnable play = setTitleAction(dialog, pick, label, supported, app);
                    if (requested[0] && supported != null && dialog.getWindow().getDecorView().hasWindowFocus()) play.run();
                });
            });
        } else {
            Intent target = cached != null && !cached.isEmpty()
                    ? supportedTitleIntent(TitleLinks.appIntent(pick, cached)) : published;
            setTitleAction(dialog, pick, label, target, app);
        }
        dialog.show();
        if (pick.externalId.isEmpty() && (TitleLinks.catalog(pick)
                || Trending.isStreamingApp(pick.pkg) && !ContinueOptions.isYouTube(pick.pkg))) {
            background.execute(() -> {
                Trending.TitleMetadata metadata;
                try { metadata = trending.details(pick); }
                catch (Exception unavailable) { return; }
                if (metadata != null) runOnUiThread(() -> {
                    if (!isDestroyed() && dialog.isShowing()) dialog.update(pick, metadata);
                });
            });
        }
    }

    private Intent supportedTitleIntent(Intent target) {
        return target != null && target.resolveActivity(pm) != null ? target : null;
    }

    private Runnable setTitleAction(TitleDetailsDialog dialog, Trending.Pick pick, String label, Intent target, Intent app) {
        if (Recommendations.EXPLORE.equals(pick.tab) && app == null && pick.pkg != null) {
            Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pick.pkg));
            if (supportedTitleIntent(store) != null) {
                Runnable install = () -> { if (tryOpenTvSettings(store)) dialog.dismiss(); };
                dialog.action("Install " + label, "", install);
                return install;
            }
        }
        String verb = target == null ? "Open " : pick.progress > 0 ? "Resume on " : "Play on ";
        Runnable action = () -> {
            if (target != null) {
                if (launchTitle(pick, target)) dialog.dismiss();
                else setTitleAction(dialog, pick, label, null, app);
            } else {
                dialog.dismiss();
                if (app != null) startFromTile(app);
            }
        };
        dialog.action(target == null && app == null ? "Close" : verb + label,
                target == null && app != null ? "Opens the app’s home screen" : "", action);
        return action;
    }

    private Intent publishedTitleIntent(Trending.Pick pick) {
        if (pick.intentUri == null || pick.pkg == null || pick.pkg.isEmpty()) return null;
        try {
            Intent intent = Intent.parseUri(pick.intentUri, Intent.URI_INTENT_SCHEME);
            if (intent.getSelector() != null) return null;
            String targetPackage = intent.getPackage();
            ComponentName component = intent.getComponent();
            if ((targetPackage != null && !pick.pkg.equals(targetPackage))
                    || (component != null && !pick.pkg.equals(component.getPackageName()))) return null;
            if (targetPackage == null && component == null) intent.setPackage(pick.pkg);

            boolean appHome = Intent.ACTION_MAIN.equals(intent.getAction()) && intent.getData() == null
                    && (intent.getExtras() == null || intent.getExtras().isEmpty());
            return appHome ? null : intent;
        } catch (java.net.URISyntaxException malformed) {
            return null;
        }
    }

    private boolean launchTitle(Trending.Pick pick, Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fromTile());
            if (pick.pkg != null) RecentApps.note(this, pick.pkg);
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return false;
        }
    }

    private TextView recommendationText(String text,float size,int color) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);
        view.setTypeface(getResources().getFont(R.font.outfit));if(settingsPopupMode)view.setFontVariationSettings("'wght' 400");view.setPadding(px(14),px(7),px(14),px(7));
        view.setMaxLines(size==14?7:4);view.setEllipsize(android.text.TextUtils.TruncateAt.END);menu.addView(view);
        return view;
    }

    private void openFormulaCard(FormulaOne.Card card) {
        final int row = chanIdx, col = row < chanCol.length ? chanCol[row] : 0;
        beginMenu(P_CONTROL, card.title);
        if (row < chanCards.size() && col < chanCards.get(row).size()) menuTile = channelCardAt(row, col);
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
        if ("scores-empty".equals(p.kind)) {
            Toast.makeText(this, p.meta, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(p.extra instanceof Cricket.Match)) {
            openTitleDetails(p);
            return;
        }
        Intent link = publishedTitleIntent(p);
        if (link != null && launchTitle(p, link)) return;
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
        else if (p.extra instanceof Cricket.Match) {
            Cricket.Match match = (Cricket.Match) p.extra;
            String score = match.s1.isEmpty() && match.s2.isEmpty() ? "" : match.t1 + " " + match.s1
                    + "   ·   " + match.t2 + " " + match.s2 + "\n";
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(match.name)
                    .setMessage(score + (match.start == null ? match.status : match.start) + "\n" + match.kind)
                    .setPositiveButton("Close", null).show();
        }
    }

    private void back() {
        if (splitSettings && !settingsSidebar && zone == Z_MENU) {
            focusSettingsSidebar();
            return;
        }
        if (zone == Z_MENU && !menuParents.isEmpty()) {
            Object[] parent = menuParents.pop();
            Runnable opener = (Runnable) parent[0];
            closeMenu();
            fromMenuRow = true;
            try { opener.run(); } finally { fromMenuRow = false; }
            menuOpener = opener;
            if (zone == Z_MENU && !menuItems.isEmpty()) focusMenu(Math.min((Integer) parent[1], menuItems.size() - 1));
        } else if (zone == Z_MENU) closeMenu();
        else if (zone != Z_DOCK) focusDock(dockIdx);
    }

    /**
     * The TV's settings. Some firmware hides the standard entry (some projectors); Android TV's own settings app is
     * usually still there. An idea from CleanYourTV (MIT).
     */
    private static Intent androidTvSettings() {
        return new Intent(Intent.ACTION_MAIN).setClassName("com.android.tv.settings",
                "com.android.tv.settings.MainSettings");
    }

    private boolean hasTvSettings() {
        return canOpenTvSettings(new Intent(Settings.ACTION_SETTINGS)) || canOpenTvSettings(androidTvSettings());
    }

    private void openTvSettings() {
        if (tryOpenTvSettings(new Intent(Settings.ACTION_SETTINGS)) || tryOpenTvSettings(androidTvSettings())) return;
        Toast.makeText(this, "TV settings are unavailable", Toast.LENGTH_SHORT).show();
    }

    private String tvSettingsLabel() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.trim();
        if ("redmi".equalsIgnoreCase(brand) || "xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) return "Xiaomi TV settings";
        if (brand.isEmpty() || "generic".equalsIgnoreCase(brand)) return "TV settings";
        if ("lg".equalsIgnoreCase(brand)) brand = "LG";
        else brand = Character.toUpperCase(brand.charAt(0)) + brand.substring(1);
        return brand + " TV settings";
    }

    private List<Intent> displaySettingsIntents() {
        List<Intent> intents = new ArrayList<>();
        if ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || "xiaomi".equalsIgnoreCase(Build.BRAND)
                || "redmi".equalsIgnoreCase(Build.BRAND)) {
            intents.add(new Intent("com.mitv.settings.PICTURE_SETTINGS"));
        }
        intents.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        intents.add(new Intent().setClassName("com.android.tv.settings",
                "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
        return intents;
    }

    private void openDisplaySettings() {
        for (Intent intent : displaySettingsIntents()) if (tryOpenTvSettings(intent)) return;
        openTvSettings();
    }

    private boolean canOpenTvSettings(Intent intent) {
        ResolveInfo match = pm.resolveActivity(intent, 0);
        if (match == null || match.activityInfo == null) return false;
        android.content.pm.ActivityInfo activity = match.activityInfo;
        return activity.enabled && activity.exported
                && (activity.permission == null || checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED);
    }

    private boolean tryOpenTvSettings(Intent intent) {
        if (!canOpenTvSettings(intent)) return false;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    /**
     * The app grows out of the focused tile or card, from the ring's rectangle. It's the window animation Android
     * draws anyway, only starting from the tile instead of the middle. An idea from Plume (MIT).
     */
    private Bundle fromTile() {
        if (focusedView == null || ring.rect.isEmpty()) return null;
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
        if (keep) saveOrder(prefs.edit());
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

    private void saveOrder(SharedPreferences.Editor editor) {
        StringBuilder d = new StringBuilder(), rest = new StringBuilder();
        for (int i = 0; i < apps.size(); i++) {
            StringBuilder b = i < dockCount ? d : rest;
            if (b.length() > 0) b.append(',');
            b.append(apps.get(i).pkg);
        }
        if (appsOrder() != ORDER_YOURS) {
            // All apps is sorted for you: only the dock's arrangement is yours to keep.
            editor.putString("order_dock", d.toString()).apply();
            loadApps();
            buildAppViews();
            return;
        }
        editor.putString("order_dock", d.toString()).putString("order_apps", rest.toString()).apply();
    }

    // ---------------------------------------------------------------- menus (Inputs, Control Center)

    private void beginSettingsMenu(int anchor,String heading){
        settingsOpening=true;
        try {
            beginMenu(anchor, heading);
            splitSettings = settingsSidebar = openingSettingsPage;
        } finally {
            settingsOpening = false;
        }
    }

    private void beginMenu(int anchor, String heading) {
        splitSettings = settingsSidebar = false;
        settingsSidebarAction = -1;
        watchNextPermissionRow = false;
        dockMenu = false;
        dockMenuPackage = null;
        dockRemoveRow = null;
        dockMenuCycles.clear();
        marketCurrencyCards.clear();
        boolean rootSettings = "Sweep TV settings".equals(heading);
        if(settingsOpening||rootSettings)settingsPopupMode=true;
        else if(!fromMenuRow)settingsPopupMode=false;
        settingsGrid=rootSettings;settingsColumns=3;settingsHeading=heading;
        appDrawer=false;
        if(!settingsPopupMode)settingsPopup.hide(false);
        menuGen++;
        if (!fromMenuRow) {  // a menu of its own, not one level down
            menuParents.clear();
            menuOpener = null;
        }
        menuAnchor = anchor;
        menuTile = null;
        menuClosed = null;
        clearThemePreview();
        focusedSettingsRow = null;
        lastSettingsSection = null;
        settingsSections.clear();
        themeChoices.clear();
        appearanceWallpaperRow = null;
        continueAppRows.clear();
        menuItems.clear();
        menuActions.clear();
        menuKeepsOpen.clear();
        menuMode = MENU_PLAIN;
        menuScroll.animate().cancel();
        menu.removeAllViews();
        menuScroll.setBackground(settingsPopupMode?new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT):rounded(theme.menuBg, theme.menuStroke, 20));
        menuScroll.scrollTo(0, 0);
        menu.setPadding(settingsPopupMode?0:px(8),settingsPopupMode?0:px(8),settingsPopupMode?0:px(8),settingsPopupMode?0:px(8));
        if (heading != null && !settingsPopupMode) addMenuHeading(heading, theme.text2);
    }

    private void addMenuHeading(String title, int color) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(13);
        heading.setTextColor(color);
        heading.setTypeface(getResources().getFont(R.font.outfit));
        heading.setFontVariationSettings("'wght' 500");
        heading.setPadding(px(14), px(8), px(14), px(6));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        menu.addView(heading);
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
            SettingsPopup.prepareRow(row,false);
        }
        menu.addView(row);
        menuItems.add(row);
        if (lastSettingsSection != null) settingsSections.put(row, lastSettingsSection);
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
        drawerHint.setVisibility(View.GONE);
        if(settingsPopupMode){
            int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
            int panelWidth = settingsPopup.panelWidth(width, splitSettings ? SettingsPopup.SPLIT_WIDTH : widthDp, appDrawer);
            int sidebarWidth = splitSettings ? px(SettingsPopup.SIDEBAR_WIDTH) : 0;
            menu.measure(View.MeasureSpec.makeMeasureSpec(panelWidth - px(48) - sidebarWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.UNSPECIFIED);
            RectF content = settingsPopup.content(width, height, panelWidth, menu.getMeasuredHeight(), appDrawer, splitSettings);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)menuScroll.getLayoutParams();
            lp.width=Math.round(content.width());lp.height=Math.round(content.height());lp.leftMargin=Math.round(content.left);lp.topMargin=Math.round(content.top);
            menuScroll.setLayoutParams(lp);menuScroll.setBackgroundColor(Color.TRANSPARENT);
            showSettingsHeading();
            menuScroll.setVisibility(View.VISIBLE);
            boolean animate = !appDrawer && !fromMenuRow;
            menuScroll.setAlpha(animate ? 0f : 1f);
            menuScroll.setTranslationY(animate ? px(5) : 0f);
            if (animate) menuScroll.animate().alpha(1f).translationY(0).setDuration(130).start();
            zone=Z_MENU;
            if(!menuItems.isEmpty())focusMenu(0);return;
        }
        placeAnchoredMenu(widthDp);
        menuScroll.setVisibility(View.VISIBLE);
        menuScroll.setAlpha(0f);
        menuScroll.setTranslationY(-px(6));
        menuScroll.animate().alpha(1f).translationY(0).setDuration(150).start();
        zone = Z_MENU;
        focusMenu(0);
    }

    private void placeAnchoredMenu(int widthDp) {
        RectF anchor = new RectF(rectOf(menuTile != null ? menuTile : icons[menuAnchor]));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) menuScroll.getLayoutParams();
        params.width = px(widthDp);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        menu.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = menu.getMeasuredHeight();
        int left = Math.round(menuTile == null ? anchor.right - params.width
                : dockMenu ? anchor.centerX() - params.width / 2f : anchor.left);
        params.leftMargin = Math.max(px(24), Math.min(left, screenWidth - params.width - px(24)));
        if (menuTile == null || (!dockMenu && anchor.bottom + px(10) + height <= screenHeight - px(16))) {
            params.topMargin = Math.round(anchor.bottom) + px(10);
        } else {
            params.topMargin = Math.max(px(16), Math.round(anchor.top) - px(10) - height);
        }
        int room = Math.max(0, (dockMenu ? Math.round(anchor.top) - px(10) : screenHeight - px(24)) - params.topMargin);
        params.height = height > room ? room : ViewGroup.LayoutParams.WRAP_CONTENT;
        menuScroll.setLayoutParams(params);
    }

    private void showSettingsHeading() {
        if (!settingsPopupMode) return;
        settingsPopup.show(settingsHeading, appDrawer, splitSettings ? SETTINGS_CATEGORIES : null,
                settingsCategory, settingsSidebar, settingsSidebarAction, tvSettingsLabel());
    }

    private void focusMenu(int i) {
        if (menuItems.isEmpty()) return;
        i = Math.max(0, Math.min(i, menuItems.size() - 1));
        menuIdx = i;
        final int index = i;
        View row = menuItems.get(i);
        View previous = focusedSettingsRow;
        if (settingsPopupMode && !appDrawer) {
            if (focusedSettingsRow != null && focusedSettingsRow != row) SettingsPopup.focus(focusedSettingsRow, false);
            SettingsPopup.focus(row, !settingsSidebar);
            focusedSettingsRow = row;
        } else for (int r = 0; r < menuItems.size(); r++) {
            View item = menuItems.get(r);
            boolean f = r == i;
            if (appDrawer) {
                ImageView icon = item.findViewById(R.id.drawer_app_icon);
                icon.setScaleX(f ? 1.14f : 1f);
                icon.setScaleY(f ? 1.14f : 1f);
                TextView label = item.findViewById(R.id.input_label);
                label.setTextColor(f ? 0xFFB7CCFF : SettingsPopup.TEXT);
                label.setPaintFlags(f ? label.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG
                        : label.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
                item.setSelected(f);
                continue;
            }
            if (dockMenu) {
                item.setBackground(f ? rounded(0xFFF2F2F4, 0, 11) : null);
                ((TextView) item.findViewById(R.id.input_label)).setTextColor(f ? 0xFF151516 : SettingsPopup.TEXT);
                ((TextView) item.findViewById(R.id.input_status)).setTextColor(f ? 0xFF525258 : 0xFFADADB4);
                continue;
            }
            item.setBackground(f ? rounded(theme.menuFocusBg, 0, 12) : null);
            ((TextView) item.findViewById(R.id.input_label)).setTextColor(f ? theme.menuFocusText : theme.text);
            ((TextView) item.findViewById(R.id.input_status)).setTextColor(f ? (theme.menuFocusText & 0x00FFFFFF) | 0x99000000 : theme.text2);
        }
        HomeTheme choice = settingsSidebar ? null : themeChoices.get(row);
        if (choice != null && (choice != activeTheme || rotatingWallpaper && hasWallpaper)) {
            TimeOfDay.Palette palette = themePalette(choice);
            if (choice.usesGlow()) {
                Theme appTheme = palette == null ? Theme.of(false) : Theme.fromPalette(palette, false);
                int glow = apps.isEmpty() ? 0 : apps.get(Math.min(dockIdx, apps.size() - 1)).ambientColour(pm, glowStep);
                float glowBlend = choice == HomeTheme.BLACK_GLOW ? .18f : .34f;
                int middle = glow == 0 ? appTheme.bgTop : TimeOfDay.blend(appTheme.bgTop, glow, glowBlend);
                backdrop.setThemePreview(appTheme.bgTop, middle, appTheme.bgBottom);
            } else backdrop.setThemePreview(palette.top, palette.mid, palette.bottom);
            settingsPopup.setThemePreview(true);
        } else clearThemePreview();
        int generation = menuGen;
        menuScroll.post(() -> {
            if (zone != Z_MENU || menuGen != generation || menuIdx != index || row.getParent() == null) return;
            Rect bounds = new Rect();
            row.getDrawingRect(bounds);
            menu.offsetDescendantRectToMyCoords(row, bounds);
            int top = bounds.top, bottom = bounds.bottom;
            int y = menuScroll.getScrollY(), height = menuScroll.getHeight();
            View section = settingsSections.get(row);
            boolean newSection = section != null && section != settingsSections.get(previous);
            if (newSection) {
                section.getDrawingRect(bounds);
                menu.offsetDescendantRectToMyCoords(section, bounds);
            }
            if (newSection && ("Appearance".equals(settingsHeading) || top < y || bottom > y + height)
                    && bottom - bounds.top <= height) {
                menuScroll.scrollTo(0, bounds.top);
            } else if (top < y || bottom > y + height) {
                menuScroll.scrollTo(0, top);
            }
        });
    }

    private void clearThemePreview() {
        backdrop.clearThemePreview();
        settingsPopup.setThemePreview(false);
    }

    private void closeMenu() {
        clearThemePreview();
        if(settingsPopupMode)settingsPopup.hide(true);
        settingsGrid = splitSettings = settingsSidebar = dockMenu = false;
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
     * HDMI and AV ports, then Live TV. With Sweep TV settings → Empty inputs set to "Under More inputs", ports with
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

    private Devices identify(TvInputInfo device) {
        int[] cec = Devices.cec(device);
        return Devices.identify(label(device), cec[0], cec[1]);
    }

    // ---------------------------------------------------------------- keys and location

    private void openWeatherLocation() {
        String area = Keys.read(this, Keys.LOCATION);
        askText("Weather location", "Enter a city such as Delhi, India, or latitude,longitude. "
                + (weather.automatic() ? "Remove the saved city to use automatic location." : ""),
                area, false, area != null, this::saveLocation);
    }

    private String recommendationEngineLabel() {
        return switch (Keys.catalogEngine(this)) {
            case "tmdb" -> Keys.activeTmdbKey(this) == null ? "TMDB · Key needed" : "TMDB";
            case "streamradar" -> "StreamRadar";
            default -> "Sweep TV";
        };
    }

    private void openRecommendationEngine() {
        beginSettingsMenu(settingsAt, "Recommendation Engine");
        String selected = Keys.catalogEngine(this);
        View sweep = addRow("Sweep TV", null, () -> selectRecommendationEngine("sweep"), true);
        setSelectionTick(sweep, "sweep".equals(selected));
        if (BuildConfig.CATALOG_COMPARISON) {
            View streamRadar = addRow("StreamRadar", null, () -> selectRecommendationEngine("streamradar"), true);
            View tmdbEngine = addRow("TMDB", null, () -> selectRecommendationEngine("tmdb"), true);
            setSelectionTick(streamRadar, "streamradar".equals(selected));
            setSelectionTick(tmdbEngine, "tmdb".equals(selected));
            if ("tmdb".equals(selected)) {
                String tmdb = Keys.read(this, Keys.TMDB);
                View key = addRow("TMDB key", tmdb == null ? "Not set" : "Saved", () -> askText("TMDB key",
                        "Enter an API key or API Read Access Token from TMDB Settings → API.",
                        null, true, tmdb != null, this::saveTmdbKey), true);
                View phone = addRow("Send TMDB key from phone", "Scan a QR code", this::openPhonePage, true);
                for (View child : new View[]{key, phone}) {
                    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) child.getLayoutParams();
                    layout.setMarginStart(px(22));
                    layout.height = px(44);
                    child.setLayoutParams(layout);
                    ((TextView) child.findViewById(R.id.input_label)).setTextSize(14.5f);
                }
            }
        }
        showMenu(530);
    }

    private void selectRecommendationEngine(String engine) {
        if (!BuildConfig.CATALOG_COMPARISON || engine.equals(Keys.catalogEngine(this))) return;
        Keys.setCatalogEngine(this, engine);
        refreshCatalogSource();
        refreshSettingsPage("Recommendation Engine", this::openRecommendationEngine);
    }

    private void refreshSettingsPage(String heading, Runnable opener) {
        if (zone != Z_MENU || !heading.equals(settingsHeading)) return;
        int focus = menuIdx;
        rebuildSettings(opener);
        focusMenu(focus);
    }

    private void refreshCatalogSource() {
        trending.readCached(this::onPicks);
        refreshOnline();
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
    private void saveTmdbKey(String text) {
        if (text.isEmpty()) {
            if (!Keys.write(this, Keys.TMDB, null)) {
                Toast.makeText(this, "Couldn't remove the TMDB key", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "TMDB key removed", Toast.LENGTH_SHORT).show();
            refreshSettingsPage("Recommendation Engine", this::openRecommendationEngine);
            refreshCatalogSource();
            return;
        }
        Toast.makeText(this, "Checking the key with TMDB…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result = acceptTmdbKey(text);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                refreshSettingsPage("Recommendation Engine", this::openRecommendationEngine);
                refreshCatalogSource();
            });
        }, "KeyCheck").start();
    }

    private void saveLocation(String text) {
        if (!Keys.write(this, Keys.LOCATION, text)) {
            Toast.makeText(this, "Couldn't save the location", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, text.isEmpty() ? (weather.automatic()?"Using automatic city":"Weather location removed") : "Weather location saved",
                Toast.LENGTH_SHORT).show();
        refreshSettingsPage("System", this::openSystemSettings);
        updateInfo();
        refreshHeaderWidgets();
    }

    /**
     * A QR code for a one-time settings page on the home network. Closing the dialog stops the page.
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
                    runOnUiThread(() -> status.setText("Checking with TMDB…"));
                }

                @Override public List<String> accept(Map<String, String> fields) {
                    String message = acceptTmdbKey(fields.get("tmdb"));
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (dialog[0] != null) dialog[0].dismiss();
                        Toast.makeText(HomeActivity.this, message, Toast.LENGTH_LONG).show();
                        refreshSettingsPage("Recommendation Engine", HomeActivity.this::openRecommendationEngine);
                        refreshCatalogSource();
                    });
                    return Collections.singletonList(message);
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
                .setMessage("Scan with your phone on the same Wi-Fi as the TV to enter your TMDB key.")
                .setView(box)
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> page.close())
                .create();
        dialog[0].show();
    }

    private PhoneQrDialog dashboardDialog;

    private void openHomeDashboard() {
        java.net.InetAddress lan = PhonePage.lanAddress(this);
        if (lan == null) {
            Toast.makeText(this, "Connect the TV to your home network first", Toast.LENGTH_LONG).show();
            return;
        }
        if (dashboardDialog != null) dashboardDialog.dismiss();
        HomeDashboard dashboard = HomeDashboard.read(prefs);
        PhoneQrDialog[] session = new PhoneQrDialog[1];
        PhonePage page;
        try {
            page = new PhonePage(lan, dashboard, new PhonePage.Listener() {
                @Override public void checking() { }
                @Override public List<String> accept(Map<String, String> fields) { return null; }

                @Override public List<String> acceptWallpaper(byte[] jpeg) {
                    File dir = getExternalFilesDir(null);
                    File prepared = preparePicture(() -> new java.io.ByteArrayInputStream(jpeg), dir, 1920, 1080);
                    if (prepared == null) return Collections.singletonList("Couldn't use that picture.");
                    java.util.concurrent.FutureTask<List<String>> apply = new java.util.concurrent.FutureTask<>(() -> {
                        if (!isDashboardOpen(session[0])) return null;
                        boolean saved = commitPicture(prepared, new File(dir, "wallpaper.jpg"));
                        pictureSaved(null, saved);
                        return Collections.singletonList(saved ? "Wallpaper saved to your TV." : "Couldn't save that picture.");
                    });
                    try {
                        return finishDashboardSave(apply);
                    } finally {
                        prepared.delete();
                    }
                }

                @Override public List<String> acceptDashboard(Map<HomeDashboard.Option, Boolean> settings) {
                    java.util.concurrent.FutureTask<List<String>> apply = new java.util.concurrent.FutureTask<>(() -> {
                        if (!isDashboardOpen(session[0])) return null;
                        Set<HomeDashboard.Option> changed = dashboard.save(prefs, settings);
                        if (changed.isEmpty()) return Collections.singletonList("No settings changed.");
                        root.post(() -> {
                            if (!isDestroyed()) applyHomeDashboard(changed);
                        });
                        return Collections.singletonList("Home settings saved to your TV.");
                    });
                    return finishDashboardSave(apply);
                }

                @Override public void closed(boolean expired) {
                    runOnUiThread(() -> {
                        if (dashboardDialog != session[0] || session[0] == null) return;
                        if (expired) session[0].expire();
                        else session[0].dismiss();
                    });
                }
            });
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Could not open the dashboard", Toast.LENGTH_SHORT).show();
            return;
        }
        session[0] = new PhoneQrDialog(this, qrCode(page.url), this::openHomeDashboard);
        session[0].setOnDismissListener(dialog -> {
            if (dashboardDialog == dialog) dashboardDialog = null;
            page.close();
        });
        dashboardDialog = session[0];
        dashboardDialog.show();
    }

    private boolean isDashboardOpen(PhoneQrDialog session) {
        return session != null && dashboardDialog == session && session.isShowing() && inFront && !isDestroyed();
    }

    private List<String> finishDashboardSave(java.util.concurrent.FutureTask<List<String>> apply) {
        runOnUiThread(apply);
        try {
            return apply.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            apply.cancel(false);
            return null;
        } catch (InterruptedException e) {
            apply.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void applyHomeDashboard(Set<HomeDashboard.Option> changed) {
        boolean sectionsChanged = false;
        boolean refresh = false;
        for (HomeDashboard.Option option : changed) {
            if (option.section == null) continue;
            sectionsChanged = true;
            refresh |= prefs.getBoolean(option.key, option.fallback);
        }
        if (changed.contains(HomeDashboard.Option.DISCOVERY)) {
            forYou = prefs.getBoolean("for_you", true);
            prefs.edit().remove("show_trending_tab").remove("show_recently_released_tab").apply();
            applyForYou();
            buildPicksRow(picksRow.anchor());
            refresh |= forYou;
        }
        if (changed.contains(HomeDashboard.Option.TIME_BACKGROUND)) {
            refreshHomeTheme();
            loadWallpaper();
        } else if (changed.contains(HomeDashboard.Option.GREETING)) {
            updateInfo();
        }
        if (changed.contains(HomeDashboard.Option.RAM) || changed.contains(HomeDashboard.Option.STORAGE)
                || changed.contains(HomeDashboard.Option.UPTIME)) refreshMeters();
        if (changed.contains(HomeDashboard.Option.BITCOIN) || changed.contains(HomeDashboard.Option.NVIDIA)
                || changed.contains(HomeDashboard.Option.GOLD)) {
            marketIndex=0;
            marketCard.stopSlide();
            showMarket();
            scheduleMarketRotation();
            refresh=true;
        }
        if (sectionsChanged) {
            chanSignature = null;
            showChannels(appChannels);
            refreshChannels();
        }
        if (refresh) refreshOnline();
        if (zone == Z_MENU && menuOpener != null && settingsPopupMode) {
            int position = menuIdx;
            rebuildSettings(menuOpener);
            focusMenu(position);
        }
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



    /** Checks a key from the phone page with its service and saves it unless turned down; the line the phone shows. */
    private String acceptTmdbKey(String text) {
        if (!BuildConfig.CATALOG_COMPARISON) return "The TV uses the Sweep TV catalog.";
        String key = text.replaceAll("\\s", "");
        int result = Trending.check(key);
        if (result == Keys.REJECTED) return "TMDB didn't accept that key.";
        if (!Keys.write(this, Keys.TMDB, key)) return "Couldn't save the TMDB key.";
        return result == Keys.OK ? "TMDB key saved." : "TMDB key saved. Sweep TV couldn't verify it.";
    }

    // ---------------------------------------------------------------- remote keys

    private void openRemoteKeys() {
        beginSettingsMenu(settingsAt, "Remote buttons");
        addContentSwitch("Double-press Home for app drawer", () -> prefs.getBoolean(RemoteKeys.DOUBLE_HOME_DRAWER, true),
                () -> {
                    prefs.edit().putBoolean(RemoteKeys.DOUBLE_HOME_DRAWER,
                            !prefs.getBoolean(RemoteKeys.DOUBLE_HOME_DRAWER, true)).apply();
                    updateDrawerHint();
                });
        addRow("Set up a shortcut button", SleepService.isRunning() ? "›" : "Home helper needed", this::learnKey, true);
        Map<Integer, String> shortcuts = RemoteKeys.all(this);
        shortcuts.keySet().removeIf(code -> !RemoteKeys.learnable(code));
        if (!shortcuts.isEmpty()) {
            addContentSwitch("Use saved shortcuts", () -> SleepService.watchButtons(this), () -> {
                prefs.edit().putBoolean("remote_keys_on", !SleepService.watchButtons(this)).apply();
                SleepService.applyButtonWatching();
            });
            settingsSection("Saved shortcuts");
        }
        for (Map.Entry<Integer, String> shortcut : shortcuts.entrySet()) {
            int code = shortcut.getKey();
            String hold = RemoteKeys.holdAction(this, code);
            addRow(RemoteKeys.keyName(code), RemoteKeys.describe(this, shortcut.getValue())
                    + (hold == null ? "" : " · Hold " + RemoteKeys.describe(this, hold)), () -> editRemoteButton(code, ""), true);
        }
        addPhoneSettingsShortcut();
        showMenu(640);
    }

    private void learnKey() {
        if (!SleepService.isRunning()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Turn on the Home button helper")
                    .setMessage("In Accessibility, turn on Sweep TV so it can detect shortcut buttons. The helper also lets the Home button open Sweep TV.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open TV settings", (d, w) -> openHomeHelperSettings())
                    .show();
            return;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Press a shortcut button")
                .setMessage("Press an app or coloured button on your remote. If Sweep TV detects it, you can choose an action. Some TVs keep certain buttons to themselves.")
                .setNegativeButton("Cancel", null)
                .create();
        Runnable timeout = () -> {
            if (!dialog.isShowing()) return;
            dialog.dismiss();
            Toast.makeText(this, "No shortcut detected. Try a different button.", Toast.LENGTH_LONG).show();
        };
        dialog.setOnDismissListener(d -> {
            root.removeCallbacks(timeout);
            SleepService.learn(null);
        });
        SleepService.KeyListener identified = (code, device) -> {
            if (!dialog.isShowing()) return;
            dialog.dismiss();
            editRemoteButton(code, device);
        };
        dialog.setOnKeyListener((window, keyCode, event) -> {
            int code = RemoteKeys.id(event);
            if (!RemoteKeys.learnable(code)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) identified.onKey(code, "");
            return true;
        });
        dialog.show();
        root.postDelayed(timeout, 15_000);
        SleepService.learn(identified);
    }

    private void editRemoteButton(int code, String device) {
        if (!RemoteKeys.learnable(code)) return;
        String press = RemoteKeys.action(this, code);
        if (press == null) {
            chooseRemoteAction(code, false);
            return;
        }
        String hold = RemoteKeys.holdAction(this, code);
        List<String> labels = new ArrayList<>();
        labels.add("Press · " + RemoteKeys.describe(this, press));
        if (press != null) labels.add("Hold · " + (hold == null ? "Same as press" : RemoteKeys.describe(this, hold)));
        android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(RemoteKeys.keyName(code) + (device.isEmpty() ? "" : " · " + device))
                .setItems(labels.toArray(new String[0]), (d, which) -> chooseRemoteAction(code, which == 1))
                .setNegativeButton("Cancel", null);
        dialog.show();
    }

    private void chooseRemoteAction(int code, boolean hold) {
        List<String> actions = new ArrayList<>(Arrays.asList(null, RemoteKeys.INPUTS, RemoteKeys.RECENTS,
                RemoteKeys.HOME, RemoteKeys.APP_DRAWER, RemoteKeys.NONE));
        for (AppEntry app : apps) actions.add(RemoteKeys.APP + app.pkg);
        List<String> labels = new ArrayList<>();
        for (String action : actions) {
            labels.add(action == null ? hold ? "Same as press" : "TV default" : RemoteKeys.describe(this, action));
        }
        String current = hold ? RemoteKeys.holdAction(this, code) : RemoteKeys.action(this, code);
        final int[] selection = {Math.max(0, actions.indexOf(current))};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle((hold ? "Hold " : "Press ") + RemoteKeys.keyName(code))
                .setSingleChoiceItems(labels.toArray(new String[0]), selection[0], (d, i) -> selection[0] = i)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String action = actions.get(selection[0]);
                    if (hold) RemoteKeys.setHold(this, code, action);
                    else RemoteKeys.set(this, code, action);
                    if (action != null) prefs.edit().putBoolean("remote_keys_on", true).apply();
                    SleepService.applyButtonWatching();
                    int index = menuIdx;
                    rebuildSettings(this::openRemoteKeys);
                    focusMenu(index);
                }).show();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_WATCH_NEXT) {
            if (!watchNextPermissionMissing()) {
                refreshWatchNext();
                refreshWatchNextPermissionRow();
            } else if (shouldShowRequestPermissionRationale(WATCH_NEXT_PERMISSION)) {
                Toast.makeText(this, "TV listings access is off", Toast.LENGTH_SHORT).show();
            } else {
                showWatchNextPermissionHelp();
            }
        }
        if (req == REQ_PICTURES && checkSelfPermission(picturesPermission()) == PackageManager.PERMISSION_GRANTED) openTvPictures();
    }

    // ---------------------------------------------------------------- finish setup

    private static final int REQ_WATCH_NEXT = 8;
    private static final String WATCH_NEXT_PERMISSION = "android.permission.READ_TV_LISTINGS";
    private boolean watchNextPermissionRow;

    private boolean watchNextPermissionMissing() {
        return checkSelfPermission(WATCH_NEXT_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    private void addWatchNextPermissionRow() {
        if (!watchNextPermissionMissing()) return;
        watchNextPermissionRow = true;
        addRow("Read TV listings", "For Continue Watching", () -> {
            try {
                requestPermissions(new String[]{WATCH_NEXT_PERMISSION}, REQ_WATCH_NEXT);
            } catch (IllegalArgumentException | SecurityException unavailable) {
                showWatchNextPermissionHelp();
            }
        }, true);
    }

    private void refreshWatchNextPermissionRow() {
        if (zone != Z_MENU || watchNextPermissionRow == watchNextPermissionMissing()) return;
        refreshSettingsPage("Continue Watching", this::openContinueSettings);
        refreshSettingsPage("Finish setup", this::openFinishSetup);
    }

    private void showWatchNextPermissionHelp() {
        new android.app.AlertDialog.Builder(this).setTitle("TV listings access is off")
                .setMessage("Android didn’t grant access to titles and progress for Continue Watching. "
                        + "Open Sweep TV’s app permissions to allow TV listings if your TV offers it.")
                .setNegativeButton("Close", null)
                .setPositiveButton("App permissions", (dialog, which) -> {
                    Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    if (!tryOpenTvSettings(appInfo)) {
                        Toast.makeText(this, "This TV could not open Sweep TV’s app permissions", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private boolean notificationAccess() {
        String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(getPackageName() + "/");
    }

    private void openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, WatchService.class).flattenToString());
            if (startNotificationSettings(detail)) return;
        }
        if (startNotificationSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
        if (startNotificationSettings(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))) {
            Toast.makeText(this, "Open Special app access → Notification access", Toast.LENGTH_LONG).show();
        } else if (startNotificationSettings(new Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(this, "Open Apps → Special app access → Notification access", Toast.LENGTH_LONG).show();
        } else {
            new android.app.AlertDialog.Builder(this).setTitle("Notification access")
                    .setMessage("This TV could not open Android Settings. Check whether your TV offers Notification access under Apps → Special app access.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private boolean startNotificationSettings(Intent intent) {
        if (intent.resolveActivity(pm) == null) return false;
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    /** Sweep TV settings → System → Finish setup. */
    private void openFinishSetup() {
        beginSettingsMenu(settingsAt, "Finish setup");
        if (needsHomeSetup()) {
            addRow("Home app", currentHomeName(), this::openHomeSetup, false);
            addRow("Home button helper", null, this::openHomeHelperSettings, false);
        }
        // Android 13 and later grey out these switches for an app installed from an APK, until allowed in App info
        // (or with one adb command where the TV's App info doesn't offer it: README → Questions and fixes).
        if (Build.VERSION.SDK_INT >= 33 && (needsHomeSetup() || !notificationAccess()))
            addRow("Switch greyed out?", "App info → Allow restricted settings", () -> {
                Toast.makeText(this, "Look for Allow restricted settings here. Not there? The README's Questions and fixes "
                        + "has the one command that allows it", Toast.LENGTH_LONG).show();
                start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }, false);
        if (!notificationAccess()) addRow("Notification access", "Continue Watching", this::openNotificationAccess, false);
        addWatchNextPermissionRow();
        settingsSection("Sports to follow");
        addContentSwitch("Formula 1", () -> sportEnabled(FormulaOne.KEY), () -> toggleSport(FormulaOne.KEY));
        addContentSwitch("Cricket", () -> sportEnabled("cricket"), () -> toggleSport("cricket"));
        showMenu(420);
    }

    // ---------------------------------------------------------------- network debugging

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
        View currencyCard = focusedMarketCurrencyCard();
        if (currencyCard != null) {
            holdFired = true;
            MarketQuotes.Asset asset = marketCurrencyCards.get(currencyCard);
            marketQuotes.flipCurrency(asset);
            setStatus(currencyCard, marketQuotes.displayUnit(asset).name());
            SettingsPopup.focus(currencyCard, true);
            updateMarketSelection(asset);
            return;
        }
        if (zone == Z_MENU) { startMove(); return; }  // the Channels editor: pick the row up
        holdFired = true;
        if (zone == Z_DOCK && appIndex() >= 0 && appIndex() < dockCount) openDockMenu(apps.get(appIndex()).pkg);
        else if (zone == Z_GRID) openAppMenu();
        else if (zone == Z_TOP && iconIdx == P_SETTINGS) {
            openTvSettings();
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
        boolean inDock = ai < dockCount;
        if (!inDock) addRow("Add to dock", null, () -> addToDock(a.pkg), false);
        else addRow("Remove from dock", null, () -> removeFromDock(a.pkg, true), false);
        // A sorted All apps would put a moved app straight back, so there only the dock's apps move.
        if (inDock || appsOrder() == ORDER_YOURS) addRow("Move", null, this::startMove, false);
        File own = AppEntry.customImage(a.pkg);
        addRow("Change image", own != null && own.isFile() ? "your picture" : null, () -> openTileImage(a.pkg), false);
        addRow("Hide", null, () -> hideApp(a.pkg), false);
        if (canUninstall(a.pkg)) {
            addRow("Uninstall", null, () -> start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))), false);
        }
        addRow("App info", null, () -> openAppInfo(a.pkg), false);
        showMenu(240);
    }

    private void openDockMenu(String pkg) {
        beginMenu(P_CONTROL, "Dock Settings");
        dockMenu = true;
        dockMenuPackage = pkg;
        menuTile = tileOf(pkg);
        menuClosed = () -> {
            int index = indexOf(pkg);
            focusDock(index >= 0 && index < dockCount ? index : dockIdx);
        };
        menuScroll.setBackground(rounded(0xFF1C1C1E, 0xFF3D3E45, 15));
        ((TextView) menu.getChildAt(0)).setTextColor(0xFFA6A6AA);
        addDockOptions();
        View divider = new View(this);
        divider.setBackgroundColor(0xFF444449);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, px(1));
        line.setMargins(px(12), px(4), px(12), px(4));
        menu.addView(divider, line);
        addMenuHeading(labelOf(pkg) + " settings", 0xFFA6A6AA);
        dockRemoveRow = addRow("Remove from dock", null, () -> {
            int index = indexOf(pkg);
            if (index < 0 || index >= dockCount) return;
            closeMenu();
            removeFromDock(pkg, true);
        }, true);
        addRow("App info", null, () -> openAppInfo(pkg), false);
        for (View row : menuItems) row.getLayoutParams().height = px(35);
        showMenu(DOCK_MENU_WIDTH);
    }

    private void addDockOptions() {
        addDockCycle("Dock size", () -> dockSize() + " apps", step -> {
            int capacity = DOCK_SIZES[cycleIndex(dockSize() - DOCK_SIZES[0], DOCK_SIZES.length, step)];
            prefs.edit().putInt("dock_size", capacity).apply();
            reloadApps();
        });
        addDockCycle("Icon shape", () -> DOCK_SHAPES[dockIconShape()], step -> {
            prefs.edit().putInt(DOCK_ICON_SHAPE, cycleIndex(dockIconShape(), DOCK_SHAPES.length, step)).apply();
            buildAppViews();
            styleTop();
        });
        addDockCycle(dockMenu ? "Show background" : "Background", () -> DOCK_BACKGROUNDS[dockSurface()], step -> {
            prefs.edit().putInt("dock_background", cycleIndex(dockSurface(), DOCK_BACKGROUNDS.length, step)).apply();
            styleDockSurface();
        });
        addDockCycle("Theme", () -> prefs.getBoolean(DOCK_GRAYSCALE, false) ? "Grayscale" : "Full colour", step -> {
            prefs.edit().putBoolean(DOCK_GRAYSCALE, !prefs.getBoolean(DOCK_GRAYSCALE, false)).apply();
            for (View tile : dockTiles) styleDockTile(tile);
        });
    }

    private int dockIconShape() {
        int shape = prefs.getInt(DOCK_ICON_SHAPE, 0);
        return shape >= 0 && shape < DOCK_SHAPES.length ? shape : 0;
    }

    private static int cycleIndex(int index, int count, int step) {
        return index >= 0 && index < count ? Math.floorMod(index + step, count) : step > 0 ? 0 : count - 1;
    }

    private void addDockCycle(String label, java.util.function.Supplier<String> status,
                              java.util.function.IntConsumer change) {
        View row = addRow(label, status.get(), () -> {}, true);
        java.util.function.IntConsumer cycle = step -> {
            change.accept(step);
            setStatus(row, status.get());
            if (!dockMenu) return;
            int index = indexOf(dockMenuPackage);
            View tile = index >= 0 && index < dockCount ? dockTiles.get(index) : null;
            setStatus(dockRemoveRow, tile == null ? "Not in Dock" : null);
            if (tile != menuTile) {
                menuTile = tile;
                if (tile == null) {
                    setFocus(null, 1f, 0);
                    return;
                }
                int generation = menuGen;
                tile.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                          int oldL, int oldT, int oldR, int oldB) {
                        view.removeOnLayoutChangeListener(this);
                        if (zone == Z_MENU && dockMenu && menuGen == generation && menuTile == view) {
                            setFocus(view, 1f, dockTileRadius());
                        }
                    }
                });
            }
        };
        if (dockMenu) dockMenuCycles.put(row, cycle);
        menuActions.set(menuActions.size() - 1, () -> cycle.accept(1));
    }

    private void openAppInfo(String pkg) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    // ---------------------------------------------------------------- dock and hidden apps

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private void addToDock(String pkg) { addToDock(pkg, true); }

    private void addToDock(String pkg, boolean focus) {
        int index = indexOf(pkg);
        if (index < 0 || index < dockCount) return;
        if (dockCount >= dockLimit()) {
            Toast.makeText(this, "Dock is full. Remove an app to add another.", Toast.LENGTH_SHORT).show();
            return;
        }
        int capacity = Math.max(dockSize(), dockCount + 1);
        AppEntry app = apps.remove(index);
        apps.add(dockCount++, app);
        saveCustomDock(capacity);
        if (focus) root.post(() -> focusDock(dockCount - 1));
    }

    private void removeFromDock(String pkg, boolean focus) {
        int index = indexOf(pkg);
        if (index < 0 || index >= dockCount) return;
        AppEntry app = apps.remove(index);
        apps.add(--dockCount, app);
        saveCustomDock(Math.max(DOCK_SIZES[0], dockCount));
        if (focus) root.post(() -> focusDock(index));
    }

    private void saveCustomDock(int capacity) {
        saveOrder(prefs.edit().putBoolean("dock_custom", true).putInt("dock_size", capacity));
        buildAppViews();
    }

    /** Apps you hid (Hide in an app's hold-OK menu): left out of the dock and All apps, still installed. */
    private Set<String> hiddenApps() {
        return HomeOptions.hiddenApps(this);
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
        if (ai < dockCount) {
            if (prefs.getBoolean("dock_custom", false) || apps.size() < dockCount) dockCount--;
            else apps.add(ai, apps.remove(dockCount - 1));
        }
        saveOrder(prefs.edit());
        setHidden(pkg, true);
        reloadApps();
        if (SpotifyPicks.PACKAGE.equals(pkg)) showChannels(appChannels);
        Toast.makeText(this, app.label + " hidden. Sweep TV settings → Dock → Hidden apps brings it back", Toast.LENGTH_LONG).show();
    }

    /** Sweep TV settings → Hidden apps: OK on one shows it again (at the end of All apps). */
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

    /** System apps (and Sweep TV) can't be uninstalled from here; Android's own dialog asks before removing one. */
    private boolean canUninstall(String pkg) {
        if (pkg.equals(getPackageName())) return false;
        try {
            int f = pm.getApplicationInfo(pkg, 0).flags;
            return (f & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The focused Continue Watching card, or null. */
    private Trending.Pick continueCard() {
        if (zone == Z_PICKS && tabIdx == 0 && !rowPicks.isEmpty()) return rowPicks.get(Math.min(picksRow.anchor(), rowPicks.size() - 1));
        return null;
    }

    /** Remove from Continue, or open the app itself rather than the episode. */
    private void openContinueMenu() {
        Trending.Pick p = continueCard();
        if (p == null) return;
        int anchor = picksRow.anchor();
        beginMenu(P_CONTROL, p.title);
        menuTile = picksRow.anchorCard();
        menuClosed = () -> focusPicks(anchor);
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
        recentPicks = continuePicks(rememberSessionApps(RecentStore.load(this)), watchNextPicks);
        if (zone == Z_PICKS && tabIdx == 0) {
            int a = Math.max(0, Math.min(picksRow.anchor(), recentPicks.size() - 1));
            buildPicksRow(a);
            if (tabIdx == 0 && !rowPicks.isEmpty()) focusPicks(Math.min(a, rowPicks.size() - 1));
            else focusDock(dockIdx);
        } else if (tabIdx == 0) {
            buildPicksRow(0);
        }
        showChannels(appChannels);
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

    private void openQuickSettings(int anchor) {
        settingsAt = anchor;
        settingsPopupMode = false;
        beginMenu(anchor, "Settings");
        addRow("Sweep TV settings", "›", () -> openSweepSettings(anchor), false);
        View divider = new View(this);
        divider.setBackgroundColor(theme.menuStroke);
        LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(-1, px(1));
        separator.setMargins(px(12), px(4), px(12), px(4));
        menu.addView(divider, separator);

        if (hasTvSettings()) addRow(tvSettingsLabel(), null, this::openTvSettings, false);
        Intent screenSaver = screensaverIntent();
        if (screenSaver != null) addRow("Start screen saver", null, () -> start(screenSaver), false);
        if (Sleep.SUPPORTED) {
            addSettingsCycle("Sleep timer", this::sleepStatus, () -> {
                if (Sleep.available()) Sleep.cycle(this);
                else needsTurnOffSetup();
            });
            addRow("Turn off", null, () -> { if (!Sleep.now()) needsTurnOffSetup(); }, false);
        }
        addRow("Restart", null, () -> { if (!SleepService.powerMenu()) needsTurnOffSetup(); }, false);
        addRow("Free memory", null, this::confirmFreeMemory, true);
        speedTestRow = addRow("Speed test", speedTestStatus, this::runSpeedTest, true);
        addRow("Home app", currentHomeName(), this::openHomeSetup, false);
        for (View row : menuItems) row.getLayoutParams().height = px(36);
        showMenu(300);
        menuOpener = () -> openQuickSettings(anchor);
    }

    private boolean speedTestInProgress;
    private View speedTestRow;
    private String speedTestStatus;

    private void runSpeedTest() {
        if (speedTestInProgress) return;
        speedTestInProgress = true;
        speedTestStatus = "Testing…";
        setStatus(speedTestRow, speedTestStatus);
        new Thread(() -> {
            String result;
            try {
                result = String.format(java.util.Locale.getDefault(), "%.1f Mbps ↓", SpeedTest.downloadMbps());
            } catch (java.io.IOException failure) {
                result = "Test failed";
            }
            String status = result;
            runOnUiThread(() -> {
                speedTestInProgress = false;
                speedTestStatus = status;
                if (!isDestroyed() && speedTestRow.isAttachedToWindow()) setStatus(speedTestRow, status);
            });
        }, "download-speed-test").start();
    }

    private void openSweepSettings(int anchor) {
        settingsAt = anchor;
        beginSettingsMenu(anchor, "Sweep TV settings");
        openSettingsPage(settingsCategory);
    }

    private void openSettingsPage(int category) {
        settingsCategory = category;
        openingSettingsPage = true;
        try {
            rebuildSettings(settingsPages[category]);
        } finally {
            openingSettingsPage = false;
        }
        menuOpener = () -> {
            openSweepSettings(settingsAt);
            settingsSidebar = false;
            showSettingsHeading();
        };
    }

    private void focusSettingsSidebar() {
        settingsSidebar = true;
        settingsSidebarAction = -1;
        clearThemePreview();
        showSettingsHeading();
        focusMenu(menuIdx);
    }

    private void moveSettingsSidebar(boolean down) {
        int current = settingsSidebarAction < 0 ? settingsCategory : SETTINGS_CATEGORIES.length + settingsSidebarAction;
        int next = current + (down ? 1 : -1);
        if (next == SETTINGS_CATEGORIES.length) {
            settingsSidebarAction = SIDEBAR_TV_SETTINGS;
            showSettingsHeading();
        } else if (next == SETTINGS_CATEGORIES.length + 1) {
            settingsSidebarAction = SIDEBAR_SCAN_QR;
            showSettingsHeading();
        } else if (next >= 0 && next < SETTINGS_CATEGORIES.length) {
            if (next == settingsCategory) {
                settingsSidebarAction = -1;
                showSettingsHeading();
                return;
            }
            openSettingsPage(next);
        }
    }

    private void settingsSection(String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setTypeface(getResources().getFont(R.font.outfit));
        heading.setFontVariationSettings("'wght' 600");
        heading.setTextColor(SettingsPopup.TEXT);
        heading.setPadding(px(2), px(menu.getChildCount() == 0 ? 2 : 15), 0, px(8));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        menu.addView(heading);
        lastSettingsSection = heading;
    }

    private void addPhoneSettingsShortcut() {
        View link = addRow("Scan QR to customize", null, this::openHomeDashboard, true);
        LinearLayout.LayoutParams position = (LinearLayout.LayoutParams) link.getLayoutParams();
        position.width = px(196);
        position.height = px(40);
        position.gravity = Gravity.END;
        position.topMargin = px(8);
        link.setLayoutParams(position);
        TextView label = link.findViewById(R.id.input_label);
        label.setTextSize(13);
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings_qr, 0, 0, 0);
        label.setCompoundDrawablePadding(px(8));
    }

    private void openScreenSavers() {
        beginSettingsMenu(settingsAt, "Screen saver");
        ComponentName active = ScreenSavers.active(this);
        java.util.Map<ComponentName, String> available = ScreenSavers.available(this);
        String activeLabel = available.remove(active);
        if (activeLabel != null) addScreenSaverChoice(active, activeLabel, true);
        for (java.util.Map.Entry<ComponentName, String> entry : available.entrySet()) {
            addScreenSaverChoice(entry.getKey(), entry.getValue(), false);
        }
        boolean aerialActive = activeLabel != null && ScreenSavers.AERIAL.equals(active.getPackageName());
        if (!aerialActive && available.keySet().stream().noneMatch(
                component -> ScreenSavers.AERIAL.equals(component.getPackageName()))) {
            addRow("Get Aerial Views", "Google Play", this::openAerialStore, false);
        }
        Intent start = screensaverIntent();
        Intent idleSettings = nativeScreenSaverIntent();
        if (start != null || idleSettings != null) settingsSection("Actions");
        if (start != null) {
            View preview = addRow("Start screen saver now", null, () -> start(start), false);
            TextView label = preview.findViewById(R.id.input_label);
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings_play, 0, 0, 0);
            label.setCompoundDrawablePadding(px(9));
        }
        if (idleSettings != null) addRow("Idle time", null, () -> start(idleSettings), false);
        addPhoneSettingsShortcut();
        showMenu(824);
    }

    private void addScreenSaverChoice(ComponentName component, String label, boolean selected) {
        boolean aerial = ScreenSavers.AERIAL.equals(component.getPackageName());
        View row = addRow(label, null, () -> selectScreenSaver(component), true);
        setSelectionTick(row, selected);
        if (selected && aerial) groupAerialSettings(row);
    }

    private void groupAerialSettings(View choice) {
        View link = addRow("Aerial Views settings", null, this::openAerialSettings, false);
        TextView label = link.findViewById(R.id.input_label);
        android.text.SpannableString text = new android.text.SpannableString(label.getText());
        text.setSpan(new android.text.style.ForegroundColorSpan(0xFFC2D4FF), 0, text.length(), 0);
        text.setSpan(new android.text.style.UnderlineSpan(), 0, text.length(), 0);
        label.setText(text);
        label.setTextSize(13);

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(px(4), px(4), px(4), px(4));
        group.setBackground(rounded(0xFF222938, 0xFF4B5871, 14));
        menu.removeView(choice);
        menu.removeView(link);
        group.addView(choice, new LinearLayout.LayoutParams(-1, px(47)));
        LinearLayout.LayoutParams linkPosition = new LinearLayout.LayoutParams(-1, px(35));
        linkPosition.topMargin = px(3);
        group.addView(link, linkPosition);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.bottomMargin = px(8);
        menu.addView(group, position);
    }

    private void openAerialSettings() {
        beginSettingsMenu(settingsAt, "Aerial Views settings");
        settingsSection("Video quality");
        View automatic = addRow("Auto", "Detecting…", () -> importAerialQuality(AerialQuality.detect(this)), true);
        recommendationText("Confirm Import in Aerial Views to apply the quality to its built-in sources. Leave Clear existing settings unchecked.", 13, SettingsPopup.MUTED);
        addRow("4K SDR", "For 4K TVs", () -> importAerialQuality(AerialQuality.UHD_SDR), true);
        addRow("1080p SDR", "Uses less bandwidth", () -> importAerialQuality(AerialQuality.HD_HEVC), true);
        addRow("1080p H.264", "For older TVs", () -> importAerialQuality(AerialQuality.HD_H264), true);
        addRow("Open Aerial Views", "Playback and sources", () -> {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(ScreenSavers.AERIAL);
            if (launch != null) start(launch);
        }, false);
        showMenu(824);
        background.execute(() -> {
            AerialQuality detected = AerialQuality.detect(this);
            runOnUiThread(() -> {
                if (automatic.isAttachedToWindow()) setStatus(automatic, detected.label);
            });
        });
    }

    private void importAerialQuality(AerialQuality quality) {
        start(AerialPresetProvider.importIntent(this, quality));
    }

    private void openAerialStore() {
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ScreenSavers.AERIAL));
        try { startActivity(store); }
        catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Search for Aerial Views in Google Play", Toast.LENGTH_SHORT).show();
        }
    }

    private Intent nativeScreenSaverIntent() {
        // This shortcut can resolve to a screen that immediately closes on Xiaomi TVs.
        if ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || "xiaomi".equalsIgnoreCase(Build.BRAND)
                || "redmi".equalsIgnoreCase(Build.BRAND)) return null;
        Intent intent = new Intent(Settings.ACTION_DREAM_SETTINGS);
        ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null && resolved.activityInfo.exported
                && !resolved.activityInfo.name.contains("EmptyStub") ? intent : null;
    }

    private void selectScreenSaver(ComponentName component) {
        if (component.equals(ScreenSavers.active(this))) return;
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (ScreenSavers.select(this, component)) {
                    rebuildSettings(this::openScreenSavers);
                    return;
                }
            } catch (SecurityException ignored) { }
        }
        Intent settings = nativeScreenSaverIntent();
        if (settings != null) start(settings);
        else {
            Toast.makeText(this, "Choose the screen saver in your TV settings", Toast.LENGTH_SHORT).show();
            openTvSettings();
        }
    }

    private void addSettingsGridCard(View card, Runnable action, boolean keepOpen, int height) {
        addSettingsGridCard(menu, card, action, keepOpen, height, 8);
    }

    private void addSettingsGridCard(LinearLayout grid, View card, Runnable action, boolean keepOpen, int height, int gap) {
        View last = grid.getChildCount() == 0 ? null : grid.getChildAt(grid.getChildCount() - 1);
        LinearLayout row = last != null && last.getTag() == settingsCardRow ? (LinearLayout) last : null;
        if (row == null || row.getChildCount() == settingsColumns || row.getWeightSum() != settingsColumns
                || row.getLayoutParams().height != px(height)) {
            row = new LinearLayout(this);
            row.setTag(settingsCardRow);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(height));
            lp.bottomMargin = px(gap);
            row.setWeightSum(settingsColumns);
            grid.addView(row, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (row.getChildCount() > 0) lp.setMarginStart(px(gap));
        row.addView(card, lp);
        menuItems.add(card);
        if (lastSettingsSection != null) settingsSections.put(card, lastSettingsSection);
        menuActions.add(action);
        menuKeepsOpen.add(keepOpen);
    }

    private void addSettingsCycle(String label, java.util.function.Supplier<String> value, Runnable change) {
        final View[] row = new View[1];
        row[0] = addRow(label, value.get(), () -> {
            change.run();
            setStatus(row[0], value.get());
        }, true);
    }

    private View settingsCategory(String label,String description,int iconId){
        FrameLayout card=new FrameLayout(this);
        ImageView icon=new ImageView(this);icon.setTag("settings-icon");icon.setImageResource(iconId);icon.setPadding(px(2),px(2),px(2),px(2));
        FrameLayout.LayoutParams il=new FrameLayout.LayoutParams(px(22),px(22));il.leftMargin=px(13);il.topMargin=px(7);card.addView(icon,il);
        TextView title=new TextView(this);title.setId(R.id.input_label);title.setText(label);title.setTypeface(getResources().getFont(R.font.outfit));title.setFontVariationSettings("'wght' 600");title.setTextSize(15);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);tl.leftMargin=px(13);tl.rightMargin=px(12);tl.topMargin=px(29);card.addView(title,tl);
        TextView detail=new TextView(this);detail.setId(R.id.input_status);detail.setText(description);detail.setTypeface(getResources().getFont(R.font.outfit));detail.setFontVariationSettings("'wght' 400");detail.setTextSize(10.5f);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams dl=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);dl.leftMargin=px(13);dl.rightMargin=px(12);dl.topMargin=px(49);card.addView(detail,dl);
        SettingsPopup.prepareRow(card,true);return card;
    }

    private void openContentSettings() {
        beginSettingsMenu(settingsAt, "Content settings");
        addRow("Select Content Languages", contentLanguageSummary(), this::openContentLanguages, false);
        if (BuildConfig.CATALOG_COMPARISON) {
            String active = recommendationEngineLabel();
            String displayed = trending.displayedSource();
            addRow("Recommendation Engine", displayed.isEmpty() || active.equals(displayed) ? active : active + " · " + displayed,
                    this::openRecommendationEngine, false);
        }
        addPhoneSettingsShortcut();
        showMenu(690);
        if (!splitSettings) menuOpener = this::openContentSettings;
    }

    private void openHomePageWidgets() {
        beginSettingsMenu(settingsAt, "Home Page Widgets");
        addRow("Header widgets", "›", this::openHeaderWidgets, false);
        addRow("Continue Watching", ContinueOptions.showTab(this) ? "On  ›" : "Off  ›",
                this::openContinueSettings, false);
        addDiscoveryTabSetting("Show Trending", TAB_TRENDING);
        addDiscoveryTabSetting("Show Recently Released", 2);
        addRow("Sports", sportsSummary() + "  ›", this::openSportsSettings, false);
        addContentSwitch(Recommendations.TITLE, () -> sectionEnabled(Recommendations.KEY),
                () -> setSectionVisibility(Recommendations.KEY, !sectionEnabled(Recommendations.KEY)));
        addContentSwitch("New releases row", () -> sectionEnabled(Recommendations.NEW_KEY),
                () -> setSectionVisibility(Recommendations.NEW_KEY, !sectionEnabled(Recommendations.NEW_KEY)));
        if (rottenTomatoesPicks.configured()) {
            addContentSwitch(RottenTomatoesPicks.TITLE, () -> sectionEnabled(RottenTomatoesPicks.KEY),
                    () -> setSectionVisibility(RottenTomatoesPicks.KEY, !sectionEnabled(RottenTomatoesPicks.KEY)));
        }
        addRow("App sections", "Show, hide or reorder", this::openChannelsEditor, false);
        addPhoneSettingsShortcut();
        showMenu(690);
        if (!splitSettings) menuOpener = this::openHomePageWidgets;
    }

    private void addDiscoveryTabSetting(String label, int tab) {
        addContentSwitch(label, () -> discoveryTabEnabled(tab), () -> {
            prefs.edit().putBoolean(discoveryTabKey(tab), !discoveryTabEnabled(tab)).apply();
            applyForYou();
            buildPicksRow(0);
            if (discoveryTabEnabled(tab)) refreshOnline();
        });
    }

    private static String discoveryTabKey(int tab) {
        return tab == TAB_TRENDING ? "show_trending_tab" : "show_recently_released_tab";
    }

    private boolean discoveryTabEnabled(int tab) {
        return prefs.getBoolean(discoveryTabKey(tab), forYou);
    }

    private View addContentSwitch(String label, java.util.function.BooleanSupplier enabled, Runnable change) {
        View[] row = new View[1];
        row[0] = addRow(label, null, () -> {
            change.run();
            SettingsPopup.setSwitchOnly(row[0], enabled.getAsBoolean());
        }, true);
        SettingsPopup.setSwitchOnly(row[0], enabled.getAsBoolean());
        row[0].getLayoutParams().height = px(44);
        return row[0];
    }

    private void openSystemSettings() {
        beginSettingsMenu(settingsAt, "System");
        addRow("Weather location", weather.locationLabel(), this::openWeatherLocation, true);
        addRow("Home app", currentHomeName(), this::openHomeSetup, false);
        addSweepUpdateCheck();
        if (serviceStopped) addRow("Restore Home and power controls", "Accessibility", this::openHomeHelperSettings, false);
        addRow("Network shortcut", shortcutLabel("network"), this::openShortcuts, false);
        int hiddenApps = hiddenApps().size();
        if (hiddenApps > 0) addRow("Hidden apps", String.valueOf(hiddenApps), this::openHiddenApps, false);
        addContentSwitch("Show other apps in drawer", () -> prefs.getBoolean(SHOW_OTHER_DRAWER_APPS, true),
                () -> prefs.edit().putBoolean(SHOW_OTHER_DRAWER_APPS,
                        !prefs.getBoolean(SHOW_OTHER_DRAWER_APPS, true)).apply());
        settingsSection("Maintenance");
        addRow("Finish setup", "Optional", this::openFinishSetup, false);
        addRow("Network debugging", null, this::openNetworkDebugging, false);
        addRow("About", null, this::openAbout, false);
        addPhoneSettingsShortcut();
        showMenu(690);
    }

    private void openDockSettings() {
        beginSettingsMenu(settingsAt, "Dock");
        addDockOptions();
        addContentSwitch("Show All Apps button", () -> prefs.getBoolean(DOCK_ALL_APPS_BUTTON, false), () -> {
            prefs.edit().putBoolean(DOCK_ALL_APPS_BUTTON, !prefs.getBoolean(DOCK_ALL_APPS_BUTTON, false)).apply();
            buildAppViews();
            styleTop();
        });
        addRow("Edit Dock apps", dockCount + " in Dock", this::openDockApps, false);
        addPhoneSettingsShortcut();
        showMenu(690);
    }

    private void openDockApps() { openDockApps(0); }

    private void openDockApps(int page) {
        beginSettingsMenu(settingsAt, "Edit Dock apps");
        settingsGrid = true;
        settingsColumns = 5;
        TextView status = recommendationText(dockEditorStatus(), 13, SettingsPopup.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        List<AppEntry> eligible = new ArrayList<>();
        for (AppEntry app : apps) if (inDock(app.pkg) || isEntertainmentApp(app)) eligible.add(app);
        int pageSize = 20;
        int pages = Math.max(1, (eligible.size() + pageSize - 1) / pageSize);
        int current = Math.min(page, pages - 1);
        int end = Math.min(eligible.size(), (current + 1) * pageSize);
        for (AppEntry app : eligible.subList(current * pageSize, end)) {
            View card = appChoiceCard(app, app.label);
            setSelectionTick(card, inDock(app.pkg));
            addSettingsGridCard(card, () -> {
                if (inDock(app.pkg)) removeFromDock(app.pkg, false);
                else addToDock(app.pkg, false);
                setSelectionTick(card, inDock(app.pkg));
                status.setText(dockEditorStatus());
                styleTop();
            }, true, 53);
        }
        if (menuItems.isEmpty()) recommendationText("No entertainment apps to add", 15, SettingsPopup.MUTED);
        if (current > 0) addRow("Previous page", null,
                () -> rebuildSettings(() -> openDockApps(current - 1)), true).getLayoutParams().height = px(40);
        if (current + 1 < pages) addRow("Next page", (current + 1) + " of " + pages,
                () -> rebuildSettings(() -> openDockApps(current + 1)), true).getLayoutParams().height = px(40);
        showMenu(760);
    }

    private String dockEditorStatus() {
        String count = dockCount + " of " + dockLimit() + " apps";
        return dockCount >= dockLimit() ? count + " · Remove an app to add another" : count;
    }

    private View appChoiceCard(AppEntry app, String label) {
        View card = settingsCategory(label, null, R.drawable.ic_settings_play);
        ImageView icon = card.findViewWithTag("settings-icon");
        icon.setTag(null);
        icon.setImageTintList(null);
        Drawable logo = copyDrawable(app.icon(pm));
        if (logo != null) icon.setImageDrawable(logo);
        card.findViewById(R.id.input_status).setVisibility(View.GONE);
        return card;
    }

    private void addInlineChoices(String label, String[] choices, int active,
                                  java.util.function.IntConsumer update) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(15);
        caption.setTypeface(getResources().getFont(R.font.outfit));
        caption.setTextColor(SettingsPopup.TEXT);
        caption.setMaxLines(2);
        line.addView(caption, new LinearLayout.LayoutParams(px(116), -2));
        LinearLayout options = new LinearLayout(this);
        line.addView(options, new LinearLayout.LayoutParams(0, -2, 1));
        menu.addView(line, new LinearLayout.LayoutParams(-1, -2));
        settingsColumns = choices.length;
        View[] cards = new View[choices.length];
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            FrameLayout card = new FrameLayout(this);
            TextView text = new TextView(this);
            text.setId(R.id.input_label);
            text.setText(choices[i]);
            text.setTextSize(12);
            text.setGravity(Gravity.CENTER);
            text.setTypeface(getResources().getFont(R.font.outfit));
            text.setPadding(px(3), 0, px(3), 0);
            text.setMaxLines(2);
            card.addView(text, new FrameLayout.LayoutParams(-1, -1));
            SettingsPopup.prepareRow(card, true);
            cards[i] = card;
            setSelectionTick(card, i == active);
            addSettingsGridCard(options, card, () -> {
                update.accept(index);
                if (!card.isAttachedToWindow()) return;
                for (int j = 0; j < cards.length; j++) setSelectionTick(cards[j], j == index);
            }, true, 46, 5);
        }
    }

    private boolean inDock(String pkg) {
        int index = indexOf(pkg);
        return index >= 0 && index < dockCount;
    }

    private Drawable copyDrawable(Drawable original) {
        if (original == null || original.getConstantState() == null) return null;
        return original.getConstantState().newDrawable(getResources()).mutate();
    }

    private void openAppearance() {
        beginSettingsMenu(settingsAt, "Appearance");
        settingsGrid = true;
        settingsSection("Themes");
        settingsColumns = 4;
        HomeTheme selected = activeTheme;
        List<HomeTheme> themes = new ArrayList<>(Arrays.asList(HomeTheme.values()));
        themes.remove(selected);
        themes.add(0, selected);
        for (HomeTheme choice : themes) {
            View card = themeCard(choice, choice == selected && !(rotatingWallpaper && hasWallpaper));
            themeChoices.put(card, choice);
            addSettingsGridCard(card, () -> {
                if (randomisingThemes()) prefs.edit().remove(HomeTheme.RANDOMISE_SINCE)
                        .putBoolean("wallpaper_paused", true).apply();
                choice.save(prefs);
                refreshHomeTheme();
                loadWallpaper();
                rebuildSettings(this::openAppearance);
            }, true, 74);
        }
        addContentSwitch("Randomise Themes", this::randomisingThemes, () -> {
            SharedPreferences.Editor edit = prefs.edit();
            if (randomisingThemes()) edit.remove(HomeTheme.RANDOMISE_SINCE);
            else edit.putLong(HomeTheme.RANDOMISE_SINCE, timeOfDay.nowMillis());
            edit.apply();
            refreshHomeTheme();
            loadWallpaper();
            updateAppearanceSelection();
            refreshDailyWallpaper();
            nextFromFolder();
        });
        appearanceWallpaperRow = addRow("Show custom wallpaper", wallpaperChoiceStatus(), () -> {
            if (randomisingThemes()) {
                prefs.edit().remove(HomeTheme.RANDOMISE_SINCE).apply();
                refreshHomeTheme();
            }
            if (!wallpaperEnabled()) toggleWallpaper();
            else loadWallpaper();
            openWallpaperSettings();
        }, false);
        if (hasTvSettings() || displaySettingsIntents().stream().anyMatch(this::canOpenTvSettings))
            addRow("Picture and display settings", tvSettingsLabel(), this::openDisplaySettings, true);
        showMenu(710);
    }

    private String wallpaperChoiceStatus() {
        if (randomisingThemes()) return rotatingWallpaper && hasWallpaper ? "Showing now  ›" : "In rotation  ›";
        return wallpaperEnabled() ? "On  ›" : "Off";
    }

    private void updateAppearanceSelection() {
        if (zone != Z_MENU || appearanceWallpaperRow == null) return;
        for (Map.Entry<View, HomeTheme> choice : themeChoices.entrySet()) {
            setSelectionTick(choice.getKey(), choice.getValue() == activeTheme && !(rotatingWallpaper && hasWallpaper));
        }
        setStatus(appearanceWallpaperRow, wallpaperChoiceStatus());
    }

    private void setSelectionTick(View view, boolean selected) {
        ViewGroup card = (ViewGroup) view;
        ImageView tick = card.findViewWithTag("selection-tick");
        if (tick == null) {
            tick = new ImageView(this);
            tick.setTag("selection-tick");
            tick.setImageResource(R.drawable.ic_selection_check);
            tick.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tick.setBackground(rounded(0xFFB7EBCB, 0, 5));
            tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (card instanceof FrameLayout) {
                FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(px(16), px(16), Gravity.TOP | Gravity.END);
                position.topMargin = px(3);
                position.setMarginEnd(px(4));
                card.addView(tick, position);
            } else {
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(px(16), px(16));
                position.setMarginStart(px(4));
                card.addView(tick, position);
            }
        }
        tick.setVisibility(selected ? View.VISIBLE : card instanceof FrameLayout ? View.GONE : View.INVISIBLE);
        card.setSelected(selected);
        SettingsPopup.focus(card, focusedSettingsRow == card && !settingsSidebar);
    }

    private View themeCard(HomeTheme choice, boolean selected) {
        FrameLayout card = (FrameLayout) settingsCategory(choice.label, choice.description,
                R.drawable.ic_settings_palette);
        card.removeView(card.findViewWithTag("settings-icon"));

        ThemePreview preview = new ThemePreview(this, choice, choice.usesGlow()
                ? new int[]{0xFF6D64F0, 0xFF368DCF, 0xFFCE5269} : new int[0]);
        if (choice.usesGlow()) {
            Theme base = choice.fixed() ? Theme.fromPalette(choice.preview, false) : Theme.of(false);
            configureBackdrop(preview, base, false, choice);
        }
        preview.setBackground(rounded(Color.TRANSPARENT, 0, 10));
        preview.setClipToOutline(true);
        FrameLayout.LayoutParams previewPosition = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(36));
        previewPosition.setMargins(px(7), px(7), px(7), 0);
        card.addView(preview, previewPosition);

        TextView title = card.findViewById(R.id.input_label);
        FrameLayout.LayoutParams titlePosition = (FrameLayout.LayoutParams) title.getLayoutParams();
        titlePosition.topMargin = px(48);
        title.setTextSize(13);
        title.setLayoutParams(titlePosition);
        card.findViewById(R.id.input_status).setVisibility(View.GONE);
        card.setContentDescription(choice.label + ". " + choice.description);
        setSelectionTick(card, selected);
        return card;
    }

    private void rebuildSettings(Runnable opener) {
        boolean previous = fromMenuRow, wasOpening = openingSettingsPage;
        boolean keepSidebar = splitSettings && settingsSidebar;
        openingSettingsPage = wasOpening || splitSettings;
        fromMenuRow = true;
        try {
            opener.run();
            if (splitSettings && !wasOpening && !keepSidebar) {
                settingsSidebar = false;
                showSettingsHeading();
                focusMenu(menuIdx);
            }
        } finally {
            fromMenuRow = previous;
            openingSettingsPage = wasOpening;
        }
    }
    private void openHeaderWidgets() {
        beginSettingsMenu(settingsAt, "Header widgets");
        addContentSwitch("Show AQI, calendar and news", () -> sectionEnabled(HomeInsights.KEY),
                () -> setSectionVisibility(HomeInsights.KEY, !sectionEnabled(HomeInsights.KEY)));
        addRow("Market prices", marketQuotes.enabled().size() + " in rotation", this::openMarketSettings, false);
        for (HomeInsights.Widget widget : HomeInsights.Widget.values()) {
            addContentSwitch(widget.label, () -> widget.enabled(this), () -> {
                widget.setEnabled(this, !widget.enabled(this));
                refreshHeaderWidgets();
            });
        }
        addRow("News sources", null, this::openNewsSources, false);
        addRow("Weather location", weather.locationLabel(), this::openWeatherLocation, false);
        for (HomeOptions.Metric metric : HomeOptions.Metric.values()) {
            addContentSwitch(metric.label, () -> metric.shown(prefs), () -> {
                prefs.edit().putBoolean(metric.key, !metric.shown(prefs)).apply();
                refreshMeters();
            });
        }
        addContentSwitch("Show greeting", () -> prefs.getBoolean(HomeOptions.GREETING, true), () -> {
            prefs.edit().putBoolean(HomeOptions.GREETING, !prefs.getBoolean(HomeOptions.GREETING, true)).apply();
            updateInfo();
        });
        addPhoneSettingsShortcut();
        showMenu(690);
    }
    private void openMarketSettings() {
        beginSettingsMenu(settingsAt, "Market prices");
        settingsGrid = true;
        settingsColumns = 5;
        for (MarketQuotes.Group group : MarketQuotes.Group.values()) {
            LinearLayout section = new LinearLayout(this);
            TextView heading = new TextView(this);
            heading.setText(group.title);
            heading.setTextSize(15);
            heading.setTextColor(SettingsPopup.TEXT);
            heading.setTypeface(getResources().getFont(R.font.outfit));
            heading.setFontVariationSettings("'wght' 600");
            heading.setGravity(Gravity.CENTER_VERTICAL);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            section.addView(heading, new LinearLayout.LayoutParams(px(92), px(42)));

            LinearLayout grid = new LinearLayout(this);
            grid.setOrientation(LinearLayout.VERTICAL);
            section.addView(grid, new LinearLayout.LayoutParams(0, -2, 1f));
            menu.addView(section, new LinearLayout.LayoutParams(-1, -2));
            lastSettingsSection = section;
            for (MarketQuotes.Asset asset : MarketQuotes.Asset.values()) {
                if (asset.group == group) addMarketCard(grid, asset);
            }
        }
        recommendationText("OK to add or remove · Hold OK for USD / INR", 12, SettingsPopup.MUTED);
        addPhoneSettingsShortcut();
        showMenu(818);
    }

    private void addMarketCard(LinearLayout grid, MarketQuotes.Asset asset) {
        boolean priced = asset.nativeUnit != MarketQuotes.Unit.POINTS;
        View card = settingsCategory(asset.title, priced ? marketQuotes.displayUnit(asset).name() : "Points", asset.icon);
        ImageView logo = card.findViewWithTag("settings-icon");
        logo.setTag(null);
        logo.setImageTintList(null);
        logo.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams logoPosition = new FrameLayout.LayoutParams(px(18), px(18), Gravity.START | Gravity.CENTER_VERTICAL);
        logoPosition.setMarginStart(px(8));
        logo.setLayoutParams(logoPosition);

        TextView title = card.findViewById(R.id.input_label);
        title.setTextSize(13);
        placeMarketLabel(title, 4);
        TextView currency = card.findViewById(R.id.input_status);
        currency.setTextSize(10.5f);
        currency.setSingleLine(true);
        placeMarketLabel(currency, 22);

        setSelectionTick(card, marketQuotes.isEnabled(asset));
        View tick = card.findViewWithTag("selection-tick");
        FrameLayout.LayoutParams tickPosition = new FrameLayout.LayoutParams(px(15), px(15), Gravity.TOP | Gravity.END);
        tickPosition.topMargin = px(6);
        tickPosition.setMarginEnd(px(5));
        tick.setLayoutParams(tickPosition);
        addSettingsGridCard(grid, card, () -> {
            marketQuotes.setEnabled(asset, !marketQuotes.isEnabled(asset));
            setSelectionTick(card, marketQuotes.isEnabled(asset));
            updateMarketSelection(asset);
        }, true, 42, 5);
        if (priced) marketCurrencyCards.put(card, asset);
    }

    private void placeMarketLabel(TextView label, int top) {
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2);
        position.setMarginStart(px(32));
        position.setMarginEnd(px(22));
        position.topMargin = px(top);
        label.setLayoutParams(position);
    }

    private View focusedMarketCurrencyCard() {
        if (zone != Z_MENU || settingsSidebar || menuIdx >= menuItems.size()) return null;
        View card = menuItems.get(menuIdx);
        return marketCurrencyCards.containsKey(card) ? card : null;
    }

    private void updateMarketSelection(MarketQuotes.Asset asset) {
        marketIndex = Math.max(0, marketQuotes.enabled().indexOf(asset));
        marketCard.stopSlide();
        showMarket();
        scheduleMarketRotation();
        refreshOnline();
    }

    private void openInsightsSettings() {
        openHeaderWidgets();
    }

    private void openNewsSources() {
        beginSettingsMenu(settingsAt, "News sources");
        for (String[] source : new String[][]{{"WSJ", "wsj"}, {"CNN", "cnn"}, {"CNBC", "cnbc"}, {"TechCrunch AI", "techcrunch"}}) {
            String key = "insight_source_" + source[1];
            addContentSwitch(source[0], () -> prefs.getBoolean(key, true), () -> {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, true)).apply();
                refreshHeaderWidgets();
            });
        }
        showMenu(440);
    }

    private void refreshHeaderWidgets() {
        insights.invalidate();
        showChannels(appChannels);
        refreshOnline();
    }

    private void openInsight(HomeInsights.Card card){
        if("news".equals(card.kind)){
            List<HomeInsights.Story> stories=card.stories;String[] labels=new String[stories.size()];for(int i=0;i<labels.length;i++){HomeInsights.Story story=stories.get(i);labels[i]=story.source+" · "+story.topic+" · "+HomeInsights.time(story.published)+"\n"+story.title;}
            android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("US AI, tech & markets").setNegativeButton("Close",null).setNeutralButton("Sources",(d,w)->new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Publisher feeds").setMessage(card.detail.isEmpty()?"Connect to refresh publisher feeds":card.detail).setPositiveButton("OK",null).show());
            if(labels.length>0)dialog.setItems(labels,(d,index)->{HomeInsights.Story story=stories.get(index);showInsightLink(story.title,story.source+" · "+HomeInsights.time(story.published)+(story.source.startsWith("WSJ")?"\nPublisher sign-in may be required.":""),story.url);});else dialog.setMessage("No fresh headlines are available. Check Sources for each feed's status.");dialog.show();return;
        }
        android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("air".equals(card.kind)?"Air quality":card.title).setMessage(card.detail).setNegativeButton("Close",null);
        if(!card.url.isEmpty())dialog.setPositiveButton("air".equals(card.kind)&&card.url.contains("delhi.gov.in")?"Delhi AQI on phone":"Open on phone",(d,w)->showInsightLink(card.title,"air".equals(card.kind)?(card.url.contains("delhi.gov.in")?"Delhi government station readings":"Compare with AQI.in station readings"):"Office Holidays regional calendar",card.url));
        if("air".equals(card.kind))dialog.setNeutralButton("Location settings",(d,w)->openWeatherLocation());dialog.show();
    }
    private void showInsightLink(String title,String detail,String url){
        if(url==null||!url.startsWith("https://"))return;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(px(24),px(8),px(24),px(8));box.addView(qrCode(url),new LinearLayout.LayoutParams(px(180),px(180)));TextView note=new TextView(this);note.setText("Scan with your phone to read at the publisher. No streaming account is needed by Sweep TV.");note.setTextSize(12);box.addView(note);new android.app.AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setMessage(detail).setView(box).setPositiveButton("Close",null).show();
    }

    private Set<String> visibleAppPackages() {
        Set<String> packages = new HashSet<>();
        for (AppEntry app : apps) packages.add(app.pkg);
        return packages;
    }

    private List<Channels.Channel> recommendationSections() {
        List<Channels.Channel> rows = new ArrayList<>();
        for (AppEntry app : apps) {
            if (!Trending.isStreamingApp(app.pkg) || !Recommendations.appProvider(app.pkg)) continue;
            Channels.Channel row = new Channels.Channel();
            row.pkg = app.pkg;
            row.key = Recommendations.appKey(app.pkg);
            row.title = app.label + " · Recommendations";
            rows.add(row);
        }
        if (indexOf(SpotifyPicks.PACKAGE) >= 0) {
            Channels.Channel music = SpotifyPicks.row();
            rows.add(music);
        }
        return rows;
    }
    private boolean sectionEnabled(String key) {
        if(SectionOptions.hidden(this,key))return false;
        if(HomeInsights.KEY.equals(key))return prefs.getBoolean("insights",true);
        if(FormulaOne.KEY.equals(key))return prefs.getBoolean(FormulaOne.KEY,false);
        if(Recommendations.KEY.equals(key))return prefs.getBoolean("recommendations",true);
        if(Recommendations.NEW_KEY.equals(key))return prefs.getBoolean("new_releases",true);
        if(RottenTomatoesPicks.KEY.equals(key))return rottenTomatoesPicks.configured();
        if(Recommendations.isAppRow(key)||SpotifyPicks.KEY.equals(key))return true;
        if ("cricket".equals(key)) return sportEnabled(key);
        return false;
    }
    private void setSectionVisibility(String key,boolean shown) {
        SectionOptions.set(this,key,shown);
        if(HomeInsights.KEY.equals(key))prefs.edit().putBoolean("insights",shown).apply();
        else if(FormulaOne.KEY.equals(key))prefs.edit().putBoolean("formula_one",shown).apply();
        else if(Recommendations.KEY.equals(key))prefs.edit().putBoolean("recommendations",shown).apply();
        else if(Recommendations.NEW_KEY.equals(key))prefs.edit().putBoolean("new_releases",shown).apply();
        else if ("cricket".equals(key)) setSports(key, shown);
        else if (!Recommendations.isAppRow(key) && !RottenTomatoesPicks.KEY.equals(key) && !SpotifyPicks.KEY.equals(key)) {
            List<Channels.Channel> rows = Channels.list(this);
            for (Channels.Channel row : rows) if (key.equals(row.key)) row.shown = shown;
            Channels.save(this, rows);
        }
        chanSignature=null;showChannels(appChannels);refreshChannels();if(shown)refreshOnline();
    }
    private void addHomeSectionToggle(String key, String title) {
        View[] row = new View[1];
        row[0] = addRow(title, homeSectionStatus(key), () -> {
            setSectionVisibility(key, !sectionEnabled(key));
            setStatus(row[0], homeSectionStatus(key));
        }, true);
        row[0].getLayoutParams().height = px(42);
    }

    private String homeSectionStatus(String key) {
        if (!sectionEnabled(key)) return "Hidden";
        if ("cricket".equals(key)) return cricketShown().isEmpty() ? "No scores available" : "Shown";
        if (Recommendations.isRow(key)) {
            if (trending.catalogSource().isEmpty()) return "No catalog for this region";
            for (Channels.Channel row : chanRows) {
                if (key.equals(row.key) && !row.items.isEmpty()) return "Shown";
            }
            return "No matching titles";
        }
        return "Shown";
    }

    private String publishedSectionStatus(Channels.Channel section) {
        if (!section.shown) return "Hidden";
        if (!trending.languages().isEmpty()) {
            for (Channels.Channel row : appChannels) {
                if (!section.key.equals(row.key)) continue;
                if (row.items.stream().noneMatch(pick -> !pick.lang.isEmpty())) return "Language unavailable";
                if (ContentLanguages.filter(row.items, trending.languages()).isEmpty()) return "No matching titles";
                return "Shown";
            }
            // The published channel is known to the TV, but its cards have not loaded yet.
            return "No titles available";
        }
        return "Shown";
    }

    private void openSectionMenu() {
        if(chanIdx>=chanRows.size())return;
        final int row=chanIdx,col=chanCol[row];final Channels.Channel section=chanRows.get(row);
        beginMenu(P_CONTROL,section.title);menuTile=channelCardAt(row,col);menuClosed=()->{if(row<chanRows.size())focusChan(row,col);else focusDock(dockIdx);};
        addRow("Remove section from Home","Restore in Settings",()->{focusDock(dockIdx);setSectionVisibility(section.key,false);Toast.makeText(this,section.title+" hidden",Toast.LENGTH_SHORT).show();},false);
        addRow("Home Page Widgets",null,this::openHomePageWidgets,false);showMenu(430);
    }
    private void openDiscoveryMenu() {
        beginMenu(P_CONTROL,"Discovery shelf");
        if (discoveryTabEnabled(TAB_TRENDING) || discoveryTabEnabled(2)) addRow("Hide Trending and recent tabs", null, () -> {
            forYou = false;
            prefs.edit().putBoolean("for_you", false).remove("show_trending_tab").remove("show_recently_released_tab").apply();
            applyForYou();
            buildPicksRow(0);
            focusDock(dockIdx);
        }, false);
        addRow("Home Page Widgets",null,this::openHomePageWidgets,false);showMenu(400);
    }
    private boolean checkingSweepUpdate;
    private String sweepUpdateResult = "";
    private TextView sweepUpdateFeedback;

    private void addSweepUpdateCheck() {
        addRow("Updates", SelfUpdate.installed(this), this::checkSweepUpdate, true);
        sweepUpdateFeedback = new TextView(this);
        sweepUpdateFeedback.setTextColor(SettingsPopup.MUTED);
        sweepUpdateFeedback.setTextSize(13);
        sweepUpdateFeedback.setTypeface(getResources().getFont(R.font.outfit));
        sweepUpdateFeedback.setPadding(px(16), 0, px(16), px(10));
        sweepUpdateFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        menu.addView(sweepUpdateFeedback, new LinearLayout.LayoutParams(-1, -2));
        showSweepUpdateResult();
    }

    private void checkSweepUpdate() {
        if (checkingSweepUpdate) return;
        checkingSweepUpdate = true;
        sweepUpdateResult = "Checking for updates…";
        showSweepUpdateResult();
        background.execute(() -> {
            String result = SelfUpdate.checkPublished(this);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                checkingSweepUpdate = false;
                sweepUpdateResult = result;
                if ("System".equals(settingsHeading)) showSweepUpdateResult();
            });
        });
    }

    private void showSweepUpdateResult() {
        sweepUpdateFeedback.setText(sweepUpdateResult);
        sweepUpdateFeedback.setVisibility(sweepUpdateResult.isEmpty() ? View.GONE : View.VISIBLE);
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

    private void openContinueSettings() {
        beginSettingsMenu(settingsAt, "Continue Watching");
        settingsGrid = true;
        int first = menu.getChildCount();
        View show = addContentSwitch("Show Continue Watching", () -> ContinueOptions.showTab(this), () -> {
            prefs.edit().putBoolean(ContinueOptions.SHOW_TAB, !ContinueOptions.showTab(this)).apply();
            applyForYou();
            buildPicksRow(0);
            showChannels(appChannels);
            int focus = menuIdx;
            rebuildSettings(this::openContinueSettings);
            focusMenu(focus);
        });
        lastSettingsSection = show;
        if (ContinueOptions.showTab(this)) addContinueChoices();

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(px(10), px(6), px(10), px(4));
        group.setBackground(rounded(0xFF1E222A, 0xFF353B47, 13));
        while (menu.getChildCount() > first) {
            View child = menu.getChildAt(first);
            menu.removeViewAt(first);
            group.addView(child);
        }
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = px(8);
        menu.addView(group, layout);
        lastSettingsSection = null;
        addWatchNextPermissionRow();
        if (!notificationAccess()) {
            addRow("Notification access", "Include playback shared by more apps", this::openNotificationAccess, false);
        }
        showMenu(740);
    }

    private void addContinueChoices() {
        recommendationText("Choose which apps to appear in Continue Watching", 14, SettingsPopup.TEXT);
        settingsColumns = 5;
        List<AppEntry> installed = new ArrayList<>(apps);
        installed.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        Set<String> observed = new HashSet<>();
        for (Trending.Pick pick : watchNextPicks) observed.add(pick.pkg);
        observed.addAll(recentSessionApps);
        boolean youtubeAdded = false;
        List<AppEntry> eligible = new ArrayList<>();
        for (AppEntry app : installed) {
            if (ContinueOptions.hiddenFromPicker(app.pkg)) continue;
            if (!Trending.isStreamingApp(app.pkg) && !ContinueOptions.isYouTube(app.pkg)
                    && !observed.contains(app.pkg) && !app.isMediaApp()) continue;
            if (ContinueOptions.isYouTube(app.pkg)) {
                if (youtubeAdded) continue;
                youtubeAdded = true;
            }
            eligible.add(app);
        }
        for (AppEntry app : eligible) addContinueApp(app);
    }

    private void addContinueApp(AppEntry app) {
        View card = appChoiceCard(app, ContinueOptions.isYouTube(app.pkg) ? "YouTube" : app.label);
        setSelectionTick(card, ContinueOptions.include(this, app.pkg));
        addSettingsGridCard(card, () -> {
            ContinueOptions.setIncluded(this, app.pkg, !ContinueOptions.include(this, app.pkg));
            refreshContinueChoices();
            rebuildSettings(this::openContinueSettings);
            focusMenu(menuItems.indexOf(continueAppRows.get(app.pkg)));
        }, true, 60);
        continueAppRows.put(app.pkg, card);
    }

    private void refreshContinueChoices() {
        if(ContinueOptions.titleArtwork(this))for(Trending.Pick p:watchNextPicks) {
            TitleArtwork.applyCached(this,p);
        }
        recentPicks=continuePicks(rememberSessionApps(RecentStore.load(this)),watchNextPicks);
        if (tabIdx == 0 || rowPicks.isEmpty()) buildPicksRow(picksRow.anchor());
        showChannels(appChannels);
        refreshWatchNext();
    }

    private void contentLanguagesChanged() {
        Profiles.remember(this);
        Set<String> chosen=trending.languages();
        recommendationCatalog=ContentLanguages.filter(recommendationCatalog,chosen);
        trendPicks=ContentLanguages.filter(trendPicks,chosen);indiaPicks=ContentLanguages.filter(indiaPicks,chosen);
        tabs[2].setText(chosen.isEmpty()?"New in "+Region.name(Region.country(this)):"Recently released");
        // Scrub the old rows immediately, rather than waiting for the usual navigation-idle window.
        refocusChannels=true;showChannels(appChannels);if(tabIdx!=0)buildPicksRow(0);
        background.execute(()->{List<Trending.Pick> saved=trending.cached();runOnUiThread(()->{
            recommendationCatalog=ContentLanguages.filter(saved,trending.languages());
            trendPicks = Trending.shelf(recommendationCatalog, Trending.TRENDING);
            indiaPicks = Trending.shelf(recommendationCatalog, Trending.INDIA);
            showChannels(appChannels);if(tabIdx!=0)buildPicksRow(0);refreshOnline();
        });});
    }

    private String contentLanguageSummary() {
        Set<String> selected = trending.languages();
        if (selected.isEmpty()) return "All languages";
        List<String> names = new ArrayList<>();
        for (String code : selected) names.add(Trending.languageName(code));
        return String.join(", ", names);
    }

    private void openContentLanguages() {
        beginSettingsMenu(settingsAt, "Select Content Languages");
        settingsGrid = true;
        settingsColumns = 5;
        recommendationText("Home shows only movies and series in your selected languages. Continue Watching includes all languages.",
                13, SettingsPopup.MUTED);
        Set<String> available = new java.util.LinkedHashSet<>(ContentLanguages.AVAILABLE);
        available.addAll(trending.seenLanguages());
        available.addAll(trending.languages());
        List<String> codes = new ArrayList<>();
        codes.add(null);
        codes.addAll(available);

        List<View> boxes = new ArrayList<>();
        Runnable mark = () -> {
            Set<String> chosen = trending.languages();
            for (int i = 0; i < boxes.size(); i++) {
                String code = codes.get(i);
                setSelectionTick(boxes.get(i), code == null ? chosen.isEmpty() : chosen.contains(code));
            }
        };
        for (String code : codes) {
            View box = LayoutInflater.from(this).inflate(R.layout.item_input, menu, false);
            box.findViewById(R.id.input_dot).setVisibility(View.GONE);
            TextView label = box.findViewById(R.id.input_label);
            label.setText(code == null ? "All languages" : Trending.languageName(code));
            label.setTextSize(13);
            box.setPadding(px(10), 0, px(10), 0);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            SettingsPopup.prepareRow(box, true);
            boxes.add(box);
            addSettingsGridCard(box, () -> {
                Set<String> chosen = trending.languages();
                if (code == null) chosen.clear();
                else if (!chosen.remove(code)) chosen.add(code);
                trending.setLanguages(chosen);
                mark.run();
                contentLanguagesChanged();
            }, true, 42);
        }
        mark.run();
        showMenu(760);
    }

    private boolean ownWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return false;
        for (String n : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) if (new File(dir, n).isFile()) return true;
        return false;
    }

    private boolean wallpaperEnabled() {
        return !prefs.getBoolean("wallpaper_paused", false)
                && (prefs.getBoolean("custom_wallpaper_enabled", false) || ownWallpaper()
                || prefs.getBoolean("bing_wallpaper", false) || prefs.getString("wall_folder", null) != null);
    }

    private void toggleWallpaper() {
        boolean on = !wallpaperEnabled();
        prefs.edit().putBoolean("wallpaper_paused", !on).putBoolean("custom_wallpaper_enabled", on).apply();
        loadWallpaper();
        if (on) {
            refreshDailyWallpaper();
            nextFromFolder();
        }
    }

    private void refreshDailyWallpaper() {
        if ((wallpaperEnabled() || randomisingThemes()) && dailyWallpaperEnabled() && !ownWallpaper() && !hasFolderWallpaper())
            bing.refreshIfStale(this::loadWallpaper);
    }

    // ---------------------------------------------------------------- wallpaper

    private static final String FOLDER_WALLPAPER = "wallpaper-folder.jpg";
    private static final String SHUFFLE_MINUTES = "wall_folder_shuffle_minutes";
    private static final String FOLDER_ATTEMPT = "wall_folder_attempt";
    private static final int[] ROTATION_MINUTES = {0, 15, 60, 1440};
    private static final String[] ROTATION_LABELS = {"In order · Daily", "Shuffle · 15 min", "Shuffle · Hourly", "Shuffle · Daily"};

    private int rotationIndex() {
        int minutes = prefs.getInt(SHUFFLE_MINUTES, 0);
        for (int i = 0; i < ROTATION_MINUTES.length; i++) if (ROTATION_MINUTES[i] == minutes) return i;
        return 0;
    }

    private boolean hasFolderWallpaper() {
        File dir = getExternalFilesDir(null);
        return dir != null && prefs.contains("wall_folder") && prefs.contains("wall_folder_last")
                && new File(dir, FOLDER_WALLPAPER).isFile();
    }

    private boolean dailyWallpaperEnabled() {
        return prefs.getBoolean("wallpaper_picture_of_day", true);
    }

    private void openWallpaperSettings() {
        beginSettingsMenu(settingsAt, "Custom wallpaper");
        addContentSwitch("Show custom wallpaper", this::wallpaperEnabled, () -> {
            toggleWallpaper();
            rebuildSettings(this::openWallpaperSettings);
        });
        if (wallpaperEnabled()) {
            addRow("Choose from TV", "Picture or folder", () -> {
                pictureFor = null;
                openTvPictures();
            }, true);
            addContentSwitch("Picture of the day", this::dailyWallpaperEnabled, () -> {
                prefs.edit().putBoolean("wallpaper_picture_of_day", !dailyWallpaperEnabled()).apply();
                loadWallpaper();
                refreshDailyWallpaper();
            });
            boolean folder = prefs.contains("wall_folder");
            if (folder) addSettingsCycle("Wallpaper rotation", () -> ROTATION_LABELS[rotationIndex()], () -> {
                int index = (rotationIndex() + 1) % ROTATION_MINUTES.length;
                folderGeneration++;
                prefs.edit().putInt(SHUFFLE_MINUTES, ROTATION_MINUTES[index])
                        .remove("wall_folder_day").remove(FOLDER_ATTEMPT).apply();
                nextFromFolder();
            });
            if (folder || ownWallpaper()) addRow(folder ? "Remove wallpaper folder" : "Remove your picture", null, () -> {
                stopFolder();
                removeOwnWallpaper();
                loadWallpaper();
                refreshDailyWallpaper();
                rebuildSettings(this::openWallpaperSettings);
                focusMenu(0);
            }, true);
        }
        showMenu(500);
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
            if (ok) {
                stopFolder();
                prefs.edit().putBoolean("wallpaper_paused", false).apply();
            }
            Toast.makeText(this, ok ? "Your picture is the wallpaper now" : "Couldn't use that picture", Toast.LENGTH_SHORT).show();
            if (ok) {
                loadWallpaper();
                refreshSettingsPage("Custom wallpaper", this::openWallpaperSettings);
            }
            return;
        }
        Toast.makeText(this, ok ? "Your picture is on " + labelOf(pkg) + " now" : "Couldn't use that picture", Toast.LENGTH_SHORT).show();
        if (ok) reloadApps();
    }

    /** Back to one picture: the folder no longer changes it. */
    private void stopFolder() {
        folderGeneration++;
        prefs.edit().remove("wall_folder").remove("wall_folder_name").remove("wall_folder_last")
                .remove("wall_folder_day").remove(FOLDER_ATTEMPT).apply();
        File dir = getExternalFilesDir(null);
        if (dir != null) new File(dir, FOLDER_WALLPAPER).delete();
    }

    private void removeOwnWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        for (String n : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) new File(dir, n).delete();
    }

    /** Save a 16:9 wallpaper at 1920 × 1080 or an app tile at 640 × 360. */
    private boolean savePicture(java.util.function.Supplier<java.io.InputStream> open, String pkg) {
        File dir = pkg == null ? getExternalFilesDir(null) : AppEntry.customDir;
        File prepared = preparePicture(open, dir, pkg == null ? 1920 : 640, pkg == null ? 1080 : 360);
        return commitPicture(prepared, pkg == null ? new File(dir, "wallpaper.jpg") : AppEntry.customImage(pkg));
    }

    private static boolean commitPicture(File prepared, File destination) {
        if (prepared == null) return false;
        if (prepared.renameTo(destination)) return true;
        prepared.delete();
        return false;
    }

    private File preparePicture(java.util.function.Supplier<java.io.InputStream> open, File dir, int w, int h) {
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return null;
        File prepared = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = open.get()) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap src;
            try (java.io.InputStream in = open.get()) { src = BitmapFactory.decodeStream(in, null, o); }
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            float k = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            float sw = src.getWidth() * k, sh = src.getHeight() * k;
            new android.graphics.Canvas(out).drawBitmap(src, null,
                    new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            try {
                prepared = File.createTempFile("picture-", ".tmp", dir);
                try (java.io.FileOutputStream f = new java.io.FileOutputStream(prepared)) {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, 90, f)) throw new java.io.IOException("Image encoding failed");
                }
            } finally {
                out.recycle();
            }
            return prepared;
        } catch (Exception | OutOfMemoryError e) {
            if (prepared != null) prepared.delete();
            Log.w(TAG, "Couldn't prepare the picture: " + e);
            return null;
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
            addThumb(addRow(name, "Rotate this folder", () -> useFolder(pic[3], pic[4], name), false), Uri.parse(pic[0]));
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

    private void useFolder(String volume, String id, String name) {
        stopFolder();
        prefs.edit().putBoolean("wallpaper_paused", false).putString("wall_folder", volume + "/" + id)
                .putString("wall_folder_name", name).apply();
        nextFromFolder();
        Toast.makeText(this, "Wallpaper folder: " + name, Toast.LENGTH_SHORT).show();
    }

    private boolean folderBusy;
    private int folderGeneration;

    /** While Home is visible, update the photo at the chosen interval. Keep the last photo if the folder disappears. */
    private void nextFromFolder() {
        String folder = prefs.getString("wall_folder", null);
        if ((!randomisingThemes() && prefs.getBoolean("wallpaper_paused", false)) || folder == null || folderBusy) return;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int today = now.get(java.util.Calendar.YEAR) * 1000 + now.get(java.util.Calendar.DAY_OF_YEAR);
        long timestamp = now.getTimeInMillis();
        int minutes = ROTATION_MINUTES[rotationIndex()];
        long elapsed = timestamp - prefs.getLong(FOLDER_ATTEMPT, 0);
        if (minutes == 0 ? prefs.getInt("wall_folder_day", 0) == today
                : elapsed >= 0 && elapsed < minutes * 60_000L) return;
        folderBusy = true;
        int generation = folderGeneration;
        String last = prefs.getString("wall_folder_last", null);
        File dir = getExternalFilesDir(null);
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
                if (minutes == 0 && !uris.isEmpty()) {
                    next = uris.get((uris.indexOf(last) + 1) % uris.size());
                } else {
                    uris.remove(last);
                    if (!uris.isEmpty()) next = uris.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(uris.size()));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't read the wallpaper folder: " + e);
            }
            String pick = next;
            File prepared = pick == null ? null : preparePicture(() -> {
                try { return getContentResolver().openInputStream(Uri.parse(pick)); }
                catch (java.io.FileNotFoundException e) { throw new RuntimeException(e); }
            }, dir, 1920, 1080);
            runOnUiThread(() -> {
                folderBusy = false;
                if (generation != folderGeneration || (!randomisingThemes() && prefs.getBoolean("wallpaper_paused", false)) || isDestroyed()) {
                    if (prepared != null) prepared.delete();
                    if (!isDestroyed() && generation != folderGeneration) nextFromFolder();
                    return;
                }
                boolean saved = commitPicture(prepared, new File(dir, FOLDER_WALLPAPER));
                SharedPreferences.Editor state = prefs.edit().putInt("wall_folder_day", today)
                        .putLong(FOLDER_ATTEMPT, timestamp);
                if (saved) state.putString("wall_folder_last", pick);
                state.apply();
                if (saved) loadWallpaper();
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
    /** Credits for the data Sweep TV shows; TMDB's wording is what its terms ask for. */
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
                {"Streaming availability", "JustWatch"},
                {"Catalog fallback", "StreamRadar · TVmaze"},
                {"Speed test", "Cloudflare"},
                {"Code from", "tv-vibehack, CleanYourTV, Free My TV, Matinee, Luma, GlassLauncher"},
                {"Font", "Outfit (SIL OFL)"},
        };
        addRow("Sweep TV", "Your personalised home", () -> { }, true);
        addRow("What's new", null, this::openReleaseNotes, false);
        addRow("Included components", null, this::openReleaseComponents, false);
        addRow("Launcher licence", "MIT", () -> openLicence("LICENSE"), false);
        addRow("Open-source licences", "Full notices", () -> openLicence("THIRD_PARTY_NOTICES.md"), false);
        addRow("Profile illustrations", "Twemoji · CC BY 4.0; DiceBear Thumbs · CC0", () -> openLicence("TWEMOJI-CC-BY-4.0.txt"), false);

        // TMDB's terms ask for its logo with the credit: sized below the launcher name.
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
        if (wallpaperEnabled() && caption != null) addRow("Wallpaper", "Bing: " + caption, () -> { }, true);
        showMenu(720);
    }

    // ---------------------------------------------------------------- recent apps

    /**
     * Control Center → Recent apps (or a remote button set to it): the apps you opened last, newest first, each with
     * its banner. OK opens one. At the end, close them all (apps in the background only, never system apps).
     */
    private void confirmFreeMemory() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Free memory")
                .setMessage("Close recent background apps where your TV allows it? Some apps may reload next time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> {
                    closeRecentBackgroundApps();
                    Toast.makeText(this, "Requested background app cleanup", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void closeRecentBackgroundApps() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) return;
        for (String pkg : RecentApps.list(this)) {
            try {
                if ((pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    manager.killBackgroundProcesses(pkg);
                }
            } catch (PackageManager.NameNotFoundException | SecurityException ignored) { }
        }
    }

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
                long before = availableMb();
                closeRecentBackgroundApps();
                close[0].postDelayed(() -> setStatus(close[0], "Freed " + Math.max(0, availableMb() - before) + " MB"), 800);
            }, true);
        }
        showMenu(460);
    }

    // ---------------------------------------------------------------- control center shortcuts

    /** Control Center rows whose screen you can choose (Sweep TV settings → Control Center shortcuts): key → row name. */
    private static final String[][] SHORTCUTS = {{"network", "Network"}};
    /** Words that make a settings screen a likely match for each row, shown first in the list. */
    private static final String[][] SHORTCUT_WORDS = {{"wifi", "network", "internet", "ethernet"}};

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

    private void openShortcuts() {
        beginSettingsMenu(settingsAt, "Network shortcut");
        addRow("Choose settings screen", shortcutLabel("network"), () -> openShortcutPicker(0, false), false);
        showMenu(460);
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
                    Toast.makeText(this, SHORTCUTS[k][1] + " opens " + r[0] + ". Try it from Settings", Toast.LENGTH_LONG).show();
                    openShortcuts();
                }, false);
                if (more > 0) addRow("Show every screen", String.valueOf(more), () -> openShortcutPicker(k, true), false);
                showMenu(520);
            });
        });
    }

    // ---------------------------------------------------------------- dock apps

    // ---------------------------------------------------------------- channels editor

    /** Every channel your apps publish: OK shows or hides it; hold OK, then Up/Down, to move it. */
    private void openChannelsEditor() {
        beginSettingsMenu(settingsAt, "App sections");
        recommendationText("Loading app sections…", 12, SettingsPopup.MUTED);
        showMenu(670);
        int generation = menuGen;
        background.execute(() -> {
            List<Channels.Channel> sections = Channels.list(this);
            runOnUiThread(() -> {
                if (isDestroyed() || zone != Z_MENU || menuGen != generation) return;
                editList = sections;
                channelsEdited = false;
                rebuildSettings(() -> {
                    fillChannelsMenu();
                    showMenu(670);
                });
            });
        });
    }

    private void fillChannelsMenu() {
        beginSettingsMenu(settingsAt, "App sections");
        if (!editList.isEmpty()) {
            settingsSection("Published app rows");
            recommendationText("Hold OK to move a row.", 12, SettingsPopup.MUTED);
        }
        menuMode = MENU_CHANNELS;
        for (Channels.Channel ch : editList) {
            final Channels.Channel c = ch;
            addRow(ch.title, null, () -> {
                c.shown = !c.shown;SectionOptions.set(this,c.key,c.shown);
                channelsEdited = true;
                saveChannelEdit();
                channelStatuses();
            }, true);
        }
        channelStatuses();
        settingsSection("App recommendations");
        for (Channels.Channel row : recommendationSections()) {
            addHomeSectionToggle(row.key, row.title);
        }
    }

    private void saveChannelEdit() {
        Channels.save(this, editList);
    }

    private void channelStatuses() {
        for (int i = 0; i < editList.size() && i < menuItems.size(); i++) {
            Channels.Channel ch = editList.get(i);
            String status = publishedSectionStatus(ch);
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
            saveChannelEdit();
            channelStatuses();
        } else {
            editList = beforeEdit;
            int at = menuIdx;
            rebuildSettings(this::fillChannelsMenu);
            showMenu(670);
            focusMenu(Math.min(at, menuItems.size() - 1));
        }
        moveHint.animate().alpha(0f).setDuration(120).withEndAction(() -> { moveHint.setVisibility(View.GONE); showServiceNote(); }).start();
    }

    private static final String ORIGINAL_TV_HOME = "original_tv_home";

    private void rememberOriginalTvHome() {
        if (prefs.contains(ORIGINAL_TV_HOME)) return;
        ResolveInfo current = resolveHome();
        if (current == null || current.activityInfo == null) return;
        android.content.pm.ActivityInfo home = current.activityInfo;
        if (home.applicationInfo == null || "android".equals(home.packageName) || getPackageName().equals(home.packageName)) return;
        if ((home.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) return;
        prefs.edit().putString(ORIGINAL_TV_HOME, home.packageName).apply();
    }

    private void openOriginalHomeSetup() {
        beginSettingsMenu(settingsAt, "Return to original Home");
        String original = prefs.getString(ORIGINAL_TV_HOME, "");
        String label = original.isEmpty() ? "the TV's original launcher" : Channels.label(pm, original);
        ResolveInfo current = resolveHome();
        boolean alreadyDefault = current != null && current.activityInfo != null
                && original.equals(current.activityInfo.packageName);
        boolean helperActive = SleepService.isRunning();
        if (alreadyDefault) {
            recommendationText(label + " is already selected in Android."
                    + (helperActive ? " Turn off Sweep TV’s Home button helper to return to it." : ""),
                    15, SettingsPopup.TEXT);
        } else {
            recommendationText("Turn off the Sweep TV Home button helper if it is on. Then select " + label
                    + " in Android's Home app settings.", 15, SettingsPopup.TEXT);
            Intent choose = new Intent(Settings.ACTION_HOME_SETTINGS);
            if (canOpenTvSettings(choose)) addRow("Choose Home app", label, () -> tryOpenTvSettings(choose), false);
            else if (hasTvSettings()) addRow(tvSettingsLabel(), null, this::openTvSettings, false);
        }
        if (helperActive) addRow("Home button helper", "Accessibility", this::openHomeHelperSettings, false);
        showMenu(540);
    }

    private ResolveInfo resolveHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        return pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    }

    private boolean needsHomeSetup() {
        if (SleepService.isRunning()) return false;
        ResolveInfo home = resolveHome();
        return home == null || home.activityInfo == null
                || !getPackageName().equals(home.activityInfo.packageName);
    }

    private String currentHomeName() {
        ResolveInfo home = resolveHome();
        if (SleepService.handlesHome(home)) return getString(R.string.app_name);
        if (home == null || home.activityInfo == null) return "Unavailable";
        if ("android".equals(home.activityInfo.packageName)) return "Android chooser";
        return String.valueOf(home.loadLabel(pm));
    }

    private void openHomeHelperSettings() {
        Intent direct = new Intent().setClassName("com.android.tv.settings",
                "com.android.tv.settings.oemlink.AccessibilitySettingsActivity");
        if (tryOpenTvSettings(direct)) return;
        Intent standard = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        ResolveInfo match = pm.resolveActivity(standard, 0);
        if (match != null && match.activityInfo != null && !match.activityInfo.name.contains("Stub")
                && tryOpenTvSettings(standard)) return;
        if (tryOpenTvSettings(androidTvSettings()) || tryOpenTvSettings(new Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(this, "Open Device Preferences → Accessibility → Sweep TV.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "On your TV, open Settings → Accessibility → Sweep TV.", Toast.LENGTH_LONG).show();
        }
    }

    private void openHomeSetup() {
        ResolveInfo current = resolveHome();
        String androidDefault = current == null || current.activityInfo == null ? "" : current.activityInfo.packageName;
        String activePackage = SleepService.handlesHome(current) ? getPackageName() : androidDefault;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        beginSettingsMenu(settingsAt, "Home app");
        settingsSection("Open a Home app");
        Set<String> shown = new HashSet<>();
        int activeRow = -1;
        for (ResolveInfo app : pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (app.activityInfo == null || !app.activityInfo.exported || !app.activityInfo.enabled || app.priority < 0) continue;
            String pkg = app.activityInfo.packageName;
            if ("android".equals(pkg) || "com.duokan.factorytest".equals(pkg) || !shown.add(pkg)) continue;
            ComponentName component = new ComponentName(pkg, app.activityInfo.name);
            String label = pkg.equals(getPackageName()) ? getString(R.string.app_name) : String.valueOf(app.loadLabel(pm));
            boolean active = pkg.equals(activePackage);
            String status = pkg.equals(androidDefault) ? "Android default" : active ? "Home button helper" : "Open once";
            if (active) activeRow = menuItems.size();
            View row = addRow(label, status, () -> {
                closeMenu();
                if (pkg.equals(getPackageName())) return;
                Intent preview = pm.getLeanbackLaunchIntentForPackage(pkg);
                start(preview == null ? new Intent(home).setComponent(component) : preview);
            }, false);
            if (active) setSelectionTick(row, true);
        }
        Intent choose = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (canOpenTvSettings(choose) || hasTvSettings()) addRow("Change Android default", "System settings", () -> {
            if (!tryOpenTvSettings(choose)) openTvSettings();
        }, false);
        if (canOpenTvSettings(choose) || hasTvSettings() || SleepService.isRunning())
            addRow("Return to original Home", null, this::openOriginalHomeSetup, false);
        showMenu(540);
        if (activeRow >= 0) focusMenu(activeRow);
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

    private float animationScale() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    private long availableMb() {
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
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
            root.postDelayed(rereadWatchNext, 1500);
        }
        wasOnline = online;
    }

    /** Your own wallpaper (files/wallpaper.jpg|png|webp) replaces the soft backdrop. */
    private void loadWallpaper() {
        if (isDestroyed()) return;
        File dir = getExternalFilesDir(null);
        File f = null;
        if (dir != null && (randomisingThemes() ? rotatingWallpaper : wallpaperEnabled())) {
            if (hasFolderWallpaper()) f = new File(dir, FOLDER_WALLPAPER);
            for (String name : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
                File c = new File(dir, name);
                if (f == null && c.isFile()) { f = c; break; }
            }
            // No picture of your own: Bing's, when that's on.
            File b = new File(dir, Bing.FILE);
            if (f == null && dailyWallpaperEnabled() && b.isFile()) f = b;
        }
        long stamp = f == null ? 0 : f.lastModified() ^ f.length() ^ f.getName().hashCode();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (f == null) {
            if (hasWallpaper) showWallpaper(null, null);
            return;
        }
        File file = f;
        int screen = AerialQuality.hasUhdDisplay(this) ? 3840 : getResources().getDisplayMetrics().widthPixels;
        artworkColours.execute(() -> decodeWallpaper(file, stamp, screen));
    }

    private void decodeWallpaper(File file, long stamp, int screen) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        // Full size and full colour: at half size, or in 16-bit colour, a photo looks soft and skies band.
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= screen) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
        Legibility fit = null;
        if (bmp != null) {
            fit = Legibility.of(bmp, Theme.of(false).scrimTop & 0x00FFFFFF, Theme.of(true).scrimTop & 0x00FFFFFF);
            // Kept only where it's drawn, on the graphics side, once its veil is worked out: half the memory.
            Bitmap gpu = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) { bmp.recycle(); bmp = gpu; }
        }
        Bitmap ready = bmp;
        Legibility veil = fit;
        runOnUiThread(() -> {
            if (isDestroyed() || wallpaperStamp != stamp) {
                if (ready != null) ready.recycle();
                return;
            }
            showWallpaper(ready, veil);
        });
    }

    private void showWallpaper(Bitmap picture, Legibility veil) {
        hasWallpaper = picture != null;
        wallFit = veil;
        backdrop.setWallpaper(picture);
        updateAppearanceSelection();
        applyTheme(false);
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
        homeStarted = true;
        if (appsStale) {
            appsStale = false;
            reloadApps();
        }
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { runOnUiThread(() -> updateNetwork(c)); }
            @Override public void onLost(Network n) { runOnUiThread(() -> updateNetwork(null)); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
        Network active = cm.getActiveNetwork();
        updateNetwork(active == null ? null : cm.getNetworkCapabilities(active));

        refreshHomeTheme();
        loadWallpaper();
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
            trendPicks = Trending.shelf(recommendationCatalog, Trending.TRENDING);
            indiaPicks = Trending.shelf(recommendationCatalog, Trending.INDIA);
            refreshDiscoveryShelf();
        }
        // Something new may have been watched while we were away.
        refreshChannels();
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
        if (titleDialog != null) titleDialog.dismiss();
        insightTimer.removeCallbacks(rotateInsight);if(insights!=null)insights.close();
        if(spotify!=null)spotify.close();
        if (sportsScores != null) sportsScores.close();
        if(marketQuotes!=null)marketQuotes.close();
        if (trending != null) trending.close();
        insightTimer.removeCallbacks(rotateMarket);
        if (pendingArtworkColour != null) root.removeCallbacks(pendingArtworkColour);
        if (pendingContinueMetadata != null) root.removeCallbacks(pendingContinueMetadata);
        artworkColours.shutdownNow();
        titleCoverGeneration++;titleCoverWorker.shutdownNow();titleLinkWorker.shutdownNow();cardArtwork.close();channelWorker.shutdownNow();appChannelWorker.shutdownNow();root.removeCallbacks(applyPreparedChannels);
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        if (dashboardDialog != null) dashboardDialog.dismiss();
        super.onStop();
        homeStarted = false;
        getContentResolver().unregisterContentObserver(watchNextChanged);
        root.removeCallbacks(rereadWatchNext);
        root.removeCallbacks(applyPreparedChannels);
        unregisterReceiver(timeReceiver);
        getApplicationContext().getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rememberOriginalTvHome();
        refreshWatchNextPermissionRow();
        tabs[2].setText(trending.languages().isEmpty()?"New in " + Region.name(Region.country(this)):"Recently released");  // the country can change (Region)
        inFront = true;
        scheduleHomeShelfRotation();
        requestTitleCovers();
        scheduleInsightRotation();
        scheduleMarketRotation();
        homeInteractiveAfter=android.os.SystemClock.uptimeMillis()+1200;
        root.removeCallbacks(applyPreparedChannels);root.postDelayed(applyPreparedChannels,1200);
        refreshHomeTheme();
        updateAmbient();
        root.post(this::snapRing);
        // Give Android time to bind the service after Sweep TV (re)starts before calling it stopped.
        root.removeCallbacks(checkService);
        root.postDelayed(checkService, 6000);
        refreshOnline();   // back on the home screen: catch up on anything that went stale meanwhile
        InstallCount.onLaunch(this, background);
        if (appsOrder() == ORDER_RECENT && !moving && !recentSig.equals(String.join(",", RecentApps.all(this)))) {
            String focused = zone == Z_GRID && dockCount + gridIdx < apps.size() ? apps.get(dockCount + gridIdx).pkg : null;
            reloadApps();
            int at = focused == null ? -1 : indexOf(focused);
            if (at >= dockCount) focusGrid(at - dockCount);
        }
    }

    /** Is the accessibility service down when it should be up? Switch it off and on if we may; else say so. */
    private final Runnable checkService = () -> {
        if (!inFront) return;
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
        updateDrawerHint();
        if (stopped) root.postDelayed(this.checkService, 5000);
    };

    private void showServiceNote() {
        boolean show = serviceStopped && moveHint.getVisibility() != View.VISIBLE;
        if (serviceNote == null) {
            if (!show) return;
            serviceNote = new TextView(this);
            serviceNote.setText("Home, Turn off and Remote keys stopped working  ·  Settings → System → Restore Home and power controls");
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

    /** The OK or Back button that went down while Sweep TV was in front (0: none), so only its release counts. */
    private int pressedHere;

    @Override
    protected void onPause() {
        super.onPause();
        pressedHere = 0;
        SleepService.learn(null);
        inFront = false;
        scheduleHomeShelfRotation();
        root.removeCallbacks(checkService);
        updateDrawerHint();
        titleCoverGeneration++;
        insightTimer.removeCallbacks(rotateInsight);
        scheduleMarketRotation();
        showTopInsight(insightIdx);
    }

    private void showMarket() { showMarket(false); }

    private void showMarket(boolean animate) {
        List<MarketQuotes.Asset> selected=marketQuotes.enabled();
        marketCard.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);
        if (selected.isEmpty()) {
            marketCard.stopSlide();
            return;
        }
        marketIndex=Math.floorMod(marketIndex,selected.size());
        MarketQuotes.Asset asset=selected.get(marketIndex);
        MarketQuotes.Quote quote = marketQuotes.quote(asset);
        marketCard.bind(asset, quote, marketQuotes.displayUnit(asset), marketQuotes.displayPrice(asset, quote), animate);
    }

    private void scheduleMarketRotation() {
        insightTimer.removeCallbacks(rotateMarket);
        if(marketQuotes==null)return;
        if(inFront&&hasWindowFocus()&&marketQuotes.enabled().size()>1) {
            insightTimer.postDelayed(rotateMarket,10_000);
        } else {
            marketCard.stopSlide();
        }
    }

    private void advanceMarket() {
        if(!inFront||!hasWindowFocus())return;
        marketIndex++;
        showMarket(animationScale() > 0f && android.animation.ValueAnimator.areAnimatorsEnabled());
        scheduleMarketRotation();
    }

    private void refreshMusic() {
        if (indexOf(SpotifyPicks.PACKAGE) >= 0 && !SectionOptions.hidden(this, SpotifyPicks.KEY)) {
            spotify.refresh(Region.country(this), row -> { musicRow = row; showChannels(appChannels); });
        }
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
        if (recommendationWindow != Recommendations.window(System.currentTimeMillis())) showChannels(appChannels);
        boolean clock24 = DateFormat.is24HourFormat(this);
        if (sportEnabled("cricket")) {
            if (Keys.read(this, Keys.CRICKET) != null) cricket.refreshIfStale(this::onMatches);
            sportsScores.refresh(false, false, clock24, list -> { cricketFeed = list; showChannels(appChannels); });
        }
        if (sportEnabled(FormulaOne.KEY)) formula.refreshIfStale(this::onFormula);
        weather.refreshIfStale(this::onWeather);
        insights.refresh(state->{if(!isDestroyed()){insightState=state;showChannels(appChannels);}});
        refreshDailyWallpaper();
        nextFromFolder();  // no network needed, but the same moments: a new day's picture while you can see it
        trending.refreshIfStale(this::onPicks);
        refreshMusic();
        showMarket();
        marketQuotes.refresh(this::showMarket);
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
            // Sharper YouTube pictures arrived: redraw Continue Watching.
            recentPicks = continuePicks(rememberSessionApps(RecentStore.load(this)), watchNextPicks);
            if (tabIdx == 0 && zone != Z_PICKS) buildPicksRow(0);
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        scheduleHomeShelfRotation();
        updateDrawerHint();
        // The screensaver or a system dialog took the screen, or gave it back.
        if (!hasFocus) showTopInsight(insightIdx);
        scheduleInsightRotation();
        scheduleMarketRotation();
        if (hasFocus) {
            refreshHomeTheme();
            refreshOnline();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (BuildConfig.CATALOG_COMPARISON && ComparisonKeyImport.ACTION.equals(intent.getAction())) {
            background.execute(() -> {
                ComparisonKeyImport.handle(getApplicationContext());
                runOnUiThread(() -> { if (!isDestroyed()) refreshCatalogSource(); });
            });
            return;
        }
        if (titleDialog != null) titleDialog.dismiss();
        // backup-settings.sh: write files/sweep-tv-backup.json for it to pull. restore-settings.sh: start again, so
        // onCreate applies the file it pushed.
        if (intent.getBooleanExtra(Backup.EXTRA_BACKUP, false)) {
            Toast.makeText(this, Backup.save(this) ? "Settings backed up" : "Couldn't back up the settings", Toast.LENGTH_SHORT).show();
            return;
        }
        if (intent.getBooleanExtra(Backup.EXTRA_RESTORE, false)) {
            recreate();
            return;
        }
        SleepService.learn(null);  // Home while "Waiting for a button…": stop listening
        // Keep the Continue card selected when returning from a title or pressing Home on the shelf.
        boolean returnToContinue = continueShelfPinned && tabIdx == 0 && shelfFocused() && !rowPicks.isEmpty();
        if (menuMoving) endMenuMove(false);
        if (zone == Z_MENU) {
            menuScroll.animate().cancel();
            clearThemePreview();menuScroll.setVisibility(View.GONE);settingsPopup.hide(false);settingsPopupMode=false;settingsGrid=false;
            splitSettings = settingsSidebar = dockMenu = false;
            if (menuMode == MENU_CHANNELS && channelsEdited) refreshChannels();
            menuMode = MENU_PLAIN;
            zone = Z_TOP;
        }
        endMove(false);
        loadWallpaper();
        String panel = intent.getStringExtra(RemoteKeys.EXTRA_OPEN);
        if (isDrawerGesture(panel)) {
            continueShelfPinned = false;
            scrollPage(0);
            openRemotePanel(panel);
        } else {
            if (returnToContinue && panel == null) {
                if (zone == Z_TABS) focusTabs(0);
                else focusPicks(picksRow.anchor());
            } else {
                continueShelfPinned = false;
                focusDock(dockIdx);
            }
            root.post(() -> openRemotePanel(panel));
        }
        scheduleHomeShelfRotation();
    }

    private boolean isDrawerGesture(String panel) {
        return RemoteKeys.APP_DRAWER_GESTURE.equals(panel) && RemoteKeys.doubleHomeDrawer(this);
    }

    private void openRemotePanel(String panel) {
        if (RemoteKeys.INPUTS.equals(panel)) openInputs();
        else if (RemoteKeys.RECENTS.equals(panel)) openRecents();
        else if (isDrawerGesture(panel)) {
            openAppDrawer();
            if (!prefs.getBoolean(RemoteKeys.DRAWER_GESTURE_USED, false)) {
                prefs.edit().putBoolean(RemoteKeys.DRAWER_GESTURE_USED, true).apply();
            }
        } else if (RemoteKeys.APP_DRAWER.equals(panel)) openAppDrawer();
    }

    private static final String SHOW_OTHER_DRAWER_APPS = "drawer_show_other_apps";

    private void openAppDrawer() {
        beginSettingsMenu(P_CONTROL, "All apps");
        appDrawer = true;
        settingsGrid = true;
        settingsColumns = 7;
        menuClosed = () -> focusDock(dockIdx);

        List<AppEntry> entertainment = new ArrayList<>();
        List<AppEntry> other = new ArrayList<>();
        for (AppEntry app : apps) {
            (isEntertainmentApp(app) ? entertainment : other).add(app);
        }
        boolean showOther = prefs.getBoolean(SHOW_OTHER_DRAWER_APPS, true);
        addDrawerSection("Entertainment", entertainment);
        if (showOther) addDrawerSection("Other apps", other);
        if (menuItems.isEmpty()) recommendationText(showOther ? "No apps to show" : "No entertainment apps to show", 15, SettingsPopup.TEXT);
        showMenu(824);
    }

    private boolean isEntertainmentApp(AppEntry app) {
        if (app.isSonyLiv() || Trending.isStreamingApp(app.pkg)) return true;
        switch (app.pkg) {
            case "com.formulaone.production":
            case "com.spotify.tv.android":
            case "com.spotify.music":
                return true;
        }
        try {
            int category = pm.getApplicationInfo(app.pkg, 0).category;
            return category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO
                    || category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
                    || category == android.content.pm.ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException removed) {
            return false;
        }
    }

    private void addDrawerSection(String title, List<AppEntry> entries) {
        if (entries.isEmpty()) return;
        entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(13);
        heading.setTextColor(SettingsPopup.MUTED);
        heading.setTypeface(getResources().getFont(R.font.outfit));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setPadding(px(4), px(6), 0, px(5));
        menu.addView(heading);
        for (int first = 0; first < entries.size(); first += settingsColumns) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, px(92));
            if (first > 0) rowParams.topMargin = px(9);
            menu.addView(row, rowParams);
            for (int col = 0; col < settingsColumns; col++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) cell.setMarginStart(px(9));
                int index = first + col;
                if (index >= entries.size()) {
                    row.addView(new View(this), cell);
                    continue;
                }
                AppEntry app = entries.get(index);
                View tile = appDrawerTile(app);
                row.addView(tile, cell);
                menuItems.add(tile);
                menuKeepsOpen.add(false);
                menuActions.add(() -> {
                    start(app.launchIntent());
                    RecentApps.note(this, app.pkg);
                });
            }
        }
    }

    private void moveAppDrawer(boolean left, boolean right, boolean up, boolean down) {
        if (menuItems.isEmpty()) return;
        View selected = menuItems.get(menuIdx);
        LinearLayout row = (LinearLayout) selected.getParent();
        int col = row.indexOfChild(selected);
        View next = null;
        if (left || right) {
            next = row.getChildAt(col + (left ? -1 : 1));
        } else if (up || down) {
            int step = up ? -1 : 1;
            for (int i = menu.indexOfChild(row) + step; i >= 0 && i < menu.getChildCount(); i += step) {
                if (!(menu.getChildAt(i) instanceof LinearLayout)) continue;
                LinearLayout adjacent = (LinearLayout) menu.getChildAt(i);
                for (int candidate = Math.min(col, adjacent.getChildCount() - 1); candidate >= 0; candidate--) {
                    View tile = adjacent.getChildAt(candidate);
                    if (menuItems.contains(tile)) {
                        next = tile;
                        break;
                    }
                }
                break;
            }
        }
        int nextIndex = menuItems.indexOf(next);
        if (nextIndex >= 0) focusMenu(nextIndex);
    }

    private View appDrawerTile(AppEntry app) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(px(6), px(6), px(6), px(6));

        ImageView icon = new ImageView(this);
        icon.setId(R.id.drawer_app_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(app.pkg));
        } catch (PackageManager.NameNotFoundException removed) {
            icon.setImageDrawable(pm.getDefaultActivityIcon());
        }
        tile.addView(icon, new LinearLayout.LayoutParams(px(48), px(48)));

        TextView name = new TextView(this);
        name.setId(R.id.input_label);
        name.setText(app.label);
        name.setTextSize(12);
        name.setTypeface(getResources().getFont(R.font.outfit));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-1, -2);
        label.topMargin = px(5);
        tile.addView(name, label);
        tile.setContentDescription(app.label);
        return tile;
    }
}
