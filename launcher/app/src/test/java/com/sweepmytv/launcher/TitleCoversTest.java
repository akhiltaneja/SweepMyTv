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
import java.util.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=29) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TitleCoversTest {
 private Context c;private File root,source;
 @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();root=new File(c.getExternalFilesDir(null),"discovery-title-covers");source=new File(c.getCacheDir(),"cover.png");Bitmap b=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);try(OutputStream out=new FileOutputStream(source)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 private Trending.Pick pick(){Trending.Pick p=new Trending.Pick();p.kind="movie";p.id=123;p.tab=Trending.TRENDING;p.title="Trusted TMDB title";p.intentUri="fixture://original";return p;}
 private final class Catalog implements TitleArtwork.Catalog {int calls,images;boolean none,fail;public JSONObject get(String path)throws Exception{calls++;assertTrue(path.startsWith("/movie/123/images?"));if(fail)throw new IOException();return new JSONObject().put("backdrops",new JSONArray().put(new JSONObject().put("file_path","/cover.jpg").put("width",3840).put("height",2160).put("iso_639_1",none?JSONObject.NULL:"en")));}public File image(String path){images++;assertEquals("/cover.jpg",path);return ArtworkAssets.load(c,Uri.fromFile(source).toString(),root);}}
 @Test public void trustedIdFetchesOnlyItsGalleryAndReusesIsolatedSourceCache(){Trending.Pick p=pick();Catalog api=new Catalog();TitleCovers.Result first=TitleCovers.resolve(c,p,api);assertNotNull(first.image);assertTrue(first.checked);assertEquals(root,first.image.getParentFile());assertEquals(first.image,TitleCovers.resolve(c,p,api).image);assertEquals(1,api.calls);assertEquals(1,api.images);assertEquals("fixture://original",p.intentUri);}
 @Test public void neutralSceneIsNotDownloadedAndNegativeResultsAreCached(){Catalog api=new Catalog();api.none=true;Trending.Pick p=pick();assertNull(TitleCovers.resolve(c,p,api).image);assertTrue(TitleCovers.resolve(c,p,api).checked);assertEquals(1,api.calls);assertEquals(0,api.images);}
 @Test public void failedRefreshRetainsCoverAndThrottlesRetry()throws Exception{Catalog api=new Catalog();Trending.Pick p=pick();File first=TitleCovers.resolve(c,p,api).image;File index=new File(root,"cover_"+TitleCovers.key(p)+".json");JSONObject old=ArtworkAssets.read(index);long stale=System.currentTimeMillis()-ArtworkAssets.WEEK-1000;old.put("checked",stale).put("attempt",stale);ArtworkAssets.write(index,old);api.fail=true;assertEquals(first,TitleCovers.resolve(c,p,api).image);assertEquals(first,TitleCovers.resolve(c,p,api).image);assertEquals(2,api.calls);}
 @Test public void programIdsAndContinueHistoryCannotBeUsedAsTmdbIds(){Catalog api=new Catalog();Trending.Pick p=pick();p.kind="watchnext";p.tab="recent";assertNull(TitleCovers.resolve(c,p,api).image);assertEquals(0,api.calls);p.kind="tv";p.tab=Trending.INDIA;assertTrue(TitleCovers.discovery(p));p.id=0;assertFalse(TitleCovers.discovery(p));}
}
