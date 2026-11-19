package com.sweepmytv.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows the local dashboard QR at TV viewing distance with a single remote action. */
final class PhoneQrDialog extends Dialog {
    private final float density;
    private final int panelWidth;
    private final ImageView code;
    private final FrameLayout tile;
    private final Runnable reopen;
    private final TextView instructions;
    private final TextView validity;
    private final TextView close;

    PhoneQrDialog(Context context, ImageView code, Runnable reopen) {
        super(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        var metrics = context.getResources().getDisplayMetrics();
        density = metrics.density;
        this.reopen = reopen;
        panelWidth = Math.min(px(444), metrics.widthPixels - px(40));
        int qrSize = Math.min(px(236), Math.min(panelWidth - px(72), metrics.heightPixels - px(200)));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(px(24), px(20), px(24), px(20));
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF273249, 0xFF171D2A});
        surface.setCornerRadius(px(28));
        surface.setStroke(px(1), 0xFF44526D);
        panel.setBackground(surface);

        TextView title = text("Customize from phone", 28, SettingsPopup.TEXT, 650);
        title.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        add(panel, title, 0, -1, -2);
        instructions = text("Scan with your phone’s camera on the same Wi‑Fi.", 15, 0xFFC9D5EC, 500);
        instructions.setGravity(Gravity.CENTER);
        add(panel, instructions, 4, -1, -2);

        tile = new FrameLayout(context);
        tile.setBackground(SettingsPopup.shape(Color.WHITE, 0, 18, density));
        this.code = code;
        code.setContentDescription("QR code for Sweep TV phone settings");
        if (code.getDrawable() instanceof BitmapDrawable) ((BitmapDrawable) code.getDrawable()).setFilterBitmap(false);
        FrameLayout.LayoutParams image = new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER);
        tile.addView(code, image);
        add(panel, tile, 18, qrSize + px(12), qrSize + px(12));

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        validity = text("Code expires in 5 minutes", 13, 0xFFC9D5EC, 500);
        footer.addView(validity, new LinearLayout.LayoutParams(0, -2, 1f));
        close = text("Close", 16, 0xFF142449, 650);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(px(94));
        close.setPadding(px(20), 0, px(20), 0);
        close.setFocusable(true);
        close.setOnClickListener(view -> dismiss());
        close.setOnFocusChangeListener((view, focused) -> styleButton(focused));
        styleButton(false);
        footer.addView(close, new LinearLayout.LayoutParams(-2, px(44)));
        add(panel, footer, 16, -1, -2);

        setContentView(panel);
        setCanceledOnTouchOutside(false);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.72f);
    }

    @Override protected void onStart() {
        super.onStart();
        getWindow().setLayout(panelWidth, -2);
        close.requestFocus();
    }

    void expire() {
        code.setImageDrawable(null);
        tile.setVisibility(View.GONE);
        instructions.setText("This code has expired.");
        validity.setVisibility(View.INVISIBLE);
        close.setText("Get a new code");
        close.setOnClickListener(view -> reopen.run());
    }

    private void styleButton(boolean focused) {
        close.setBackground(SettingsPopup.shape(focused ? 0xFFC9D9FF : 0xFF354660, 0, 13, density));
        close.setTextColor(focused ? 0xFF142449 : SettingsPopup.TEXT);
    }

    private TextView text(String value, int size, int color, int weight) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(getContext().getResources().getFont(R.font.outfit));
        text.setFontVariationSettings("'wght' " + weight);
        return text;
    }

    private void add(LinearLayout parent, View view, int gap, int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = px(gap);
        parent.addView(view, params);
    }

    private int px(int value) { return Math.round(density * value); }
}
