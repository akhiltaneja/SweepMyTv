package com.sweepmytv.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android TV's "Watch Next" list: what apps like Netflix and Prime Video say you're in the middle of, with a link
 * that opens the right title or episode. Readable with the TV listings permissions on this TV. Only "continue" and
 * "next episode" entries are used; "new season" and watchlist entries are promotions, not things you watched.
 * Blocking (database + artwork downloads): call it off the main thread.
 * Episodes you haven't started (the "next episode" entries) don't show the app's still from them, which can give the
 * story away (Sweep TV settings → Hide spoilers): the app's poster when it's landscape, else the still blurred, with
 * a portrait poster on the small cards. An idea from harrydbarnes (discussion #14).
 */
final class WatchNext {
    private static final String TAG = "GlassWatchNext";
    private static final int MAX = 16;

    private WatchNext() { }

    static List<Trending.Pick> load(Context c) {
        List<Trending.Pick> out = new ArrayList<>();
        File dir = new File(c.getExternalFilesDir(null), "recent/watchnext");
        boolean hideSpoilers = hideSpoilers(c);
        Set<String> keep = new HashSet<>();
        boolean applePublished=false,allowCache=true,complete=false;
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, TvContract.WatchNextPrograms.COLUMN_TYPE,
                TvContract.WatchNextPrograms.COLUMN_CANONICAL_GENRE, TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
        };
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if(cur==null){out=AppleResumeCache.reconcile(c,out,false,System.currentTimeMillis());TitleArtwork.resolveRecent(c,out);return out;}
            Log.i(TAG, cur.getCount() + " rows");
            List<Integer> selected = new ArrayList<>(MAX);
            List<Integer> extra = new ArrayList<>(MAX);
            Set<String> seen = new HashSet<>();
            while (cur.moveToNext() && selected.size() < MAX) {
                String pkg = cur.getString(0);
                if (AppleResumeCache.PACKAGE.equals(pkg)) applePublished = true;
                int type = cur.isNull(5) ? -1 : cur.getInt(5);
                boolean browsable = cur.isNull(11) || cur.getInt(11) != 0;
                if (type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue;
                if (!browsable || pkg == null || str(cur, 1).isEmpty() || !ContinueOptions.include(c, pkg)
                        || c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) == null) continue;

                // Reserve each app's latest entry before filling spare slots with other recent titles.
                if (seen.add(pkg)) selected.add(cur.getPosition());
                else if (extra.size() < MAX) extra.add(cur.getPosition());
            }
            selected.addAll(extra.subList(0, Math.min(MAX - selected.size(), extra.size())));
            Collections.sort(selected);
            for (int positionInCursor : selected) {
                if (!cur.moveToPosition(positionInCursor)) continue;
                String pkg = cur.getString(0);
                int type = cur.getInt(5);
                String title = str(cur, 1), episode = str(cur, 2), season = str(cur, 3), number = str(cur, 4);
                // The line under the card: "Season 2 · Episode 1 · Prime Video" (or the episode's name).
                StringBuilder m = new StringBuilder();
                if (!season.isEmpty()) m.append("Season ").append(season);
                if (!number.isEmpty()) m.append(m.length() > 0 ? " · " : "").append("Episode ").append(number);
                if (m.length() == 0 && !episode.isEmpty() && !episode.equals(title)) m.append(episode);
                String t = title;

                Trending.Pick p = new Trending.Pick();
                p.id = (pkg + "\n" + t + "\n" + m).hashCode();
                p.kind = "watchnext";
                p.tab = RecentStore.TAB;
                p.pkg = pkg;
                p.title = t;
                p.service = label(c, pkg);
                p.meta = (m.length() > 0 ? m + " · " : "") + p.service;
                long position = cur.isNull(7) ? 0 : cur.getLong(7), duration = cur.isNull(12) ? 0 : cur.getLong(12);
                if (position > 0 && duration > 0) p.progress = Math.min(1f, position / (float) duration);
                p.at = cur.isNull(6) ? 0 : cur.getLong(6);
                p.intentUri = str(cur, 10).isEmpty() ? null : str(cur, 10);
                String art = !str(cur, 8).isEmpty() ? str(cur, 8) : str(cur, 9);
                int contentType = cur.isNull(13) ? -1 : cur.getInt(13);
                p.mediaKind = mediaKind(contentType);
                p.providerGenres = genreLabels(str(cur, 14));
                p.releaseDate = str(cur, 15);
                if (ContinueOptions.titleArtwork(c)) {
                    boolean titleLevel=contentType==TvContract.WatchNextPrograms.TYPE_MOVIE||contentType==TvContract.WatchNextPrograms.TYPE_TV_SERIES||contentType==TvContract.WatchNextPrograms.TYPE_TV_SEASON;
                    TitleArtwork.apply(c,p,str(cur,9),str(cur,8),titleLevel);
                    if(p.poster!=null)keep.add(p.poster.getName());
                } else if (hideSpoilers && type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) {
                    String posterArt = str(cur, 9);
                    File poster = posterArt.isEmpty() || posterArt.equals(str(cur, 8)) ? null : cached(c, dir, posterArt);
                    if (poster != null && landscape(poster)) {
                        p.image = poster;
                    } else {
                        File still = art.isEmpty() ? null : cached(c, dir, art);
                        p.image = still == null ? null : blurred(still);
                        if (still != null) keep.add(still.getName());  // kept, so the blur isn't redone each time
                        if (ArtworkAssets.portrait(poster)) p.poster = poster;
                    }
                } else {
                    p.image = art.isEmpty() ? null : cached(c, dir, art);
                    String posterUri = str(cur, 9);
                    File poster = posterUri.isEmpty() ? null : posterUri.equals(art) ? p.image : cached(c, dir, posterUri);
                    if (ArtworkAssets.portrait(poster)) p.poster = poster;
                }
                if (p.image != null) keep.add(p.image.getName());
                out.add(p);
            }
            complete=true;
        } catch (SecurityException e) {
            allowCache=false;
            Log.w(TAG, "No permission to read Watch Next");
        } catch (Exception e) {
            Log.w(TAG, "Could not read Watch Next: " + e.getClass().getSimpleName());
        }
        if(allowCache)out=AppleResumeCache.reconcile(c,out,applePublished,System.currentTimeMillis());
        TitleArtwork.resolveRecent(c,out);
        if(complete)AppleResumeCache.saveHome(c,out,System.currentTimeMillis());
        for(Trending.Pick p:out){if(p.image!=null)keep.add(p.image.getName());if(p.poster!=null)keep.add(p.poster.getName());}
        if (!ContinueOptions.include(c, AppleResumeCache.PACKAGE)) {
            for (Trending.Pick cached : AppleResumeCache.restore(c, System.currentTimeMillis())) {
                if (cached.image != null) keep.add(cached.image.getName());
                if (cached.poster != null) keep.add(cached.poster.getName());
            }
        }
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
        Log.i(TAG, out.size() + " to show");
        return out;
    }

    private static String mediaKind(int type) {
        switch (type) {
            case TvContract.WatchNextPrograms.TYPE_MOVIE:
                return "movie";
            case TvContract.WatchNextPrograms.TYPE_TV_SERIES:
            case TvContract.WatchNextPrograms.TYPE_TV_SEASON:
            case TvContract.WatchNextPrograms.TYPE_TV_EPISODE:
                return "tv";
            default:
                return "";
        }
    }

    static String genreLabels(String encoded) {
        Set<String> labels = new java.util.LinkedHashSet<>();
        for (String genre : TvContract.Programs.Genres.decode(encoded)) {
            String label;
            switch (genre) {
                case "COMEDY": label = "Comedy"; break;
                case "DRAMA": label = "Drama"; break;
                case "DOCUMENTARY": label = "Documentary"; break;
                case "ACTION": label = "Action"; break;
                case "THRILLER": label = "Thriller"; break;
                case "HORROR": label = "Horror"; break;
                case "FANTASY": label = "Fantasy"; break;
                case "SCI_FI": label = "Sci-Fi"; break;
                case "FAMILY_KIDS": label = "Family"; break;
                case "MUSIC": label = "Music"; break;
                case "SPORTS": label = "Sports"; break;
                default: continue;
            }
            labels.add(label);
            if (labels.size() == 2) break;
        }
        return String.join(" / ", labels);
    }

    /** Sweep TV settings → Hide spoilers (off unless you turn it on: the app's own stills are what people expect). */
    static boolean hideSpoilers(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("hide_spoilers", false);
    }

    private static boolean landscape(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0 && o.outWidth > o.outHeight;
    }

    /** A soft blur of the picture (shrunk to 32 px wide and grown back in steps), saved next to it once. */
    private static File blurred(File src) {
        File out = new File(src.getParentFile(), src.getName().replaceAll("\\.(jpg|png)$", "") + "_blur.jpg");
        if (out.isFile()) return out;
        try {
            Bitmap b = BitmapFactory.decodeFile(src.getPath());
            if (b == null) return null;
            int w = 32, h = Math.max(1, b.getHeight() * w / b.getWidth());
            Bitmap big = Bitmap.createScaledBitmap(b, w, h, true);
            for (int k = 2; w * k <= 640; k *= 2) big = Bitmap.createScaledBitmap(big, w * k, h * k, true);  // doubling: no blocks
            File tmp = new File(out.getPath() + ".part");
            try (OutputStream o = new FileOutputStream(tmp)) { big.compress(Bitmap.CompressFormat.JPEG, 85, o); }
            return tmp.renameTo(out) ? out : null;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Blur failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String str(Cursor c, int i) {
        String s = c.isNull(i) ? null : c.getString(i);
        return s == null ? "" : s.trim();
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Up to 1280 px: these fill the large card (about 1040 px on screen). */
    private static File cached(Context c, File dir, String uri) { return cached(c, dir, uri, 1280); }

    /**
     * The artwork as a small JPEG on disk (at most `width` px wide), downloaded once per address; a PNG instead when it
     * has transparency (team logos), which JPEG would fill with black. Also used for channels and match cards.
     */
    static File cached(Context c, File dir, String uri, int width) {
        String base = Integer.toHexString(uri.hashCode()) + "_" + width;
        File f = new File(dir, base + ".jpg"), png = new File(dir, base + ".png");
        if (f.isFile()) return f;
        if (png.isFile()) return png;
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        try (InputStream in = open(c.getContentResolver(), uri)) {
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return null;
            if (b.getWidth() > width) b = Bitmap.createScaledBitmap(b, width, Math.max(1, b.getHeight() * width / b.getWidth()), true);
            boolean alpha = b.hasAlpha();
            File done = alpha ? png : f, tmp = new File(dir, done.getName() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, out);
            }
            return tmp.renameTo(done) ? done : null;
        } catch (Exception e) {
            Log.w(TAG, "Artwork failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static InputStream open(ContentResolver cr, String uri) throws Exception {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            HttpURLConnection h = (HttpURLConnection) new URL(uri).openConnection();
            h.setConnectTimeout(15_000);
            h.setReadTimeout(20_000);
            return h.getInputStream();
        }
        return cr.openInputStream(Uri.parse(uri));
    }
}
