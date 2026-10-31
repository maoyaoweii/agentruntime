package com.agentruntime.vision;

import android.content.Context;

/** Screenshot-based reading is a separate, explicit user choice. */
public final class ScreenOcrSettings {
    private static final String PREFS = "dsh_screen_ocr";
    private static final String ENABLED = "enabled";
    private static final String REVISION = "revision";

    private ScreenOcrSettings() {}

    public static boolean enabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false);
    }

    public static long revision(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(REVISION, 0);
    }

    public static synchronized boolean setEnabled(Context context, boolean enabled) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, enabled).putLong(REVISION, revision(context) + 1).commit();
    }
}
