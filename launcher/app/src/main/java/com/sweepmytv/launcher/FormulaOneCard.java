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
    private static final int RED = 0xFFE10600, WHITE = 0xFFF7F7FB;
    private static final int MUTED = 0xFFB0B0BC, TRACK = 0xFF2C2C36;
    private static final float RADIUS = 10;
    private final FormulaOne.Card card;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Rect visible = new Rect();
    private final Path clip = new Path(), stripe = new Path();
    private final float dp;
    private final Typeface regular, bold;
    private final Drawable logo;
    private Shader fill, raceHeader;
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
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        logo = context.getDrawable(R.drawable.f1_mark);
        setContentDescription((card.champion ? "Champions, " : card.kicker + ", ")
                + card.title + ", " + card.metric + ", " + card.body);
    }
    int focusAccent() { return card.champion ? 0xFFFFD77A : 0xFFFF6A63; }

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
        fill = new LinearGradient(0, 0, w, h, card.champion ? 0xFF68481C : 0xFF22222D,
                card.champion ? 0xFF26180B : 0xFF111118, Shader.TileMode.CLAMP);
        raceHeader = new LinearGradient(0, 0, w, 108 * dp, 0xFFAB0500, RED, Shader.TileMode.CLAMP);
        bounds.set(.5f * dp, .5f * dp, w - .5f * dp, h - .5f * dp);
        clip.reset();
        clip.addRoundRect(bounds, RADIUS * dp, RADIUS * dp, Path.Direction.CW);
    }
    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth() / dp, h = getHeight() / dp;
        paint.setShader(fill);
        canvas.drawRoundRect(bounds, RADIUS * dp, RADIUS * dp, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp); paint.setColor(TRACK);
        canvas.drawRoundRect(bounds, RADIUS * dp, RADIUS * dp, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.save();
        canvas.clipPath(clip);
        if (!"next".equals(card.key)) block(canvas, 0, 0, w, 2, card.champion ? 0xFFFFD77A : RED);
        if ("next".equals(card.key)) next(canvas, w, h);
        else if ("drivers".equals(card.key) || "teams".equals(card.key)) champion(canvas, w, h);
        else if ("weekend".equals(card.key)) weekend(canvas, w, h);
        else classification(canvas, w, h);
        canvas.restore();
    }
    private void next(Canvas c, float w, float h) {
        paint.setShader(raceHeader);
        c.drawRect(0, 0, w * dp, 107 * dp, paint);
        paint.setShader(null);
        stripes(c, w, 0, 107, 0x24FFFFFF);
        mark(c, 17, 17, 52, 13);
        text(c, "NEXT GRAND PRIX", 10, 82, 28, w - 100, WHITE, true);
        text(c, card.title.replace("Grand Prix", "GP"), 27, 16, 66, w - 32, WHITE, true);
        String date = card.lines.size() > 1 ? card.lines.get(1) : card.metric;
        text(c, date, 10, 17, 88, w - 34, WHITE, false);

        String place = card.lines.isEmpty() ? "" : card.lines.get(0);
        text(c, place, 11, 17, 128, w - 34, WHITE, true);
        if (circuit != null) {
            image(c, circuit, new RectF(48, 135, w - 33, 180), false, 235);
        } else if (!card.body.isEmpty()) {
            String[] details = card.body.split("\n");
            if (details.length > 1) text(c, details[1], 10, 17, 149, w - 34, MUTED, false);
            if (details.length > 2) text(c, details[2].toUpperCase(java.util.Locale.ROOT), 9, 17, 171, w - 34, MUTED, true);
        }
        divider(c, 180, w);
        if (!card.table.isEmpty()) text(c, "CHAMPIONSHIP LEADERS", 8.5f, 17, 195, w - 34, MUTED, true);
        for (int i = 0; i < Math.min(3, card.table.size()); i++) {
            FormulaOne.Line row = card.table.get(i); float x = 38 + i * 76;
            avatar(c, row, x, 217, 16);
            text(c, F1Assets.surname(row.name).toUpperCase(java.util.Locale.ROOT), 8.5f, x - 22, 244, 66, WHITE, true);
        }
        block(c, 16, 254, w - 16, 289, 0xFF08080C);
        block(c, 16, 254, 19, 289, RED);
        text(c, card.target > 0 ? "RACE IN" : "STATUS", 9, 28, 275, 46, MUTED, true);
        String metric = card.target > 0 ? FormulaOne.countdown(card.target, System.currentTimeMillis()) : card.metric;
        text(c, metric, card.target > 0 ? 19 : 12, 74, 278, w - 92, WHITE, true);
    }
    private void classification(Canvas c, float w, float h) {
        block(c, 0, 2, w, 37, 0xFF101017);
        mark(c, 16, 14, 44, 11);
        text(c, "latest".equals(card.key) ? "RACE RESULT" : "SESSION RESULT", 10, w - 107, 24, 92, WHITE, true);
        divider(c, 37, w);
        text(c, card.title.replace("Grand Prix", "GP"), 13, 16, 56, w - 32, 0xFFFFFFFF, true);
        if (card.table.isEmpty()) {
            text(c, card.metric, 13, 16, 95, w - 32, 0xFFE7E7E7, true);
            text(c, "Free published classifications", 10, 16, 115, w - 32, 0xFFB2B2B2, false);
        }
        for (int i = 0; i < Math.min(3, card.table.size()); i++) {
            FormulaOne.Line row = card.table.get(i); float y = 82 + i * 29;
            block(c, 15, y - 12, 32, y + 6, i == 0 ? RED : TRACK);
            text(c, row.rank, 10, 20, y + 1, 11, WHITE, true);
            avatar(c, row, 47, y - 4, 12.5f);
            image(c, pictures.get(row.logo), new RectF(66, y - 13, 84, y + 5), true, 255);
            text(c, F1Assets.surname(row.name), 12, 92, y + 1, w - 175, 0xFFF8F8F8, true);
            text(c, row.value, 10.5f, w - 76, y + 1, 61, 0xFFEDEDED, false);
            divider(c, y + 13, w);
        }
    }
    private void champion(Canvas c, float w, float h) {
        boolean driver = "drivers".equals(card.key);
        stripes(c, w, 2, h, 0x12FFFFFF);
        String title = card.champion ? (driver ? "WORLD CHAMPION" : "CONSTRUCTORS’ CHAMPION")
                : (driver ? "DRIVER LEADER" : "TEAM LEADER");
        text(c, title, 9, 13, 18, w - (card.champion ? 39 : 26), card.champion ? 0xFFFFD77A : MUTED, true);
        if (card.champion) crown(c, w - 25, 10);
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
    private void crown(Canvas canvas, float left, float top) {
        Path crown = new Path();
        crown.moveTo(left * dp, (top + 2) * dp);
        crown.lineTo((left + 3) * dp, (top + 5) * dp);
        crown.lineTo((left + 6) * dp, top * dp);
        crown.lineTo((left + 9) * dp, (top + 5) * dp);
        crown.lineTo((left + 12) * dp, (top + 2) * dp);
        crown.lineTo((left + 10) * dp, (top + 10) * dp);
        crown.lineTo((left + 2) * dp, (top + 10) * dp);
        crown.close();
        paint.setColor(0xFFFFD77A);
        canvas.drawPath(crown, paint);
    }

    private void weekend(Canvas c, float w, float h) {
        block(c, 0, 2, w, 29, 0xFF101017);
        mark(c, 15, 10, 36, 9);
        text(c, "RACE WEEKEND", 10, 62, 20, w - 77, WHITE, true);
        divider(c, 29, w);
        int complete = 0;
        java.util.List<String> future = new java.util.ArrayList<>();
        for (String line : card.lines) { if (line.contains(" · Completed")) complete++; else if (!line.contains(" · Awaiting result") && !line.contains(" · Cancelled")) future.add(line); }
        text(c, complete > 0 ? complete + " sessions completed · OK for details" : card.title.replace("Grand Prix", "GP"), 9, 15, 44, w - 30, 0xFFAEB1B2, false);
        for (int i = 0; i < Math.min(3, future.size()); i++) {
            String line = future.get(i).replace(" · Scheduled", "").replace(" · Awaiting result", " · awaiting");
            text(c, line, 8.8f, 15, 62 + i * 15, w - 30, 0xFFECECEC, false);
        }
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
    private void block(Canvas c, float left, float top, float right, float bottom, int color) {
        paint.setColor(color);
        c.drawRect(left * dp, top * dp, right * dp, bottom * dp, paint);
    }
    private void stripes(Canvas c, float width, float top, float bottom, int color) {
        paint.setColor(color);
        for (int i = 0; i < 2; i++) {
            float right = width - 12 + i * 27;
            stripe.reset();
            stripe.moveTo((right - 14) * dp, top * dp);
            stripe.lineTo(right * dp, top * dp);
            stripe.lineTo((right - 70) * dp, bottom * dp);
            stripe.lineTo((right - 84) * dp, bottom * dp);
            stripe.close();
            c.drawPath(stripe, paint);
        }
    }
    private void divider(Canvas c, float y, float w) { paint.setColor(0x16FFFFFF); c.drawRect(15*dp,y*dp,(w-15)*dp,(y+.5f)*dp,paint); }
    private void text(Canvas c, String value, float size, float x, float y, float width, int color, boolean heavy) {
        paint.setTextSize(size*dp); paint.setColor(color); paint.setTypeface(heavy ? bold : regular);
        float available = Math.max(0,width*dp);
        if (paint.measureText(value)>available) { int end=paint.breakText(value,true,Math.max(0,available-paint.measureText("…")),null); value=value.substring(0,Math.max(0,end))+"…"; }
        c.drawText(value,x*dp,y*dp,paint);
    }
}
