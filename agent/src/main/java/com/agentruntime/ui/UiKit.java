package com.agentruntime.ui;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.agentruntime.agent.R;

/**
 * 界面小工具：dp 换算、状态栏内边距、状态徽章、权限入口。
 *
 * <p>集中在这么个小类里，是因为这些逻辑在 4 个界面里都要用；
 * 复制粘贴四遍之后，改一个圆角就会漏掉三处。
 */
public final class UiKit {

    /** 徽章语义。 */
    public enum Badge { OK, WARN, DANGER, NEUTRAL }

    private UiKit() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context c, String msg) {
        if (c != null) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    /** 给自定义顶栏补上状态栏高度，避免标题被状态栏压住。 */
    public static void applyStatusBarPadding(View topBar) {
        if (topBar == null) return;
        int base = topBar.getPaddingTop();
        topBar.setPadding(topBar.getPaddingLeft(), base + statusBarHeight(topBar.getContext()),
                topBar.getPaddingRight(), topBar.getPaddingBottom());
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    /** 设置状态徽章的样式与文案。 */
    public static void setBadge(TextView tv, String text, Badge kind) {
        if (tv == null) return;
        tv.setText(text);
        int bg;
        int fg;
        switch (kind) {
            case OK:
                bg = R.drawable.bg_badge_ok;
                fg = R.color.ok;
                break;
            case WARN:
                bg = R.drawable.bg_badge_warn;
                fg = R.color.warn;
                break;
            case DANGER:
                bg = R.drawable.bg_badge_danger;
                fg = R.color.danger;
                break;
            default:
                bg = R.drawable.bg_badge_neutral;
                fg = R.color.text_secondary;
                break;
        }
        tv.setBackgroundResource(bg);
        tv.setTextColor(tv.getContext().getColor(fg));
    }

    // ------------------------------------------------------------- 权限

    public static boolean hasOverlayPermission(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void openOverlayPermission(Activity a, int requestCode) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + a.getPackageName()));
        try {
            a.startActivityForResult(i, requestCode);
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception fallbackError) {
                toast(a, "无法打开悬浮窗权限设置");
            }
        }
    }

    public static void openAccessibilitySettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast(c, "无法打开无障碍设置");
        }
    }

    public static boolean hasNotificationPermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) {
            return systemNotificationsEnabled(c);
        }
        return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean systemNotificationsEnabled(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void requestNotificationPermission(Activity a, int requestCode) {
        if (Build.VERSION.SDK_INT >= 33) {
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, requestCode);
        } else {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            try {
                a.startActivity(i);
            } catch (Exception e) {
                toast(a, "无法打开通知设置");
            }
        }
    }

    // ------------------------------------------------------------- 布局

    /** 往容器里加一条 1dp 分隔线。 */
    public static void addDivider(ViewGroup parent) {
        View v = new View(parent.getContext());
        v.setBackgroundResource(R.drawable.bg_divider);
        parent.addView(v, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(parent.getContext(), 1)));
    }
}
