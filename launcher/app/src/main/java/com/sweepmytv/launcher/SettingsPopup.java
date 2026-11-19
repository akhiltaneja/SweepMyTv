package com.sweepmytv.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;

/** Lightweight settings modal: one dim layer and a native card, without blur or full-screen bitmap copies. */
final class SettingsPopup extends View {
    static final int TEXT=0xFFF4F5F8, MUTED=0xFFB2B5C0;
    static final int SIDEBAR_WIDTH = 186;
    static final int SPLIT_WIDTH = 780;
    private static final int SPLIT_HEIGHT = 400;
    private static final int CONTENT_TOP = 59, CONTENT_BOTTOM = 19;
    private static final int SIDEBAR_TEXT = 0xFFE1E6F0, ACTIVE_TEXT = 0xFF16223B;
    private static final int ACCENT=0xFFB7CCFF, FOCUS_FILL=0xFF303949, SELECTED_FILL=0xFF2B3547,
            SELECTED_BORDER=0xFF7482A2, SURFACE=0xFF17181E;
    private final float dp;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sidebarPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel=new RectF();
    private final Drawable qrIcon;
    private String heading="Settings";
    private boolean drawer, sidebarFocused;
    private int sidebarAction;
    private String tvSettings;
    private String[] categories;
    private int category;
    private boolean themePreview;
    private Shader surface;
    SettingsPopup(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        Typeface font = getResources().getFont(R.font.outfit);
        headingPaint.setTypeface(android.os.Build.VERSION.SDK_INT >= 28
                ? Typeface.create(font, 650, false) : Typeface.create(font, Typeface.BOLD));
        headingPaint.setFontVariationSettings("'wght' 650");
        sidebarPaint.setTypeface(Typeface.create(font, Typeface.NORMAL));
        sidebarPaint.setFontVariationSettings("'wght' 400");
        qrIcon = context.getDrawable(R.drawable.ic_settings_qr).mutate();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    int panelWidth(int screenWidth, int requestedDp, boolean drawer) {
        return Math.min(Math.round((drawer ? 896 : requestedDp) * dp), screenWidth - Math.round(56 * dp));
    }

    RectF content(int width, int height, int panelWidth, int desiredHeight, boolean drawer, boolean split) {
        float topSpace = (drawer ? 61 : CONTENT_TOP) * dp, bottomSpace = CONTENT_BOTTOM * dp;
        int maxHeight = split ? SPLIT_HEIGHT : drawer ? 430 : 455;
        float available = Math.min(maxHeight * dp, height - 52 * dp);
        float panelHeight = drawer || split ? available : Math.min(available, desiredHeight + topSpace + bottomSpace);
        panel.set((width - panelWidth) / 2f, (height - panelHeight) / 2f,
                (width + panelWidth) / 2f, (height + panelHeight) / 2f);
        surface = new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                0xFF22242B, SURFACE, Shader.TileMode.CLAMP);
        return new RectF(panel.left + (24 + (split ? SIDEBAR_WIDTH : 0)) * dp, panel.top + topSpace,
                panel.right - 24 * dp, panel.bottom - bottomSpace);
    }

    void show(String heading, boolean drawer, String[] categories, int category, boolean sidebarFocused,
              int sidebarAction, String tvSettings) {
        this.heading = heading == null ? "Settings" : heading;
        this.drawer = drawer;
        this.categories = categories;
        this.category = category;
        this.sidebarFocused = sidebarFocused;
        this.sidebarAction = sidebarAction;
        this.tvSettings = tvSettings;
        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
    }

    void hide(boolean animate) {
        animate().cancel();
        if (animate) animate().alpha(0f).setDuration(110).withEndAction(() -> setVisibility(GONE)).start();
        else setVisibility(GONE);
    }

