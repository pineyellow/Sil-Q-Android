package com.pineyellow.silq;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable engine choices; clicking returns an identifier, never game logic. */
final class SongDialog {
    private final SilActivity activity;
    private Dialog dialog;
    private int request;
    private boolean resumed;

    SongDialog(SilActivity activity) { this.activity = activity; }
    void resume() { resumed = true; }
    void pause() { resumed = false; finish(-1); }
    private int dp(int value) { return activity.dpToPx(value); }

    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(UiStyle.PANEL_CORNER_RADIUS_DP));
        bg.setStroke(dp(1), Palette.BORDER_DIM);
        return bg;
    }

    private TextView text(String value, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(14);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    void show(String json, int id) {
        if (!resumed || activity.isFinishing()) {
            activity.nativeSongReply(id, -1);
            return;
        }
        finish(-1);
        request = id;
        activity.setOverlayInputBlocked(true);
        try {
            JSONObject data = new JSONObject(json);
            JSONArray songs = data.getJSONArray("songs");
            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(18), dp(18), dp(18), dp(18));
            panel.setBackground(background(Palette.PANEL_BG));
            TextView heading = text("SING", Palette.HEADER_TEXT, true);
            heading.setGravity(Gravity.CENTER);
            heading.setPadding(0, 0, 0, dp(12));
            panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            if (songs.length() == 0)
                rows.addView(text("You do not know any songs of power.", Palette.TEXT, false));
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                int choice = song.getInt("id");
                boolean current = song.optBoolean("current");
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(12), dp(12), dp(12));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.BUTTON_RIPPLE),
                    background(current ? Palette.EQUIPPED_BG : Palette.ITEM_BG), null));
                row.addView(text(song.getString("name") + (current ? " (singing)" : ""),
                    current ? Palette.HEADER_TEXT : Palette.TEXT, true));
                String description = song.optString("description");
                if (!description.isEmpty()) {
                    TextView body = text(description, android.graphics.Color.rgb(170, 174, 178), false);
                    body.setTextSize(12);
                    body.setPadding(0, dp(6), 0, 0);
                    row.addView(body);
                }
                row.setOnClickListener(v -> { if (request == id) finish(choice); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                rows.addView(row, lp);
            }
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(rows);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            if (data.has("stopChoice")) {
                int stopChoice = data.getInt("stopChoice");
                TextView stop = text("Stop Singing", Palette.TEXT, true);
                stop.setGravity(Gravity.CENTER);
                stop.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.BUTTON_RIPPLE),
                    background(Palette.ACTION_BG), null));
                stop.setOnClickListener(v -> { if (request == id) finish(stopChoice); });
                LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, dp(48));
                footer.topMargin = dp(8);
                panel.addView(stop, footer);
            }
            Dialog current = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            dialog = current;
            current.setContentView(panel);
            current.setCancelable(true);
            current.setCanceledOnTouchOutside(true);
            current.setOnDismissListener(d -> { if (dialog == current) finish(-1); });
            Window window = current.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setGravity(Gravity.CENTER);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(.45f);
                android.view.View decor = activity.getWindow().getDecorView();
                window.setLayout(Math.max(1, Math.min(dp(450), decor.getWidth() - dp(36))),
                    Math.max(1, Math.min(dp(420), decor.getHeight() - dp(36))));
            }
            activity.showImmersiveDialog(current);
        } catch (Exception error) { finish(-1); }
    }

    private void finish(int choice) {
        int id = request;
        request = 0;
        Dialog previous = dialog;
        dialog = null;
        if (previous != null) previous.dismiss();
        if (id != 0) {
            activity.setOverlayInputBlocked(false);
            activity.nativeSongReply(id, choice);
        }
    }
}
