package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.util.*;
import java.io.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29) @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class ContentProfilesTest {
    @Test public void originalLanguageIsStrictAndUnknownNeverFallsBackToEnglish(){
        List<Trending.Pick> all=new ArrayList<>();for(String language:new String[]{"en","hi","ta","","en"}){Trending.Pick p=new Trending.Pick();p.lang=language;all.add(p);}
        assertEquals(2,ContentLanguages.filter(all,Set.of("en")).size());assertEquals(1,ContentLanguages.filter(all,Set.of("hi")).size());assertEquals(3,ContentLanguages.filter(all,Set.of("en","hi")).size());assertEquals(5,ContentLanguages.filter(all,Collections.emptySet()).size());
    }
    @Test public void providerRecommendationsAreFilteredWithoutChangingHistoryOrSavedRows(){
        Channels.Channel app=new Channels.Channel();app.key="provider";for(String lang:new String[]{"en","hi",""}){Trending.Pick p=new Trending.Pick();p.lang=lang;app.items.add(p);}
        Channels.Channel history=new Channels.Channel();history.key=Channels.WATCH_NEXT;history.items.addAll(app.items);
        List<Channels.Channel> filtered=ContentLanguages.channels(Arrays.asList(app,history),Set.of("hi"));
        assertEquals(2,filtered.size());assertEquals(1,filtered.get(0).items.size());assertEquals("hi",filtered.get(0).items.get(0).lang);assertEquals(3,filtered.get(1).items.size());assertEquals(3,app.items.size());
        assertEquals(1,ContentLanguages.channels(Arrays.asList(app,history),Set.of("ta")).size());
    }
    @Test public void weeklyTrendingHonoursHindiAndMixedSelections() throws Exception {
        JSONArray movies=new JSONArray();for(int i=0;i<3;i++)movies.put(new JSONObject().put("id",i+1).put("original_language",new String[]{"en","hi","ta"}[i]).put("backdrop_path","/x.jpg"));
        assertEquals("hi",Trending.weeklyInLanguages(movies,new JSONArray(),Set.of("hi")).get(0).getString("original_language"));assertEquals(2,Trending.weeklyInLanguages(movies,new JSONArray(),Set.of("en","ta")).size());
    }
    @Test public void cacheImmediatelyHonoursSelectionWithoutAnotherNetworkRefresh() throws Exception {
        Context c=RuntimeEnvironment.getApplication();Keys.write(c,Keys.TMDB,"fixture-not-a-real-key");
        android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();info.packageName="fixture.provider";Shadows.shadowOf(c.getPackageManager()).installPackage(info);
        File dir=new File(c.getExternalFilesDir(null),"trending");dir.mkdirs();new File(dir,"art.jpg").createNewFile();JSONArray picks=new JSONArray();
        for(String lang:new String[]{"en","hi","ta",""})picks.put(new JSONObject().put("id",picks.length()+1).put("kind","movie").put("title","Fixture").put("pkg",info.packageName).put("service","Fixture").put("image","art.jpg").put("lang",lang));
        try(FileWriter w=new FileWriter(new File(dir,"picks.json"))){w.write(picks.toString());}
        Trending trending=new Trending(c);trending.setLanguages(Set.of("hi"));assertEquals(1,trending.cached().size());assertEquals("hi",trending.cached().get(0).lang);trending.setLanguages(Set.of("en"));assertEquals("en",trending.cached().get(0).lang);trending.setLanguages(Collections.emptySet());assertEquals(4,trending.cached().size());
    }
    @Test public void localProfilesPersistDifferentAvatarsNamesAndLanguages(){
        Context c=RuntimeEnvironment.getApplication();Keys.write(c,HomeOptions.NAME,"Akhil");new Trending(c).setLanguages(Set.of("en"));String first=Profiles.active(c);int random=Profiles.avatar(c);
        assertTrue(random>=0&&random<Avatars.COUNT);assertEquals(random,Profiles.avatar(c));Profiles.avatar(c,3);Profiles.remember(c);
        String second=Profiles.add(c,"Second");assertTrue(Profiles.switchTo(c,second));assertEquals("Second",HomeOptions.name(c));Profiles.avatar(c,8);new Trending(c).setLanguages(Set.of("hi"));
        assertTrue(Profiles.switchTo(c,first));assertEquals("Akhil",HomeOptions.name(c));assertEquals(3,Profiles.avatar(c));assertEquals(Set.of("en"),ContentLanguages.selected(c));
        assertTrue(Profiles.switchTo(c,second));assertEquals(8,Profiles.avatar(c));assertEquals(Set.of("hi"),ContentLanguages.selected(c));assertFalse(Profiles.switchTo(c,"unknown"));
    }
    @Test public void eachProfileGetsOneDistinctRandomDefaultThatStaysSaved(){
        Context c=RuntimeEnvironment.getApplication();String original=Profiles.active(c);
        Map<String,Integer> assigned=new LinkedHashMap<>();assigned.put(original,Profiles.avatar(c));Set<Integer> used=new HashSet<>(assigned.values());
        for(int i=1;i<6;i++){
            String id=Profiles.add(c,"Person "+i);assertTrue(Profiles.switchTo(c,id));int avatar=Profiles.avatar(c);
            assertTrue(avatar>=0&&avatar<Avatars.COUNT);assertTrue("New profiles must not share default avatars",used.add(avatar));assigned.put(id,avatar);
        }
        for(Map.Entry<String,Integer> e:assigned.entrySet()){
            assertTrue(Profiles.switchTo(c,e.getKey()));assertEquals(e.getValue().intValue(),Profiles.avatar(c));assertEquals(e.getKey(),Profiles.active(c));
        }
        Profiles.switchTo(c,original);int old=Profiles.avatar(c);Profiles.randomizeAvatar(c);int next=Profiles.avatar(c);assertNotEquals(old,next);
        for(Map.Entry<String,Integer> e:assigned.entrySet())if(!e.getKey().equals(original)){
            assertNotEquals(e.getValue().intValue(),next);Profiles.switchTo(c,e.getKey());assertEquals(e.getValue().intValue(),Profiles.avatar(c));
        }
        Profiles.switchTo(c,original);assertEquals(next,Profiles.avatar(c));
        // Choosing a photo or avatar explicitly must not be overwritten on reopening.
        Profiles.avatar(c,-1);assertEquals(original,Profiles.active(c));assertEquals(-1,Profiles.avatar(c));
    }
    @Test public void bundledAvatarsDecodeSmallAndPhotoUploadBelongsToCapturedProfile(){
        Context c=RuntimeEnvironment.getApplication();for(int i=0;i<Avatars.COUNT;i++){Bitmap b=Avatars.preset(c,i);assertNotNull(b);assertTrue(b.getWidth()<=128);}
        String first=Profiles.active(c),second=Profiles.add(c,"Other");Profiles.switchTo(c,second);int otherAvatar=Profiles.avatar(c);
        Bitmap b=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,bytes);
        assertFalse(Avatars.savePhoto(c,first,new byte[]{1,2,3}));assertTrue(Avatars.savePhoto(c,first,bytes.toByteArray()));assertEquals(otherAvatar,Profiles.avatar(c));
        Profiles.switchTo(c,first);assertEquals(-1,Profiles.avatar(c));assertEquals(512,BitmapFactory.decodeFile(Profiles.photo(c,first).getPath()).getWidth());assertNotNull(Avatars.active(c));
    }
    @Test public void phoneProfileUploadCropsSquareWhileWallpaperStaysLandscape() throws Exception {
        PhonePage.Listener listener=new PhonePage.Listener(){public void checking(){}public List<String> accept(Map<String,String> f){return Collections.emptyList();}public void closed(boolean expired){}};
        java.lang.reflect.Method form=PhonePage.class.getDeclaredMethod("photoForm");form.setAccessible(true);
        PhonePage page=new PhonePage(java.net.InetAddress.getLoopbackAddress(),true,"your profile picture",512,512,listener);
        try{String html=new String((byte[])form.invoke(page),java.nio.charset.StandardCharsets.UTF_8);assertTrue(html.contains("W=512,H=512"));assertFalse(html.contains("W=1920"));}finally{page.close();}
        PhonePage wallpaper=new PhonePage(java.net.InetAddress.getLoopbackAddress(),true,listener);
        try{String html=new String((byte[])form.invoke(wallpaper),java.nio.charset.StandardCharsets.UTF_8);assertTrue(html.contains("W=1920,H=1080"));}finally{wallpaper.close();}
    }
}
