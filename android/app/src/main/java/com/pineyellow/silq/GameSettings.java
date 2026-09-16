package com.pineyellow.silq;

import android.content.Context;

/** Java control preferences, stored independently from the engine's save files. */
final class GameSettings {
    private static final String PREFS = "silq_controls";
    static final String PREF_TILES = "graphics_tiles";
    private GameSettings() {}

    static boolean getBool(Context context, String key, boolean fallback) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, fallback);
    }

    static void setBool(Context context, String key, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply();
    }

    static float getFloat(Context context, String key, float fallback) {
        Object saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getAll().get(key);
        float value = saved instanceof Number ? ((Number) saved).floatValue() : fallback;
        return Float.isFinite(value) ? value : fallback;
    }

    static void setFloat(Context context, String key, float value) {
        if (Float.isFinite(value)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(key, value).apply();
        }
    }
}
