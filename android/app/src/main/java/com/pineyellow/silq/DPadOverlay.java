package com.pineyellow.silq;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Transparent 3x3 Android D-pad for Sil-Q direction commands.
 *  Shown at the bottom-left during gameplay
 *  when enabled from Settings. Each cell consumes its own touches and leaves
 *  the rest of the screen's touch handling alone. */
final class DPadOverlay {

    static final String PREF_ENABLED = "enable_dpad";
    static final String PREF_OFFSET_X = "dpad_offset_x";
    static final String PREF_OFFSET_Y = "dpad_offset_y";
    static final String PREF_SIZE = "dpad_size";
    static final String PREF_BUTTON_WIDTH = "dpad_button_width";

    static final float DEFAULT_SIZE = 1f;
    static final float DEFAULT_BUTTON_WIDTH = 1f;
    static final float MIN_SIZE = 0.5f;
    static final float MAX_SIZE = 2.0f;
    static final float MIN_BUTTON_WIDTH = 0.5f;
    static final float MAX_BUTTON_WIDTH = 2.0f;
    static final int SIZE_DP = 185;
    static final int MARGIN_DP = 12;
    static final float DEAD_ZONE_BASE_DP = 12.5f;
    static final float DEAD_ZONE_MIN_DP = 7.5f;
    static final float DEAD_ZONE_MAX_DP = 20f;
    private static final long HOLD_REPEAT_DELAY_MS = 300L;
    private static final long HOLD_REPEAT_INTERVAL_MS = 70L;

    // Subtle, translucent strokes keep the dungeon visible under the controls.
    private static final int GRID_LINE_COLOR = Color.argb(40, 255, 255, 255);
    private static final int GLYPH_COLOR = Color.argb(90, 255, 255, 255);

    private final SilActivity activity;
    private final Runnable onInteraction;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private final DrawLastLinearLayout root;
    private final ScaledIconView[][] cells = new ScaledIconView[3][3];
    private Runnable repeatRunnable;
    private View activeView;
    private char activeCommand;
    private boolean repeatArmed;
    private int pressToken;
    private boolean reportingTouchClick;
    private ScaledIconView visualOwner;
    private DrawLastLinearLayout visualOwnerRow;
    private int visualOwnershipGeneration;

    DPadOverlay(SilActivity activity, Runnable onInteraction) {
        this.activity = activity;
        this.onInteraction = onInteraction;
        this.root = build();
    }

    View getView() {
        return root;
    }

