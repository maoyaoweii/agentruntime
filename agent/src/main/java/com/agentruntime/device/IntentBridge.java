package com.agentruntime.device;

import android.content.Context;
import android.content.Intent;

import com.agentruntime.executor.ExecResult;
import com.agentruntime.state.UiNode;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android Intent / 公共 API Bridge：启动允许的应用、分享、文件选择等用户可感知流程。
 * 仅允许 Task DSL 白名单内的应用包名。
 */
public final class IntentBridge implements DeviceBridge {

    private final Context context;

    public IntentBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public JSONObject capabilityProbe() {
        JSONObject o = new JSONObject();
        try {
            o.put("bridge", name());
            o.put("available", true);
            o.put("can_launch_app", true);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public ExecResult performClick(UiNode target, long timeoutMs) {
        return ExecResult.reject("UNSUPPORTED", "click not supported on intent bridge", 0);
    }

    @Override
    public ExecResult performInput(UiNode target, String text, long timeoutMs) {
        return ExecResult.reject("UNSUPPORTED", "input not supported on intent bridge", 0);
    }

    @Override
    public ExecResult performScroll(UiNode target, String direction, long timeoutMs) {
        return ExecResult.reject("UNSUPPORTED", "scroll not supported on intent bridge", 0);
    }

    @Override
    public ExecResult performGlobalBack() {
        return ExecResult.reject("UNSUPPORTED", "back not supported on intent bridge", 0);
    }

    @Override
    public ExecResult performLaunchApp(String packageName, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                return ExecResult.fail("APP_NOT_FOUND", "no launch intent for " + packageName, 0, elapsed(start));
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return ExecResult.ok("LAUNCH_OK", packageName, 0, elapsed(start));
        } catch (SecurityException e) {
            return ExecResult.fail("LAUNCH_BLOCKED", e.getMessage(), 0, elapsed(start));
        }
    }

    public ExecResult closeSystemDialogs() {
        long start = System.currentTimeMillis();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                com.agentruntime.service.AgentAccessibilityService service =
                        com.agentruntime.service.AgentAccessibilityService.getInstance();
                if (service == null) return ExecResult.reject("NEED_ACCESSIBILITY",
                        "closing the notification shade requires accessibility on Android 12+", 0);
                boolean closed = service.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                return closed ? ExecResult.ok("DIALOGS_CLOSED", "notification shade dismissed", 0, elapsed(start))
                        : ExecResult.fail("DISMISS_FAILED", "notification shade could not be dismissed", 0, elapsed(start));
            }
            closeLegacyDialogs();
            return ExecResult.ok("DIALOGS_CLOSED", "CLOSE_SYSTEM_DIALOGS broadcast sent", 0, elapsed(start));
        } catch (SecurityException e) {
            return ExecResult.reject("PLATFORM_RESTRICTED", "CLOSE_SYSTEM_DIALOGS is a protected broadcast (system signature required)", 0);
        } catch (RuntimeException e) {
            return ExecResult.fail("BROADCAST_FAILED", e.getMessage(), 0, elapsed(start));
        }
    }

    // API <= 30 permits this broadcast; Android 12+ takes the accessibility path above.
    @android.annotation.SuppressLint("MissingPermission")
    private void closeLegacyDialogs() {
        context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
