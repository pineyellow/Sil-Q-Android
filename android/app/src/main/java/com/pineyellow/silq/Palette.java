package com.pineyellow.silq;

import android.graphics.Color;

/** Sil-Q panel colors, with the floating control buttons styled separately. */
final class Palette {

    private Palette() {}

    static final int ACTION_BUTTON_BG = Color.argb(170, 29, 31, 33);
    static final int ACTION_BUTTON_TEXT = Color.argb(210, 216, 218, 220);
    static final int ACTION_BUTTON_TEXT_ACTIVE = Color.rgb(117, 194, 190);
    static final int BUTTON_BORDER = Color.argb(160, 105, 113, 117);
    static final int BUTTON_BORDER_ACTIVE = Color.argb(220, 117, 194, 190);
    static final int BUTTON_RIPPLE = Color.argb(60, 117, 194, 190);

    static final int HEADER_TEXT = Color.rgb(117, 194, 190);
    static final int TEXT = Color.rgb(216, 218, 220);
    static final int PANEL_BG = Color.rgb(29, 31, 33);
    static final int ITEM_BG = Color.rgb(36, 38, 40);
    static final int EQUIPPED_BG = Color.rgb(46, 57, 58);
    static final int EQUIPPED_BORDER = Color.rgb(83, 121, 119);
    static final int EQUIPPED_TEXT = Color.rgb(240, 243, 243);
    static final int ACTION_BG = Color.rgb(47, 50, 53);
    static final int RIPPLE_GLOW = Color.argb(28, 216, 218, 220);
    static final int BORDER_DIM = Color.rgb(57, 61, 64);
    static final int BORDER_ACTIVE = Color.rgb(105, 113, 117);
    /** Toggle-active background used by animateToggle's "active" state. */
    static final int TOGGLE_ACTIVE  = Color.argb(200, 46, 66, 65);
}
