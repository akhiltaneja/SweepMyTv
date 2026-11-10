package com.sweepmytv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Display-sized artwork, decoded independently of network/provider work and reused while navigating. */
final class CardArtwork implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "card-artwork"));
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private volatile boolean closed;

    void bind(ImageView view, File file, int width, int height, boolean crop, boolean alpha) {
        String key = file.getAbsolutePath() + ':' + file.lastModified() + ':' + file.length()
                + '@' + width + 'x' + height + ':' + crop + ':' + alpha;
        if (key.equals(view.getTag(R.id.img_state))) return;
        view.setTag(R.id.img_state, key);
        Bitmap ready = cache.get(key);
        if (ready != null) { view.setImageBitmap(ready); return; }
        if (closed) return;
        worker.execute(() -> {
            // A rapid direction change or a rebuilt row made this request obsolete.
            if (closed || !key.equals(view.getTag(R.id.img_state))) return;
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) {
                bitmap = decode(file, width, height, crop, alpha);
                if (bitmap != null && !closed) cache.put(key, bitmap);
            }
            Bitmap result = bitmap;
            main.post(() -> {
                if (!closed && result != null && key.equals(view.getTag(R.id.img_state))) view.setImageBitmap(result);
            });
        });
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
            view.setTag(R.id.img_state,null);
            ((ImageView)view).setImageDrawable(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)clear(group.getChildAt(i));
        }
    }

    @Override public void close() { closed = true; worker.shutdownNow(); cache.evictAll(); }
}
