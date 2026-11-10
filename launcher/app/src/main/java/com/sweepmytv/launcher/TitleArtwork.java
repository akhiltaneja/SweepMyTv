package com.sweepmytv.launcher;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Exact-title catalog artwork, separate from captured playback artwork. Network calls run off the UI thread. */
final class TitleArtwork {
    private static final int MATCHING_VERSION=3;
    private TitleArtwork(){}
    static final class Pair {
        File landscape,portrait;
        Pair(){}
        Pair(File landscape,File portrait){this.landscape=landscape;this.portrait=portrait;}
        void fill(Pair fallback){if(landscape==null)landscape=fallback.landscape;if(portrait==null)portrait=fallback.portrait;}
        void put(Trending.Pick p){p.image=landscape;p.poster=portrait;}
    }
    interface Catalog {JSONObject get(String path)throws Exception;File image(String path)throws Exception;}
    private static String key(Trending.Pick p){return ArtworkAssets.hash(p.pkg+"\n"+queryTitle(p.title)+"\n"+java.util.Locale.getDefault().getLanguage());}
    private static File index(Context c,Trending.Pick p,String role){return new File(ArtworkAssets.dir(c),role+"_"+key(p)+".json");}
    private static Pair read(Context c,JSONObject value){File wide=ArtworkAssets.saved(c,value.optString("landscape")),tall=ArtworkAssets.saved(c,value.optString("portrait"));return new Pair(ArtworkAssets.landscape(wide)?wide:null,ArtworkAssets.portrait(tall)?tall:null);}
    private static JSONObject json(Pair pair)throws Exception{return new JSONObject().put("landscape",pair.landscape==null?"":pair.landscape.getName()).put("portrait",pair.portrait==null?"":pair.portrait.getName());}
    static void applyCached(Context c,Trending.Pick p){read(c,ArtworkAssets.read(index(c,p,"selection"))).put(p);}
    /** Only an explicit, separate poster field is trusted as title art; an ambiguous thumbnail may be a playback still. */
    static void apply(Context c,Trending.Pick p,String posterUri,String thumbnailUri){
        apply(c,p,posterUri,thumbnailUri,false);
    }
    static void apply(Context c,Trending.Pick p,String posterUri,String thumbnailUri,boolean titleLevel){
        File provider=posterUri==null||posterUri.isEmpty()||posterUri.equals(thumbnailUri)?null:ArtworkAssets.load(c,posterUri);
        // An episode's landscape "poster" may still be a scene, as Apple TV publishes for Monarch.
        if(!titleLevel&&AppleResumeCache.PACKAGE.equals(p.pkg)&&!ArtworkAssets.portrait(provider))provider=null;
        apply(c,p,provider,catalog(c));
    }
    static void apply(Context c,Trending.Pick p,File provider,Catalog api){
        Pair selected=new Pair();if(ArtworkAssets.landscape(provider))selected.landscape=provider;else if(ArtworkAssets.portrait(provider))selected.portrait=provider;
        if(selected.landscape==null||selected.portrait==null)selected.fill(resolve(c,p,api));
        selected.put(p);saveSelection(c,p,selected);
    }
    private static void saveSelection(Context c,Trending.Pick p,Pair pair){try{ArtworkAssets.write(index(c,p,"selection"),json(pair));}catch(Exception ignored){}}
    static void restoreCached(Context c,Trending.Pick p,boolean titleSnapshot){
        File provider=p.poster;Pair selected=read(c,ArtworkAssets.read(index(c,p,"selection")));
        if(titleSnapshot){if(selected.landscape==null&&selected.portrait==null){if(ArtworkAssets.landscape(p.image))selected.landscape=p.image;if(ArtworkAssets.portrait(p.poster))selected.portrait=p.poster;}}
        else if(ArtworkAssets.portrait(provider))selected.portrait=provider;
        selected.put(p);
    }
    static void resolveRecent(Context c,List<Trending.Pick> list){
        if(ContinueOptions.titleArtwork(c))resolveRecent(c,list,catalog(c));
    }
    static void resolveRecent(Context c,List<Trending.Pick> list,Catalog api){
        for(Trending.Pick p:list)if(ContinueOptions.include(c,p.pkg)){
            Pair previous=withLegacy(c,p,read(c,ArtworkAssets.read(index(c,p,"catalog")))),fresh=resolve(c,p,api),selected=new Pair(p.image,p.poster);
            if(fresh.landscape!=null&&(selected.landscape==null||selected.landscape.equals(previous.landscape)))selected.landscape=fresh.landscape;
            else if(fresh.landscape==null&&fresh.portrait!=null&&java.util.Objects.equals(selected.landscape,previous.landscape)
                    &&ArtworkAssets.read(index(c,p,"catalog")).optInt("matching_version")==MATCHING_VERSION)selected.landscape=null;
            if(fresh.portrait!=null&&(selected.portrait==null||selected.portrait.equals(previous.portrait)))selected.portrait=fresh.portrait;
            selected.put(p);saveSelection(c,p,selected);
        }
    }
    private static Catalog catalog(Context c){return catalogApi(c,ArtworkAssets.dir(c));}
    static Catalog catalogApi(Context c,File root){
        String token=Keys.read(c,Keys.TMDB);if(token==null)return null;
        return new Catalog(){public JSONObject get(String path)throws Exception{return TitleArtwork.get(token,path);}public File image(String path){return ArtworkAssets.load(c,"https://image.tmdb.org/t/p/original"+path,root);}};
    }
    private static Pair withLegacy(Context c,Trending.Pick p,Pair selected){
        // Old files were exact-title TMDB catalog art, never the app's playback thumbnail.
        if(selected.landscape==null&&selected.portrait==null){
            File old=new File(c.getExternalFilesDir(null),"recent/title-artwork/"+ArtworkAssets.hash(p.pkg+"\n"+queryTitle(p.title))+".jpg");
            if(old.isFile()){
                File saved=ArtworkAssets.load(c,android.net.Uri.fromFile(old).toString());
                if(selected.landscape==null&&ArtworkAssets.landscape(saved))selected.landscape=saved;
                if(selected.portrait==null&&ArtworkAssets.portrait(saved))selected.portrait=saved;
            }
        }
        return selected;
    }
    static Pair resolve(Context c,Trending.Pick p,Catalog api){
        if(ContinueOptions.isYouTube(p.pkg))return new Pair();
        File index=index(c,p,"catalog");JSONObject old=ArtworkAssets.read(index);Pair cached=read(c,old);long now=System.currentTimeMillis();
        boolean current=old.optInt("matching_version")==MATCHING_VERSION;
        boolean recentAttempt=old.optInt("attempt_version",old.optInt("matching_version"))==MATCHING_VERSION&&now-old.optLong("attempt")<ArtworkAssets.RETRY;
        if((current&&(cached.landscape!=null||cached.portrait!=null)&&now-old.optLong("checked")<ArtworkAssets.WEEK)||recentAttempt||api==null)return withLegacy(c,p,cached);
        String status="not_found";int matchedId=0;
        try{
            boolean episodic=episodic(p.meta);JSONArray rows=api.get((episodic?"/search/tv":"/search/multi")+"?include_adult=false&query="+URLEncoder.encode(queryTitle(p.title),"UTF-8")).optJSONArray("results");
            if(episodic)for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&!row.has("media_type"))row.put("media_type","tv");}
            List<JSONObject> candidates=matches(rows,p.title,p.meta);JSONObject match=candidates.size()==1?candidates.get(0):matchSeason(candidates,p.meta,api);
            if(!candidates.isEmpty()&&match==null)status="ambiguous";
            if(match!=null){
                status="matched";matchedId=match.getInt("id");
                String language=java.util.Locale.getDefault().getLanguage();JSONObject images=api.get("/"+match.getString("media_type")+"/"+match.getInt("id")+"/images?include_image_language="+URLEncoder.encode(language+",en,null","UTF-8"));
                String landscape=titleCover(images.optJSONArray("backdrops"),language),portrait=choose(images.optJSONArray("posters"),true,language);
                if(portrait==null&&validPath(match.optString("poster_path")))portrait=match.optString("poster_path");
                File wide=landscape==null?null:api.image(landscape),tall=portrait==null?null:api.image(portrait);
                if(landscape!=null&&!ArtworkAssets.landscape(wide))throw new java.io.IOException("Title cover unavailable");
                if(ArtworkAssets.landscape(wide))cached.landscape=wide;
                else if(ArtworkAssets.portrait(tall))cached.landscape=null;
                if(ArtworkAssets.portrait(tall))cached.portrait=tall;
            }
            ArtworkAssets.write(index,json(cached).put("checked",now).put("attempt",now).put("matching_version",MATCHING_VERSION).put("attempt_version",MATCHING_VERSION).put("match_state",status).put("match_id",matchedId));
        }catch(Exception ignored){try{old.put("attempt",now).put("attempt_version",MATCHING_VERSION).put("match_state","unavailable");ArtworkAssets.write(index,old);}catch(Exception unused){}}
        return withLegacy(c,p,cached);
    }
    private static boolean validPath(String path){return path!=null&&path.matches("/[A-Za-z0-9]+\\.(jpg|png|webp)");}
    /** Language-tagged landscape art is a title-bearing-cover signal; neutral backdrops are not title covers. */
    static String titleCover(JSONArray images,String language){
        JSONArray tagged=new JSONArray();for(int i=0;images!=null&&i<images.length();i++){
            JSONObject row=images.optJSONObject(i);if(row==null)continue;String lang=row.optString("iso_639_1","");
            if(!row.isNull("iso_639_1")&&!lang.isEmpty()&&(lang.equals(language)||lang.equals("en")))tagged.put(row);
        }
        return choose(tagged,false,language);
    }

    /** Stable orientation/language/size selection, rather than the single primary search-result image. */
    static String choose(JSONArray images,boolean portrait,String language){
        double best=-Double.MAX_VALUE;String result=null;
        for(int i=0;images!=null&&i<images.length();i++){
            JSONObject row=images.optJSONObject(i);if(row==null)continue;String path=row.optString("file_path"),lang=row.isNull("iso_639_1")?"":row.optString("iso_639_1","");
            int width=row.optInt("width"),height=row.optInt("height");double ratio=height>0?width/(double)height:0;
            if(!validPath(path)||width<(portrait?300:640)||height<(portrait?450:360)||(long)width*height>50_000_000L)continue;
            if(portrait?(ratio<.5||ratio>.85):(ratio<1.5||ratio>2.7))continue;
            if(!lang.isEmpty()&&!lang.equals(language)&&!lang.equals("en"))continue;
            int preference=portrait?(lang.equals(language)?3:lang.equals("en")?2:1):(lang.isEmpty()?3:lang.equals(language)?2:1);
            double score=preference*1000+Math.min(width,portrait?2000:3840)/(portrait?20d:38.4d)+Math.max(0,Math.min(10,row.optDouble("vote_average",0)))+Math.log1p(Math.max(0,row.optInt("vote_count",0)));
            if(score>best||(score==best&&(result==null||path.compareTo(result)<0))){best=score;result=path;}
        }
        return result;
    }
    static String queryTitle(String title) {
        return (title==null?"":title).replaceAll("(?i)\\s+(?:season\\s+\\d+|s\\d+\\s*e\\d+|episode\\s+\\d+)\\b.*$","").trim();
    }
    static String normalize(String title) {
        return Normalizer.normalize(queryTitle(title),Normalizer.Form.NFKD).replaceAll("\\p{M}","")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
    private static boolean episodic(String metadata){return metadata!=null&&metadata.matches("(?is).*(season|episode|series)\\b.*");}
    /** Keep exact-title matching; episode metadata may rule out a same-name series with too few seasons. */
    private static List<JSONObject> matches(JSONArray results,String title,String metadata){
        String wanted=normalize(title);List<JSONObject> found=new ArrayList<>();if(wanted.isEmpty())return found;
        boolean episodic=episodic(metadata);java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;results!=null&&i<results.length();i++){
            JSONObject row=results.optJSONObject(i);if(row==null||row.optBoolean("adult",false))continue;
            String kind=row.optString("media_type");if(!kind.equals("movie")&&!kind.equals("tv"))continue;if(episodic&&!kind.equals("tv"))continue;
            if(!wanted.equals(normalize(row.optString("title")))&&!wanted.equals(normalize(row.optString("name")))&&!wanted.equals(normalize(row.optString("original_title")))&&!wanted.equals(normalize(row.optString("original_name"))))continue;
            if(row.optInt("id")>0&&seen.add(kind+row.optInt("id")))found.add(row);
        }
        return found;
    }
    static JSONObject match(JSONArray results,String title,String metadata){List<JSONObject> found=matches(results,title,metadata);return found.size()==1?found.get(0):null;}
    private static JSONObject matchSeason(List<JSONObject> candidates,String metadata,Catalog api)throws Exception{
        java.util.regex.Matcher season=java.util.regex.Pattern.compile("(?i)\\bseason\\s+(\\d+)\\b").matcher(metadata==null?"":metadata);
        if(candidates.size()<2||candidates.size()>4||!season.find())return null;
        int minimum;try{minimum=Integer.parseInt(season.group(1));}catch(NumberFormatException e){return null;}if(minimum<2||minimum>100)return null;
        JSONObject chosen=null;
        for(JSONObject candidate:candidates){
            if(!"tv".equals(candidate.optString("media_type")))return null;
            JSONObject detail=api.get("/tv/"+candidate.getInt("id"));int count=detail.optInt("number_of_seasons",-1);if(count<1)return null;
            if(count>=minimum){if(chosen!=null)return null;chosen=candidate;}
        }
        return chosen;
    }
    private static JSONObject get(String token,String path) throws Exception {
        boolean bearer=token.length()>40;
        HttpURLConnection connection=(HttpURLConnection)new URL("https://api.themoviedb.org/3"+path+(bearer?"":(path.contains("?")?"&":"?")+"api_key="+URLEncoder.encode(token,"UTF-8"))).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(7000);connection.setRequestProperty("Accept","application/json");
        if(bearer)connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Artwork metadata unavailable");
            try(InputStream in=connection.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                byte[] bytes=new byte[8192];for(int n;(n=in.read(bytes))>0;){if(out.size()+n>2*1024*1024)throw new java.io.IOException("Artwork metadata too large");out.write(bytes,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
