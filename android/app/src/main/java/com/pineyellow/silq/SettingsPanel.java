package com.pineyellow.silq;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DecimalFormat;

/** Sil-Q graphics and touch control settings. */
final class SettingsPanel {

    private final SilActivity activity;
    private final FrameLayout host;

    SettingsPanel(SilActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    void show() {
        activity.applyDpadSettings(); // Migrate legacy position before reading the indicator.
        activity.cancelDpadInput();
        activity.setOverlayInputBlocked(true);
        host.removeAllViews();

        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
        backdrop.setOnClickListener(v -> hide());
        host.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(280).start();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(10);
        panel.setPadding(pad, pad, pad, pad);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadii(new float[]{
            activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
            activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP), 0, 0, 0, 0,
            activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
            activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP)});
        panelBg.setColor(Palette.PANEL_BG);
        panelBg.setStroke(1, Palette.BORDER_DIM);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackground(panelBg);
        scrollView.addView(panel);

        TextView header = new TextView(activity);
        header.setText("SETTINGS");
        header.setTextColor(Palette.HEADER_TEXT);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.15f);
        header.setPadding(activity.dpToPx(4), activity.dpToPx(2), 0, activity.dpToPx(4));
        panel.addView(header);

        View headerSep = new View(activity);
        headerSep.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams hSepP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        hSepP.setMargins(activity.dpToPx(4), 0, activity.dpToPx(4), activity.dpToPx(8));
        panel.addView(headerSep, hSepP);

        if (BuildConfig.DEBUG) {
            addAppToggle(panel, "Tutorial debug kit", "debug_tutorial_kit", false,
                enabled -> show());
        }

        // Control preferences take effect immediately and survive app restarts.
        addStepperSetting(panel, "Button Opacity", ButtonOpacity.PREF,
            ButtonOpacity.DEFAULT, 0f, 1f, 0.05f,
            "Button Opacity (Default: " + formatFloat(ButtonOpacity.DEFAULT) + ")",
            activity::applyButtonOpacity, true);
        boolean dpadEnabled = GameSettings.getBool(activity, DPadOverlay.PREF_ENABLED, true);
        addAppToggle(panel, "Enable DPAD", DPadOverlay.PREF_ENABLED, true,
            enabled -> {
                activity.setDpadEnabled(enabled);
                show();
            });
        if (dpadEnabled) {
            addStepperSetting(panel, "DPAD X", DPadOverlay.PREF_OFFSET_X,
                10f, null, null, 1f,
                "DPAD X position offset in dp\n(- goes left, + goes right)",
                activity::applyDpadSettings, true);
            addStepperSetting(panel, "DPAD Y", DPadOverlay.PREF_OFFSET_Y,
                DPadOverlay.MARGIN_DP, null, null, 1f,
                "DPAD Y position offset in dp\n(- goes down, + goes up)",
                activity::applyDpadSettings, true);
            addStepperSetting(panel, "DPAD Size", DPadOverlay.PREF_SIZE,
                DPadOverlay.DEFAULT_SIZE, DPadOverlay.MIN_SIZE, DPadOverlay.MAX_SIZE, 0.1f,
                "DPAD overall size multiplier", activity::applyDpadSettings, true);
            addStepperSetting(panel, "DPAD Button Width", DPadOverlay.PREF_BUTTON_WIDTH,
                DPadOverlay.DEFAULT_BUTTON_WIDTH,
                DPadOverlay.MIN_BUTTON_WIDTH, DPadOverlay.MAX_BUTTON_WIDTH, 0.1f,
                "DPAD button width multiplier\n(1 = square buttons)",
                activity::applyDpadSettings, true);
        }
        int panelWidth = Math.min(activity.dpToPx(280),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.6f));

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END);
        scrollParams.setMargins(0, activity.dpToPx(8),
            activity.dpToPx(SilActivity.EDGE_SAFE_DP),
            activity.dpToPx(SilActivity.EDGE_SAFE_DP));

        host.addView(scrollView, scrollParams);
        host.setVisibility(View.VISIBLE);
        host.bringToFront();

        scrollView.setTranslationX(panelWidth);
        scrollView.animate()
            .translationX(0)
            .setDuration(280)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();
    }

    void hide() {
        host.setVisibility(View.GONE);
        host.removeAllViews();
        activity.setOverlayInputBlocked(false);
    }

    // ---- Row builders ----

    // These listeners only animate; returning false preserves normal click handling.
    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout addRow(LinearLayout panel, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                       activity.dpToPx(12), activity.dpToPx(10));
        row.setMinimumHeight(activity.dpToPx(44));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.TEXT);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTypeface(Typeface.MONOSPACE);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        panel.addView(row, p);

        return row;
    }

    /** Java-only toggle (no engine notification). {@code onChange} fires after
     *  the new value is persisted, with the new boolean state. */
    private void addAppToggle(LinearLayout panel, String label, String prefKey,
                              boolean defaultValue,
                              java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = addRow(panel, label);

        boolean on = GameSettings.getBool(activity, prefKey, defaultValue);
        TextView check = makeCheckIndicator(on);
        row.addView(check, new LinearLayout.LayoutParams(
            activity.dpToPx(28), activity.dpToPx(28)));

        row.setOnClickListener(v -> {
            boolean nowOn = !GameSettings.getBool(activity, prefKey, defaultValue);
            GameSettings.setBool(activity, prefKey, nowOn);
            updateCheckIndicator(check, nowOn);
            if (onChange != null) onChange.accept(nowOn);
        });
    }

    private void addStepperSetting(LinearLayout panel, String label, String prefKey,
                                   float defaultValue, Float minValue, Float maxValue,
                                   float step, String prompt, Runnable onChange,
                                   boolean hideSettingsWhileAdjusting) {
        LinearLayout row = addRow(panel, label);
        TextView valueView = makeValueIndicator(formatFloat(
            GameSettings.getFloat(activity, prefKey, defaultValue)));
        row.addView(valueView);

        row.setOnClickListener(v -> {
            float originalValue = GameSettings.getFloat(activity, prefKey, defaultValue);
            boolean opacityPreview = ButtonOpacity.PREF.equals(prefKey);
            if (opacityPreview) activity.setButtonOpacityPreview(true);
            if (hideSettingsWhileAdjusting) {
                host.setVisibility(View.GONE);
                host.removeAllViews();
            }
            activity.stepperDialog.showStepper(
                prompt, originalValue, minValue, maxValue, step,
                preview -> {
                    GameSettings.setFloat(activity, prefKey, preview);
                    valueView.setText(formatFloat(preview));
                    if (onChange != null) onChange.run();
                },
                result -> {
                    if (opacityPreview) activity.setButtonOpacityPreview(false);
                    float finalValue = result != null ? result : originalValue;
                    GameSettings.setFloat(activity, prefKey, finalValue);
                    valueView.setText(formatFloat(finalValue));
                    if (onChange != null) onChange.run();
                    show();
                });
        });
    }

    private TextView makeCheckIndicator(boolean on) {
        TextView check = new TextView(activity);
        check.setText(on ? "\u2713" : "");
        check.setTextColor(Palette.TEXT);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        check.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(on ? Palette.ACTION_BG : Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
        check.setBackground(bg);
        return check;
    }

    private TextView makeValueIndicator(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(Palette.TEXT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setMinWidth(activity.dpToPx(54));
        text.setPadding(activity.dpToPx(8), activity.dpToPx(4),
            activity.dpToPx(8), activity.dpToPx(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
        text.setBackground(bg);
        return text;
    }

    private void updateCheckIndicator(TextView check, boolean on) {
        check.setText(on ? "\u2713" : "");
        GradientDrawable bg = (GradientDrawable) check.getBackground();
        bg.setColor(on ? Palette.ACTION_BG : Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
    }

    private String formatFloat(float value) {
        return new DecimalFormat("0.##").format(value);
    }

}
