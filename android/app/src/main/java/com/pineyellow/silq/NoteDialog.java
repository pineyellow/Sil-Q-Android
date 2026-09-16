package com.pineyellow.silq;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scrollable notes and descriptions without opening a terminal modal. */
final class NoteDialog {
    private final SilActivity activity;
    private Dialog dialog;
    private boolean resumed;
    private int request;

    NoteDialog(SilActivity activity) { this.activity = activity; }
    void resume() { resumed = true; }
    void pause() { resumed = false; dismiss(); }

    void show(String text, int id) {
        // Legacy notes use space padding to force a paragraph in a narrow terminal.
        show("NOTE", text.replaceAll("[ \\t]{3,}", "\n\n")
            .replaceAll("(?m)^[ \\t]+", ""), id);
    }

    void show(String heading, CharSequence text, int id) {
        show(heading, text, id, false);
    }

    void show(String heading, CharSequence text, int id, boolean canExchange) {
        show(heading, text, id, canExchange, "");
    }

    void show(String heading, CharSequence text, int id, boolean canExchange, CharSequence stats) {
        if (!resumed || activity.isFinishing()) {
            activity.nativeConfirmationResult(id, false);
            return;
        }
        dismiss();
        request = id;
        activity.setOverlayInputBlocked(true);
        LinearLayout panel = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                View content = activity.findViewById(android.R.id.content);
                int available = content.getHeight() - content.getPaddingTop()
                    - content.getPaddingBottom() - activity.dpToPx(36);
                int limit = Math.max(1, Math.min(activity.dpToPx(420), available));
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(18);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Palette.PANEL_BG);
        background.setCornerRadius(activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP));
        background.setStroke(activity.dpToPx(1), Palette.BORDER_DIM);
        panel.setBackground(background);
        TextView title = new TextView(activity);
        title.setText(heading);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Palette.HEADER_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(0, 0, 0, activity.dpToPx(12));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextColor(Palette.TEXT);
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(activity.dpToPx(3), 1f);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout contentColumn = new LinearLayout(activity);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        if (stats.length() > 0) {
            EnemyStatFlow flow = new EnemyStatFlow(activity, stats);
            contentColumn.addView(flow, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(activity);
            divider.setBackgroundColor(Palette.BORDER_DIM);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, activity.dpToPx(1));
            line.setMargins(0, activity.dpToPx(8), 0, activity.dpToPx(8));
            contentColumn.addView(divider, line);
        }
        contentColumn.addView(body, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(contentColumn);
        // Wrap short notes; the weight lets only the body shrink and scroll
        // when the panel reaches its height limit, keeping the heading visible.
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        if (canExchange) {
            TextView exchange = new TextView(activity);
            exchange.setText("Exchange Places");
            exchange.setTextColor(Palette.TEXT);
            exchange.setTextSize(14);
            exchange.setTypeface(Typeface.MONOSPACE);
            exchange.setGravity(Gravity.CENTER);
            GradientDrawable button = new GradientDrawable();
            button.setColor(Palette.ACTION_BG);
            button.setCornerRadius(activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP));
            button.setStroke(activity.dpToPx(1), Palette.BORDER_DIM);
            exchange.setBackground(button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, activity.dpToPx(48));
            params.topMargin = activity.dpToPx(12);
            panel.addView(exchange, params);
            exchange.setOnClickListener(v -> {
                if (request == id) dismiss(true);
            });
        }
        Dialog current = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog = current;
        current.setContentView(panel);
        current.setCancelable(true);
        current.setCanceledOnTouchOutside(true);
        current.setOnDismissListener(d -> { if (dialog == current) dismiss(); });
        Window window = current.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.CENTER);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
            android.view.View decor = activity.getWindow().getDecorView();
            window.setLayout(Math.max(1, Math.round(Math.min(activity.dpToPx(600), decor.getWidth() - pad * 2) * 0.75f)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        }
        try { activity.showImmersiveDialog(current); }
        catch (RuntimeException error) { dismiss(); }
    }

    private void dismiss() {
        dismiss(false);
    }

    private void dismiss(boolean accepted) {
        Dialog previous = dialog;
        dialog = null;
        int id = request;
        request = 0;
        if (previous != null) previous.dismiss();
        if (id != 0) {
            activity.setOverlayInputBlocked(false);
            activity.nativeConfirmationResult(id, accepted);
        }
    }
}
