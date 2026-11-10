package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PerformanceTest {
    private File image(int width,int height) throws Exception {
        File file=File.createTempFile("display-size-",".png",RuntimeEnvironment.getApplication().getCacheDir());
        Bitmap source=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,out));}source.recycle();return file;
    }
    @Test public void largePhotosDecodeAtDisplaySizeWithoutLosingCropCoverage() throws Exception {
        Bitmap scaled=CardArtwork.decode(image(3840,2160),880,372,true,false);
        assertEquals(880,scaled.getWidth());assertEquals(495,scaled.getHeight());
        assertTrue(scaled.getAllocationByteCount()<2_000_000);
        Bitmap fit=CardArtwork.decode(image(3840,2160),280,420,false,true);
        assertEquals(280,fit.getWidth());assertEquals(158,fit.getHeight());
    }
    @Test public void smallSourcesAreNeverEnlargedAndCorruptFilesFailClosed() throws Exception {
        Bitmap bitmap=CardArtwork.decode(image(120,80),880,372,true,false);assertEquals(120,bitmap.getWidth());assertEquals(80,bitmap.getHeight());
        File bad=File.createTempFile("not-artwork-",".jpg",RuntimeEnvironment.getApplication().getCacheDir());assertNull(CardArtwork.decode(bad,280,420,true,false));
    }
    @Test public void f1WorkerPreparesRealAssetsAtTheirDisplaySizes() throws Exception {
        Context app=RuntimeEnvironment.getApplication();FormulaOne.Card card=new FormulaOne.Card();card.key="next";card.title="Mexico City Grand Prix";
        FormulaOne.Line driver=new FormulaOne.Line("1","Lando Norris","25","McLaren",0xFFFF8800);driver.portrait="driver-norris";card.table.add(driver);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            F1Assets.Prepared ready=worker.submit(()->{assertNotEquals(Looper.getMainLooper(),Looper.myLooper());return F1Assets.prepare(app,card,2f);}).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(ready.circuit);assertTrue(Math.max(ready.circuit.getWidth(),ready.circuit.getHeight())<=440);
            Bitmap avatar=ready.images.get("driver-norris");assertNotNull(avatar);assertTrue(Math.max(avatar.getWidth(),avatar.getHeight())<=320);assertTrue("Portrait face crop covers the 76px avatar",avatar.getWidth()*.76f>=76);
            card.key="drivers";F1Assets.Prepared hero=worker.submit(()->F1Assets.prepare(app,card,2f)).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(Math.max(hero.images.get(driver.portrait).getWidth(),hero.images.get(driver.portrait).getHeight())<=512);assertTrue("Leader crop retains native display coverage",hero.images.get(driver.portrait).getWidth()>=170);
            assertNull(F1Assets.image(app,"../outside",128));
        } finally {worker.shutdownNow();}
    }
    @Test public void f1DrawingNeverLoadsAssetsAndDetachedResultsCannotRebindViews() throws Exception {
        android.app.Activity activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        FormulaOne.Card card=new FormulaOne.Card();card.key="next";card.title="Mexico City Grand Prix";FormulaOne.Line driver=new FormulaOne.Line("1","Lando Norris","25","McLaren",0xFFFF8800);driver.portrait="driver-norris";card.table.add(driver);
        java.util.List<java.util.function.Consumer<F1Assets.Prepared>> callbacks=new java.util.ArrayList<>();
        FormulaOneCard view=new FormulaOneCard(activity,card,(context,data,density,done)->{callbacks.add(done);return new java.util.concurrent.CompletableFuture<>();});
        FrameLayout parent=new FrameLayout(activity);activity.setContentView(parent);parent.addView(view);view.layout(0,0,560,612);assertEquals(1,callbacks.size());
        java.lang.reflect.Field cacheField=F1Assets.class.getDeclaredField("CACHE");cacheField.setAccessible(true);android.util.LruCache<?,?> cache=(android.util.LruCache<?,?>)cacheField.get(null);int misses=cache.missCount();
        Bitmap target=Bitmap.createBitmap(560,612,Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(target);
        for(int i=0;i<5;i++)view.draw(canvas);assertEquals(misses,cache.missCount());
        Bitmap image=Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888);F1Assets.Prepared ready=new F1Assets.Prepared(java.util.Collections.singletonMap(driver.portrait,image),null);
        callbacks.get(0).accept(ready);java.lang.reflect.Field pictures=FormulaOneCard.class.getDeclaredField("pictures");pictures.setAccessible(true);assertEquals(1,((java.util.Map<?,?>)pictures.get(view)).size());
        parent.removeView(view);callbacks.get(0).accept(ready);assertTrue(((java.util.Map<?,?>)pictures.get(view)).isEmpty());
        parent.addView(view);assertEquals(2,callbacks.size());callbacks.get(0).accept(ready);assertTrue(((java.util.Map<?,?>)pictures.get(view)).isEmpty());callbacks.get(1).accept(ready);assertEquals(1,((java.util.Map<?,?>)pictures.get(view)).size());
        parent.removeView(view);activity.finish();target.recycle();image.recycle();
    }

    static class Parent extends FrameLayout {
        int requests;
        Parent(Context c){super(c);}
        @Override public void requestLayout(){requests++;super.requestLayout();}
    }
    @Test public void cardSpringKeepsItsLayoutWorkInsideThePicksRow() {
        android.app.Activity activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        Context app=activity;Parent parent=new Parent(app);PicksRow row=new PicksRow(app,null);parent.addView(row);activity.setContentView(parent);
        row.setItems(Arrays.asList(new View(app),new View(app),new View(app)),0);
        int w=View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),h=View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY);
        parent.measure(w,h);parent.layout(0,0,1920,1080);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));parent.requests=0;
        row.setAnchor(1,true);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
        assertEquals(0,parent.requests);assertEquals(1,row.anchor());
        assertTrue(row.getChildAt(0).getWidth()<880);assertTrue(row.getChildAt(1).getWidth()>268);
        // A rebuild during a spring cancels the old callback; the new children have correct initial bounds.
        row.setItems(Arrays.asList(new View(app)),0);parent.measure(w,h);parent.layout(0,0,1920,1080);
        assertEquals(880,row.getChildAt(0).getWidth());
    }
}
