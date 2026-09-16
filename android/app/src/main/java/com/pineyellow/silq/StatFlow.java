package com.pineyellow.silq;

import android.view.View;

/** Keep each stat together, filling a row before starting the next one. */
class StatFlow extends android.view.ViewGroup {
    private final int horizontalGap, verticalGap;
    StatFlow(SilActivity activity, int horizontalDp, int verticalDp) {
        super(activity);
        horizontalGap = activity.dpToPx(horizontalDp);
        verticalGap = activity.dpToPx(verticalDp);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
            ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec);
        int x = 0, y = 0, rowHeight = 0, usedWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + horizontalGap + w > width) {
                y += rowHeight + verticalGap;
                x = 0;
                rowHeight = 0;
            }
            if (x > 0) x += horizontalGap;
            x += w;
            rowHeight = Math.max(rowHeight, h);
            usedWidth = Math.max(usedWidth, x);
        }
        setMeasuredDimension(resolveSize(usedWidth, widthSpec),
            resolveSize(y + rowHeight, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + horizontalGap + w > r - l) {
                y += rowHeight + verticalGap;
                x = 0;
                rowHeight = 0;
            }
            if (x > 0) x += horizontalGap;
            child.layout(x, y, x + w, y + h);
            x += w;
            rowHeight = Math.max(rowHeight, h);
        }
    }
}