    void setThemePreview(boolean active) {
        if (themePreview == active) return;
        themePreview = active;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(themePreview ? 0x410B0C11 : 0xC3090A0F);
        paint.setColor(SURFACE);
        if (drawer) paint.setColor(0xFF18212E);
        else paint.setShader(surface);
        canvas.drawRoundRect(panel, 27 * dp, 27 * dp, paint);
        paint.setShader(null);

        float contentLeft = panel.left + 24 * dp;
        if (categories != null) {
            drawSidebar(canvas);
            contentLeft += SIDEBAR_WIDTH * dp;
        }
        text(canvas, headingPaint, fit(headingPaint, heading, panel.right - contentLeft - 26 * dp, 25), contentLeft,
                panel.top + 39 * dp, 25, TEXT);
    }

    private void drawSidebar(Canvas canvas) {
        float left = panel.left + 14 * dp;
        float right = panel.left + SIDEBAR_WIDTH * dp;
        float start = panel.top + CONTENT_TOP * dp;
        float bottom = panel.bottom - CONTENT_BOTTOM * dp;
        paint.setColor(0xFF444957);
        canvas.drawRect(right + 4 * dp, start, right + 5 * dp, bottom, paint);
        float gap = 12 * dp;
        float rowHeight = Math.min(43 * dp, (bottom - start - gap) / (categories.length + 2));
        for (int i = 0; i < categories.length; i++) {
            float top = start + i * rowHeight;
            boolean selected = i == category;
            boolean focused = selected && sidebarFocused && sidebarAction < 0;
            if (selected) {
                paint.setColor(focused ? 0xFFE3ECFF : SELECTED_FILL);
                canvas.drawRoundRect(left, top, right - 4 * dp, top + rowHeight - 3 * dp, 10 * dp, 10 * dp, paint);
            }
            text(canvas, sidebarPaint, fit(sidebarPaint, categories[i], right - left - 24 * dp, 16), left + 11 * dp,
                    top + rowHeight / 2 + 4 * dp, 16, focused ? ACTIVE_TEXT : selected ? TEXT : SIDEBAR_TEXT);
        }
        float deviceTop = start + categories.length * rowHeight;
        boolean deviceFocused = sidebarFocused && sidebarAction == 0;
        if (deviceFocused) {
            paint.setColor(0xFFE3ECFF);
            canvas.drawRoundRect(left, deviceTop, right - 4 * dp, deviceTop + rowHeight - 3 * dp,
                    10 * dp, 10 * dp, paint);
        }
        text(canvas, sidebarPaint, fit(sidebarPaint, tvSettings, right - left - 24 * dp, 15), left + 11 * dp,
                deviceTop + rowHeight / 2 + 4 * dp, 15, deviceFocused ? ACTIVE_TEXT : SIDEBAR_TEXT);
        float qrTop = deviceTop + rowHeight + gap;
        paint.setColor(0xFF444957);
        canvas.drawRect(left + 8 * dp, qrTop - gap / 2, right - 12 * dp, qrTop - gap / 2 + dp, paint);
        boolean focused = sidebarFocused && sidebarAction == 1;
        paint.setColor(focused ? 0xFFE3ECFF : 0xFF252B38);
        canvas.drawRoundRect(left, qrTop, right - 4 * dp, qrTop + rowHeight - 3 * dp, 10 * dp, 10 * dp, paint);
        int color = focused ? ACTIVE_TEXT : ACCENT;
        int size = Math.round(18 * dp);
        int iconLeft = Math.round(left + 11 * dp);
        int iconTop = Math.round(qrTop + (rowHeight - 3 * dp - size) / 2);
        qrIcon.setTint(color);
        qrIcon.setBounds(iconLeft, iconTop, iconLeft + size, iconTop + size);
        qrIcon.draw(canvas);
        text(canvas, sidebarPaint, "Scan QR", left + 38 * dp, qrTop + rowHeight / 2 + 4 * dp, 16, color);
    }

