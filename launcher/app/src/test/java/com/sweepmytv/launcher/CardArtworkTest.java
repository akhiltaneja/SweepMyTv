package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CardArtworkTest {
    private File image(int color) throws Exception {
        File file=File.createTempFile("card-art-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        Bitmap bitmap=Bitmap.createBitmap(1000,1500,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        bitmap.recycle();
        return file;
    }
    private File directory() throws Exception {
        return java.nio.file.Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(),"thumbnails-").toFile();
    }
    private ExecutorService worker(CardArtwork images) throws Exception {
        var field=CardArtwork.class.getDeclaredField("worker");field.setAccessible(true);
        return (ExecutorService)field.get(images);
    }
    private void flush(CardArtwork images) throws Exception {
        worker(images).submit(()->{}).get(10,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void displaySizeCopySurvivesOriginalUnavailabilityAndNewArtUsesANewKey() throws Exception {
        File directory=directory(),red=image(Color.RED);
        Bitmap first=CardArtwork.thumbnail(directory,"original-red",red,200,300,false,true);
        assertEquals(200,first.getWidth());assertEquals(300,first.getHeight());first.recycle();
        assertTrue(red.delete());
        Bitmap restored=CardArtwork.thumbnail(directory,"original-red",red,200,300,false,true);
        assertNotNull(restored);assertEquals(Color.RED,restored.getPixel(20,20));restored.recycle();
        Bitmap changed=CardArtwork.thumbnail(directory,"updated-blue",image(Color.BLUE),200,300,false,true);
        assertEquals(Color.BLUE,changed.getPixel(20,20));changed.recycle();
    }
    @Test public void corruptPreviewFallsBackAndTheCacheStaysBoundedWithoutTouchingOriginals() throws Exception {
        File directory=directory(),source=image(Color.GREEN);
        File corrupt=new File(directory,ArtworkAssets.hash("current")+".img");
        java.nio.file.Files.write(corrupt.toPath(),"incomplete image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<200;i++) {
            File old=new File(directory,ArtworkAssets.hash("old-"+i)+".img");
            try(RandomAccessFile file=new RandomAccessFile(old,"rw")){file.setLength(i<10?4L*1024*1024:32);}
            old.setLastModified(1000+i);
        }
        File unrelated=new File(directory,"notes.txt");java.nio.file.Files.write(unrelated.toPath(),"preserve".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Bitmap ready=CardArtwork.thumbnail(directory,"current",source,200,300,false,true);
        assertEquals(Color.GREEN,ready.getPixel(20,20));ready.recycle();
        File[] previews=directory.listFiles((root,name)->name.endsWith(".img"));
        long bytes=0;for(File file:previews)bytes+=file.length();
        assertTrue(previews.length<=192);assertTrue(bytes<=32L*1024*1024);
        assertTrue(source.isFile());assertEquals("preserve",new String(java.nio.file.Files.readAllBytes(unrelated.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void hiddenCardsWaitUntilVisibleAndReleaseTheirDecodedImageWhenScrolledAway() throws Exception {
        var controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity=controller.get();FrameLayout root=new FrameLayout(activity);activity.setContentView(root);
        ImageView first=new ImageView(activity),second=new ImageView(activity);
        root.addView(first,new FrameLayout.LayoutParams(200,300));root.addView(second,new FrameLayout.LayoutParams(200,300));
        second.setTranslationY(3000);
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(960,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(540,1073741824));
        root.layout(0,0,960,540);
        assertTrue(first.getGlobalVisibleRect(new Rect()));assertFalse(second.getGlobalVisibleRect(new Rect()));
        try(CardArtwork images=new CardArtwork()) {
            images.bind(first,image(Color.RED),200,300,false,true,true);
            images.bind(second,image(Color.BLUE),200,300,false,true);
            images.refreshVisible();flush(images);
            assertNotNull(first.getDrawable());assertNull(second.getDrawable());
            first.setTranslationY(3000);second.setTranslationY(0);
            images.refreshVisible();flush(images);
            assertNull(first.getDrawable());assertNotNull(second.getDrawable());
            assertEquals(Color.BLUE,((BitmapDrawable)second.getDrawable()).getBitmap().getPixel(20,20));
            images.clear(root);assertNull(second.getDrawable());
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void replacementAndClearPreventOldWorkFromRebindingTheCard() throws Exception {
        var controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity=controller.get();ImageView view=new ImageView(activity);activity.setContentView(view);
        view.layout(0,0,200,300);
        CountDownLatch release=new CountDownLatch(1);
        try(CardArtwork images=new CardArtwork()) {
            worker(images).submit(()->{try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            images.bind(view,image(Color.RED),200,300,false,true);
            images.bind(view,image(Color.BLUE),200,300,false,true);
            release.countDown();images.refreshVisible();flush(images);
            assertEquals(Color.BLUE,((BitmapDrawable)view.getDrawable()).getBitmap().getPixel(20,20));
            images.clear(view);flush(images);assertNull(view.getDrawable());assertNull(view.getTag(R.id.img_state));
        } finally {release.countDown();controller.pause().stop().destroy();}
    }
}
