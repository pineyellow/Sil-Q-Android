package com.pineyellow.silq;

import android.annotation.SuppressLint;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Hamburger control and submenu for Sil-Q actions. */
final class MenuOverlay {
    private final SilActivity activity;
    private final FrameLayout layer;
    private final LinearLayout submenu;
    private final View menuBtn;
    private final View backdrop;
    private final View characterItem;
    private final View abilitiesItem;
    private final View saveQuitItem;
    private boolean expanded;
    private boolean dungeonAvailable, characterScreenOpen;
    private boolean inventoryOpen, aiming;

    MenuOverlay(SilActivity activity, Runnable onSettings) {
        this.activity = activity;
        layer = new FrameLayout(activity);
        layer.setVisibility(View.GONE);
        backdrop = new View(activity);
        backdrop.setOnClickListener(v -> collapseSubmenu());
        backdrop.setVisibility(View.GONE);
        layer.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        submenu = new LinearLayout(activity);
        submenu.setOrientation(LinearLayout.VERTICAL);
        submenu.setBackground(makeSubmenuBackground());
        submenu.setPadding(dp(3), dp(4), dp(3), dp(3));
        submenu.setVisibility(View.GONE);
        submenu.setElevation(dp(8));
        characterItem = makeSubmenuItem("Character", v -> {
            collapseSubmenu();
            activity.openCharacterSheet();
        });
        characterItem.setEnabled(false);
        characterItem.setAlpha(0.4f);
        submenu.addView(characterItem, submenuItemParams());
        abilitiesItem = makeSubmenuItem("Abilities", v -> {
            collapseSubmenu();
            activity.openAbilities();
        });
        abilitiesItem.setEnabled(false);
        abilitiesItem.setAlpha(0.4f);
        submenu.addView(abilitiesItem, submenuItemParams());
        submenu.addView(makeSubmenuItem("Settings", v -> {
            collapseSubmenu();
            onSettings.run();
        }), submenuItemParams());
        View abandonDivider = new View(activity);
        abandonDivider.setBackgroundColor(Palette.BORDER_DIM);
        abandonDivider.setAlpha(0.5f);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(6), dp(2), dp(6), dp(2));
        submenu.addView(abandonDivider, dividerParams);
        saveQuitItem = makeSubmenuItem("Save and Quit", v -> {
            collapseSubmenu();
            activity.nativeSaveAndQuit();
        });
        saveQuitItem.setEnabled(false);
        saveQuitItem.setAlpha(0.4f);
        submenu.addView(saveQuitItem, submenuItemParams());
        submenu.addView(makeSubmenuItem("Abandon Game", v -> {
            collapseSubmenu();
            activity.nativeAbandonGame();
        }), submenuItemParams());

        menuBtn = makeIconBarButton(R.drawable.ic_menu);
        menuBtn.setContentDescription("Menu");
        menuBtn.setOnClickListener(v -> {
            if (expanded) collapseSubmenu();
            else expandSubmenu();
        });
        LinearLayout topGroup = new LinearLayout(activity);
        topGroup.setOrientation(LinearLayout.VERTICAL);
        topGroup.setGravity(Gravity.END);
        topGroup.addView(menuBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
            dp(170), LinearLayout.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(6);
        topGroup.addView(submenu, subParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(8), dp(8), 0);
        layer.addView(topGroup, params);
    }

