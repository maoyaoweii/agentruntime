package com.agentruntime.ui;

import android.app.Activity;

import java.lang.ref.WeakReference;

/**
 * 谁占着前台。
 *
 * <p>聊天气泡是最容易踩的坑：用户一边看回复一边让 Agent 操作微信/B站，
 * 这时 {@code getRootInActiveWindow()} 返回的是 DSH 自己的窗口——
 * 于是"热门"能在自己的聊天气泡里被找到，点下去却不可交互，
 * 表现就是"无障碍明明开着，却什么也读不到、点不动"。
 *
 * <p>所以跨应用动作开始前，聊天界面要主动退到后台，把前台让给真正被操作的应用。
 */
public final class AppWindow {
    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    private AppWindow() { }

    public static void track(Activity activity) {
        resumed = new WeakReference<>(activity);
    }

    public static void untrack(Activity activity) {
        Activity current = resumed.get();
        if (current == activity) resumed = new WeakReference<>(null);
    }

    /** 把当前前台界面让出去，并等窗口切换完成。只在工具线程调用。 */
    public static void yieldDevice(long settleMs) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            try { activity.moveTaskToBack(true); } catch (Exception ignored) { }
        });
        if (settleMs > 0) {
            try { Thread.sleep(settleMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
