package com.agentruntime.executor;

import android.content.Context;
import android.content.pm.PackageManager;

import com.agentruntime.device.SystemBridge;
import com.agentruntime.state.WorldState;

import java.io.File;

/**
 * 前/后置条件解释器：条件为受约束的声明式字符串，不执行任意代码。
 * 支持：ui_contains:<text> / ui_not_contains:<text> / package_is:<pkg> / package_is_not:<pkg> /
 *       file_exists:<path> / test_account_ready / file_message_visible / text_message_visible:<text> / wifi_state_read
 */
public final class ConditionChecker {

    private final Context context;

    public ConditionChecker(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    /**
     * 条件字符串是否可被本解释器真正判定。
     * 用于在规划阶段剔除模型自造的条件（例如"clipboard_available"这类自然语言），
     * 否则任务必然以 PRECONDITION_FAILED 中止，等于没跑。
     */
    public static boolean isSupported(String condition) {
        if (condition == null || condition.trim().isEmpty()) return false;
        String c = condition.trim();
        return c.startsWith("ui_contains:") || c.startsWith("ui_not_contains:")
                || c.startsWith("text_message_visible:") || c.startsWith("package_is:")
                || c.startsWith("package_is_not:") || c.startsWith("file_exists:")
                || c.equals("test_account_ready") || c.equals("file_message_visible")
                || c.equals("wifi_state_read");
    }

    public boolean evaluate(String condition, WorldState state) {
        if (condition == null || condition.trim().isEmpty()) return true;
        String c = condition.trim();
        try {
            if (c.startsWith("ui_contains:")) {
                return state != null && state.containsText(value(c, "ui_contains:"));
            }
            if (c.startsWith("ui_not_contains:")) {
                return state != null && !state.containsText(value(c, "ui_not_contains:"));
            }
            if (c.startsWith("text_message_visible:")) {
                return state != null && state.containsText(value(c, "text_message_visible:"));
            }
            if (c.startsWith("package_is_not:")) {
                String pkg = value(c, "package_is_not:");
                return state != null && !pkg.equals(state.foregroundPackage);
            }
            if (c.startsWith("package_is:")) {
                String pkg = value(c, "package_is:");
                return state != null && pkg.equals(state.foregroundPackage);
            }
            if (c.startsWith("file_exists:")) {
                if (context == null) return false;
                String path = value(c, "file_exists:");
                File safeFile = new SystemBridge(context).resolveSafe(path);
                return safeFile != null && safeFile.exists();
            }
            if (c.equals("test_account_ready")) {
                return isPackageInstalled("com.agenttestapp");
            }
            if (c.equals("file_message_visible")) {
                return state != null && state.containsText("[文件]") && state.containsText("report.pdf");
            }
            if (c.equals("wifi_state_read")) {
                return state != null && state.containsText("Wi-Fi 状态");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取冒号后的断言值。模型常写 "package_is: com.app"，前导空格会让等值比较永远失败；
     *  也常写成 ui_contains: "Stopwatch"（带引号），引号不去掉同样永远匹配不到。 */
    private static String value(String condition, String prefix) {
        String v = condition.substring(prefix.length()).trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
