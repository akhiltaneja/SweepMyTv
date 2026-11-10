package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Original encoded artwork, with bounded downloads. Decoding stays in CardArtwork at display size. */
final class ArtworkAssets {
    static final long WEEK=7L*24*3600_000, RETRY=30*60_000;
    static final long MAX_BYTES=12L*1024*1024, CACHE_BYTES=64L*1024*1024;
    private static final android.util.LruCache<String,int[]> SIZES=new android.util.LruCache<>(128);
    private static final java.util.concurrent.ConcurrentHashMap<String,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private ArtworkAssets(){}
    static File dir(Context c){return new File(c.getExternalFilesDir(null),"recent/title-artwork-v2");}
    static String hash(String value){return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static JSONObject read(File f){try{if(!f.isFile()||f.length()>16384)return new JSONObject();return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception e){return new JSONObject();}}
    static void write(File f,JSONObject value){try{f.getParentFile().mkdirs();File temp=new File(f+".part");try(OutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}if(!temp.renameTo(f))temp.delete();}catch(Exception ignored){}}
    static File saved(Context c,String name){return saved(dir(c),name);}
    static File saved(File root,String name){return name.matches("asset_[a-f0-9]{64}\\.img")&&new File(root,name).isFile()?new File(root,name):null;}
    static int[] size(File f){if(f==null||!f.isFile())return new int[]{0,0};String key=f.getAbsolutePath()+":"+f.lastModified()+":"+f.length();int[] cached=SIZES.get(key);if(cached==null){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),o);cached=new int[]{o.outWidth,o.outHeight};SIZES.put(key,cached);}return cached.clone();}
    static boolean landscape(File f){int[] s=size(f);return s[1]>0&&s[0]/(float)s[1]>=1.2f;}
    static boolean portrait(File f){int[] s=size(f);return s[0]>0&&s[1]>0&&s[0]/(float)s[1]<=.85f;}
    static File load(Context c,String uri){return load(c,uri,dir(c));}
    static File load(Context c,String uri,File root){
        synchronized(LOCKS.computeIfAbsent(root.getAbsolutePath(),key->new Object())){return loadLocked(c,uri,root);}
    }
    private static File loadLocked(Context c,String uri,File root){
        if(uri==null||uri.isEmpty())return null;
        File index=new File(root,"url_"+hash(uri)+".json");JSONObject old=read(index);
        File saved=saved(root,old.optString("file"));long now=System.currentTimeMillis();
        if(saved!=null&&now-old.optLong("checked")<WEEK)return saved;
        if(now-old.optLong("attempt")<RETRY)return saved;
        File temp=null;
        try{
            root.mkdirs();temp=File.createTempFile("download_",".part",root);MessageDigest sum=digest();
            try(InputStream in=open(c,uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException();long count=0;byte[] buffer=new byte[16384];
                for(int n;(n=in.read(buffer))!=-1;){count+=n;if(count>MAX_BYTES)throw new IOException("Artwork too large");sum.update(buffer,0,n);out.write(buffer,0,n);}
            }
            int[] size=size(temp);if(size[0]<16||size[1]<16||(long)size[0]*size[1]>50_000_000L)throw new IOException("Invalid artwork");
            File result=new File(root,"asset_"+hex(sum.digest())+".img");
            if(result.isFile())temp.delete();else if(!temp.renameTo(result))throw new IOException();
            result.setLastModified(now);write(index,new JSONObject().put("file",result.getName()).put("checked",now).put("attempt",now));prune(root);return result;
        }catch(Exception e){try{old.put("attempt",now);write(index,old);}catch(Exception ignored){}return saved;}
        finally{if(temp!=null)temp.delete();}
    }
    private static InputStream open(Context c,String value)throws Exception{
        Uri uri=Uri.parse(value);String scheme=uri.getScheme();
        if("https".equals(scheme)||"http".equals(scheme)){
            HttpURLConnection connection=(HttpURLConnection)new URL(value).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(7000);
            try{if(connection.getResponseCode()!=200||connection.getContentLengthLong()>MAX_BYTES)throw new IOException();InputStream stream=connection.getInputStream();return new FilterInputStream(stream){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}};}catch(Exception e){connection.disconnect();throw e;}
        }
        if(!Arrays.asList("content","android.resource","file").contains(scheme))throw new IOException("Unsupported artwork URI");
        return c.getContentResolver().openInputStream(uri);
    }
    private static void prune(File root){
        File[] files=root.listFiles((d,n)->n.startsWith("asset_")&&n.endsWith(".img"));if(files==null)return;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));long bytes=0;
        for(int i=0;i<files.length;i++){bytes+=files[i].length();if(i>=48||bytes>CACHE_BYTES)files[i].delete();}
        File[] indexes=root.listFiles((d,n)->n.endsWith(".json"));if(indexes!=null&&indexes.length>192){Arrays.sort(indexes,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(int i=192;i<indexes.length;i++)indexes[i].delete();}
    }
}
