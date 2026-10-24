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
            context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            return ExecResult.ok("DIALOGS_CLOSED", "CLOSE_SYSTEM_DIALOGS broadcast sent", 0, elapsed(start));
        } catch (SecurityException e) {
            return ExecResult.reject("PLATFORM_RESTRICTED", "CLOSE_SYSTEM_DIALOGS is a protected broadcast (system signature required)", 0);
        } catch (RuntimeException e) {
            return ExecResult.fail("BROADCAST_FAILED", e.getMessage(), 0, elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
