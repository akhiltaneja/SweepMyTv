package com.sweepmytv.launcher;

import android.content.Context;
import java.io.File;
import java.util.Locale;
import org.json.JSONObject;

/** On-demand landscape title covers for trusted TMDB discovery IDs; no new title search or UI-thread I/O. */
final class TitleCovers {
    private TitleCovers(){}
    private static File dir(Context c){return new File(c.getExternalFilesDir(null),"discovery-title-covers");}
    static boolean discovery(Trending.Pick p){return (Trending.TRENDING.equals(p.tab)||Trending.INDIA.equals(p.tab))&&("movie".equals(p.kind)||"tv".equals(p.kind))&&p.id>0;}
    static String key(Trending.Pick p){return p.kind+"_"+p.id+"_"+Locale.getDefault().getLanguage();}
    static final class Result {
        final File image;final boolean checked;
        Result(File image,boolean checked){this.image=image;this.checked=checked;}
    }
    static Result resolve(Context c,Trending.Pick p){return resolve(c,p,TitleArtwork.catalogApi(c,dir(c)));}
    static Result resolve(Context c,Trending.Pick p,TitleArtwork.Catalog api){
        if(!discovery(p))return new Result(null,false);
        File root=dir(c),index=new File(root,"cover_"+key(p)+".json");JSONObject old=ArtworkAssets.read(index);long now=System.currentTimeMillis();
        File saved=ArtworkAssets.saved(root,old.optString("file"));
        if(now-old.optLong("checked")<ArtworkAssets.WEEK&&(saved!=null||old.optBoolean("no_cover")))return new Result(saved,true);
        if(now-old.optLong("attempt")<ArtworkAssets.RETRY||api==null)return new Result(saved,false);
        try{
            String language=Locale.getDefault().getLanguage();JSONObject gallery=api.get("/"+p.kind+"/"+p.id+"/images?include_image_language="+language+",en,null");
            String path=TitleArtwork.titleCover(gallery.optJSONArray("backdrops"),language);File image=null;
            if(path!=null){
                image=api.image(path);
                if(!ArtworkAssets.landscape(image))throw new java.io.IOException("Invalid title cover");
            }
            ArtworkAssets.write(index,new JSONObject().put("file",image==null?"":image.getName()).put("no_cover",image==null).put("checked",now).put("attempt",now));return new Result(image,true);
        }catch(Exception ignored){try{old.put("attempt",now);ArtworkAssets.write(index,old);}catch(Exception unused){}return new Result(saved,false);}
    }
}
