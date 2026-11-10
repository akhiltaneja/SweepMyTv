package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.*;

/** A small, readable header tile: one glanceable value, headline or calendar badge. */
final class HomeInsightCard extends View {
    private final HomeInsights.Card card;private final float dp;private final int tint;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint regularText=new TextPaint(Paint.ANTI_ALIAS_FLAG),strongText=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint text=regularText;
    private final RectF bounds=new RectF(),badge=new RectF();private Shader fill;
    private final String city,month,day,countdown,newsLabel;
    private boolean highlighted;private int accent;
    HomeInsightCard(Context context,HomeInsights.Card card,int tint){
        super(context);this.card=card;this.tint=tint;dp=getResources().getDisplayMetrics().density;
        Typeface font=getResources().getFont(R.font.outfit);regularText.setTypeface(font);regularText.setFontVariationSettings("'wght' 500");strongText.setTypeface(font);strongText.setFontVariationSettings("'wght' 650");
        String[] source=card.body.split(" · ",2);newsLabel=source.length>1?source[1].toUpperCase(Locale.ROOT)+" · "+source[0].replace(" Tech","").replace(" Markets","").replace(" AI",""):"US TECH & MARKETS";
        city=card.kicker.replace("AIR · ","").split(",",2)[0];countdown=card.foot.split(" · ",2)[0];
        java.util.regex.Matcher date=java.util.regex.Pattern.compile(".*?([0-9]{1,2}) +([A-Za-z]+)").matcher(card.body);
        if("festival".equals(card.kind)&&date.find()){day=date.group(1);String m=date.group(2);month=m.substring(0,Math.min(3,m.length())).toUpperCase(Locale.ROOT);}else{day="—";month="NEXT";}
        setContentDescription(card.kicker+", "+card.metric+", "+card.body+", "+card.foot);
    }
    void highlight(boolean on,int color){if(highlighted==on&&accent==color)return;highlighted=on;accent=color;invalidate();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        bounds.set(dp/2,dp/2,w-dp/2,h-dp/2);int base=TimeOfDay.blend(tint,0xFF101725,.45f);
        fill=new LinearGradient(0,0,0,h,new int[]{TimeOfDay.blend(base,0xFFFFFFFF,.035f),TimeOfDay.blend(base,0xFF0C1320,.16f)},null,Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas){
        paint.setShader(fill);canvas.drawRoundRect(bounds,10*dp,10*dp,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((highlighted?1.7f:1f)*dp);paint.setColor(highlighted?accent:0x25FFFFFF);canvas.drawRoundRect(bounds,10*dp,10*dp,paint);paint.setStyle(Paint.Style.FILL);
        float width=getWidth()/dp;
        if("air".equals(card.kind)){
            paint.setColor((card.color&0xFFFFFF)|0xA0000000);badge.set(0,8*dp,2*dp,24*dp);canvas.drawRoundRect(badge,dp,dp,paint);
            line(canvas,card.metric,card.metric.length()>3?15:20,8,24,40,card.color,true);
            line(canvas,city,6.5f,53,9,width-60,0xFFB4C0D3,true);
            line(canvas,"US AQI",7,53,20,width-60,0xFFD4DCEB,true);
            line(canvas,"estimate",6,53,28,width-60,0xFFA9B8CF,false);
        }else if("news".equals(card.kind)){
            newsBadge(canvas);
            line(canvas,newsLabel,7,40,9,width-50,0xFFAEBFD9,true);
            wrapped(canvas,card.metric,9.5f,40,20,width-50,2,0xFFF0F3FA);
        }else{
            paint.setColor(0x20E5C797);badge.set(8*dp,6*dp,32*dp,30*dp);canvas.drawRoundRect(badge,5*dp,5*dp,paint);
            line(canvas,month,6,11,14,20,0xFFD7B889,true);
            line(canvas,day,11,13,26,18,0xFFF0D9AF,true);
            line(canvas,"NEXT FESTIVAL · "+countdown.toUpperCase(Locale.ROOT),7,40,11,width-50,0xFFD8C3A3,true);
            line(canvas,card.metric,12,40,27,width-50,0xFFF0E0C8,true);
        }
    }
    private void newsBadge(Canvas c){
        paint.setColor(0x2694A8DF);badge.set(8*dp,6*dp,32*dp,30*dp);c.drawRoundRect(badge,6*dp,6*dp,paint);
        paint.setColor(0xFFBBCBEE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.1f*dp);paint.setStrokeCap(Paint.Cap.ROUND);
        badge.set(14*dp,12*dp,26*dp,24*dp);c.drawRoundRect(badge,2*dp,2*dp,paint);
        c.drawLine(17*dp,15*dp,23*dp,15*dp,paint);c.drawLine(17*dp,18*dp,23*dp,18*dp,paint);c.drawLine(17*dp,21*dp,21*dp,21*dp,paint);paint.setStyle(Paint.Style.FILL);
    }
    private void line(Canvas c,String value,float size,float x,float y,float max,int color,boolean bold){text=bold?strongText:regularText;text.setTextSize(size*dp);text.setColor(color);c.drawText(TextUtils.ellipsize(value==null?"":value,text,Math.max(0,max)*dp,TextUtils.TruncateAt.END).toString(),x*dp,y*dp,text);}
    private void wrapped(Canvas c,String value,float size,float x,float y,float max,int lines,int color){
        String rest=value==null?"":value;text=regularText;text.setTextSize(size*dp);text.setColor(color);
        for(int i=0;i<lines&&!rest.isEmpty();i++){
            int n=text.breakText(rest,true,max*dp,null);if(n<=0)break;
            if(i==lines-1){line(c,rest,size,x,y+i*(size+3),max,color,false);break;}
            if(n<rest.length()){int space=rest.lastIndexOf(' ',n);if(space>0)n=space;}
            c.drawText(rest.substring(0,n),x*dp,(y+i*(size+3))*dp,text);rest=rest.substring(n).trim();
        }
    }
}
