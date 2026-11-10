package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;

/** Official-source visual assets for the personal 2026 preview; bounded shared decoded-image cache. */
final class F1Assets {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    static String normalize(String name) { return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    static String surname(String name) { String[] words = name.trim().split("\\s+"); String last = words.length == 0 ? name : words[words.length - 1]; return last.isEmpty() ? last : last.substring(0,1).toUpperCase(Locale.ROOT) + last.substring(1).toLowerCase(Locale.ROOT); }
    static String team(String name) {
        String value = normalize(name);
        if (value.contains("redbull") && !value.contains("racingbull")) return "redbullracing";
        if (value.contains("racingbull") || value.equals("rb") || value.equals("rbf1team") || value.contains("alphatauri")) return "racingbulls";
        if (value.contains("haas")) return "haasf1team";
        for (String team : new String[]{"mercedes","ferrari","mclaren","alpine","astonmartin","audi","cadillac","williams"}) if (value.contains(team)) return team;
        return "";
    }
    private static org.json.JSONArray circuits;
    private static String circuitKey(Context context, String description) {
        if (circuits == null) {
            circuits = new org.json.JSONArray();
            try (java.io.Reader reader = new java.io.InputStreamReader(context.getAssets().open("f1/circuits.json"), java.nio.charset.StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder(); char[] buffer = new char[4096];
                for (int n; (n = reader.read(buffer)) > 0;) text.append(buffer, 0, n);
                circuits = new org.json.JSONArray(text.toString());
            } catch (Exception ignored) { }
        }
        String value = normalize(description), chosen = ""; int best = 0;
        for (int i = 0; i < circuits.length(); i++) {
            org.json.JSONObject item = circuits.optJSONObject(i); if (item == null) continue;
            org.json.JSONArray keys = item.optJSONArray("keys");
            for (int j = 0; keys != null && j < keys.length(); j++) {
                String key = keys.optString(j);
                if (key.length() >= 4 && key.length() > best && value.contains(key)) { chosen = item.optString("asset"); best = key.length(); }
            }
        }
        return chosen;
    }
    private static final java.util.concurrent.ExecutorService WORKER=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();
    },"f1-artwork"));
    private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
    interface Loader {java.util.concurrent.Future<?> load(Context context,FormulaOne.Card card,float density,java.util.function.Consumer<Prepared> done);}
    static final class Prepared {
        final java.util.Map<String,Bitmap> images;final Bitmap circuit;
        Prepared(java.util.Map<String,Bitmap> images,Bitmap circuit){this.images=java.util.Collections.unmodifiableMap(new java.util.HashMap<>(images));this.circuit=circuit;}
    }
    static java.util.concurrent.Future<?> load(Context context,FormulaOne.Card card,float density,java.util.function.Consumer<Prepared> done) {
        Context app=context.getApplicationContext();
        return WORKER.submit(()->{Prepared ready=prepare(app,card,density);MAIN.post(()->done.accept(ready));});
    }
    /** All asset I/O and decoding is completed before the result is delivered to the card. */
    static Prepared prepare(Context context,FormulaOne.Card card,float density) {
        java.util.Map<String,Bitmap> images=new java.util.HashMap<>();Bitmap circuit=null;
        int avatar=Math.max(1,Math.round(160*density));
        if("next".equals(card.key)) {
            circuit=image(context,circuitKey(context,card.title+" "+card.body),Math.round(220*density));
            for(int i=0;i<Math.min(3,card.table.size());i++)put(context,images,card.table.get(i).portrait,avatar);
        } else if("drivers".equals(card.key)||"teams".equals(card.key)) {
            if(!card.table.isEmpty()) {
                FormulaOne.Line leader=card.table.get(0);
                if("drivers".equals(card.key))put(context,images,leader.portrait,Math.round(260*density));
                else {put(context,images,leader.logo,avatar);put(context,images,"car-"+team(leader.name),Math.round(128*density));}
            }
        } else if(!"weekend".equals(card.key)) {
            for(int i=0;i<Math.min(3,card.table.size());i++) {FormulaOne.Line row=card.table.get(i);put(context,images,row.portrait,avatar);put(context,images,row.logo,avatar);}
        }
        return new Prepared(images,circuit);
    }
    private static void put(Context context,java.util.Map<String,Bitmap> images,String key,int size) {Bitmap b=image(context,key,size);if(b!=null)images.put(key,b);}
    static Bitmap circuit(Context context,String description){return image(context,circuitKey(context,description));}
    static Bitmap image(Context context,String key){return image(context,key,512);}
    private static final java.util.Set<String> MISSING=java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    static Bitmap image(Context context,String key,int maximum) {
        if(key==null||key.isEmpty()||!key.matches("[A-Za-z0-9_-]+")||MISSING.contains(key))return null;
        int limit=Math.max(1,Math.min(512,maximum));String cacheKey=key+"@"+limit;
        Bitmap cached=CACHE.get(cacheKey);if(cached!=null)return cached;
        for(String extension:new String[]{".webp",".png"}) {
            try {
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                try(InputStream in=context.getAssets().open("f1/"+key+extension)){BitmapFactory.decodeStream(in,null,options);}
                if(options.outWidth<1||options.outHeight<1)continue;
                float scale=Math.min(1f,limit/(float)Math.max(options.outWidth,options.outHeight));
                int width=Math.max(1,Math.round(options.outWidth*scale)),height=Math.max(1,Math.round(options.outHeight*scale));
                options.inSampleSize=1;while(options.inSampleSize<128&&1f/(options.inSampleSize*2)>=scale)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                Bitmap bitmap;try(InputStream in=context.getAssets().open("f1/"+key+extension)){bitmap=BitmapFactory.decodeStream(in,null,options);}
                if(bitmap==null)continue;
                if(bitmap.getWidth()!=width||bitmap.getHeight()!=height){Bitmap small=Bitmap.createScaledBitmap(bitmap,width,height,true);if(small!=bitmap)bitmap.recycle();bitmap=small;}
                bitmap.prepareToDraw();CACHE.put(cacheKey,bitmap);return bitmap;
            }catch(Exception ignored){}
        }
        // Failed bundled keys are stable for this process; do not retry them on every widget refresh.
        if(MISSING.size()<256)MISSING.add(key);return null;
    }
}