    private String fit(TextPaint labelPaint, String value, float width, float size) {
        labelPaint.setTextSize(size * dp);
        return TextUtils.ellipsize(value, labelPaint, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    private void text(Canvas c, TextPaint labelPaint, String value, float x, float y, float size, int color) {
        labelPaint.setColor(color);
        labelPaint.setTextSize(size * dp);
        c.drawText(value, x, y, labelPaint);
    }

    static final class RowStyle {
        final boolean category;
        String switchStatus;

        RowStyle(boolean category) {
            this.category = category;
        }
    }

    static GradientDrawable shape(int fill, int stroke, float radius, float dp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius * dp);
        if (stroke != 0) drawable.setStroke(Math.max(1, Math.round(dp)), stroke);
        return drawable;
    }

    static void prepareRow(View row, boolean category) {
        row.setTag(new RowStyle(category));
        focus(row, false);
    }

    static void focus(View row, boolean focused) {
        if (!(row.getTag() instanceof RowStyle)) return;
        RowStyle style = (RowStyle) row.getTag();
        float dp = row.getResources().getDisplayMetrics().density;
        int fill = row.isSelected() ? SELECTED_FILL : focused ? FOCUS_FILL : style.category ? 0xFF272A33 : 0xFF23262E;
        GradientDrawable background = shape(fill, row.isSelected() ? SELECTED_BORDER : 0, 12, dp);
        if (focused) background.setStroke(Math.max(1, Math.round(2 * dp)), ACCENT);
        row.setBackground(background);
        TextView label = row.findViewById(R.id.input_label);
        TextView value = row.findViewById(R.id.input_status);
        label.setTextColor(TEXT);
        if (value != null) value.setTextColor(focused ? TEXT : MUTED);
        ImageView icon = row.findViewWithTag("settings-icon");
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(ACCENT));
        String status = style.switchStatus != null ? style.switchStatus
                : value == null || value.getVisibility() == GONE ? null : value.getText().toString();
        if (!style.category) updateStatus(row, status);
        View tick = row.findViewWithTag("selection-tick");
        String selection = tick == null ? "" : tick.getVisibility() == View.VISIBLE ? ", selected" : ", unselected";
        CharSequence name = label.getContentDescription();
        row.setContentDescription((name == null ? label.getText() : name) + (status == null ? "" : ", " + status) + selection);
    }

    static void setSwitchOnly(View row, boolean checked) {
        RowStyle style = (RowStyle) row.getTag();
        style.switchStatus = checked ? "On" : "Off";
        TextView value = row.findViewById(R.id.input_status);
        value.setText(null);
        value.setVisibility(View.VISIBLE);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateStatus(row, style.switchStatus);
    }

    static void updateStatus(View row, String status) {
        if (!(row.getTag() instanceof RowStyle)) return;
        RowStyle style = (RowStyle) row.getTag();
        if (style.category) return;
        TextView value = row.findViewById(R.id.input_status);
        float dp = row.getResources().getDisplayMetrics().density;
        value.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        value.setMaxWidth(Math.round(220 * dp));
        value.setEllipsize(TextUtils.TruncateAt.END);
        boolean on = "On".equals(status) || "Shown".equals(status);
        boolean off = "Off".equals(status) || "Hidden".equals(status);
        Drawable toggle = on || off ? new Toggle(dp, on, ACCENT) : null;
        value.setCompoundDrawablePadding(Math.round(9 * dp));
        value.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, toggle, null);
        TextView label = row.findViewById(R.id.input_label);
        row.setContentDescription(label.getText() + (status == null ? "" : ", " + status));
    }
    private static final class Toggle extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final float dp;final boolean on;final int accent;
        Toggle(float dp,boolean on,int accent){this.dp=dp;this.on=on;this.accent=accent;}
        @Override public int getIntrinsicWidth(){return Math.round(29*dp);}@Override public int getIntrinsicHeight(){return Math.round(16*dp);}
        @Override public void draw(Canvas c){Rect b=getBounds();float h=b.height(),r=h/2;p.setColor(on?accent:0xFF66748B);c.drawRoundRect(b.left,b.top,b.right,b.bottom,r,r,p);p.setColor(on?TimeOfDay.foreground(accent):0xFFF1F4FA);c.drawCircle(on?b.right-r:b.left+r,b.exactCenterY(),r-3*dp,p);}
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
