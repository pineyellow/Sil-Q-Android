package com.pineyellow.silq;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** Floating controls: fills fade fully, icons strongly, and borders gently. */
final class ButtonOpacity {
    static final String PREF = "button_opacity";
    static final float DEFAULT = 0.7f;

    private ButtonOpacity() {}

    static float read(Context context) {
        return Math.max(0f, Math.min(1f, GameSettings.getFloat(context, PREF, DEFAULT)));
    }

    static int fillColor(int color, float opacity) {
        return Color.argb(Math.round(255 * opacity),
            Color.red(color), Color.green(color), Color.blue(color));
    }

    static void apply(View button, int color, float opacity) {
        apply(button, color, opacity, Palette.BUTTON_BORDER,
            Math.round(button.getResources().getDisplayMetrics().density));
    }

    static int borderColor(int color, float opacity) {
        return fillColor(color, Color.alpha(color) / 255f * contentOpacity(opacity, 0.5f));
    }

    private static float contentOpacity(float opacity, float minimum) {
        // Restore the original appearance at the default; cap at original opacity.
        float normalized = Math.max(0f, Math.min(1f, opacity / DEFAULT));
        return minimum + (1f - minimum) * normalized;
    }

    static void apply(View button, int color, float opacity, int border, int width) {
        if (button.getBackground() instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) button.getBackground();
            if (ripple.getNumberOfLayers() > 0
                    && ripple.getDrawable(0) instanceof GradientDrawable) {
                GradientDrawable background = (GradientDrawable) ripple.getDrawable(0);
                background.setColor(fillColor(color, opacity));
                background.setStroke(width, borderColor(border, opacity));
            }
        }
        applyContents(button, contentOpacity(opacity, 0.2f));
    }

    private static void applyContents(View view, float opacity) {
        if (view instanceof ImageView) {
            ((ImageView) view).setImageAlpha(Math.round(255 * opacity));
        } else if (view instanceof TextView) {
            // Quiver count has no background or border of its own.
            view.setAlpha(opacity);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyContents(group.getChildAt(i), opacity);
            }
        }
    }
}
