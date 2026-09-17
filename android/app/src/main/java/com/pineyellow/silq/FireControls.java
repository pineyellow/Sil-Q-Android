package com.pineyellow.silq;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Typeface;

/** Shared aim/cancel controls; the game thread owns firing and throwing. */
final class FireControls {
    private final SilActivity activity;
    private final ImageButton fire, cancel, interact, stealth, sing, hornUp, hornDown, ground;
    private boolean hasGroundItem;
    private int groundEpoch;
    private final LinearLayout quiver;
    private final TextView quiverNumber;
    private int selectedQuiver = 1;
    private boolean canSwitchQuiver;
    private boolean canAimVertical;
    private int state, epoch;
    private boolean blocked, paused;
    private boolean opacityPreview;

    void setOpacityPreview(boolean preview) {
        opacityPreview = preview;
        render();
    }
    private boolean stealthActive;
    private boolean singing;
    private boolean hasSongs, hasBow;

    FireControls(SilActivity activity, FrameLayout host) {
        this.activity = activity;
        fire = button(host, R.drawable.ic_bow, "Fire", 176, 8);
        interact = button(host, R.drawable.ic_interact, "Interact", 64, 8);
        stealth = button(host, R.drawable.ic_stealth, "Enable stealth", 120, 8);
        sing = button(host, R.drawable.ic_music_note, "Sing", 232, 8);
        sing.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeSingInput(epoch);
        });
        cancel = button(host, R.drawable.ic_close, "Cancel shot", 8, 64);
        ground = button(host, R.drawable.ic_close, "Item on ground", 8, 64);
        GradientDrawable groundBackground = new GradientDrawable();
        groundBackground.setColor(Palette.TOGGLE_ACTIVE);
        groundBackground.setCornerRadius(activity.dpToPx(8));
        groundBackground.setStroke(activity.dpToPx(1), Palette.EQUIPPED_BORDER);
        ground.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.BUTTON_RIPPLE), groundBackground, null));
        ground.clearColorFilter();
        ground.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ground.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeOpenGround(groundEpoch);
        });
        hornUp = button(host, R.drawable.ic_dpad_arrow, "Aim at ceiling", 120, 64);
        hornDown = button(host, R.drawable.ic_dpad_arrow, "Aim at floor", 64, 64);
        hornUp.setRotation(-90);
        hornDown.setRotation(90);
        hornUp.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeHornDirectionInput(epoch, false);
        });
        hornDown.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeHornDirectionInput(epoch, true);
        });
        quiver = new LinearLayout(activity);
        quiver.setOrientation(LinearLayout.HORIZONTAL);
        quiver.setGravity(Gravity.CENTER);
        ImageView quiverIcon = new ImageView(activity);
        quiverIcon.setImageResource(R.drawable.ic_quiver);
        quiverIcon.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        quiverIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        quiver.addView(quiverIcon, new LinearLayout.LayoutParams(
            activity.dpToPx(16), activity.dpToPx(28)));
        quiverNumber = new TextView(activity);
        quiverNumber.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        quiverNumber.setTextSize(16);
        quiverNumber.setTextColor(Palette.ACTION_BUTTON_TEXT);
        quiverNumber.setIncludeFontPadding(false);
        quiverNumber.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        numberParams.leftMargin = activity.dpToPx(3);
        quiver.addView(quiverNumber, numberParams);
        styleButton(host, quiver, 120, 64);
        quiver.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeQuiverInput(epoch);
        });
        fire.setOnClickListener(v -> {
            if (!hasBow || (state != 2 && state != 3)) return;
            activity.prepareFireInput();
            activity.nativeFireInput(epoch, false);
        });
        cancel.setOnClickListener(v -> cancel());
        stealth.setOnClickListener(v -> {
            activity.prepareFireInput();
            activity.nativeStealthInput(epoch);
        });
        interact.setOnClickListener(v -> {
            activity.prepareFireInput();
            if (state == 5) cancel();
            else activity.nativeInteractInput(epoch);
        });
        render();
    }

    private ImageButton button(FrameLayout host, int icon, String label, int right, int bottom) {
        ImageButton result = new ImageButton(activity);
        result.setImageResource(icon);
        result.setContentDescription(label);
        result.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        int pad = activity.dpToPx(10);
        result.setPadding(pad, pad, pad, pad);
        styleButton(host, result, right, bottom);
        return result;
    }

    private void styleButton(FrameLayout host, View result, int right, int bottom) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.ACTION_BUTTON_BG);
        bg.setCornerRadius(activity.dpToPx(8));
        bg.setStroke(activity.dpToPx(1), Palette.BUTTON_BORDER);
        result.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.BUTTON_RIPPLE), bg, null));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            activity.dpToPx(48), activity.dpToPx(48), Gravity.BOTTOM | Gravity.RIGHT);
        lp.rightMargin = activity.dpToPx(right);
        lp.bottomMargin = activity.dpToPx(bottom);
        host.addView(result, lp);
    }

    void applyButtonOpacity(float opacity) {
        for (View button : new View[]{fire, cancel, interact, stealth, sing,
                hornUp, hornDown, quiver}) {
            ButtonOpacity.apply(button, Palette.ACTION_BUTTON_BG, opacity);
        }
        ButtonOpacity.apply(ground, Palette.TOGGLE_ACTIVE, opacity,
            Palette.EQUIPPED_BORDER, activity.dpToPx(1));
    }

    void update(int state, int epoch, boolean stealthActive, boolean singing,
                int selectedQuiver, boolean canSwitchQuiver, boolean canAimVertical,
                boolean hasSongs, boolean hasBow) {
        this.state = state;
        this.epoch = epoch;
        this.stealthActive = stealthActive;
        this.singing = singing;
        this.hasSongs = hasSongs;
        this.hasBow = hasBow;
        this.selectedQuiver = selectedQuiver;
        this.canSwitchQuiver = canSwitchQuiver;
        this.canAimVertical = canAimVertical;
        if (paused && aiming()) cancel();
        render();
    }

    void updateGround(String json, int epoch) {
        groundEpoch = epoch;
        hasGroundItem = false;
        try {
            org.json.JSONObject data = new org.json.JSONObject(json);
            org.json.JSONArray items = data.optJSONArray("items");
            org.json.JSONObject item = items != null ? items.optJSONObject(0) : null;
            org.json.JSONArray pixels = item != null ? item.optJSONArray("pixels") : null;
            if (pixels != null && pixels.length() == 256) {
                int[] colors = new int[256];
                for (int i = 0; i < 256; i++) colors[i] = pixels.optInt(i);
                android.graphics.drawable.BitmapDrawable sprite = new android.graphics.drawable.BitmapDrawable(
                    activity.getResources(), android.graphics.Bitmap.createBitmap(colors, 16, 16,
                    android.graphics.Bitmap.Config.ARGB_8888));
                sprite.setFilterBitmap(false);
                ground.setImageDrawable(sprite);
                ground.setContentDescription(data.optBoolean("terrain")
                    ? item.optString("name") : "On ground: " + item.optString("name"));
                hasGroundItem = true;
            }
        } catch (org.json.JSONException ignored) { }
        render();
    }

    boolean aiming() { return state == 3 || state == 4 || state == 5 || state == 7; }
    void cancel() { if (aiming()) activity.nativeFireInput(epoch, true); }
    void block(boolean blocked) { this.blocked = blocked; render(); }
    void pause() { paused = true; cancel(); render(); }
    void resume() { paused = false; render(); }

    private void positionRight(View view, int rightDp) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        int right = activity.dpToPx(rightDp);
        if (params.rightMargin != right) {
            params.rightMargin = right;
            view.setLayoutParams(params);
        }
    }

    private void render() {
        positionRight(sing, hasBow ? 232 : 176);
        positionRight(cancel, state == 3 ? 176 : 8);
        ground.setVisibility(hasGroundItem && !blocked && !paused && !aiming()
            && (state == 2 || state == 6) ? View.VISIBLE : View.GONE);
        boolean enabled = hasBow && !blocked && !paused && (state == 2 || state == 3);
        fire.setVisibility(state == 0 || !hasBow ? View.GONE : View.VISIBLE);
        fire.setEnabled(enabled);
        fire.setAlpha(opacityPreview || enabled ? 1f : .35f);
        fire.setContentDescription("Fire");
        cancel.setContentDescription(state == 7 ? "Cancel horn" : state == 5 ? "Cancel interaction" : state == 4 ? "Cancel throw" : "Cancel shot");
        fire.setColorFilter(state == 3 ? Palette.ACTION_BUTTON_TEXT_ACTIVE : Palette.ACTION_BUTTON_TEXT);
        boolean vertical = state == 7 && canAimVertical && !paused;
        hornUp.setVisibility(vertical ? View.VISIBLE : View.GONE);
        hornDown.setVisibility(vertical ? View.VISIBLE : View.GONE);
        hornUp.setEnabled(!blocked && vertical);
        hornDown.setEnabled(!blocked && vertical);
        interact.setVisibility(state == 0 ? View.GONE : View.VISIBLE);
        boolean canInteract = !blocked && !paused && (state == 2 || state == 6);
        interact.setEnabled(!blocked && !paused && (canInteract || state == 5));
        interact.setContentDescription(state == 5 ? "Cancel interaction" : "Interact");
        stealth.setVisibility(state == 0 ? View.GONE : View.VISIBLE);
        stealth.setEnabled(canInteract);
        sing.setVisibility(state == 0 || !hasSongs ? View.GONE : View.VISIBLE);
        sing.setEnabled(canInteract && hasSongs);
        sing.setAlpha(opacityPreview || canInteract ? 1f : .35f);
        sing.setColorFilter(singing ? Palette.ACTION_BUTTON_TEXT_ACTIVE : Palette.ACTION_BUTTON_TEXT);
        sing.setContentDescription(singing ? "Change song (singing)" : "Sing");
        stealth.setAlpha(opacityPreview || canInteract ? 1f : .35f);
        stealth.setSelected(stealthActive);
        stealth.setColorFilter(stealthActive ? Palette.ACTION_BUTTON_TEXT_ACTIVE : Palette.ACTION_BUTTON_TEXT);
        stealth.setContentDescription(stealthActive ? "Disable stealth (on)" : "Enable stealth (off)");
        interact.setAlpha(opacityPreview || canInteract || state == 5 ? 1f : .35f);
        interact.setColorFilter(state == 5 ? Palette.ACTION_BUTTON_TEXT_ACTIVE : Palette.ACTION_BUTTON_TEXT);
        cancel.setVisibility(aiming() && state != 5 && !paused ? View.VISIBLE : View.GONE);
        cancel.setEnabled(!blocked && !paused);
        quiverNumber.setText(selectedQuiver == 2 ? "2" : "1");
        quiver.setContentDescription("Quiver " + selectedQuiver + "; switch quiver");
        quiver.setVisibility(state == 3 && !paused && canSwitchQuiver ? View.VISIBLE : View.GONE);
        boolean canSwitch = !blocked && !paused && state == 3 && canSwitchQuiver;
        quiver.setEnabled(canSwitch);
        quiver.setAlpha(opacityPreview || canSwitch ? 1f : .35f);
    }
}
