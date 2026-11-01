package com.agentruntime.vision;

import android.content.Context;

/** Screenshot-based reading is a separate, explicit user choice. */
public final class ScreenOcrSettings {
    private static final String PREFS = "dsh_screen_ocr";
    private static final String ENABLED = "enabled";
    private static final String REVISION = "revision";

    private ScreenOcrSettings() {}

    /**
     * 默认开启：微信这类自绘界面经常只返回两三个节点、零文字，
     * 每次都要用户先去设置里打开等于把活退回给用户。关掉仍然有效。
     */
    public static boolean enabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, true);
    }

    public static long revision(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(REVISION, 0);
    }

    public static synchronized boolean setEnabled(Context context, boolean enabled) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, enabled).putLong(REVISION, revision(context) + 1).commit();
    }
}
