package com.pineyellow.silq;

import android.graphics.Typeface;


import android.widget.TextView;

/** Enemy fields use the same natural-width wrapping as item stats. */
final class EnemyStatFlow extends StatFlow {


    EnemyStatFlow(SilActivity activity, CharSequence stats) {
        super(activity, 12, 0);


        int start = 0;
        for (int end = 0; end <= stats.length(); end++) {
            if (end != stats.length() && stats.charAt(end) != '\n') continue;
            if (end > start) {
                TextView field = new TextView(activity);
                field.setText(stats.subSequence(start, end));
                field.setTextColor(Palette.TEXT);
                field.setTextSize(12);
                field.setTypeface(Typeface.MONOSPACE);
                field.setPadding(0, activity.dpToPx(3), 0, activity.dpToPx(3));
                addView(field, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            }
            start = end + 1;
        }
    }

}
