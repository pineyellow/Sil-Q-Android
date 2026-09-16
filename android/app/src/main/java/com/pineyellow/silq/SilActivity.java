package com.pineyellow.silq;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.libsdl.app.SDLActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Hosts the native game; preparation finishes before SDL starts its thread. */
public class SilActivity extends SDLActivity {
    static final int EDGE_SAFE_DP = 4;
    private FrameLayout gameOverlay;
    private FrameLayout settingsHost;
    private DPadOverlay dpadOverlay;
    private View dpadView;
    private final Runnable updateDpadLayout = this::applyDpadSettings;
    private MenuOverlay menuOverlay;
    private android.widget.TextView characterClose;
    private int characterSheetEpoch;
    private SettingsPanel settingsPanel;
    private InventoryOverlay inventoryOverlay;
    private FireControls fireControls;
    StepperDialog stepperDialog;
    private final ConfirmationDialog confirmationDialog = new ConfirmationDialog(this);
    private final NoteDialog noteDialog = new NoteDialog(this);
    private final SongDialog songDialog = new SongDialog(this);
    private final java.util.ArrayList<Dialog> ownedDialogs = new java.util.ArrayList<>();
    private final android.os.Handler saveFocusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveFocusCheck = this::updateSaveFocus;
    private Boolean appHasFocus;

    public void showSongDialog(String json, int requestId) {
        runOnUiThread(() -> songDialog.show(json, requestId));
    }

    native void nativeSongReply(int requestId, int choice);

    public void showNoteDialog(String text, int requestId) {
        runOnUiThread(() -> noteDialog.show(text, requestId));
    }

