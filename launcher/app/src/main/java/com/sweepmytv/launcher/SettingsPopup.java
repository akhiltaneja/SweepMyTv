package com.sweepmytv.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;

/** Lightweight settings modal: one dim layer and a native card, without blur or full-screen bitmap copies. */
final class SettingsPopup extends View {
    static final int TEXT=0xFFF1F4FA,MUTED=0xFFA9B6C9;
    private final float dp;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel=new RectF(),temp=new RectF();
    private final Typeface regular,bold;
    private String heading="Settings",name="",subtitle="Personalise your home screen";
    private boolean grid;
    private int accent=0xFFBCB8EA;
    private Shader surface;
    private Bitmap avatar;
    SettingsPopup(Context c){super(c);dp=getResources().getDisplayMetrics().density;Typeface font=getResources().getFont(R.font.outfit);regular=android.os.Build.VERSION.SDK_INT>=28?Typeface.create(font,450,false):font;bold=android.os.Build.VERSION.SDK_INT>=28?Typeface.create(font,600,false):Typeface.create(font,Typeface.BOLD);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    RectF content(int width,int height){bounds(width,height);return new RectF(panel.left+24*dp,panel.top+104*dp,panel.right-24*dp,panel.bottom-44*dp);}
    private void bounds(int w,int h){float pw=Math.min(824*dp,w-64*dp),ph=Math.min(440*dp,h-64*dp);panel.set((w-pw)/2,(h-ph)/2,(w+pw)/2,(h+ph)/2);}
    void show(String heading,String name,int accent,boolean grid){
        this.heading=grid?"Settings":heading==null?"Settings":heading;this.name=HomeOptions.cleanName(name);this.accent=accent;this.grid=grid;
        subtitle=description(this.heading);avatar=Avatars.active(getContext());surface=null;setVisibility(VISIBLE);animate().cancel();setAlpha(1f);invalidate();
    }
    void hide(boolean animate){animate().cancel();if(animate)animate().alpha(0f).setDuration(110).withEndAction(()->setVisibility(GONE)).start();else setVisibility(GONE);}
    private static String description(String heading){
        if(heading.contains("Profile"))return "A few personal touches for your home screen";
        if(heading.contains("Appearance"))return "Sky colours, backgrounds and motion";
        if(heading.contains("section"))return "Choose the rows you want on Home";
        if(heading.contains("Watching")||heading.contains("watching")||heading.contains("Continue"))return "Your content, artwork and discovery preferences";
        if(heading.contains("Apps"))return "Arrange the apps you reach for most";
        if(heading.contains("widget"))return "Choose the information you want at a glance";
        if(heading.contains("onnection")||heading.contains("Remote"))return "Your TV, connected services and remote controls";
        if(heading.contains("pdate")||heading.contains("maintenance"))return "Review updates and keep Sweep TV up to date";
        if(heading.contains("About"))return "Version details, open source and acknowledgements";
        return "Personalise your home screen";
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){bounds(w,h);surface=null;}
    @Override protected void onDraw(Canvas c){
        bounds(getWidth(),getHeight());c.drawColor(0xB20A101A);
        if(surface==null)surface=new LinearGradient(panel.left,panel.top,panel.right,panel.bottom,new int[]{0xFF232E40,0xFF151D2A,0xFF101825},null,Shader.TileMode.CLAMP);
        paint.setShader(surface);c.drawRoundRect(panel,25*dp,25*dp,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp);paint.setColor(0xFF465167);c.drawRoundRect(panel,25*dp,25*dp,paint);paint.setStyle(Paint.Style.FILL);
        float x=panel.left+26*dp,y=panel.top;
        text(c,"SWEEP TV  /  "+(grid?"YOUR SPACE":"SETTINGS"),x,y+25*dp,9,0xFFB4C2D9,false);
        text(c,heading,x,y+56*dp,27,TEXT,true);
        text(c,subtitle,x,y+79*dp,11.5f,MUTED,false);
        if(grid&&!name.isEmpty()){
            float right=panel.right-26*dp;paint.setColor(0x20FFFFFF);temp.set(right-134*dp,y+23*dp,right,y+72*dp);c.drawRoundRect(temp,16*dp,16*dp,paint);
            paint.setColor(accent);c.drawCircle(right-108*dp,y+47*dp,15*dp,paint);
            if(avatar!=null){int save=c.save();Path clip=new Path();clip.addCircle(right-108*dp,y+47*dp,15*dp,Path.Direction.CW);c.clipPath(clip);c.drawBitmap(avatar,null,new RectF(right-123*dp,y+32*dp,right-93*dp,y+62*dp),paint);c.restoreToCount(save);}else text(c,name.substring(0,1).toUpperCase(java.util.Locale.ROOT),right-113*dp,y+53*dp,16,TimeOfDay.foreground(accent),true);
            text(c,"LOCAL PROFILE",right-84*dp,y+40*dp,7.5f,MUTED,false);
            text(c,name.length()>12?name.substring(0,11)+"…":name,right-84*dp,y+58*dp,14,TEXT,true);
        }
        paint.setColor(0x24FFFFFF);c.drawRect(panel.left+24*dp,panel.bottom-35*dp,panel.right-24*dp,panel.bottom-34.5f*dp,paint);
        text(c,grid?"Arrows to browse   ·   OK to open":"Arrows to browse   ·   OK to change",panel.left+26*dp,panel.bottom-14*dp,10.5f,MUTED,false);
        String back=grid?"BACK   Close settings":"BACK   Go back";paint.setTextSize(10.5f*dp);paint.setTypeface(regular);float width=paint.measureText(back);
        text(c,back,panel.right-26*dp-width,panel.bottom-14*dp,10.5f,TEXT,false);
    }
    private void text(Canvas c,String value,float x,float y,float size,int color,boolean heavy){paint.setColor(color);paint.setTextSize(size*dp);paint.setTypeface(heavy?bold:regular);c.drawText(value,x,y,paint);}

    static final class RowStyle {final boolean category;int accent;boolean focused;RowStyle(boolean category,int accent){this.category=category;this.accent=accent;}}
    static GradientDrawable shape(int fill,int stroke,float radius,float dp){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(radius*dp);if(stroke!=0)d.setStroke(Math.max(1,Math.round(dp)),stroke);return d;}
    static void prepareRow(View row,int accent,boolean category){row.setTag(new RowStyle(category,accent));focus(row,false,accent);}
    static void focus(View row,boolean focused,int accent){
        Object tag=row.getTag();if(!(tag instanceof RowStyle))return;RowStyle style=(RowStyle)tag;style.focused=focused;style.accent=accent;float dp=row.getResources().getDisplayMetrics().density;
        row.setBackground(shape(focused?TimeOfDay.blend(0xFF29364B,accent,.14f):0xFF222D3F,focused?accent:0xFF3A465A,14,dp));
        TextView label=row.findViewById(R.id.input_label),value=row.findViewById(R.id.input_status);label.setTextColor(TEXT);value.setTextColor(focused?0xFFE4EBF7:MUTED);
        ImageView icon=row.findViewWithTag("settings-icon");if(icon!=null){icon.setImageTintList(ColorStateList.valueOf(accent));icon.setBackground(shape((accent&0xFFFFFF)|0x22000000,0,10,dp));}
        updateStatus(row,value.getVisibility()==GONE?null:value.getText().toString());
        row.setContentDescription(label.getText()+(value.getVisibility()==VISIBLE?", "+value.getText():""));
    }
    static void updateStatus(View row,String status){
        Object tag=row.getTag();if(!(tag instanceof RowStyle))return;RowStyle style=(RowStyle)tag;TextView value=row.findViewById(R.id.input_status);float dp=row.getResources().getDisplayMetrics().density;
        if(style.category)return;
        value.setGravity(android.view.Gravity.CENTER);value.setPadding(Math.round(10*dp),Math.round(5*dp),Math.round(10*dp),Math.round(5*dp));value.setMaxWidth(Math.round(240*dp));value.setEllipsize(TextUtils.TruncateAt.END);
        value.setBackground(status==null?null:shape(0x300A111E,0,8,dp));
        boolean on="On".equals(status)||"Shown".equals(status),off="Off".equals(status)||"Hidden".equals(status);
        Drawable toggle=on||off?new Toggle(dp,on,style.accent):null;
        value.setCompoundDrawablePadding(Math.round(9*dp));value.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,toggle,null);
        TextView label=row.findViewById(R.id.input_label);row.setContentDescription(label.getText()+(status==null?"":", "+status));
    }
    private static final class Toggle extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final float dp;final boolean on;final int accent;
        Toggle(float dp,boolean on,int accent){this.dp=dp;this.on=on;this.accent=accent;}
        @Override public int getIntrinsicWidth(){return Math.round(29*dp);}@Override public int getIntrinsicHeight(){return Math.round(16*dp);}
        @Override public void draw(Canvas c){Rect b=getBounds();float h=b.height(),r=h/2;p.setColor(on?accent:0xFF66748B);c.drawRoundRect(b.left,b.top,b.right,b.bottom,r,r,p);p.setColor(on?TimeOfDay.foreground(accent):0xFFF1F4FA);c.drawCircle(on?b.right-r:b.left+r,b.exactCenterY(),r-3*dp,p);}
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
