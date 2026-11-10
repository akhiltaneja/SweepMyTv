package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.*;
import java.io.*;

/** Bundled flat illustrations: DiceBear Thumbs (CC0) and Twemoji (CC BY 4.0). No runtime avatar service or generated AI artwork. */
final class Avatars {
    static final int COUNT=36;
    static final int[] GROUP_STARTS={12,24,0};
    static final String[] GROUPS={"Animals","Little things","Simple faces"};
    static final String[] GROUP_DETAILS={"Fox, panda, rabbit, penguin and more","Robots, space, sweets and other favourites","The original colourful characters"};
    private static final int[] DRAWABLES={R.drawable.avatar_fox,R.drawable.avatar_cat,R.drawable.avatar_panda,R.drawable.avatar_koala,R.drawable.avatar_rabbit,R.drawable.avatar_frog,R.drawable.avatar_penguin,R.drawable.avatar_dog,R.drawable.avatar_tiger,R.drawable.avatar_owl,R.drawable.avatar_bear,R.drawable.avatar_unicorn,R.drawable.avatar_robot,R.drawable.avatar_alien,R.drawable.avatar_ghost,R.drawable.avatar_rocket,R.drawable.avatar_star,R.drawable.avatar_planet,R.drawable.avatar_sun,R.drawable.avatar_moon,R.drawable.avatar_cupcake,R.drawable.avatar_rainbow,R.drawable.avatar_avocado,R.drawable.avatar_strawberry};
    static final String[] NAMES={"Mango","Peach","Mint","Berry","Sunny","Sky","Cocoa","Lilac","Coral","Lime","Ocean","Rose","Fox","Cat","Panda","Koala","Rabbit","Frog","Penguin","Dog","Tiger","Owl","Bear","Unicorn","Robot","Alien","Ghost","Rocket","Star","Planet","Sun","Moon","Cupcake","Rainbow","Avocado","Strawberry"};
    private static final android.util.LruCache<String,Bitmap> cache=new android.util.LruCache<>(14);
    static void clear(){cache.evictAll();}
    static Bitmap active(Context c){String id=Profiles.active(c);int avatar=Profiles.avatar(c);return avatar<0?photo(c,id):preset(c,avatar);}
    static Bitmap preset(Context c,int index){
        if(index<0||index>=COUNT)return null;String key="preset"+index;Bitmap old=cache.get(key);if(old!=null)return old;
        if(index>=12){
            android.graphics.drawable.Drawable vector=c.getDrawable(DRAWABLES[index-12]);if(vector==null)return null;
            Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);vector.setBounds(0,0,128,128);vector.draw(new Canvas(b));cache.put(key,b);return b;
        }
        try(InputStream in=c.getAssets().open("avatars/thumbs-"+index+".png")){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;Bitmap b=BitmapFactory.decodeStream(in,null,opts);if(b!=null)cache.put(key,b);return b;}catch(IOException e){return null;}
    }
    private static Bitmap photo(Context c,String id){
        String key="photo"+id;Bitmap old=cache.get(key);if(old!=null)return old;
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=4;Bitmap b=BitmapFactory.decodeFile(Profiles.photo(c,id).getPath(),opts);if(b!=null)cache.put(key,b);return b;
    }
    static android.graphics.drawable.Drawable drawable(Context c,int size){
        Bitmap b=active(c);if(b==null)return null;
        android.graphics.drawable.Drawable d=new android.graphics.drawable.Drawable(){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            public void draw(Canvas canvas){Rect r=getBounds();int save=canvas.save();Path clip=new Path();clip.addCircle(r.exactCenterX(),r.exactCenterY(),r.width()/2f,Path.Direction.CW);canvas.clipPath(clip);canvas.drawBitmap(b,null,r,paint);canvas.restoreToCount(save);}
            public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        };d.setBounds(0,0,size,size);return d;
    }
    static boolean savePhoto(Context c,String id,byte[] bytes){
        if(bytes==null||bytes.length>8*1024*1024||!Profiles.ids(c).contains(id))return false;
        try{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
            if(o.outWidth<=0||o.outHeight<=0)return false;
            o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1024)o.inSampleSize*=2;o.inJustDecodeBounds=false;
            Bitmap source=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(source==null)return false;
            Bitmap output=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);float scale=Math.max(512f/source.getWidth(),512f/source.getHeight());float w=source.getWidth()*scale,h=source.getHeight()*scale;
            new Canvas(output).drawBitmap(source,null,new RectF((512-w)/2,(512-h)/2,(512+w)/2,(512+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));source.recycle();
            File file=Profiles.photo(c,id),tmp=new File(file.getPath()+".tmp");boolean ok;
            try(OutputStream out=new FileOutputStream(tmp)){ok=output.compress(Bitmap.CompressFormat.JPEG,90,out);}output.recycle();
            if(!ok||!tmp.renameTo(file)){tmp.delete();return false;}Profiles.photoSelected(c,id);return true;
        }catch(Exception|OutOfMemoryError e){return false;}
    }
}
