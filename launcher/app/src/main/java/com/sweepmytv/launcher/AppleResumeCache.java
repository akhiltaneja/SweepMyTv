package com.sweepmytv.launcher;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Apple TV withdraws its entire Watch Next queue at startup, then republishes it when its app opens. */
final class AppleResumeCache {
    static final String PACKAGE="com.apple.atve.androidtv.appletv";
    private static final long MAX_AGE=7*24*3600_000L;
    private static File file(Context c){return new File(c.getExternalFilesDir(null),"recent/apple-watchnext.json");}
    static synchronized List<Trending.Pick> reconcile(Context c,List<Trending.Pick> live,boolean published,long now){
        List<Trending.Pick> apple=new ArrayList<>();for(Trending.Pick p:live)if(PACKAGE.equals(p.pkg))apple.add(p);
        if(!apple.isEmpty()){save(c,apple,now);return live;}
        if(published){file(c).delete();return live;} // Explicit provider entries filtered out, not a missing provider queue.
        List<Trending.Pick> out=new ArrayList<>(live);out.addAll(restore(c,now));return out;
    }
    private static String path(Context c,File image)throws IOException {
        if(image==null)return "";String root=c.getExternalFilesDir(null).getCanonicalPath()+File.separator,actual=image.getCanonicalPath();return actual.startsWith(root)?actual.substring(root.length()):"";
    }
    private static File image(Context c,String path)throws IOException {
        if(path.isEmpty())return null;File image=new File(c.getExternalFilesDir(null),path);String root=c.getExternalFilesDir(null).getCanonicalPath()+File.separator;
        return image.getCanonicalPath().startsWith(root)&&image.isFile()?image:null;
    }
    private static void save(Context c,List<Trending.Pick> rows,long now){
        try{
            JSONArray items=new JSONArray();for(Trending.Pick p:rows)items.put(new JSONObject().put("id",p.id).put("title",p.title).put("meta",p.meta).put("service",p.service).put("at",p.at).put("intent",p.intentUri).put("progress",p.progress).put("image",path(c,p.image)).put("poster",path(c,p.poster)));
            JSONObject snapshot=new JSONObject().put("synced_at",now).put("title_artwork",ContinueOptions.titleArtwork(c)).put("items",items);File target=file(c);target.getParentFile().mkdirs();File temp=new File(target+".tmp");try(OutputStream out=new FileOutputStream(temp)){out.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));}if(!temp.renameTo(target))temp.delete();
        }catch(Exception ignored){}
    }
    static synchronized List<Trending.Pick> restore(Context c,long now){
        List<Trending.Pick> out=new ArrayList<>();
        if(c.getPackageManager().getLeanbackLaunchIntentForPackage(PACKAGE)==null)return out;
        File target=file(c);if(!target.isFile()||target.length()>128*1024)return out;
        try(InputStream in=new FileInputStream(target)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
            JSONObject root=new JSONObject(bytes.toString("UTF-8"));long at=root.optLong("synced_at",0);if(at<=0||now<at-300_000||now-at>MAX_AGE)return out;
            JSONArray rows=root.getJSONArray("items");for(int i=0;i<Math.min(16,rows.length());i++){
                JSONObject item=rows.getJSONObject(i);Trending.Pick p=new Trending.Pick();p.id=item.getInt("id");p.pkg=PACKAGE;p.kind="watchnext";p.tab=RecentStore.TAB;p.title=item.getString("title");p.meta=item.optString("meta","");p.service=item.optString("service","Apple TV");p.at=item.optLong("at");p.progress=(float)item.optDouble("progress",0);p.intentUri=item.isNull("intent")?null:item.optString("intent",null);p.image=image(c,item.optString("image",""));p.poster=image(c,item.optString("poster",""));if(ContinueOptions.titleArtwork(c)||WatchNext.hideSpoilers(c))TitleArtwork.restoreCached(c,p,root.optBoolean("title_artwork",false));p.savedResume=true;out.add(p);
            }
        }catch(Exception ignored){}
        return out;
    }
}
