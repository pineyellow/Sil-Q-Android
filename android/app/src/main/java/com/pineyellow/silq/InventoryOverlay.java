package com.pineyellow.silq;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Item rows with a separate centered detail/action panel.
 * Only immutable snapshots cross from the game thread to this UI thread. */
final class InventoryOverlay {
    private static final String[] ACTIONS = {"Equip", "Remove", "Eat", "Drink", "Refuel", "Drop",
        "Throw", "Destroy", "Use Staff", "Play Horn", "Pick Up", "Fletch"};
    private final SilActivity activity;
    private final FrameLayout host;
    private final ImageButton button;
    private FrameLayout detailLayer, choiceLayer;
    private ScrollView listScroll;
    private JSONObject browse;
    private int request, selected = -1, scrollY;
    private boolean resumed, busy;
    private EditText textEntry;
    private int safeLeft, safeTop, safeRight, safeBottom;

    InventoryOverlay(SilActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
        host.setVisibility(View.GONE);
        button = new ImageButton(activity);
        button.setImageResource(R.drawable.ic_money_bag);
        button.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        button.setContentDescription("Inventory");
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = background(Palette.ACTION_BUTTON_BG);
        bg.setStroke(dp(1), Palette.BUTTON_BORDER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.BUTTON_RIPPLE), bg, null));
        button.setOnClickListener(v -> {
            activity.openInventoryFromButton();
        });
        button.setVisibility(View.GONE);
        host.setOnApplyWindowInsetsListener((v, insets) -> {
            // SDL uses immersive layout with stable system-bar insets. Those
            // legacy insets can reserve a hidden navigation bar on reopening.
            // Only physical cutouts and the keyboard constrain this overlay;
            // transient system bars float above the immersive game.
            int left = 0, right = 0, top = 0, bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                    android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.ime());
                left = safe.left; right = safe.right;
                top = safe.top; bottom = safe.bottom;
            }
            if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            safeLeft = left; safeTop = top; safeRight = right; safeBottom = bottom;
            applySafeInsets();
            return insets;
        });
        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applySafeInsets());
    }

    private void applySafeInsets() {
        if (host.getWidth() == 0 || host.getHeight() == 0) return;
        // Recompute after layout as well as inset delivery: the first delivery
        // can precede measurement, and the window may already avoid the cutout.
        View decor = activity.getWindow().getDecorView();
        int[] origin = new int[2], windowOrigin = new int[2];
        host.getLocationInWindow(origin);
        decor.getLocationInWindow(windowOrigin);
        int x = origin[0] - windowOrigin[0], y = origin[1] - windowOrigin[1];
        int left = Math.max(0, safeLeft - x), top = Math.max(0, safeTop - y);
        int right = Math.max(0, safeRight - (decor.getWidth() - x - host.getWidth()));
        int bottom = Math.max(0, safeBottom - (decor.getHeight() - y - host.getHeight()));
        if (left != host.getPaddingLeft() || top != host.getPaddingTop()
                || right != host.getPaddingRight() || bottom != host.getPaddingBottom()) {
            host.setPadding(left, top, right, bottom);
        }
    }

    View button() { return button; }
    private boolean aiming, blocked, messagePause, commandAvailable;
    void setBlocked(boolean value) { blocked = value; updateButtonEnabled(); }
    void setMessagePause(boolean value) { messagePause = value; updateButtonEnabled(); }
    void setCommandAvailable(boolean value) { commandAvailable = value; updateButtonEnabled(); }
    private void updateButtonEnabled() {
        boolean enabled = commandAvailable && !aiming && !blocked && !messagePause && request == 0;
        button.setEnabled(enabled);
        button.setClickable(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }
    void available(boolean available) { button.setVisibility(available ? View.VISIBLE : View.GONE); }
    void setAiming(boolean active) {
        aiming = active;
        updateButtonEnabled();
    }
    boolean visible() { return host.getVisibility() == View.VISIBLE; }
    boolean editingText() { return textEntry != null; }
    void resume() { resumed = true; }
    void pause() {
        resumed = false;
        if (request != 0) activity.nativeInventoryReply(request, -1, -1);
        hide();
    }

    void show(String json, int id) {
        if (!resumed || activity.isFinishing()) {
            activity.nativeInventoryReply(id, -1, -1);
            return;
        }
        try {
            JSONObject data = new JSONObject(json);
            request = id; busy = false;
            activity.setInventoryOpen(true);
            button.setEnabled(false);
            button.setClickable(false);
            activity.setOverlayInputBlocked(true);
            host.setVisibility(View.VISIBLE);
            host.requestApplyInsets();
            String mode = data.optString("mode");
            if ("floor".equals(mode)) {
                rememberScrollPosition();
                browse = data;
                JSONArray items = data.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    host.removeAllViews();
                    detailLayer = choiceLayer = null;
                    listScroll = null;
                    showDetails(items.getJSONObject(0));
                } else reply(-1);
            } else if ("browse".equals(mode)) {
                rememberScrollPosition();
                browse = data;
                buildList();
                if (selected >= 0) {
                    JSONObject item = findItem(selected);
                    if (item != null) showDetails(item);
                }
            } else showChoice(data, "quantity".equals(mode));
        } catch (Exception error) {
            android.util.Log.e("SilQ", "Cannot display inventory", error);
            activity.nativeInventoryReply(id, -1, -1);
            hide();
        }
    }

    void hide() {
        rememberScrollPosition();
        if (textEntry != null) {
            android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(textEntry.getWindowToken(), 0);
            textEntry = null;
        }
        request = 0; selected = -1; busy = false;
        browse = null; listScroll = null; detailLayer = choiceLayer = null;
        host.removeAllViews();
        host.setVisibility(View.GONE);
        updateButtonEnabled();
        activity.setInventoryOpen(false);
        activity.setOverlayInputBlocked(false);
    }

    private void rememberScrollPosition() {
        // Floor details and filtered choices must not replace the inventory's
        // position. An unlaid-out replacement has not restored it yet.
        if (listScroll != null && listScroll.isLaidOut())
            scrollY = Math.max(0, listScroll.getScrollY());
    }

    void back() {
        if (busy) return;
        if (choiceLayer != null) { reply(-1); return; }
        if (detailLayer != null) {
            if (browse != null && "floor".equals(browse.optString("mode"))) {
                reply(-1);
                return;
            }
            host.removeView(detailLayer); detailLayer = null; selected = -1;
        } else { reply(-1); }
    }

    private void reply(int value) {
        reply(value, -1);
    }

    private void reply(int value, int action) {
        if (busy || request == 0) return;
        busy = true;
        int id = request; request = 0;
        activity.nativeInventoryReply(id, value, action);
    }

    private JSONObject findItem(int id) {
        JSONArray items = browse.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optInt("id") == id) return item;
        }
        return null;
    }

    private void buildList() {
        host.removeAllViews(); detailLayer = choiceLayer = null;
        host.addView(backdrop(140, this::back), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = panel();
        LinearLayout inventoryHeader = header("INVENTORY  " + browse.optInt("count")
            + " / " + browse.optInt("capacity"));
        inventoryHeader.setBaselineAligned(true);
        int carriedWeight = browse.optInt("totalWeight");
        TextView weight = label(carriedWeight / 10 + "." + carriedWeight % 10 + " lb", false);
        weight.setContentDescription("Carried weight: " + carriedWeight / 10 + "."
            + carriedWeight % 10 + " pounds");
        weight.setTextColor(browse.optBoolean("encumbered")
            ? android.graphics.Color.rgb(255, 85, 85) : android.graphics.Color.rgb(192, 192, 192));
        weight.setPadding(dp(12), dp(6), dp(6), dp(6));
        weight.setGravity(Gravity.RIGHT);
        inventoryHeader.addView(weight, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams headerLayout = new LinearLayout.LayoutParams(-1, -2);
        headerLayout.bottomMargin = dp(4);
        panel.addView(inventoryHeader, headerLayout);
        listScroll = scroll();
        LinearLayout rows = column();
        JSONArray items = browse.optJSONArray("items");
        for (boolean equipped : new boolean[]{true, false}) {
            int count = 0;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || (!item.optString("slot").isEmpty()) != equipped) continue;
                if (!equipped && count == 0 && rows.getChildCount() > 0) {
                    View divider = new View(activity);
                    divider.setBackgroundColor(Palette.BORDER_DIM);
                    LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
                    // The preceding item row already contributes 4 dp below it.
                    line.setMargins(dp(2), dp(6) - dp(4), dp(2), dp(6));
                    rows.addView(divider, line);
                }
                rows.addView(itemRow(item, () -> { if (!busy) showDetails(item); }));
                count++;
            }
        }
        if (rows.getChildCount() == 0) rows.addView(label("Inventory is empty", false));
        listScroll.addView(rows);
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            Math.min(dp(340), Math.round(width() * .55f)), height() - dp(16), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        params.setMargins(dp(4), dp(8), dp(4), dp(8));
        host.addView(panel, params);
        fitPanel(host, panel, params.width, dp(16));
        ScrollView restoredList = listScroll;
        restoredList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                    int oldL, int oldT, int oldR, int oldB) {
                restoredList.removeOnLayoutChangeListener(this);
                if (listScroll != restoredList) return;
                int viewport = restoredList.getHeight() - restoredList.getPaddingTop()
                    - restoredList.getPaddingBottom();
                int maximum = Math.max(0, rows.getHeight() - viewport);
                scrollY = Math.max(0, Math.min(scrollY, maximum));
                restoredList.scrollTo(0, scrollY);
            }
        });
    }

    private void showDetails(JSONObject item) {
        if (detailLayer != null) host.removeView(detailLayer);
        selected = item.optInt("id");
        detailLayer = layer(this::back);
        LinearLayout panel = panel();
        // Keep actions at a consistent height for ordinary items. Parent
        // measurement caps this minimum to the available screen space.
        panel.setMinimumHeight(dp(350));
        panel.addView(header("ITEM DETAILS"));
        ScrollView description = scroll();
        LinearLayout text = column();
        text.addView(itemRow(item, null, true));
        SpannableStringBuilder stats = styledRuns(item.optJSONArray("stats"));
        if (stats.length() > 0) {
            StatFlow flow = new StatFlow(activity, 18, 0);
            int start = 0;
            for (int i = 0; i <= stats.length(); i++) {
                if (i == stats.length() || stats.charAt(i) == '\n') {
                    if (i > start) {
                        TextView stat = label("", false);
                        stat.setPadding(0, dp(3), 0, dp(3));
                        stat.setText(stats.subSequence(start, i));
                        flow.addView(stat, new android.view.ViewGroup.LayoutParams(-2, -2));
                    }
                    start = i + 1;
                }
            }
            LinearLayout.LayoutParams statLayout = new LinearLayout.LayoutParams(-1, -2);
            // Preserve outer spacing while halving the padding between rows.
            statLayout.topMargin = dp(6) - dp(3);
            statLayout.bottomMargin = dp(6) - dp(3);
            text.addView(flow, statLayout);
        }
        SpannableStringBuilder styled = styledRuns(item.optJSONArray("runs"));
        // The terminal indents paragraphs with spaces. Keep its line breaks,
        // but align every paragraph with the Android panel's left edge.
        for (int i = styled.length() - 1; i >= 0; i--) {
            if (i == 0 || styled.charAt(i - 1) == '\n') {
                int end = i;
                while (end < styled.length()
                        && (styled.charAt(end) == ' ' || styled.charAt(end) == '\t')) end++;
                if (end > i) styled.delete(i, end);
            }
        }
        TextView descriptionText = label("", false);
        int leading = 0;
        while (leading < styled.length() && Character.isWhitespace(styled.charAt(leading))) leading++;
        styled.delete(0, leading);
        if (stats.length() > 0 && styled.length() > 0) {
            View divider = new View(activity);
            divider.setBackgroundColor(Palette.BORDER_DIM);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
            // Stats padding/margin and description padding each supply 6 dp.
            line.setMargins(0, dp(8) - dp(6), 0, dp(8) - dp(6));
            text.addView(divider, line);
        }
        descriptionText.setText(styled);
        descriptionText.setLineSpacing(dp(3), 1f);
        descriptionText.setPadding(0, dp(6), 0, 0);
        text.addView(descriptionText);
        description.addView(text);
        // Fill the panel's minimum height, grow for longer descriptions, and
        // shrink only this area into scrolling at the screen's height limit.
        panel.addView(description, new LinearLayout.LayoutParams(-1, -2, 1));
        HorizontalScrollView actionScroll = new HorizontalScrollView(activity);
        actionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        int mask = item.optInt("actions");
        int disabled = item.optInt("disabledActions");
        // Primary uses precede Pick Up. Refuel is primary for oil flasks;
        // equippable lights keep it at the end as a secondary action.
        // Include disabled actions so enabled state never changes the order.
        int visibleActions = mask | disabled;
        boolean primaryRefuel = (visibleActions & (1 << 4)) != 0
            && (visibleActions & ((1 << 0) | (1 << 1))) == 0;
        int[] actionOrder = primaryRefuel
            ? new int[]{4, 0, 1, 2, 3, 8, 9, 10, 5, 6, 7, 11}
            : new int[]{0, 1, 2, 3, 8, 9, 10, 5, 6, 7, 11, 4};
        int firstAction = -1;
        for (int action : actionOrder) {
            if (((mask | disabled) & (1 << action)) == 0) continue;
            if (firstAction < 0) firstAction = action;
            final int itemId = selected;
            final int actionId = action;
            boolean implemented = (mask & (1 << action)) != 0;
            TextView button = action(ACTIONS[action], () -> reply(itemId, actionId));
            button.setMinWidth(dp(84));
            if (!implemented) {
                button.setEnabled(false);
                button.setClickable(false);
                button.setBackground(background(Palette.ITEM_BG));
                button.setTextColor(android.graphics.Color.rgb(115, 118, 121));
                button.setContentDescription(ACTIONS[action] + ": remove the item first");
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.rightMargin = dp(6);
            actions.addView(button, bp);
        }
        if (actions.getChildCount() > 0) {
            View lastAction = actions.getChildAt(actions.getChildCount() - 1);
            ((LinearLayout.LayoutParams) lastAction.getLayoutParams()).rightMargin = 0;
        }
        // Reserve the activation column when only the three common actions
        // remain, keeping Drop or Pick Up / Throw / Destroy aligned with other items.
        if ((firstAction == 5 || firstAction == 10) && actions.getChildCount() == 3
                && ((mask | disabled) & ((1 << 6) | (1 << 7))) == ((1 << 6) | (1 << 7))) {
            actions.addView(new View(activity), 0, new LinearLayout.LayoutParams(dp(84) + dp(6), 1));
        }
        actions.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int minimumActionWidth = 4 * dp(84) + 3 * dp(6);
        int detailWidth = Math.max(minimumActionWidth, actions.getMeasuredWidth())
            + panel.getPaddingLeft() + panel.getPaddingRight();
        actionScroll.addView(actions);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(10);
        panel.addView(actionScroll, ap);
        int panelWidth = Math.min(detailWidth, width() - dp(24));
        FrameLayout.LayoutParams detailParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        detailParams.topMargin = dp(16);
        detailParams.bottomMargin = dp(16);
        detailLayer.addView(panel, detailParams);
        fitPanel(detailLayer, panel, panelWidth, dp(32));
    }

    private void showChoice(JSONObject data, boolean quantity) {
        if (choiceLayer != null) host.removeView(choiceLayer);
        choiceLayer = layer(() -> reply(-1));
        LinearLayout panel = panel();
        panel.addView(header(data.optString("prompt")));
        if ("text".equals(data.optString("mode"))) {
            ScrollView formScroll = scroll();
            LinearLayout form = column();
            textEntry = new EditText(activity);
            textEntry.setTextColor(Palette.TEXT);
            textEntry.setSingleLine(true);
            textEntry.setTypeface(Typeface.MONOSPACE);
            textEntry.setText(data.optString("value"));
            textEntry.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(data.optInt("max", 79))});
            form.addView(textEntry, new LinearLayout.LayoutParams(-1, -2));
            form.addView(action("Save", () -> {
                if (request == 0 || busy) return;
                busy = true; int id = request; request = 0;
                activity.nativeInventoryTextReply(id, textEntry.getText().toString()
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }));
            formScroll.addView(form);
            panel.addView(formScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        } else if (quantity) {
            int all = Math.max(1, data.optInt("max", 1));
            int half = Math.max(1, all / 2);
            int quarter = Math.max(1, all / 4);
            ScrollView choices = scroll();
            LinearLayout rows = column();
            String[] labels = {"All (" + all + ")", "Half (" + half + ")",
                "Quarter (" + quarter + ")", "Single (1)"};
            int[] amounts = {all, half, quarter, 1};
            for (int i = 0; i < labels.length; i++) {
                final int amount = amounts[i];
                // Prefer All for the full stack and Single for one item.
                if (i > 0 && i < 3 && (amount == all || amount == 1
                        || (i == 2 && amount == half))) continue;
                if (i == 3 && all == 1) continue;
                TextView preset = action(labels[i], () -> reply(amount));
                preset.setSingleLine(true);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(120), dp(44));
                p.gravity = Gravity.CENTER_HORIZONTAL;
                if (rows.getChildCount() > 0) p.topMargin = dp(6);
                rows.addView(preset, p);
            }
            choices.addView(rows);
            panel.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        } else {
            ScrollView scroll = scroll();
            LinearLayout rows = column();
            JSONArray items = data.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) rows.addView(itemRow(item, () -> reply(item.optInt("id"))));
            }
            scroll.addView(rows); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        TextView cancel = action("Cancel", () -> reply(-1));
        if (quantity) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(120), dp(44));
            p.gravity = Gravity.CENTER_HORIZONTAL;
            p.topMargin = dp(8);
            panel.addView(cancel, p);
        } else panel.addView(cancel);
        center(choiceLayer, panel, Math.min(dp(quantity ? 180 : 380), width() - dp(32)),
            quantity ? -2 : height() - dp(48));
        if (textEntry != null) {
            textEntry.requestFocus();
            textEntry.post(() -> {
                if (textEntry != null) ((android.view.inputmethod.InputMethodManager)
                    activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(textEntry, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private View itemRow(JSONObject item, Runnable click) {
        return itemRow(item, click, false);
    }

    private View itemRow(JSONObject item, Runnable click, boolean showSlot) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        boolean equipped = !item.optString("slot").isEmpty();
        GradientDrawable rowBackground = background(equipped ? Palette.EQUIPPED_BG : Palette.ITEM_BG);
        if (equipped) rowBackground.setStroke(dp(1), Palette.EQUIPPED_BORDER);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE_GLOW), rowBackground, null));
        ImageView icon = new ImageView(activity);
        JSONArray pixels = item.optJSONArray("pixels");
        if (pixels != null && pixels.length() == 256) {
            int[] colors = new int[256];
            for (int p = 0; p < 256; p++) colors[p] = pixels.optInt(p);
            Bitmap bitmap = Bitmap.createBitmap(colors, 16, 16, Bitmap.Config.ARGB_8888);
            BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
            drawable.setFilterBitmap(false); icon.setImageDrawable(drawable);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout words = column();
        TextView name = label(item.optString("name"), false);
        if (equipped) name.setTextColor(Palette.EQUIPPED_TEXT);
        if (showSlot) name.setTextColor(android.graphics.Color.YELLOW);
        words.addView(name);
        String slot = item.optString("slot");
        if (showSlot && !slot.isEmpty()) {
            // Keep the equipment-use line directly below the name, without
            // stacking the two labels' usual vertical padding between them.
            name.setPadding(dp(6), dp(6), dp(6), 0);
            TextView use = label(slot, true);
            use.setPadding(dp(6), 0, dp(6), dp(6));
            words.addView(use);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(4); row.setLayoutParams(rp);
        if (click != null) row.setOnClickListener(v -> { if (!busy) click.run(); });
        return row;
    }

    private FrameLayout layer(Runnable dismiss) {
        FrameLayout layer = new FrameLayout(activity);
        layer.addView(backdrop(110, dismiss), new FrameLayout.LayoutParams(-1, -1));
        host.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }
    private View backdrop(int alpha, Runnable click) {
        View view = new View(activity);
        view.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0));
        view.setOnClickListener(v -> { if (!busy) click.run(); });
        return view;
    }
    private void center(FrameLayout layer, View panel, int width, int height) {
        layer.addView(panel, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        fitPanel(layer, panel, width, dp(32));
    }
    private void fitPanel(FrameLayout parent, View panel, int maximumWidth, int verticalGap) {
        parent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                if (panel.getParent() != parent) {
                    parent.removeOnLayoutChangeListener(this);
                    return;
                }
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
                int w = Math.max(1, Math.min(maximumWidth,
                    r - l - parent.getPaddingLeft() - parent.getPaddingRight() - dp(24)));
                int h = p.height == FrameLayout.LayoutParams.WRAP_CONTENT
                    ? FrameLayout.LayoutParams.WRAP_CONTENT
                    : Math.max(1, b - t - parent.getPaddingTop() - parent.getPaddingBottom() - verticalGap);
                if (p.width != w || p.height != h) {
                    p.width = w; p.height = h; panel.setLayoutParams(p);
                }
            }
        });
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout panel() {
        LinearLayout view = column();
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackground(background(Palette.PANEL_BG)); view.setClickable(true);
        return view;
    }
    private LinearLayout header(String title) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, true), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private SpannableStringBuilder styledRuns(JSONArray runs) {
        SpannableStringBuilder styled = new SpannableStringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            int start = styled.length();
            styled.append(run.optString("text"));
            if (styled.length() == start) continue;
            styled.setSpan(new ForegroundColorSpan(run.optInt("color", Palette.TEXT)),
                start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    private TextView label(String text, boolean header) {
        TextView view = new TextView(activity); view.setText(text);
        view.setTextColor(header ? Palette.HEADER_TEXT : Palette.TEXT);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE, header ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(dp(6), dp(6), dp(6), dp(6)); return view;
    }
    private TextView action(String text, Runnable click) {
        TextView view = label(text, false); view.setGravity(Gravity.CENTER); view.setMinHeight(dp(44));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE_GLOW), background(Palette.ACTION_BG), null));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(6);
        view.setLayoutParams(p); view.setOnClickListener(v -> { if (!busy) click.run(); }); return view;
    }
    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color);
        bg.setCornerRadius(dp(UiStyle.PANEL_CORNER_RADIUS_DP)); bg.setStroke(dp(1), Palette.BORDER_DIM); return bg;
    }
    private ScrollView scroll() {
        ScrollView view = new ScrollView(activity); view.setOverScrollMode(View.OVER_SCROLL_NEVER); return view;
    }
    private int width() {
        int size = host.getWidth() > 0 ? host.getWidth() : activity.getWindow().getDecorView().getWidth();
        return Math.max(dp(240), size - host.getPaddingLeft() - host.getPaddingRight());
    }
    private int height() {
        int size = host.getHeight() > 0 ? host.getHeight() : activity.getWindow().getDecorView().getHeight();
        return Math.max(dp(200), size - host.getPaddingTop() - host.getPaddingBottom());
    }
    private int dp(float value) { return activity.dpToPx(value); }
}