    View getView() { return layer; }
    void setDungeonAvailable(boolean available, boolean canSave) {
        dungeonAvailable = available;
        saveQuitItem.setEnabled(canSave);
        saveQuitItem.setAlpha(canSave ? 1f : 0.4f);
        characterItem.setEnabled(available);
        characterItem.setAlpha(available ? 1f : 0.4f);
        abilitiesItem.setEnabled(available);
        abilitiesItem.setAlpha(available ? 1f : 0.4f);
        updateVisibility();
    }
    void setCharacterScreenOpen(boolean open) {
        characterScreenOpen = open;
        updateVisibility();
    }
    private void updateVisibility() {
        boolean visible = dungeonAvailable && !characterScreenOpen;
        if (!visible) collapseSubmenu();
        layer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
    boolean isExpanded() { return expanded; }
    private boolean messagePause;
    void setMessagePause(boolean active) {
        messagePause = active;
        updateButtonEnabled();
    }
    void setInventoryOpen(boolean open) {
        inventoryOpen = open;
        updateButtonEnabled();
    }
    void setAiming(boolean active) {
        aiming = active;
        if (active) collapseSubmenu();
        updateButtonEnabled();
    }
    private void updateButtonEnabled() {
        boolean enabled = !inventoryOpen && !aiming && !messagePause;
        menuBtn.setEnabled(enabled);
        menuBtn.setAlpha(enabled ? 1f : .35f);
    }
    private int dp(float value) { return activity.dpToPx(value); }

    void collapseSubmenu() {
        if (!expanded) return;
        expanded = false;
        submenu.animate().cancel();
        submenu.setVisibility(View.GONE);
        backdrop.setVisibility(View.GONE);
        animateToggle(menuBtn, false);
        activity.setOverlayInputBlocked(false);
    }

    private void expandSubmenu() {
        activity.cancelDpadInput();
        activity.setOverlayInputBlocked(true);
        expanded = true;
        backdrop.setVisibility(View.VISIBLE);
        submenu.animate().cancel();
        submenu.setAlpha(0f);
        submenu.setTranslationY(dp(-8));
        submenu.setVisibility(View.VISIBLE);
        submenu.animate().alpha(1f).translationY(0).setDuration(150)
            .setInterpolator(new DecelerateInterpolator()).start();
        animateToggle(menuBtn, true);
    }

    private void animateToggle(View v, boolean active) {
        GradientDrawable bg = extractBackground(v);
        if (bg == null) return;

        int from = active ? Palette.ACTION_BUTTON_BG : Palette.TOGGLE_ACTIVE;
        int to   = active ? Palette.TOGGLE_ACTIVE : Palette.ACTION_BUTTON_BG;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        anim.setDuration(150);
        anim.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
        anim.start();

        bg.setStroke(1, active ? Palette.BUTTON_BORDER_ACTIVE : Palette.BUTTON_BORDER);

        if (v instanceof ImageButton) {
            ((ImageButton) v).setColorFilter(active
                ? Palette.ACTION_BUTTON_TEXT_ACTIVE
                : Palette.ACTION_BUTTON_TEXT);
        } else if (v instanceof TextView) {
            ((TextView) v).setTextColor(active
                ? Palette.ACTION_BUTTON_TEXT_ACTIVE
                : Palette.ACTION_BUTTON_TEXT);
        }
    }

    private GradientDrawable extractBackground(View v) {
        if (v.getBackground() instanceof RippleDrawable) {
            RippleDrawable rd = (RippleDrawable) v.getBackground();
            if (rd.getNumberOfLayers() > 0
                    && rd.getDrawable(0) instanceof GradientDrawable) {
                return (GradientDrawable) rd.getDrawable(0);
            }
        }
        return null;
    }

    // ---- View factories ----------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeIconBarButton(int drawableResId) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(drawableResId);
        btn.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        btn.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        btn.setPadding(dp(10), dp(10), dp(10), dp(10));
        btn.setFocusable(false);
        btn.setStateListAnimator(null);
        btn.setElevation(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ACTION_BUTTON_BG);
        bg.setStroke(1, Palette.BUTTON_BORDER);

        btn.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.BUTTON_RIPPLE), bg, null));

        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
            }
            return false;
        });

        return btn;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View makeSubmenuItem(String label, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.setMinimumHeight(dp(36));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().translationX(dp(2)).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().translationX(0).setDuration(100).start();
            }
            return false;
        });

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.TEXT);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setGravity(Gravity.CENTER);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout.LayoutParams submenuItemParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(3), dp(2), dp(3), dp(2));
        return p;
    }

    private GradientDrawable makeSubmenuBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.PANEL_CORNER_RADIUS_DP));
        bg.setColor(Palette.PANEL_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        return bg;
    }
}
