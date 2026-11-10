package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DisplayOptionsTest {
    @Test public void timeGradientsChangeAcrossDayAndWrapWithoutMidnightJump() {
        int[] night=TimeOfDay.colors(0),morning=TimeOfDay.colors(8*60),day=TimeOfDay.colors(12*60),evening=TimeOfDay.colors(17*60);
        assertFalse(java.util.Arrays.equals(night,morning));assertFalse(java.util.Arrays.equals(day,evening));
        assertArrayEquals(night,TimeOfDay.colors(1440));assertArrayEquals(TimeOfDay.colors(1439),TimeOfDay.colors(-1));
        for(int minute=0;minute<1440;minute++)for(int part=0;part<2;part++) {
            int a=TimeOfDay.colors(minute)[part],b=TimeOfDay.colors(minute+1)[part];
            assertEquals(255,a>>>24);
            for(int shift=0;shift<=16;shift+=8)assertTrue(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))<=8);
        }
    }
    @Test public void timeBackgroundIgnoresAppGlowUntilSwitchedBack() {
        Context app=RuntimeEnvironment.getApplication();Backdrop view=new Backdrop(app,null);view.layout(0,0,960,540);
        view.setTheme(false,0xFF121419,0xFF08090C,0,0,0,.3f);int[] colors=TimeOfDay.colors(720);
        view.setTimeColors(colors[0],colors[1]);view.setGlow(0xFFFF0000);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        Bitmap first=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(first));
        view.setGlow(0xFF00FF00);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        Bitmap second=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(second));
        assertEquals(first.getPixel(350,250),second.getPixel(350,250));
        view.clearTimeColors();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        second.eraseColor(0);view.draw(new android.graphics.Canvas(second));assertNotEquals(first.getPixel(350,250),second.getPixel(350,250));
    }
    @Test public void youtubeChoiceIsReversibleAndLimitedToYoutubePackages() {
        Context c=RuntimeEnvironment.getApplication();assertTrue(ContinueOptions.youtube(c));
        c.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.YOUTUBE,false).commit();
        for(String p:new String[]{"com.google.android.youtube.tv","com.google.android.youtube","com.amazon.firetv.youtube"})assertFalse(ContinueOptions.include(c,p));
        assertTrue(ContinueOptions.include(c,"com.netflix.ninja"));
        c.getSharedPreferences("home",0).edit().putBoolean(ContinueOptions.YOUTUBE,true).commit();
        assertTrue(ContinueOptions.include(c,"com.google.android.youtube.tv"));
    }
    @Test public void daylightIsWarmAndForegroundRemainsReadableAcrossTheWholeDay() {
        TimeOfDay.Palette noon=TimeOfDay.palette(720),night=TimeOfDay.palette(0),sunset=TimeOfDay.palette(1050);
        assertFalse(noon.light);assertFalse(night.light);assertTrue(TimeOfDay.luminance(noon.bottom)>TimeOfDay.luminance(night.bottom));
        assertTrue(android.graphics.Color.red(noon.bottom)>android.graphics.Color.blue(noon.bottom)+15);
        for(int minute:new int[]{12*60,14*60,15*60+30}) {
            TimeOfDay.Palette sun=TimeOfDay.palette(minute);
            for(int colour:new int[]{sun.top,sun.mid,sun.bottom,sun.accent})assertTrue("Sunlit hues at "+minute,android.graphics.Color.red(colour)>android.graphics.Color.blue(colour)+20);
        }
        assertNotEquals(noon.accent,night.accent);assertNotEquals(noon.accent,sunset.accent);
        for(int minute=0;minute<1440;minute++) {
            TimeOfDay.Palette p=TimeOfDay.palette(minute);
            for(int background:new int[]{p.top,p.mid,p.bottom}) {
                assertTrue("Sky stays dark at minute "+minute,TimeOfDay.luminance(background)<.13);
                assertTrue("Primary text at minute "+minute,TimeOfDay.contrast(p.text,background)>=4.5);
                assertTrue("Secondary text at minute "+minute,TimeOfDay.contrast(p.muted,background)>=4.5);
            }
        }
    }
    @Test public void exactArtworkMatchingRejectsPopularNearMatchesAndAmbiguousTitles() throws Exception {
        JSONArray rows=new JSONArray();
        rows.put(new JSONObject().put("id",1).put("media_type","movie").put("title","Modern Family Reunion"));
        JSONObject show=new JSONObject().put("id",2).put("media_type","tv").put("name","Modern Family");rows.put(show);
        assertSame(show,TitleArtwork.match(rows,"Modern Family Season 2","Season 2 · Episode 3"));
        rows.put(new JSONObject().put("id",3).put("media_type","tv").put("name","Modern Family"));
        assertNull(TitleArtwork.match(rows,"Modern Family","Series"));
        assertNull(TitleArtwork.match(rows,"An unknown title",""));
        assertEquals("amelie",TitleArtwork.normalize("Amélie"));
    }
    @Test public void englishDiscoveryChecksContentLanguageAndNewSeriesUsePremiereDate() throws Exception {
        JSONArray movies=new JSONArray();
        for(int i=0;i<3;i++)movies.put(new JSONObject().put("id",i).put("title","English display title")
                .put("original_language",i==0?"en":"fr").put("backdrop_path","/fixture.jpg"));
        movies.put(new JSONObject().put("id",9).put("original_language","en").put("adult",true).put("backdrop_path","/fixture.jpg"));
        java.util.List<JSONObject> out=Trending.englishWeekly(movies,new JSONArray());assertEquals(1,out.size());assertTrue(out.get(0).getBoolean("_weekly"));
        String common="&watch_region=IN&sort_by=popularity.desc&include_adult=false";
        String tv=Trending.releasePath("tv","2026-08-01","2026-11-02",common,true);
        assertTrue(tv.contains("first_air_date.gte=2026-08-01"));assertTrue(tv.contains("first_air_date.lte=2026-11-02"));
        assertFalse(tv.contains("?air_date."));assertFalse(tv.contains("&air_date."));assertTrue(tv.contains("with_original_language=en"));
        assertTrue(Trending.releasePath("movie","2026-08-01","2026-11-02",common,false).contains("primary_release_date.gte="));
    }
}
