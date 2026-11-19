package com.sweepmytv.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** A remote-focused title preview with one explicit playback action. */
final class TitleDetailsDialog extends Dialog {
    private static final int TEXT = 0xFFF4F6FC;
    private static final int SECONDARY = 0xFFB7C3D7;
    private static final int BLUE = 0xFFD7E5FF;
    private final float density;
    private final Typeface font;
    private final int panelWidth;
    private final TextView facts;
    private final TextView review;
    private final TextView overview;
    private final TextView cast;
    private final TextView actionLabel;
    private final TextView status;
    private final LinearLayout action;
    private final ImageView play;
    private final ImageView artwork;

    TitleDetailsDialog(Context context, Trending.Pick pick, Drawable icon, CardArtwork images) {
        super(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        var metrics = context.getResources().getDisplayMetrics();
        density = metrics.density;
        font = context.getResources().getFont(R.font.outfit);
        panelWidth = Math.min(px(816), metrics.widthPixels - px(56));
        int height = Math.min(px(360), metrics.heightPixels - px(76));
        int posterWidth = Math.min(height * 2 / 3, panelWidth / 3);

        LinearLayout panel = new LinearLayout(context);
        panel.setPadding(px(16), px(16), px(28), px(16));
        panel.setBackground(SettingsPopup.shape(0xFF171F2D, 0xFF394861, 24, density));

        FrameLayout art = new FrameLayout(context);
        art.setBackground(SettingsPopup.shape(0xFF222F43, 0, 14, density));
        art.setClipToOutline(true);
        artwork = new ImageView(context);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        File image = ArtworkAssets.portrait(pick.poster) ? pick.poster : pick.image;
        if (image != null && image.isFile()) {
            art.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
            images.bind(artwork, image, posterWidth, height, false, true);
        } else if (icon != null) {
            artwork.setImageDrawable(copy(icon));
            art.addView(artwork, new FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER));
        }
        panel.addView(art, new LinearLayout.LayoutParams(posterWidth, height));

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(px(26), px(10), 0, px(6));
        panel.addView(details, new LinearLayout.LayoutParams(0, height, 1f));

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        details.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout description = new LinearLayout(context);
        description.setOrientation(LinearLayout.VERTICAL);
        description.setPadding(0, 0, 0, px(9));
        scroll.addView(description);

        TextView title = text(pick.title, 31, TEXT, 620, 2);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        description.addView(title);
        facts = text("", 14, BLUE, 550, 2);
        add(description, facts, 8);
        review = text("", 14, BLUE, 550, 1);
        review.setVisibility(View.GONE);
        add(description, review, 8);
        overview = text("", 16, TEXT, 400, 12);
        overview.setLineSpacing(px(3), 1);
        add(description, overview, 16);
        cast = text("", 14, SECONDARY, 450, 2);
        add(description, cast, 12);

        action = new LinearLayout(context);
        action.setTag("title-action");
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(px(15), 0, px(16), 0);
        action.setFocusable(true);
        action.setClickable(true);
        action.setOnFocusChangeListener((view, focused) -> styleAction(focused));
        play = new ImageView(context);
        play.setImageResource(android.R.drawable.ic_media_play);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(play, new LinearLayout.LayoutParams(px(24), px(24)));
        actionLabel = text("", 16, TEXT, 600, 1);
        actionLabel.setPadding(px(8), 0, icon == null ? 0 : px(12), 0);
        action.addView(actionLabel);
        if (icon != null) {
            ImageView provider = new ImageView(context);
            provider.setImageDrawable(copy(icon));
            provider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            action.addView(provider, new LinearLayout.LayoutParams(px(24), px(24)));
        }
        details.addView(action, new LinearLayout.LayoutParams(-2, px(49)));
        status = text("", 12, SECONDARY, 400, 1);
        add(details, status, 7);
        status.setMinHeight(px(16));
        styleAction(false);
        update(pick, null);
        setContentView(panel);
        setCanceledOnTouchOutside(false);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.67f);
    }

    @Override protected void onStart() {
        super.onStart();
        getWindow().setLayout(panelWidth, -2);
        action.requestFocus();
    }

    void update(Trending.Pick pick, Trending.TitleMetadata metadata) {
        String year = metadata == null || metadata.year.isEmpty() ? pick.year : metadata.year;
        String kind = metadata == null ? pick.kind : metadata.kind;
        String format = "movie".equals(kind) ? "Film" : "tv".equals(kind) ? "Series" : "";
        float rating = metadata == null || metadata.rating <= 0 ? pick.rating : metadata.rating;
        String score = rating > 0 ? String.format(Locale.US, "%s %.1f / 10", pick.ratingSource, rating) : "";
        String genres = metadata == null ? "" : metadata.genres;
        visibleText(facts, join(year, format, genres, score));
        String summary = metadata == null || metadata.overview.isEmpty() ? pick.overview : metadata.overview;
        if (summary.isEmpty() && !TitleLinks.catalog(pick) && !pick.meta.isEmpty()) summary = pick.meta;
        visibleText(overview, summary);
        String stars = metadata == null || metadata.cast.isEmpty() ? pick.cast : metadata.cast;
        visibleText(cast, stars.isEmpty() ? "" : "Starring  " + stars);
    }

    void action(String label, String hint, Runnable click) {
        actionLabel.setText(label);
        status.setText(hint);
        action.setContentDescription(hint.isEmpty() ? label : label + ", " + hint);
        play.setVisibility("Close".equals(label) ? View.GONE : View.VISIBLE);
        action.setOnClickListener(view -> click.run());
    }

    void reviewLink(String label, Runnable click) {
        review.setText(label + "  ›");
        review.setVisibility(View.VISIBLE);
        review.setFocusable(true);
        review.setPadding(px(8), px(6), px(8), px(6));
        review.setOnFocusChangeListener((view, focused) ->
                view.setBackground(focused ? SettingsPopup.shape(0xFF364764, BLUE, 8, density) : null));
        review.setOnClickListener(view -> click.run());
    }

    ImageView artwork() { return artwork; }

    private void styleAction(boolean focused) {
        action.setBackground(SettingsPopup.shape(focused ? BLUE : 0xFF2D3B52, focused ? BLUE : 0xFF4B5970, 13, density));
        int colour = focused ? 0xFF14233D : TEXT;
        actionLabel.setTextColor(colour);
        play.setColorFilter(colour);
    }

    private TextView text(String value, float size, int colour, int weight, int lines) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTypeface(font);
        view.setFontVariationSettings("'wght' " + weight);
        view.setTextSize(size);
        view.setTextColor(colour);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private void add(LinearLayout parent, TextView view, int gap) {
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.topMargin = px(gap);
        parent.addView(view, position);
    }

    private static void visibleText(TextView view, String value) {
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String join(String... parts) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (String part : parts) if (!part.isEmpty()) values.add(part);
        return String.join("  ·  ", values);
    }

    private Drawable copy(Drawable icon) {
        Drawable.ConstantState state = icon.getConstantState();
        return state == null ? icon : state.newDrawable(getContext().getResources()).mutate();
    }

    private int px(float value) { return Math.round(density * value); }
}
