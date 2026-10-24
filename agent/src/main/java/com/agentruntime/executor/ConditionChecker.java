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

    public boolean evaluate(String condition, WorldState state) {
        if (condition == null || condition.trim().isEmpty()) return true;
        String c = condition.trim();
        try {
            if (c.startsWith("ui_contains:")) {
                return state != null && state.containsText(c.substring("ui_contains:".length()));
            }
            if (c.startsWith("ui_not_contains:")) {
                return state != null && !state.containsText(c.substring("ui_not_contains:".length()));
            }
            if (c.startsWith("text_message_visible:")) {
                return state != null && state.containsText(c.substring("text_message_visible:".length()));
            }
            if (c.startsWith("package_is_not:")) {
                String pkg = c.substring("package_is_not:".length());
                return state != null && !pkg.equals(state.foregroundPackage);
            }
            if (c.startsWith("package_is:")) {
                String pkg = c.substring("package_is:".length());
                return state != null && pkg.equals(state.foregroundPackage);
            }
            if (c.startsWith("file_exists:")) {
                if (context == null) return false;
                String path = c.substring("file_exists:".length());
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

    private boolean isPackageInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
