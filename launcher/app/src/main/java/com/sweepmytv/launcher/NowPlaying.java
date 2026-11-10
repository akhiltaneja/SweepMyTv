package com.sweepmytv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/**
 * Control Center → Now playing: the song (or show) playing on the TV, with play, pause and skip. It reads the same
 * media sessions as Continue, so it needs no access beyond the notification access Talkies already has. An idea from
 * Luma (MIT).
 */
final class NowPlaying {
    private NowPlaying() { }

    /** What's playing now, else the most recent paused session with a title; null when nothing (or no access). */
    static MediaController current(Context c) {
        List<MediaController> list;
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            list = m == null ? null : m.getActiveSessions(new ComponentName(c, WatchService.class));
        } catch (SecurityException e) {
            return null;  // notification access is off
        }
        if (list == null) return null;
        for (MediaController s : list) if (ours(c, s) && playing(s) && !title(s).isEmpty()) return s;
        for (MediaController s : list) if (ours(c, s) && !title(s).isEmpty()) return s;
        return null;
    }

    private static boolean ours(Context c, MediaController s) { return !s.getPackageName().equals(c.getPackageName()); }

    static boolean playing(MediaController s) {
        PlaybackState p = s.getPlaybackState();
        return p != null && (p.getState() == PlaybackState.STATE_PLAYING || p.getState() == PlaybackState.STATE_BUFFERING);
    }

    static String title(MediaController s) {
        return WatchService.text(s.getMetadata(), MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
    }

    /** The artist, else the album or whatever line the app gives. */
    static String artist(MediaController s) {
        return WatchService.text(s.getMetadata(), MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM);
    }

    static android.graphics.Bitmap art(MediaController s) {
        MediaMetadata m = s.getMetadata();
        return m == null ? null : WatchService.art(m);
    }

    /** True when the app can skip; most music apps can, many video apps can't. */
    static boolean can(MediaController s, long action) {
        PlaybackState p = s.getPlaybackState();
        return p != null && (p.getActions() & action) != 0;
    }
}
