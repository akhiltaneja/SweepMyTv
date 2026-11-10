package com.sweepmytv.launcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinueArtworkTest {
    private Context c;
    @Before public void setup(){c=RuntimeEnvironment.getApplication();}
    private File picture(String name,int width,int height,int color)throws Exception{
        File f=new File(c.getCacheDir(),name+".png");Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);b.eraseColor(color);
        try(OutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();return f;
    }
    private File asset(String name,int width,int height,int color)throws Exception{return ArtworkAssets.load(c,Uri.fromFile(picture(name,width,height,color)).toString());}
    private Trending.Pick pick(){Trending.Pick p=new Trending.Pick();p.pkg=AppleResumeCache.PACKAGE;p.title="Fixture Show";p.meta="Season 1 · Episode 3 · Apple TV";p.kind="watchnext";p.progress=.4f;p.intentUri="fixture://resume/3";return p;}
    private JSONObject image(String path,int w,int h,String language)throws Exception{return new JSONObject().put("file_path",path).put("width",w).put("height",h).put("iso_639_1",language==null?JSONObject.NULL:language).put("vote_average",8).put("vote_count",40);}
    private final class Catalog implements TitleArtwork.Catalog {
        int calls,downloads;boolean fail;File wide,tall;
        Catalog()throws Exception{wide=asset("catalog-wide",1920,1080,Color.GREEN);tall=asset("catalog-tall",600,900,Color.YELLOW);}
        public JSONObject get(String path)throws Exception{
            calls++;if(fail)throw new IOException();
            if(path.startsWith("/search/"))return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id",123).put("media_type","tv").put("name","Fixture Show")));
            assertTrue(path.startsWith("/tv/123/images?"));assertTrue(path.contains("include_image_language="));
            return new JSONObject().put("backdrops",new JSONArray().put(ContinueArtworkTest.this.image("/wide.jpg",1920,1080,"en"))).put("posters",new JSONArray().put(ContinueArtworkTest.this.image("/tall.jpg",600,900,"en")));
        }
        public File image(String path){downloads++;return path.equals("/wide.jpg")?wide:path.equals("/tall.jpg")?tall:null;}
    }
    @Test public void preservesOriginalBytesDimensionsAndTransparency()throws Exception{
        File source=picture("original",1920,1080,0x80336699),saved=ArtworkAssets.load(c,Uri.fromFile(source).toString());
        assertNotNull(saved);assertArrayEquals(Files.readAllBytes(source.toPath()),Files.readAllBytes(saved.toPath()));assertArrayEquals(new int[]{1920,1080},ArtworkAssets.size(saved));
        assertTrue(BitmapFactory.decodeFile(saved.getPath()).hasAlpha());
    }
    @Test public void providerArtWinsForItsOrientationAndCatalogFillsTheOther()throws Exception{
        Catalog catalog=new Catalog();Trending.Pick p=pick();File provider=asset("provider-wide",1600,900,Color.BLUE);
        TitleArtwork.apply(c,p,provider,catalog);assertEquals(provider,p.image);assertEquals(catalog.tall,p.poster);assertEquals(.4f,p.progress,0);assertEquals("fixture://resume/3",p.intentUri);
        File tall=asset("provider-tall",800,1200,Color.MAGENTA);TitleArtwork.apply(c,p,tall,catalog);assertEquals(tall,p.poster);assertEquals(catalog.wide,p.image);
        Trending.Pick restored=pick();TitleArtwork.applyCached(c,restored);assertEquals(tall,restored.poster);assertEquals(catalog.wide,restored.image);assertEquals(2,catalog.calls);
    }
    @Test public void galleryRankingChecksOrientationLanguageAndResolution()throws Exception{
        JSONArray wide=new JSONArray().put(image("/tiny.jpg",320,180,null)).put(image("/wrongorientation.jpg",1000,1500,null)).put(image("/french.jpg",3840,2160,"fr")).put(image("/hd.jpg",1280,720,null)).put(image("/uhd.jpg",3840,2160,null));
        assertEquals("/uhd.jpg",TitleArtwork.choose(wide,false,"en"));
        JSONArray posters=new JSONArray().put(image("/english.jpg",1000,1500,"en")).put(image("/neutral.jpg",2000,3000,null)).put(image("/local.jpg",1000,1500,"fr"));
        assertEquals("/local.jpg",TitleArtwork.choose(posters,true,"fr"));assertEquals("/english.jpg",TitleArtwork.choose(posters,true,"en"));
        assertNull(TitleArtwork.choose(new JSONArray().put(image("https://example.com/a.jpg",1920,1080,null)),false,"en"));
    }
    @Test public void sharedPosterAndThumbnailNeverBecomeTitleArt()throws Exception{
        File still=picture("still",1280,720,Color.RED);String uri=Uri.fromFile(still).toString();Trending.Pick p=pick();TitleArtwork.apply(c,p,uri,uri);
        assertNull(p.image);assertNull(p.poster);
        p.pkg="com.google.android.youtube.tv";Catalog catalog=new Catalog();TitleArtwork.resolve(c,p,catalog);assertEquals(0,catalog.calls);
    }
    @Test public void episodeLandscapePosterIsNotMistakenForPromotionalTitleArt()throws Exception{
        Trending.Pick p=pick();File scene=picture("episode-scene",1280,720,Color.RED);TitleArtwork.apply(c,p,Uri.fromFile(scene).toString(),"content://different-thumbnail",false);assertNull(p.image);assertNull(p.poster);
        File title=picture("title-poster",600,900,Color.BLUE);TitleArtwork.apply(c,p,Uri.fromFile(title).toString(),"content://different-thumbnail",false);assertNull(p.image);assertNotNull(p.poster);assertTrue(ArtworkAssets.portrait(p.poster));
    }
    @Test public void cacheRefreshUsesNewBytesAndRetainsGoodArtworkOnFailure()throws Exception{
        File source=picture("changing",1280,720,Color.BLUE);String uri=Uri.fromFile(source).toString();File first=ArtworkAssets.load(c,uri);
        File index=new File(ArtworkAssets.dir(c),"url_"+ArtworkAssets.hash(uri)+".json");JSONObject state=ArtworkAssets.read(index);long old=System.currentTimeMillis()-ArtworkAssets.WEEK-1000;state.put("checked",old).put("attempt",old);ArtworkAssets.write(index,state);
        picture("changing",1280,720,Color.GREEN);File second=ArtworkAssets.load(c,uri);assertNotEquals(first,second);
        state=ArtworkAssets.read(index);state.put("checked",old).put("attempt",old);ArtworkAssets.write(index,state);assertTrue(source.delete());assertEquals(second,ArtworkAssets.load(c,uri));
    }
    @Test public void ambiguousTitlesDoNotDownloadUnrelatedArtwork()throws Exception{
        Trending.Pick p=pick();final int[] downloads={0};TitleArtwork.Catalog api=new TitleArtwork.Catalog(){
            public JSONObject get(String path)throws Exception{return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id",1).put("name",p.title).put("media_type","tv")).put(new JSONObject().put("id",2).put("name",p.title).put("media_type","tv")));}
            public File image(String path){downloads[0]++;return null;}
        };
        TitleArtwork.Pair result=TitleArtwork.resolve(c,p,api);assertNull(result.landscape);assertNull(result.portrait);assertEquals(0,downloads[0]);
    }
    @Test public void expiredCatalogCacheSurvivesFailureAndThrottlesRetries()throws Exception{
        Catalog api=new Catalog();Trending.Pick p=pick();TitleArtwork.Pair first=TitleArtwork.resolve(c,p,api);assertEquals(2,api.calls);
        File[] files=ArtworkAssets.dir(c).listFiles((d,name)->name.startsWith("catalog_"));assertEquals(1,files.length);JSONObject saved=ArtworkAssets.read(files[0]);long old=System.currentTimeMillis()-ArtworkAssets.WEEK-1000;saved.put("checked",old).put("attempt",old);ArtworkAssets.write(files[0],saved);api.fail=true;
        TitleArtwork.Pair fallback=TitleArtwork.resolve(c,p,api);assertEquals(first.landscape,fallback.landscape);assertEquals(first.portrait,fallback.portrait);assertEquals(3,api.calls);TitleArtwork.resolve(c,p,api);assertEquals(3,api.calls);
    }
    @Test public void localRecentArtworkRefreshesWithoutReplacingTheProviderOrientation()throws Exception{
        Catalog api=new Catalog();Trending.Pick p=pick();File provider=asset("preferred-provider",1600,900,Color.MAGENTA);TitleArtwork.apply(c,p,provider,api);File oldPoster=p.poster;
        File index=ArtworkAssets.dir(c).listFiles((d,name)->name.startsWith("catalog_"))[0];JSONObject state=ArtworkAssets.read(index);long old=System.currentTimeMillis()-ArtworkAssets.WEEK-1000;state.put("checked",old).put("attempt",old);ArtworkAssets.write(index,state);
        api.wide=asset("fresh-catalog-wide",1920,1080,Color.CYAN);api.tall=asset("fresh-catalog-tall",600,900,Color.RED);TitleArtwork.resolveRecent(c,Collections.singletonList(p),api);
        assertEquals(provider,p.image);assertEquals(api.tall,p.poster);assertNotEquals(oldPoster,p.poster);assertEquals(4,api.calls);
    }
    @Test public void otherAppsExplicitLandscapePostersAreNotDiscardedByTheAppleEpisodeRule()throws Exception{
        Trending.Pick p=pick();p.pkg="in.startv.hotstar";File title=picture("published-title-cover",1600,900,Color.BLUE);
        TitleArtwork.apply(c,p,Uri.fromFile(title).toString(),"content://distinct-playback-still",false);assertNotNull(p.image);assertNull(p.poster);assertTrue(ArtworkAssets.landscape(p.image));
    }
    @Test public void legacyExactTitleCatalogArtBridgesAnUnavailableReplacement()throws Exception{
        Trending.Pick p=pick();File legacy=new File(c.getExternalFilesDir(null),"recent/title-artwork/"+ArtworkAssets.hash(p.pkg+"\n"+TitleArtwork.queryTitle(p.title))+".jpg");legacy.getParentFile().mkdirs();Files.copy(picture("verified-old-catalog",1280,720,Color.BLUE).toPath(),legacy.toPath());
        TitleArtwork.Pair fallback=TitleArtwork.resolve(c,p,null);assertNotNull(fallback.landscape);assertArrayEquals(Files.readAllBytes(legacy.toPath()),Files.readAllBytes(fallback.landscape.toPath()));
        Catalog api=new Catalog();TitleArtwork.Pair fresh=TitleArtwork.resolve(c,p,api);assertEquals(api.wide,fresh.landscape);assertEquals(api.tall,fresh.portrait);assertNotEquals(fallback.landscape,fresh.landscape);
    }
    @Test public void sameNameSeriesCanBeIdentifiedByVerifiedSeasonCount()throws Exception{
        Trending.Pick p=pick();p.meta="Season 8 · Episode 6 · Apple TV";File wide=asset("matched-wide",1920,1080,Color.BLUE),tall=asset("matched-portrait",1000,1500,Color.GREEN);final boolean[] tvSearch={false};
        TitleArtwork.Catalog api=new TitleArtwork.Catalog(){
            public JSONObject get(String path)throws Exception{
                if(path.startsWith("/search/")){tvSearch[0]=path.startsWith("/search/tv?");return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id",1).put("name",p.title)).put(new JSONObject().put("id",2).put("name",p.title)));}
                if(path.equals("/tv/1"))return new JSONObject().put("number_of_seasons",1);
                if(path.equals("/tv/2"))return new JSONObject().put("number_of_seasons",11);
                assertTrue(path.startsWith("/tv/2/images?"));return new JSONObject().put("backdrops",new JSONArray().put(ContinueArtworkTest.this.image("/correctwide.jpg",1920,1080,"en"))).put("posters",new JSONArray().put(ContinueArtworkTest.this.image("/correctportrait.jpg",1000,1500,"en")));
            }
            public File image(String path){return path.contains("portrait")?tall:wide;}
        };
        TitleArtwork.Pair result=TitleArtwork.resolve(c,p,api);assertTrue(tvSearch[0]);assertEquals(wide,result.landscape);assertEquals(tall,result.portrait);
    }
    @Test public void seasonEvidenceMustRuleOutEveryOtherSameNameSeries()throws Exception{
        for(boolean missing:new boolean[]{false,true}){
            Trending.Pick p=pick();p.title=missing?"Missing details":"Ambiguous seasons";p.meta="Season 8 · Episode 6";final int[] downloads={0};
            TitleArtwork.Catalog api=new TitleArtwork.Catalog(){
                public JSONObject get(String path)throws Exception{
                    if(path.startsWith("/search/"))return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id",1).put("name",p.title)).put(new JSONObject().put("id",2).put("name",p.title)));
                    if(missing&&path.equals("/tv/2"))return new JSONObject();return new JSONObject().put("number_of_seasons",11);
                }
                public File image(String path){downloads[0]++;return null;}
            };
            TitleArtwork.Pair result=TitleArtwork.resolve(c,p,api);assertNull(result.landscape);assertNull(result.portrait);assertEquals(0,downloads[0]);
        }
    }
    @Test public void newMatchingRulesRetryAnOldUnresolvedCacheWithoutWaitingForItsBackoff()throws Exception{
        Trending.Pick p=pick();String id=ArtworkAssets.hash(p.pkg+"\n"+TitleArtwork.queryTitle(p.title)+"\n"+Locale.getDefault().getLanguage());
        File marker=new File(ArtworkAssets.dir(c),"catalog_"+id+".json");ArtworkAssets.write(marker,new JSONObject().put("attempt",System.currentTimeMillis()).put("landscape","").put("portrait",""));Catalog api=new Catalog();TitleArtwork.Pair result=TitleArtwork.resolve(c,p,api);assertEquals(api.tall,result.portrait);assertEquals(2,api.calls);assertEquals("matched",ArtworkAssets.read(marker).getString("match_state"));
        TitleArtwork.resolve(c,p,api);assertEquals(2,api.calls);
    }
    @Test public void invalidImagesAreRejectedAndOldFlatCacheIsNotReused()throws Exception{
        File bogus=new File(c.getCacheDir(),"bogus.jpg");Files.write(bogus.toPath(),"not an image".getBytes());assertNull(ArtworkAssets.load(c,Uri.fromFile(bogus).toString()));
        Trending.Pick p=pick();File legacy=new File(c.getExternalFilesDir(null),"recent/title-artwork/old.jpg");legacy.getParentFile().mkdirs();Files.write(legacy.toPath(),new byte[]{1});TitleArtwork.applyCached(c,p);assertNull(p.image);assertNull(p.poster);
    }
    @Test public void landscapeTitleCoversPreferReadableLanguageAndRejectNeutralScenes() throws Exception {
        JSONArray images=new JSONArray().put(image("/scene.jpg",7680,4320,null)).put(image("/englishcover.jpg",3840,2160,"en")).put(image("/frenchcover.jpg",1920,1080,"fr"));
        assertEquals("/englishcover.jpg",TitleArtwork.titleCover(images,"en"));assertEquals("/frenchcover.jpg",TitleArtwork.titleCover(images,"fr"));
        assertNull(TitleArtwork.titleCover(new JSONArray().put(image("/scene.jpg",3840,2160,null)),"en"));
    }
    @Test public void completeOldCatalogIsUpgradedToTitleCoverAndCurrentSelectionBeatsResumeSnapshot() throws Exception {
        Trending.Pick p=pick();File old=asset("old-plain-backdrop",3840,2160,Color.RED),poster=asset("old-title-poster",800,1200,Color.BLUE);String key=ArtworkAssets.hash(p.pkg+"\n"+TitleArtwork.queryTitle(p.title)+"\n"+Locale.getDefault().getLanguage());
        File index=new File(ArtworkAssets.dir(c),"catalog_"+key+".json");ArtworkAssets.write(index,new JSONObject().put("landscape",old.getName()).put("portrait",poster.getName()).put("checked",System.currentTimeMillis()).put("attempt",System.currentTimeMillis()).put("matching_version",2));
        p.image=old;p.poster=poster;Catalog api=new Catalog();TitleArtwork.resolveRecent(c,Collections.singletonList(p),api);assertEquals(api.wide,p.image);assertEquals(api.tall,p.poster);assertEquals(2,api.calls);
        Trending.Pick snapshot=pick();snapshot.image=old;snapshot.poster=poster;TitleArtwork.restoreCached(c,snapshot,true);assertEquals(api.wide,snapshot.image);assertEquals(api.tall,snapshot.poster);
    }
    @Test public void neutralOnlyGalleryUsesPortraitInsteadOfRevivingAnOldScene() throws Exception {
        Trending.Pick p=pick();Catalog first=new Catalog();TitleArtwork.apply(c,p,(File)null,first);File index=ArtworkAssets.dir(c).listFiles((d,n)->n.startsWith("catalog_"))[0];JSONObject old=ArtworkAssets.read(index);old.put("matching_version",2);old.remove("attempt_version");ArtworkAssets.write(index,old);
        File poster=asset("poster-fallback",1200,1800,Color.CYAN);TitleArtwork.Catalog api=new TitleArtwork.Catalog(){public JSONObject get(String path)throws Exception{if(path.startsWith("/search/"))return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("id",123).put("name",p.title).put("media_type","tv")));return new JSONObject().put("backdrops",new JSONArray().put(ContinueArtworkTest.this.image("/scene.jpg",3840,2160,null))).put("posters",new JSONArray().put(ContinueArtworkTest.this.image("/poster.jpg",1200,1800,"en")));}public File image(String path){assertEquals("/poster.jpg",path);return poster;}};
        TitleArtwork.resolveRecent(c,Collections.singletonList(p),api);assertNull(p.image);assertEquals(poster,p.poster);
    }

}
