package com.sweepmytv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Display-sized artwork, decoded independently of network/provider work and reused while navigating. */
final class CardArtwork implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    // Visible cards should not compete with hundreds of hidden images at boot's background priority.
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            queue, task -> new Thread(task, "card-artwork"));
    private static final long DISK_BYTES = 32L * 1024 * 1024;
    private final Map<ImageView, Request> requests = new java.util.LinkedHashMap<>();
    private final Map<View, ViewTreeObserver.OnPreDrawListener> roots = new IdentityHashMap<>();
    private final Rect visibleBounds = new Rect();
    private File directory;
    private final long startedAt = android.os.SystemClock.elapsedRealtime();
    private boolean firstImageShown;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private volatile boolean closed;

    void bind(ImageView view, File file, int width, int height, boolean crop, boolean alpha) {
        bind(view, file, width, height, crop, alpha, false);
    }

    void bind(ImageView view, File file, int width, int height, boolean crop, boolean alpha, boolean urgent) {
        String key = file.getAbsolutePath() + ':' + file.lastModified() + ':' + file.length()
                + '@' + width + 'x' + height + ':' + crop + ':' + alpha;
        Request previous = requests.get(view);
        if (previous != null && key.equals(previous.key)) return;
        if (previous != null) remove(previous);
        view.setTag(R.id.img_state, key);
        if (closed) return;
        if (directory == null) directory = new File(view.getContext().getCacheDir(), "card-artwork-v1");
        Request request = new Request(view, file, key, width, height, crop, alpha, urgent);
        requests.put(view, request);
        view.addOnAttachStateChangeListener(request);
        if (view.isAttachedToWindow()) {
            observe(view.getRootView());
            refreshVisible();
        }
    }

    private void observe(View root) {
        if (roots.containsKey(root)) return;
        ViewTreeObserver.OnPreDrawListener listener = () -> { refreshVisible(); return true; };
        root.getViewTreeObserver().addOnPreDrawListener(listener);
        roots.put(root, listener);
    }

    /** Called on layout/scroll frames; no file reads or decoding happen on this thread. */
    void refreshVisible() {
        if (closed) return;
        for (Request request : requests.values()) {
            ImageView view = request.view;
            boolean visible = view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE
                    && view.getAlpha() > .01f && view.getGlobalVisibleRect(visibleBounds);
            request.visible = visible;
            if (!visible) {
                cancel(request);
                if (request.loaded) { view.setImageDrawable(null); request.loaded = false; }
            } else if (!request.loaded && request.task == null) {
                Bitmap ready = cache.get(request.key);
                if (ready != null) { view.setImageBitmap(ready); request.loaded = true; }
                else {
                    Task task = new Task(request);
                    request.task = task;
                    if (request.urgent && worker.getPoolSize() > 0) queue.offerFirst(task);
                    else worker.execute(task);
                }
            }
        }
    }

    private final class Request implements View.OnAttachStateChangeListener {
        final ImageView view;
        final File file;
        final String key;
        final int width, height;
        final boolean crop, alpha, urgent;
        boolean visible, loaded;
        Task task;
        Request(ImageView view, File file, String key, int width, int height, boolean crop, boolean alpha, boolean urgent) {
            this.view=view; this.file=file; this.key=key; this.width=width; this.height=height;
            this.crop=crop; this.alpha=alpha; this.urgent=urgent;
        }
        @Override public void onViewAttachedToWindow(View view) {
            observe(view.getRootView());
            view.postInvalidateOnAnimation();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            visible=false;
            cancel(this);
            this.view.setImageDrawable(null);
            loaded=false;
            unobserveUnused();
        }
    }

    private final class Task implements Runnable {
        final Request request;
        volatile boolean cancelled;
        Task(Request request) { this.request=request; }
        @Override public void run() {
            if (closed || cancelled) return;
            Bitmap bitmap = cache.get(request.key);
            if (bitmap == null) {
                bitmap = thumbnail(directory, request.key, request.file, request.width, request.height,
                        request.crop, request.alpha);
                if (bitmap != null && !closed) cache.put(request.key, bitmap);
            }
            Bitmap result=bitmap;
            main.post(() -> {
                if (!closed && !cancelled && requests.get(request.view) == request && request.task == this) {
                    request.task=null;
                    // A failed decode is retried on the next bind/file change, not on every frame.
                    request.loaded=true;
                    if (request.visible && result != null) {
                        request.view.setImageBitmap(result);
                        if (!firstImageShown) {
                            firstImageShown=true;
                            android.util.Log.i("SweepArtwork", "First visible thumbnail ready in "
                                    + (android.os.SystemClock.elapsedRealtime()-startedAt) + " ms");
                        }
                    }
                }
            });
        }
    }

    private void cancel(Request request) {
        if (request.task != null) {
            request.task.cancelled=true;
            worker.remove(request.task);
            request.task=null;
        }
    }

    private void remove(Request request) {
        cancel(request);
        request.view.removeOnAttachStateChangeListener(request);
        requests.remove(request.view);
        unobserveUnused();
    }

    private void unobserveUnused() {
        roots.entrySet().removeIf(entry -> {
            for (Request request : requests.values()) {
                if (request.view.isAttachedToWindow() && request.view.getRootView() == entry.getKey()) return false;
            }
            ViewTreeObserver observer=entry.getKey().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(entry.getValue());
            return true;
        });
    }

    /** Encoded display-size copies survive a process restart; original artwork stays untouched. */
    static Bitmap thumbnail(File directory, String key, File source, int width, int height, boolean crop, boolean alpha) {
        File saved=new File(directory, ArtworkAssets.hash(key)+".img");
        if (saved.length() > 0 && saved.length() <= 4L * 1024 * 1024) {
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inPreferredConfig=alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(saved.getPath(), options);
            if (options.outWidth > 0 && options.outHeight > 0
                    && (long)options.outWidth * options.outHeight <= 4_000_000L) {
                options.inJustDecodeBounds=false;
                Bitmap ready=BitmapFactory.decodeFile(saved.getPath(),options);
                if (ready != null) { saved.setLastModified(System.currentTimeMillis()); return ready; }
            }
        }
        Bitmap bitmap=decode(source,width,height,crop,alpha);
        if (bitmap == null) return null;
        android.util.AtomicFile file=new android.util.AtomicFile(saved);
        FileOutputStream output=null;
        try {
            output=file.startWrite();
            if (!bitmap.compress(bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, output))
                throw new java.io.IOException("Thumbnail encoding failed");
            file.finishWrite(output);
            prune(directory);
        } catch (Exception unavailable) { if (output != null) file.failWrite(output); }
        return bitmap;
    }

    private static void prune(File directory) {
        File[] files=directory.listFiles((root,name)->name.matches("[a-f0-9]{64}\\.img"));
        if (files == null) return;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        long bytes=0;
        for (int i=0;i<files.length;i++) {
            bytes+=files[i].length();
            if (i>=192 || bytes>DISK_BYTES) files[i].delete();
        }
    }

    static Bitmap decode(File file, int width, int height, boolean crop, boolean alpha) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sourceWidth = options.outWidth, sourceHeight = options.outHeight;
        width = Math.max(1, width); height = Math.max(1, height);
        float scale = Math.min(1f, crop ? Math.max(width / (float) options.outWidth, height / (float) options.outHeight)
                : Math.min(width / (float) options.outWidth, height / (float) options.outHeight));
        options.inSampleSize = 1;
        while (options.inSampleSize <= 128 && 1f / (options.inSampleSize * 2) >= scale) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return null;
        // Exact display size after inexpensive subsampling. Never enlarge the source or sharpen it.
        int w = Math.max(1, Math.round(sourceWidth * scale));
        int h = Math.max(1, Math.round(sourceHeight * scale));
        if (bitmap.getWidth() != w || bitmap.getHeight() != h) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        return bitmap;
    }

    void clear(View view) {
        if (view instanceof ImageView) {
            Request request=requests.get(view);
            if (request != null) remove(request);
            view.setTag(R.id.img_state,null);
            ((ImageView)view).setImageDrawable(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)clear(group.getChildAt(i));
        }
    }

    @Override public void close() {
        closed = true;
        for (Request request : new ArrayList<>(requests.values())) remove(request);
        for (Map.Entry<View, ViewTreeObserver.OnPreDrawListener> entry : roots.entrySet()) {
            ViewTreeObserver observer=entry.getKey().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(entry.getValue());
        }
        roots.clear(); worker.shutdownNow(); cache.evictAll();
    }
}