    public void showMonsterDialog(String json, int requestId) {
        runOnUiThread(() -> {
            try {
                org.json.JSONObject data = new org.json.JSONObject(json);
                org.json.JSONArray runs = data.getJSONArray("runs");
                android.text.SpannableStringBuilder body = new android.text.SpannableStringBuilder();
                for (int i = 0; i < runs.length(); i++) {
                    org.json.JSONObject run = runs.getJSONObject(i);
                    int start = body.length();
                    body.append(run.getString("text"));
                    body.setSpan(new android.text.style.ForegroundColorSpan(run.getInt("color")),
                        start, body.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                android.text.SpannableStringBuilder stats = new android.text.SpannableStringBuilder();
                org.json.JSONArray statRuns = data.optJSONArray("stats");
                if (statRuns != null) for (int i = 0; i < statRuns.length(); i++) {
                    org.json.JSONObject run = statRuns.getJSONObject(i);
                    int start = stats.length();
                    stats.append(run.getString("text"));
                    stats.setSpan(new android.text.style.ForegroundColorSpan(run.getInt("color")),
                        start, stats.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                noteDialog.show(data.getString("name"), body, requestId,
                    data.optBoolean("canExchange", false), stats);
            } catch (org.json.JSONException error) {
                nativeConfirmationResult(requestId, false);
            }
        });
    }

    public void showConfirmationDialog(String prompt, int requestId) {
        confirmationDialog.show(prompt, requestId);
    }

    native void nativeConfirmationResult(int requestId, boolean confirmed);
    private boolean dungeonControlsAvailable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCutoutSafeArea();
        // SDL keeps ownership of the surface. Empty overlay space passes through.
        gameOverlay = new FrameLayout(this);
        gameOverlay.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addContentView(gameOverlay, new FrameLayout.LayoutParams(-1, -1));
        settingsHost = new FrameLayout(this);
        settingsHost.setClickable(true);
        settingsHost.setVisibility(View.GONE);
        addContentView(settingsHost, new FrameLayout.LayoutParams(-1, -1));
        settingsPanel = new SettingsPanel(this, settingsHost);
        stepperDialog = new StepperDialog(this);
        menuOverlay = new MenuOverlay(this, settingsPanel::show);
        dpadOverlay = new DPadOverlay(this, menuOverlay::collapseSubmenu);
        dpadView = dpadOverlay.getView();
        dpadView.setVisibility(View.GONE);
        gameOverlay.addView(dpadView, new FrameLayout.LayoutParams(
            dpToPx(DPadOverlay.SIZE_DP), dpToPx(DPadOverlay.SIZE_DP),
            Gravity.TOP | Gravity.LEFT));
        // Menu always stays reachable, including when the D-pad overlaps it.
        gameOverlay.addView(menuOverlay.getView(), new FrameLayout.LayoutParams(-1, -1));
        characterClose = new android.widget.TextView(this);
        characterClose.setText("[x]");
        characterClose.setTextSize(20);
        characterClose.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        characterClose.setTextColor(Color.WHITE);
        characterClose.setGravity(Gravity.CENTER);
        characterClose.setContentDescription("Escape");
        characterClose.setVisibility(View.GONE);
        characterClose.setOnClickListener(v -> {
            nativeCharacterInput(true, characterSheetEpoch);
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
            dpToPx(48), dpToPx(48), Gravity.TOP | Gravity.RIGHT);
        closeParams.setMargins(0, dpToPx(8), dpToPx(8), 0);
        gameOverlay.addView(characterClose, closeParams);
        FrameLayout inventoryHost = new FrameLayout(this);
        inventoryOverlay = new InventoryOverlay(this, inventoryHost);
        FrameLayout.LayoutParams inventoryButton = new FrameLayout.LayoutParams(
            dpToPx(48), dpToPx(48), Gravity.BOTTOM | Gravity.RIGHT);
        inventoryButton.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        gameOverlay.addView(inventoryOverlay.button(), inventoryButton);
        fireControls = new FireControls(this, gameOverlay);
        addContentView(inventoryHost, new FrameLayout.LayoutParams(-1, -1));
        gameOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                // Wait until this layout pass finishes, using the latest bounds.
                v.removeCallbacks(updateDpadLayout);
                v.post(updateDpadLayout);
            }
        });
        updateDpadVisibility();
    }

    private void installCutoutSafeArea() {
        // Resize SDL's surface and every Activity overlay together. SDL receives
        // the new surface size and keeps rendering and touch coordinates aligned.
        FrameLayout content = findViewById(android.R.id.content);
        content.setBackgroundColor(Color.BLACK);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            applyCutoutSafeArea(content, insets);
            // Inventory still needs the keyboard insets and already accounts for
            // its position inside the safe area when calculating its own padding.
            return insets;
        });
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
            applyCutoutSafeArea(content, content.getRootWindowInsets()));
        content.requestApplyInsets();
    }

    private void applyCutoutSafeArea(FrameLayout content, android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT < 28 || insets == null
                || content.getWidth() == 0 || content.getHeight() == 0) return;
        android.view.DisplayCutout cutout = insets.getDisplayCutout();
        int left = 0, top = 0, right = 0, bottom = 0;
        if (cutout != null) {
            // Older Android versions may already keep the window out of the
            // cutout. Reserve only the part that still overlaps our content.
            View decor = getWindow().getDecorView();
            int[] origin = new int[2], windowOrigin = new int[2];
            content.getLocationInWindow(origin);
            decor.getLocationInWindow(windowOrigin);
            int x = origin[0] - windowOrigin[0], y = origin[1] - windowOrigin[1];
            left = Math.max(0, cutout.getSafeInsetLeft() - x);
            top = Math.max(0, cutout.getSafeInsetTop() - y);
            right = Math.max(0, cutout.getSafeInsetRight()
                - (decor.getWidth() - x - content.getWidth()));
            bottom = Math.max(0, cutout.getSafeInsetBottom()
                - (decor.getHeight() - y - content.getHeight()));
        }
        if (left != content.getPaddingLeft() || top != content.getPaddingTop()
                || right != content.getPaddingRight() || bottom != content.getPaddingBottom()) {
            cancelDpadInput();
            content.setPadding(left, top, right, bottom);
        }
    }

    // Called by the SDL/game thread; all View operations belong on the UI thread.
    public void onNativeDpadAvailability(boolean available, boolean canSave, boolean more) {
        runOnUiThread(() -> {
            dungeonControlsAvailable = available;
            if (menuOverlay != null) menuOverlay.setDungeonAvailable(available, canSave);
            if (inventoryOverlay != null) inventoryOverlay.available(available);
            if (menuOverlay != null) menuOverlay.setMessagePause(more);
            if (inventoryOverlay != null) inventoryOverlay.setMessagePause(more);
            updateDpadVisibility();
        });
    }

    private void updateDpadVisibility() {
        if (dpadView == null) return;
        boolean visible = dungeonControlsAvailable
            && GameSettings.getBool(this, DPadOverlay.PREF_ENABLED, true);
        if (!visible) cancelDpadInput();
        dpadView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) applyDpadSettings();
    }

    void setDpadEnabled(boolean enabled) {
        GameSettings.setBool(this, DPadOverlay.PREF_ENABLED, enabled);
        updateDpadVisibility();
    }

    void cancelDpadInput() {
        if (dpadOverlay != null) dpadOverlay.cancelInput();
    }

    void openInventoryFromButton() {
        if (inventoryOverlay.visible()) return;
        menuOverlay.collapseSubmenu();
        cancelDpadInput();
        nativeOpenInventory();
    }

    void openCharacterSheet() {
        cancelDpadInput();
        nativeCharacterInput(false, 0);
    }

    native void nativeCharacterInput(boolean close, int epoch);
    void openAbilities() {
        prepareFireInput();
        nativeOpenAbilities();
    }
    native void nativeOpenAbilities();
    native void nativeAbandonGame();
    native void nativeSaveAndQuit();
    native void nativeSaveLifecycle(int reason, boolean inactive);
    native void nativeOpenGround(int epoch);

    public void onNativeGroundItem(String json, int epoch) {
        runOnUiThread(() -> {
            if (fireControls != null) fireControls.updateGround(json, epoch);
        });
    }

    public void onNativeCharacterSheet(boolean visible, int epoch) {
        runOnUiThread(() -> {
            characterSheetEpoch = epoch;
            cancelDpadInput();
            menuOverlay.setCharacterScreenOpen(visible);
            characterClose.setEnabled(visible);
            characterClose.setVisibility(visible ? View.VISIBLE : View.GONE);
        });
    }

    void setInventoryOpen(boolean open) {
        if (open) menuOverlay.collapseSubmenu();
        menuOverlay.setInventoryOpen(open);
    }

    void setOverlayInputBlocked(boolean blocked) {
        cancelDpadInput();
        if (fireControls != null) fireControls.block(blocked);
        if (inventoryOverlay != null) inventoryOverlay.setBlocked(blocked);
        nativeSetOverlayInputBlocked(blocked);
    }

    void prepareFireInput() {
        menuOverlay.collapseSubmenu();
        cancelDpadInput();
    }

    native void nativeFireInput(int epoch, boolean cancel);
    native void nativeInteractInput(int epoch);
    native void nativeStealthInput(int epoch);
    native void nativeSingInput(int epoch);
    native void nativeQuiverInput(int epoch);
    native void nativeHornDirectionInput(int epoch, boolean down);

    public void onNativeFireState(int state, int epoch, boolean stealth, boolean singing,
                                  int quiver, boolean canSwitchQuiver, boolean canAimVertical,
                                  boolean hasSongs, boolean hasBow) {
        runOnUiThread(() -> {
            if (fireControls != null) {
                fireControls.update(state, epoch, stealth, singing, quiver, canSwitchQuiver,
                    canAimVertical, hasSongs, hasBow);
                boolean aiming = fireControls.aiming();
                if (inventoryOverlay != null) inventoryOverlay.setAiming(aiming);
                if (inventoryOverlay != null) inventoryOverlay.setCommandAvailable(state == 2 || state == 6);
                if (menuOverlay != null) menuOverlay.setAiming(aiming);
            }
        });
    }

    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static float boundedOrDefault(float value, float fallback, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    void applyDpadSettings() {
        if (dpadView == null || gameOverlay == null) return;
        if (gameOverlay.getVisibility() != View.VISIBLE
                || gameOverlay.getWidth() == 0 || gameOverlay.getHeight() == 0) {
            return;
        }

        float savedSizeScale = GameSettings.getFloat(
            this, DPadOverlay.PREF_SIZE, DPadOverlay.DEFAULT_SIZE);
        float sizeScale = boundedOrDefault(savedSizeScale, DPadOverlay.DEFAULT_SIZE,
            DPadOverlay.MIN_SIZE, DPadOverlay.MAX_SIZE);
        if (savedSizeScale != sizeScale) {
            GameSettings.setFloat(this, DPadOverlay.PREF_SIZE, sizeScale);
        }

        float savedButtonWidthScale = GameSettings.getFloat(
            this, DPadOverlay.PREF_BUTTON_WIDTH, DPadOverlay.DEFAULT_BUTTON_WIDTH);
        float buttonWidthScale = boundedOrDefault(savedButtonWidthScale,
            DPadOverlay.DEFAULT_BUTTON_WIDTH,
            DPadOverlay.MIN_BUTTON_WIDTH, DPadOverlay.MAX_BUTTON_WIDTH);
        if (savedButtonWidthScale != buttonWidthScale) {
            GameSettings.setFloat(this, DPadOverlay.PREF_BUTTON_WIDTH, buttonWidthScale);
        }

        int gridWidthPx = dpToPx(DPadOverlay.SIZE_DP * sizeScale * buttonWidthScale);
        int gridHeightPx = dpToPx(DPadOverlay.SIZE_DP * sizeScale);
        // Keep every direction reachable even at the largest scale on a small display.
        float fit = Math.min(1f, Math.min((float) gameOverlay.getWidth() / gridWidthPx,
            (float) gameOverlay.getHeight() / gridHeightPx));
        gridWidthPx = Math.max(3, Math.round(gridWidthPx * fit));
        gridHeightPx = Math.max(3, Math.round(gridHeightPx * fit));
        int deadZonePx = dpToPx(DPadOverlay.deadZoneDp(sizeScale));
        int widthPx = gridWidthPx + deadZonePx * 2;
        int heightPx = gridHeightPx + deadZonePx * 2;
        dpadOverlay.setDeadZoneSize(deadZonePx);

        float defaultAnchorXPx = dpToPx(DPadOverlay.SIZE_DP / 2f);
        float defaultAnchorYPx = gameOverlay.getHeight()
            - dpToPx(DPadOverlay.MARGIN_DP + DPadOverlay.SIZE_DP / 2f);
        float anchorXPx = defaultAnchorXPx
            + dpToPx(GameSettings.getFloat(this, DPadOverlay.PREF_OFFSET_X, 1f));
        float anchorYPx = defaultAnchorYPx
            - dpToPx(GameSettings.getFloat(this, DPadOverlay.PREF_OFFSET_Y, 0f));

        // Clamp the visible grid exactly as before, then place the guard
        // around it. Any guard extending beyond a screen edge is harmlessly
        // clipped without shifting the controls the player positioned.
        int gridLeft = Math.round(anchorXPx - gridWidthPx / 2f);
        int gridTop = Math.round(anchorYPx - gridHeightPx / 2f);
        gridLeft = Math.max(0,
            Math.min(gameOverlay.getWidth() - gridWidthPx, gridLeft));
        gridTop = Math.max(0,
            Math.min(gameOverlay.getHeight() - gridHeightPx, gridTop));
        int left = gridLeft - deadZonePx;
        int top = gridTop - deadZonePx;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dpadView.getLayoutParams();
        if (params != null && params.width == widthPx && params.height == heightPx
                && params.gravity == (Gravity.TOP | Gravity.LEFT)
                && params.leftMargin == left && params.topMargin == top
                && params.rightMargin == 0 && params.bottomMargin == 0) return;
        if (params == null) {
            params = new FrameLayout.LayoutParams(widthPx, heightPx, Gravity.TOP | Gravity.LEFT);
        }
        params.width = widthPx;
        params.height = heightPx;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = 0;
        params.bottomMargin = 0;
        dpadView.setLayoutParams(params);
    }

    private void restoreImmersiveMode(Window window) {
        if (window == null) return;
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29)
            window.setNavigationBarContrastEnforced(false);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    void showImmersiveDialog(Dialog dialog) {
        dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        ownedDialogs.add(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(
                focused -> {
                    if (focused) restoreImmersiveMode(window);
                    scheduleSaveFocusCheck();
                });
            window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { scheduleSaveFocusCheck(); }
                @Override public void onViewDetachedFromWindow(View view) {
                    ownedDialogs.remove(dialog);
                    scheduleSaveFocusCheck();
                }
            });
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility());
        }
        dialog.show();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility());
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            restoreImmersiveMode(window);
        }
    }

    @Override public void onBackPressed() {
        // Disabled until Android Back has explicit contextual behavior.
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        if (fireControls != null && fireControls.aiming()
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.getAction() == KeyEvent.ACTION_UP) fireControls.cancel();
            return true;
        }
        if (inventoryOverlay != null && inventoryOverlay.visible()) {
            if ((event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)
                    && event.getAction() == KeyEvent.ACTION_UP) {
                inventoryOverlay.back();
                return true;
            }
            return inventoryOverlay.editingText() ? super.dispatchKeyEvent(event) : true;
        }
        if (settingsHost != null && (settingsHost.getVisibility() == View.VISIBLE
                || menuOverlay.isExpanded())) {
            if ((event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)
                    && event.getAction() == KeyEvent.ACTION_UP) {
                if (settingsHost.getVisibility() == View.VISIBLE) settingsPanel.hide();
                else menuOverlay.collapseSubmenu();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() {
        nativeSaveLifecycle(1, false);
        songDialog.resume();
        if (fireControls != null) fireControls.resume();
        noteDialog.resume();
        if (inventoryOverlay != null) inventoryOverlay.resume();
        confirmationDialog.onActivityResumed();
        super.onResume();
        restoreImmersiveMode(getWindow());
    }

    @Override protected void onPause() {
        nativeSaveLifecycle(1, true);
        songDialog.pause();
        if (fireControls != null) fireControls.pause();
        noteDialog.pause();
        if (inventoryOverlay != null) inventoryOverlay.pause();
        confirmationDialog.onActivityPaused();
        cancelDpadInput();
        if (stepperDialog != null) stepperDialog.cancel();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) restoreImmersiveMode(getWindow());
        if (!hasFocus) cancelDpadInput();
        scheduleSaveFocusCheck();
    }

    private void scheduleSaveFocusCheck() {
        saveFocusHandler.removeCallbacks(saveFocusCheck);
        // Resume immediately on confirmed focus gain. Defer only focus loss
        // so transfers between our activity and dialogs can settle.
        if (hasAppWindowFocus()) updateSaveFocus();
        else saveFocusHandler.postDelayed(saveFocusCheck, 100);
    }

    private boolean hasAppWindowFocus() {
        boolean focused = getWindow().getDecorView().hasWindowFocus();
        for (Dialog dialog : ownedDialogs) {
            Window window = dialog.getWindow();
            focused |= dialog.isShowing() && window != null && window.getDecorView().hasWindowFocus();
        }
        return focused;
    }

    private void updateSaveFocus() {
        boolean focused = hasAppWindowFocus();
        if (appHasFocus != null && appHasFocus == focused) return;
        appHasFocus = focused;
        nativeSaveLifecycle(2, !focused);
        if (!focused) {
            cancelDpadInput();
            if (songDialog != null) songDialog.pause();
            if (fireControls != null) fireControls.pause();
            if (noteDialog != null) noteDialog.pause();
            if (inventoryOverlay != null) inventoryOverlay.pause();
            if (confirmationDialog != null) confirmationDialog.onActivityPaused();
            if (stepperDialog != null) stepperDialog.cancel();
        } else {
            if (songDialog != null) songDialog.resume();
            if (fireControls != null) fireControls.resume();
            if (noteDialog != null) noteDialog.resume();
            if (inventoryOverlay != null) inventoryOverlay.resume();
            if (confirmationDialog != null) confirmationDialog.onActivityResumed();
        }
        // SDL also interprets window focus loss as backgrounding. Forward
        // application focus, so an in-app confirmation doesn't suspend SDL.
        super.onWindowFocusChanged(focused);
    }

    @Override protected void onDestroy() {
        saveFocusHandler.removeCallbacks(saveFocusCheck);
        songDialog.pause();
        if (fireControls != null) fireControls.pause();
        noteDialog.pause();
        if (inventoryOverlay != null) inventoryOverlay.pause();
        confirmationDialog.onActivityDestroying();
        cancelDpadInput();
        if (stepperDialog != null) stepperDialog.cancel();
        super.onDestroy();
    }

    native int nativeBeginDpadPress();
    native void nativeOpenInventory();
    native void nativeInventoryReply(int requestId, int value, int action);
    native void nativeInventoryTextReply(int requestId, byte[] text);

    public void showInventory(String json, int requestId) {
        runOnUiThread(() -> {
            if (inventoryOverlay != null) inventoryOverlay.show(json, requestId);
            else nativeInventoryReply(requestId, -1, -1);
        });
    }

    public void hideInventory() {
        runOnUiThread(() -> { if (inventoryOverlay != null) inventoryOverlay.hide(); });
    }
    native boolean nativeSendDpadKey(int command, int pressToken, boolean repeat);
    native void nativeCancelDpadInput();
    native void nativeSetOverlayInputBlocked(boolean blocked);
    native void nativeSetGraphicsTiles(boolean tiles);
    native void nativeSetCamera(int mode, int speed, float zoom);

    // Read by the engine only when starting a tutorial; no live inventory edits.
    public boolean isDebugTutorialEnabled() {
        return BuildConfig.DEBUG && GameSettings.getBool(this, "debug_tutorial_kit", false);
    }

    void applyCameraSettings() {
        GameSettings.setBool(this, "camera_free", true);
        nativeSetCamera(1,
            3, // Instant only for now; native follow speeds remain available.
            GameSettings.getFloat(this, "camera_zoom", 1.5f));
    }

    public void onNativeCameraZoom(float zoom) {
        runOnUiThread(() -> GameSettings.setFloat(this, "camera_zoom", zoom));
    }

    @Override protected String[] getLibraries() {
        return new String[] { "SDL2", "silq" };
    }

    @Override public void loadLibraries() {
        try {
            copyAssets("lib");
            for (String dir : new String[] {"apex", "data", "save", "user", "xtra"}) {
                File destination = new File(getFilesDir(), "lib/" + dir);
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("Cannot create " + destination);
                }
            }
            writeFontAtlas(new File(getFilesDir(), "font.bmp"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare Sil-Q resources", e);
        }
        super.loadLibraries();
        GameSettings.setBool(this, GameSettings.PREF_TILES, true);
        nativeSetGraphicsTiles(true);
        applyCameraSettings();
    }

    private void copyAssets(String path) throws IOException {
        String[] children = getAssets().list(path);
        if (children == null) throw new IOException("Cannot list " + path);
        File destination = new File(getFilesDir(), path);
        if (children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("Cannot create " + destination);
            }
            for (String child : children) copyAssets(path + "/" + child);
        } else {
            try (InputStream in = getAssets().open(path);
                 FileOutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            }
        }
    }

    // Rasterize Android's own monospace font; no desktop font distribution needed.
    // A plain 24-bit BMP can be loaded by SDL without SDL_image or SDL_ttf.
    private void writeFontAtlas(File file) throws IOException {
        final int width = 256, height = 512, cellWidth = 16, cellHeight = 32;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(26);
        paint.setColor(Color.WHITE);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (cellHeight - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent;
        for (int c = 32; c < 256; c++) {
            if (c >= 127 && c < 160) continue;
            String glyph = String.valueOf((char)c);
            float x = (c % 16) * cellWidth + (cellWidth - paint.measureText(glyph)) / 2;
            canvas.drawText(glyph, x, (c / 16) * cellHeight + baseline, paint);
        }
        int imageSize = width * height * 3;
        ByteBuffer header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte)'B').put((byte)'M').putInt(54 + imageSize).putInt(0).putInt(54);
        header.putInt(40).putInt(width).putInt(height).putShort((short)1).putShort((short)24);
        header.putInt(0).putInt(imageSize).putInt(0).putInt(0).putInt(0).putInt(0);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header.array());
            int[] pixels = new int[width];
            byte[] row = new byte[width * 3];
            for (int y = height - 1; y >= 0; y--) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    row[x * 3] = (byte)Color.blue(pixels[x]);
                    row[x * 3 + 1] = (byte)Color.green(pixels[x]);
                    row[x * 3 + 2] = (byte)Color.red(pixels[x]);
                }
                out.write(row);
            }
        } finally {
            bitmap.recycle();
        }
    }
}
