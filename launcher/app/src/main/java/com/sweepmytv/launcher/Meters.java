package com.sweepmytv.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.*;

/** A compact, optional status pill. Every metric can be switched off independently. */
public class Meters extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;private int text=0xFFEDF2FA;private String line="";
    public Meters(Context c,AttributeSet a){super(c,a);dp=getResources().getDisplayMetrics().density;paint.setTypeface(getResources().getFont(R.font.outfit));paint.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);}
    void setColor(int text){this.text=text;invalidate();}
    void update(){
        List<String> bits=new ArrayList<>();Context c=getContext();
        if(HomeOptions.stat(c,"ram")){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();ActivityManager manager=c.getSystemService(ActivityManager.class);if(manager!=null){manager.getMemoryInfo(info);bits.add("RAM "+gb(info.availMem));}}
        if(HomeOptions.stat(c,"storage"))try{bits.add("Disk "+gb(new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes()));}catch(RuntimeException ignored){}
        if(HomeOptions.stat(c,"uptime")){long minutes=SystemClock.elapsedRealtime()/60_000;bits.add("Up "+minutes/60+"h "+minutes%60+"m");}
        if(HomeOptions.stat(c,"codex")){CodexUsage usage=CodexUsage.read(c);bits.add("Codex "+(usage==null?"not connected":usage.compact(System.currentTimeMillis())));}
        line=String.join("  ·  ",bits);setContentDescription(line+". RAM and disk values are free space.");
        setVisibility(HomeOptions.prefs(c).getBoolean("meters",true)&&!bits.isEmpty()?VISIBLE:GONE);requestLayout();invalidate();
    }
    String content(){return line;}
    private static String gb(long bytes){return bytes<1e9?Math.round(bytes/1e6)+" MB":String.format(Locale.getDefault(),"%.1f GB",bytes/1e9);}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize((int)(paint.measureText(line)+16*dp),w),resolveSize((int)(18*dp),h));}
    @Override protected void onDraw(Canvas canvas){paint.setColor((text&0xFFFFFF)|0x10000000);canvas.drawRoundRect(0,0,getWidth(),getHeight(),9*dp,9*dp,paint);paint.setColor((text&0xFFFFFF)|0xD9000000);String visible=line;while(paint.measureText(visible)>getWidth()-16*dp&&visible.length()>1)visible=visible.substring(0,visible.length()-2)+"…";canvas.drawText(visible,8*dp,(getHeight()-paint.ascent()-paint.descent())/2,paint);}
}
