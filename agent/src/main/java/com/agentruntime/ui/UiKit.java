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

    // ------------------------------------------------------- 运行时权限（动态）

    /**
     * 本机需要动态申请的全部权限，按"用户理解顺序"排列。
     *
     * <p>顺序不是随便排的：通知放最前（用户最容易理解），随后才是存储。
     * 存储只在 API ≤ 28 真正弹窗（29 用 legacy 标志兜底、30+ 分区存储下这两个权限
     * 被系统忽略、33+ 换 READ_MEDIA_*），所以低版本机型和 iOS 式的"一串弹窗"体验
     * 都靠这份清单 + 逐条过滤来保证。
     */
    public static String[] runtimePermissions(Context c) {
        java.util.List<String> want = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            want.add("android.permission.POST_NOTIFICATIONS");
            want.add("android.permission.READ_MEDIA_IMAGES");
            want.add("android.permission.READ_MEDIA_VIDEO");
            want.add("android.permission.READ_MEDIA_AUDIO");
        } else {
            want.add("android.permission.POST_NOTIFICATIONS");
            if (Build.VERSION.SDK_INT >= 30) {
                // 30/31/32 分区存储强制执行：共享目录只能靠 MANAGE_EXTERNAL_STORAGE 访问，
                // 而 READ_EXTERNAL_STORAGE 已被系统忽略。这里只申请真正读得到的 app 专属
                // 媒体目录（getExternalFilesDir），它对共享 /sdcard 的读取其实不起作用——
                // 申请它是因为部分 ROM 会用它来放行 app 专属外部目录的读取权限。
                // 换句话说：申请的是"能读的"，不是"看起来全能的"。
                want.add("android.permission.READ_EXTERNAL_STORAGE");
            } else {
                want.add("android.permission.READ_EXTERNAL_STORAGE");
                want.add("android.permission.WRITE_EXTERNAL_STORAGE");
            }
        }
        // 只申请清单里真的声明了的。requestPermissions 传未声明权限会被系统直接判为拒绝；
        // 而 maxSdkVersion 只影响安装时的授予，不改变"已声明"这个事实，
        // 所以这里用声明表判断而不是运行期是否可授予。
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : want) {
            if (isDeclared(c, p)) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    /** 仍未被授予的运行时权限。用于按钮显示"还差几项"。 */
    public static String[] ungrantedRuntimePermissions(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : runtimePermissions(c)) {
            if (!isGranted(c, p)) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    public static String[] grantedRuntimePermissions(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : runtimePermissions(c)) {
            if (isGranted(c, p)) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    public static boolean isGranted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isDeclared(Context c, String permission) {
        try {
            android.content.pm.PackageInfo info = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] declared = info.requestedPermissions;
            if (declared == null) return false;
            for (String p : declared) {
                if (permission.equals(p)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 一次性把所有未授予的运行时权限丢给系统。
     *
     * <p>系统会按自己的节奏逐个弹窗（用户点一次同意才会弹下一个），
     * 这正是需求要的"用户本人一个个点同意"；这里不做任何跳过，
     * 也不提供"不同意也放行"的分支——未授权的能力在调用点会被明确拒绝。
     */
    public static void requestRuntimePermissions(Activity a, int requestCode) {
        String[] missing = ungrantedRuntimePermissions(a);
        if (missing.length == 0) {
            toast(a, "运行时权限都已授予");
            return;
        }
        try {
            a.requestPermissions(missing, requestCode);
        } catch (Exception e) {
            toast(a, "无法发起权限申请：" + e.getMessage());
        }
    }

    /** 通知权限在 API < 33 上不是运行时权限，属于系统开关，需跳设置页。 */
    public static boolean notificationNeedsSettingsPage() {
        return Build.VERSION.SDK_INT < 33;
    }

    /** 权限名 → 给用户看的短名，用于申请前的说明列表。 */
    public static String permissionLabel(String permission) {
        if (permission == null) return "未知权限";
        switch (permission) {
            case "android.permission.POST_NOTIFICATIONS":
                return "通知（任务进度常驻通知）";
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.READ_MEDIA_IMAGES":
            case "android.permission.READ_MEDIA_VIDEO":
            case "android.permission.READ_MEDIA_AUDIO":
                return "读取共享存储 / 媒体文件";
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return "写入共享存储（导出结果文件）";
            default:
                return permission.substring(permission.lastIndexOf('.') + 1);
        }
    }

    // ------------------------------------------------------- 存储可用性

    /** 共享存储是否可读（分区存储下按版本判断该看哪个权限）。 */
    public static boolean hasReadStoragePermission(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return isGranted(c, "android.permission.READ_MEDIA_IMAGES")
                    || isGranted(c, "android.permission.READ_MEDIA_VIDEO")
                    || isGranted(c, "android.permission.READ_MEDIA_AUDIO");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // 30/31/32：分区存储强制执行，共享媒体只能靠系统媒体库选择器访问，
            // 这里如实返回 false，不假装拿到了一个其实没生效的权限。
            return false;
        }
        return isGranted(c, "android.permission.READ_EXTERNAL_STORAGE");
    }

    /** 共享存储是否可写。 */
    public static boolean hasWriteStoragePermission(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return false;
        return isGranted(c, "android.permission.WRITE_EXTERNAL_STORAGE");
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
