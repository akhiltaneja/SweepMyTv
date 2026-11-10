package com.sweepmytv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Reference-style native widgets with real portraits, team marks and circuit art. */
final class FormulaOneCard extends View {
    private final FormulaOne.Card card;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Rect visible = new Rect();
    private final float dp;
    private final Typeface regular, bold;
    private final Drawable logo;
    private Shader fill;
    private boolean focusedWindow;
    private final F1Assets.Loader assetLoader;
    private java.util.concurrent.Future<?> assetRequest;
    private int assetGeneration;
    private java.util.Map<String,Bitmap> pictures=java.util.Collections.emptyMap();
    private Bitmap circuit;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!focusedWindow || !isAttachedToWindow() || card.target <= 0) return;
            if (isShown() && getGlobalVisibleRect(visible)) invalidate();
            postDelayed(this, 1000);
        }
    };
    FormulaOneCard(Context context, FormulaOne.Card card) {
        this(context,card,F1Assets::load);
    }
    FormulaOneCard(Context context,FormulaOne.Card card,F1Assets.Loader assetLoader) {
        super(context); this.card = card; this.assetLoader=assetLoader; dp = getResources().getDisplayMetrics().density;
        regular = Typeface.create("sans-serif", Typeface.NORMAL); bold = Typeface.create("sans-serif", Typeface.BOLD);
        logo = context.getDrawable(R.drawable.f1_mark);
        setContentDescription(card.kicker + ", " + card.title + ", " + card.metric + ", " + card.body);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();focusedWindow=hasWindowFocus();restart();
        final int generation=++assetGeneration;
        assetRequest=assetLoader.load(getContext(),card,dp,ready->{
            if(generation!=assetGeneration||!isAttachedToWindow())return;
            pictures=ready.images;circuit=ready.circuit;assetRequest=null;invalidate();
        });
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);assetGeneration++;if(assetRequest!=null)assetRequest.cancel(false);assetRequest=null;
        pictures=java.util.Collections.emptyMap();circuit=null;super.onDetachedFromWindow();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); focusedWindow = focus; restart(); }
    private void restart() { removeCallbacks(tick); if (focusedWindow && card.target > 0) post(tick); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        int tint = "next".equals(card.key) ? 0xFF39362E : "drivers".equals(card.key) ? 0xFF155448 : 0xFF303132;
        fill = new LinearGradient(0, 0, w, h, new int[]{tint, 0xFF111214}, null, Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth() / dp, h = getHeight() / dp;
        bounds.set(.5f * dp, .5f * dp, getWidth() - .5f * dp, getHeight() - .5f * dp);
        paint.setShader(fill); canvas.drawRoundRect(bounds, 18 * dp, 18 * dp, paint); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp); paint.setColor(0x24FFFFFF);
        canvas.drawRoundRect(bounds, 18 * dp, 18 * dp, paint); paint.setStyle(Paint.Style.FILL);
        Path clip = new Path(); clip.addRoundRect(bounds, 18 * dp, 18 * dp, Path.Direction.CW); canvas.save(); canvas.clipPath(clip);
        if ("next".equals(card.key)) next(canvas, w, h);
        else if ("drivers".equals(card.key) || "teams".equals(card.key)) champion(canvas, w, h);
        else if ("weekend".equals(card.key)) weekend(canvas, w, h);
        else classification(canvas, w, h);
        canvas.restore();
    }
    private void next(Canvas c, float w, float h) {
        paint.setColor(0xFFE90012); c.drawCircle(35 * dp, 34 * dp, 20 * dp, paint); mark(c, 20, 30, 31, 8);
        text(c, card.title.replace("Grand Prix", "GP"), 14, 65, 30, w - 80, 0xFFFFFFFF, true);
        String date = card.lines.size() > 1 ? card.lines.get(1) : card.metric;
        text(c, date, 8.5f, 65, 46, w - 80, 0xFFC5C4C1, false);
        divider(c, 64, w);
        text(c, card.lines.isEmpty() ? "NEXT GRAND PRIX" : card.lines.get(0), 10, 17, 81, w - 34, 0xFFE8E6E2, false);
        if (circuit != null) image(c, circuit, new RectF(65, 88, w - 36, 173), false, 255);
        else text(c, "NEXT GRAND PRIX", 19, 17, 130, w - 34, 0xFFFFFFFF, true);
        text(c, "CHAMPIONSHIP LEADERS", 8, 17, 188, w - 34, 0xFFB5B6B4, true);
        for (int i = 0; i < Math.min(3, card.table.size()); i++) {
            FormulaOne.Line row = card.table.get(i); float x = 36 + i * 73;
            avatar(c, row, x, 213, 19);
            text(c, F1Assets.surname(row.name).toUpperCase(java.util.Locale.ROOT), 8, x - 24, 243, 62, 0xFFE2E2E2, true);
        }
        paint.setColor(0x24FFFFFF); c.drawRoundRect(new RectF(17 * dp, 254 * dp, (w - 17) * dp, 290 * dp), 18 * dp, 18 * dp, paint);
        text(c, "RACE IN", 8.5f, 29, 276, 47, 0xFFC4C5C4, true);
        String metric = card.target > 0 ? FormulaOne.countdown(card.target, System.currentTimeMillis()) : card.metric;
        text(c, metric, 16.5f, 78, 278, w - 106, 0xFFFFFFFF, true);
        foot(c, card.foot, w, h);
    }
    private void classification(Canvas c, float w, float h) {
        mark(c, 16, 16, 43, 11);
        text(c, "latest".equals(card.key) ? "RACE RESULT" : "SESSION RESULT", 9, w - 105, 25, 90, 0xFFEAEAEA, true);
        divider(c, 38, w);
        text(c, card.title.replace("Grand Prix", "GP"), 13, 16, 56, w - 32, 0xFFFFFFFF, true);
        if (card.table.isEmpty()) {
            text(c, card.metric, 13, 16, 95, w - 32, 0xFFE7E7E7, true);
            text(c, "Free published classifications", 10, 16, 115, w - 32, 0xFFB2B2B2, false);
        }
        for (int i = 0; i < Math.min(3, card.table.size()); i++) {
            FormulaOne.Line row = card.table.get(i); float y = 82 + i * 29;
            text(c, row.rank, 10.5f, 16, y, 16, 0xFFAFAFAF, false);
            avatar(c, row, 47, y - 4, 12.5f);
            image(c, pictures.get(row.logo), new RectF(66, y - 13, 84, y + 5), true, 255);
            text(c, F1Assets.surname(row.name), 12, 92, y + 1, w - 175, 0xFFF8F8F8, true);
            text(c, row.value, 10.5f, w - 76, y + 1, 61, 0xFFEDEDED, false);
            divider(c, y + 13, w);
        }
        foot(c, card.foot, w, h);
    }
    private void champion(Canvas c, float w, float h) {
        boolean driver = "drivers".equals(card.key);
        text(c, driver ? "DRIVER LEADER" : "TEAM LEADER", 8, 13, 18, w - 26, 0xFFDFE4E1, true);
        if (card.table.isEmpty()) { text(c, "Not loaded", 12, 13, 54, w - 26, 0xFFFFFFFF, true); return; }
        FormulaOne.Line leader = card.table.get(0);
        if (driver) {
            Bitmap photo = pictures.get(leader.portrait);
            if (photo != null) {
                Rect src = new Rect(0, 0, photo.getWidth(), Math.min(photo.getHeight(), (int)(photo.getWidth()*1.6f)));
                c.drawBitmap(photo, src, scale(new RectF(76, 20, w+18, 20+(w+18-76)*1.6f)), paint);
            }
            text(c, F1Assets.surname(leader.name), 12, 13, 40, w - 26, 0xFFFFFFFF, true);
        } else {
            text(c, leader.name, 12, 13, 40, w - 26, 0xFFFFFFFF, true);
            image(c, pictures.get(leader.logo), new RectF(96, 49, 132, 85), true, 255);
            image(c, pictures.get("car-" + F1Assets.team(leader.name)), new RectF(40, 83, 156, 112), true, 90);
        }
        text(c, leader.value, 27, 13, 76, 80, 0xFFFFFFFF, true);
        text(c, "PTS", 8, 15, 89, 42, 0xFFBDC7C3, true);
        text(c, "Full standings  ›", 8, 13, 104, w - 26, 0xFFE0E3E1, false);
    }
    private void weekend(Canvas c, float w, float h) {
        mark(c, 15, 12, 34, 8.5f);
        text(c, "RACE WEEKEND", 9, 60, 20, w - 75, 0xFFF1F1F1, true);
        divider(c, 29, w);
        int complete = 0;
        java.util.List<String> future = new java.util.ArrayList<>();
        for (String line : card.lines) { if (line.contains(" · Completed")) complete++; else if (!line.contains(" · Awaiting result") && !line.contains(" · Cancelled")) future.add(line); }
        text(c, complete > 0 ? complete + " sessions completed · OK for details" : card.title.replace("Grand Prix", "GP"), 9, 15, 44, w - 30, 0xFFAEB1B2, false);
        for (int i = 0; i < Math.min(3, future.size()); i++) {
            String line = future.get(i).replace(" · Scheduled", "").replace(" · Awaiting result", " · awaiting");
            text(c, line, 8.8f, 15, 62 + i * 15, w - 30, 0xFFECECEC, false);
        }
        foot(c, card.foot, w, h);
    }
    private void avatar(Canvas c, FormulaOne.Line row, float x, float y, float r) {
        paint.setColor(row.color); c.drawCircle(x * dp, y * dp, r * dp, paint);
        Bitmap photo = pictures.get(row.portrait); if (photo == null) return;
        Path clip = new Path(); clip.addCircle(x * dp, y * dp, r * dp, Path.Direction.CW); c.save(); c.clipPath(clip);
        int inset = Math.round(photo.getWidth() * .12f);
        Rect src = new Rect(inset, 0, photo.getWidth() - inset, Math.min(photo.getHeight(), photo.getWidth() - inset * 2));
        c.drawBitmap(photo, src, scale(new RectF(x-r,y-r,x+r,y+r)), paint); c.restore();
    }
    private void mark(Canvas c, float x, float y, float w, float h) {
        if (logo == null) return;
        logo.setBounds(Math.round(x*dp),Math.round(y*dp),Math.round((x+w)*dp),Math.round((y+h)*dp)); logo.draw(c);
    }
    private RectF scale(RectF r) { return new RectF(r.left * dp, r.top * dp, r.right * dp, r.bottom * dp); }
    private void image(Canvas c, Bitmap b, RectF target, boolean fit, int alpha) {
        if (b == null) return;
        float factor = Math.min(target.width()/b.getWidth(), target.height()/b.getHeight());
        float w = b.getWidth()*factor, h = b.getHeight()*factor;
        RectF dest = new RectF(target.centerX()-w/2, target.centerY()-h/2, target.centerX()+w/2, target.centerY()+h/2);
        paint.setAlpha(alpha); c.drawBitmap(b, null, scale(dest), paint); paint.setAlpha(255);
    }
    private void divider(Canvas c, float y, float w) { paint.setColor(0x16FFFFFF); c.drawRect(15*dp,y*dp,(w-15)*dp,(y+.5f)*dp,paint); }
    private void foot(Canvas c, String value, float w, float h) { text(c, value, 6.8f, 15, h-7, w-30, 0xFF8E9296, false); }
    private void text(Canvas c, String value, float size, float x, float y, float width, int color, boolean heavy) {
        paint.setTextSize(size*dp); paint.setColor(color); paint.setTypeface(heavy ? bold : regular);
        float available = Math.max(0,width*dp);
        if (paint.measureText(value)>available) { int end=paint.breakText(value,true,Math.max(0,available-paint.measureText("…")),null); value=value.substring(0,Math.max(0,end))+"…"; }
        c.drawText(value,x*dp,y*dp,paint);
    }
}