    private DrawLastLinearLayout build() {
        DrawLastLinearLayout grid = new DrawLastLinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // Child cells handle DPAD presses. The clickable padded area around
        // them consumes near-misses so they cannot become dungeon-map taps.
        grid.setClickable(true);
        grid.setOnClickListener(v -> { });
        grid.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                onInteraction.run();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        });

        addRow(grid, 0,
            new Cell(-135f, "Move up-left", '7'),
            new Cell(-90f, "Move up", '8'),
            new Cell(-45f, "Move up-right", '9'));
        addRow(grid, 1,
            new Cell(180f, "Move left", '4'),
            new Cell("Wait one turn", '5'),
            new Cell(0f, "Move right", '6'));
        addRow(grid, 2,
            new Cell(135f, "Move down-left", '1'),
            new Cell(90f, "Move down", '2'),
            new Cell(45f, "Move down-right", '3'));

        return grid;
    }

    private void addRow(DrawLastLinearLayout parent, int rowIndex,
                        Cell left, Cell center, Cell right) {
        DrawLastLinearLayout row = new DrawLastLinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        parent.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addCell(row, rowIndex, 0, left);
        addCell(row, rowIndex, 1, center);
        addCell(row, rowIndex, 2, right);
    }

    // handleTouch calls performClick on release; lint cannot follow the helper.
    @SuppressLint("ClickableViewAccessibility")
    private void addCell(DrawLastLinearLayout row, int rowIndex,
                         int colIndex, Cell cell) {
        CellBorderDrawable border = new CellBorderDrawable(
            rowIndex, colIndex,
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        ScaledIconView view = new ScaledIconView(activity, cell.rotationDegrees,
            activity.dpToPx(SIZE_DP / 3f), rowIndex, colIndex, border);
        view.setImageResource(cell.center
            ? R.drawable.ic_dpad_center
            : R.drawable.ic_dpad_arrow);
        // Keep tint opaque and apply transparency to the image itself. The
        // default SRC_ATOP color filter preserves the drawable's alpha.
        view.setColorFilter(Color.rgb(Color.red(GLYPH_COLOR),
            Color.green(GLYPH_COLOR), Color.blue(GLYPH_COLOR)));
        view.setImageAlpha(Color.alpha(GLYPH_COLOR));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(cell.contentDescription);
        view.setBackground(border);
        view.setOnClickListener(v -> {
            if (!reportingTouchClick) {
                onInteraction.run();
                int token = activity.nativeBeginDpadPress();
                activity.nativeSendDpadKey(cell.command, token, false);
                activity.nativeCancelDpadInput();
            }
        });
        view.setOnTouchListener((v, event) -> handleTouch(v, event, cell.command));
        cells[rowIndex][colIndex] = view;

        row.addView(view, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private boolean handleTouch(View view, MotionEvent event, char command) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onInteraction.run();
                startHold(view, command);
                return true;
            case MotionEvent.ACTION_UP:
                reportingTouchClick = true;
                view.performClick();
                reportingTouchClick = false;
                // fall through
            case MotionEvent.ACTION_CANCEL:
                stopHold(view);
                return true;
            default:
                return true;
        }
    }

    private void startHold(View view, char command) {
        cancelRepeat(true);
        activeView = view;
        activeCommand = command;
        repeatArmed = true;
        pressToken = activity.nativeBeginDpadPress();
        activity.nativeSendDpadKey(command, pressToken, false);
        pressVisual(view);

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!repeatArmed || activeView == null) return;
                if (!activity.nativeSendDpadKey(activeCommand, pressToken, true)) {
                    cancelRepeat(true);
                    return;
                }
                repeatHandler.postDelayed(this, HOLD_REPEAT_INTERVAL_MS);
            }
        };
        repeatHandler.postDelayed(repeatRunnable, HOLD_REPEAT_DELAY_MS);
    }

    private void stopHold(View view) {
        if (view == activeView) {
            cancelRepeat(true);
        } else {
            releaseVisual(view);
        }
    }

    private void cancelRepeat(boolean releaseActiveVisual) {
        repeatArmed = false;
        if (repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
        if (releaseActiveVisual && activeView != null) {
            releaseVisual(activeView);
        }
        activity.nativeCancelDpadInput();
        activeView = null;
    }

    void cancelInput() {
        cancelRepeat(true);
    }

    void setDeadZoneSize(int insetPx) {
        int inset = Math.max(0, insetPx);
        if (root.getPaddingLeft() == inset && root.getPaddingTop() == inset
                && root.getPaddingRight() == inset && root.getPaddingBottom() == inset) return;
        root.setPadding(inset, inset, inset, inset);
    }

    static float deadZoneDp(float sizeScale) {
        return Math.max(DEAD_ZONE_MIN_DP,
            Math.min(DEAD_ZONE_MAX_DP, DEAD_ZONE_BASE_DP * sizeScale));
    }

    private void pressVisual(View view) {
        view.animate().cancel();
        if (view instanceof ScaledIconView) {
            claimVisualOwnership((ScaledIconView) view);
        }
        view.animate().alpha(0.65f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(50).start();
    }

    private void releaseVisual(View view) {
        view.animate().cancel();
        final ScaledIconView cell = view instanceof ScaledIconView
            ? (ScaledIconView) view : null;
        final int ownershipGeneration = cell == null
            ? -1 : cell.visualOwnershipGeneration;
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(90)
            .withEndAction(() -> releaseVisualOwnership(cell, ownershipGeneration))
            .start();
    }

    private void claimVisualOwnership(ScaledIconView cell) {
        visualOwnershipGeneration++;
        resetVisualOwnership();

        visualOwner = cell;
        cell.visualOwnershipGeneration = visualOwnershipGeneration;
        visualOwnerRow = (DrawLastLinearLayout) cell.getParent();
        visualOwnerRow.setDrawLast(cell);
        root.setDrawLast(visualOwnerRow);

        cell.border.claimAllEdges();
        if (cell.rowIndex > 0) {
            cells[cell.rowIndex - 1][cell.colIndex].border.yieldBottomEdge();
        }
        if (cell.colIndex > 0) {
            cells[cell.rowIndex][cell.colIndex - 1].border.yieldRightEdge();
        }
    }

    private void releaseVisualOwnership(ScaledIconView cell, int ownershipGeneration) {
        if (cell != visualOwner || ownershipGeneration != visualOwnershipGeneration) return;
        resetVisualOwnership();
    }

    private void resetVisualOwnership() {
        if (visualOwnerRow != null) {
            visualOwnerRow.setDrawLast(null);
        }
        root.setDrawLast(null);
        for (ScaledIconView[] row : cells) {
            for (ScaledIconView cell : row) {
                if (cell != null) cell.border.resetEdgeOwnership();
            }
        }
        visualOwner = null;
        visualOwnerRow = null;
    }

    private static final class Cell {
        final boolean center;
        final float rotationDegrees;
        final String contentDescription;
        final char command;

        Cell(float rotationDegrees, String contentDescription, char command) {
            this.center = false;
            this.rotationDegrees = rotationDegrees;
            this.contentDescription = contentDescription;
            this.command = command;
        }

        Cell(String contentDescription, char command) {
            this.center = true;
            this.rotationDegrees = 0f;
            this.contentDescription = contentDescription;
            this.command = command;
        }
    }

    /** Draws one selected child last without changing LinearLayout child order. */
    private static final class DrawLastLinearLayout extends LinearLayout {
        private View drawLast;

        DrawLastLinearLayout(SilActivity activity) {
            super(activity);
            setChildrenDrawingOrderEnabled(true);
        }

        void setDrawLast(View child) {
            if (drawLast == child) return;
            drawLast = child;
            invalidate();
        }

        @Override
        protected int getChildDrawingOrder(int childCount, int drawingPosition) {
            int lastIndex = drawLast == null ? -1 : indexOfChild(drawLast);
            if (lastIndex < 0 || lastIndex == childCount - 1) return drawingPosition;
            if (drawingPosition == childCount - 1) return lastIndex;
            return drawingPosition >= lastIndex ? drawingPosition + 1 : drawingPosition;
        }
    }

    /** Scales and rotates only the icon, leaving its cell border untouched. */
    private static final class ScaledIconView extends ImageView {
        private final float rotationDegrees;
        private final float defaultCellSizePx;
        private final int rowIndex;
        private final int colIndex;
        private final CellBorderDrawable border;
        private int visualOwnershipGeneration;

        ScaledIconView(SilActivity activity, float rotationDegrees,
                       float defaultCellSizePx, int rowIndex, int colIndex,
                       CellBorderDrawable border) {
            super(activity);
            this.rotationDegrees = rotationDegrees;
            this.defaultCellSizePx = defaultCellSizePx;
            this.rowIndex = rowIndex;
            this.colIndex = colIndex;
            this.border = border;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int saveCount = canvas.save();
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float iconScale = defaultCellSizePx > 0f
                ? Math.min(getWidth(), getHeight()) / defaultCellSizePx
                : 1f;
            canvas.scale(iconScale, iconScale, centerX, centerY);
            canvas.rotate(rotationDegrees, centerX, centerY);
            super.onDraw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    /** Draws only the grid outline while keeping the fill transparent. */
    private static final class CellBorderDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path border = new Path();
        private final RectF arcBounds = new RectF();
        private final boolean defaultDrawTop;
        private final boolean defaultDrawLeft;
        private final boolean roundTopLeft;
        private final boolean roundTopRight;
        private final boolean roundBottomLeft;
        private final boolean roundBottomRight;
        private final float cornerRadius;
        private boolean drawTop;
        private boolean drawRight;
        private boolean drawBottom;
        private boolean drawLeft;

        CellBorderDrawable(int rowIndex, int colIndex, float cornerRadius) {
            this.defaultDrawTop = rowIndex == 0;
            this.defaultDrawLeft = colIndex == 0;
            this.roundTopLeft = rowIndex == 0 && colIndex == 0;
            this.roundTopRight = rowIndex == 0 && colIndex == 2;
            this.roundBottomLeft = rowIndex == 2 && colIndex == 0;
            this.roundBottomRight = rowIndex == 2 && colIndex == 2;
            this.cornerRadius = cornerRadius;
            paint.setColor(GRID_LINE_COLOR);
            paint.setStrokeWidth(1f);
            paint.setStyle(Paint.Style.STROKE);
            resetEdgeOwnership();
        }

        void resetEdgeOwnership() {
            setEdges(defaultDrawTop, true, true, defaultDrawLeft);
        }

        void claimAllEdges() {
            setEdges(true, true, true, true);
        }

        void yieldRightEdge() {
            setEdges(drawTop, false, drawBottom, drawLeft);
        }

        void yieldBottomEdge() {
            setEdges(drawTop, drawRight, false, drawLeft);
        }

        private void setEdges(boolean top, boolean right, boolean bottom, boolean left) {
            if (drawTop == top && drawRight == right
                    && drawBottom == bottom && drawLeft == left) {
                return;
            }
            drawTop = top;
            drawRight = right;
            drawBottom = bottom;
            drawLeft = left;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            float halfStroke = paint.getStrokeWidth() / 2f;
            float boundsLeft = getBounds().left;
            float boundsTop = getBounds().top;
            float boundsRight = getBounds().right;
            float boundsBottom = getBounds().bottom;
            float left = boundsLeft + halfStroke;
            float top = boundsTop + halfStroke;
            float right = boundsRight - halfStroke;
            float bottom = boundsBottom - halfStroke;
            float radius = Math.min(cornerRadius,
                Math.min((right - left) / 2f, (bottom - top) / 2f));
            radius = Math.max(0f, radius);
            float diameter = radius * 2f;
            border.reset();

            if (drawTop) {
                if (roundTopLeft) {
                    border.moveTo(left, top + radius);
                    arcBounds.set(left, top,
                        left + diameter, top + diameter);
                    border.arcTo(arcBounds, 180f, 90f, false);
                } else {
                    border.moveTo(drawLeft ? left : boundsLeft, top);
                }

                border.lineTo(roundTopRight ? right - radius : right, top);
                if (roundTopRight) {
                    arcBounds.set(right - diameter, top,
                        right, top + diameter);
                    border.arcTo(arcBounds, 270f, 90f, false);
                }
            }

            if (drawRight) {
                if (!drawTop) {
                    border.moveTo(right, boundsTop);
                }
                border.lineTo(right,
                    roundBottomRight ? bottom - radius : bottom);
                if (roundBottomRight) {
                    arcBounds.set(right - diameter, bottom - diameter,
                        right, bottom);
                    border.arcTo(arcBounds, 0f, 90f, false);
                }
            }

            if (drawBottom) {
                if (!drawRight) {
                    border.moveTo(right, bottom);
                }
                border.lineTo(
                    roundBottomLeft ? left + radius : (drawLeft ? left : boundsLeft),
                    bottom);
                if (roundBottomLeft) {
                    arcBounds.set(left, bottom - diameter,
                        left + diameter, bottom);
                    border.arcTo(arcBounds, 90f, 90f, false);
                }
            }

            if (drawLeft) {
                if (!drawBottom) {
                    border.moveTo(left, bottom);
                }
                border.lineTo(left,
                    roundTopLeft ? top + radius : (drawTop ? top : boundsTop));
                if (drawTop && drawRight && drawBottom) border.close();
            }

            canvas.drawPath(border, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
