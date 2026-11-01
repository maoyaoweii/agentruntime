/*
 * Inspired by Mika (Apache-2.0, https://github.com/wangzan101/mika)
 */
package com.agentruntime.goal;

import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;
import com.agentruntime.ui.AppWindow;
import com.agentruntime.vision.GestureBridge;
import com.agentruntime.vision.OnDeviceScreenOcr;
import com.agentruntime.vision.ScreenCapturer;
import com.agentruntime.vision.ScreenTextBox;
import com.agentruntime.vision.ScreenTextOcr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真机上的"看世界"。
 *
 * <p>启动刻意从无障碍服务的 Context 发出，并带 NEW_TASK | RESET_TASK_IF_NEEDED：
 * 从 Application Context 启动在部分 ROM 上会被当成后台启动而失败。Application Context
 * 只在无障碍服务没连上时兜底。
 */
public final class DeviceGoalScreen implements GoalScreen {
    private final Context context;
    private final StateEngine stateEngine;
    private final ScreenCapturer capturer;
    private final ScreenTextOcr ocr;
    private final GestureBridge gestures;

    public DeviceGoalScreen(Context context, StateEngine stateEngine) {
        this.context = context.getApplicationContext();
        this.stateEngine = stateEngine;
        this.capturer = new ScreenCapturer(null);
        this.ocr = new OnDeviceScreenOcr(this.context, capturer);
        this.gestures = new GestureBridge(null);
    }

    @Override public String selfPackage() {
        return context.getPackageName();
    }

    @Override public Facts read() {
        WorldState state = current();
        if (state == null) return new Facts("", "", Collections.emptyList(), Collections.emptyList());
        List<UiNode> nodes = state.root == null ? Collections.emptyList() : state.root.flat();
        return new Facts(state.foregroundPackage, state.windowTitle, nodes, Collections.emptyList());
    }

    private WorldState current() {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        return service != null ? service.snapshot("goal") : stateEngine.current();
    }

    @Override public boolean launchByPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        Context launcher = service != null ? service : context;
        Intent intent = launcher.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null && launcher != context) {
            intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            launcher.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public boolean tapNode(UiNode node) {
        if (node == null) return false;
        return new AccessibilityBridge(stateEngine).performClick(node, 5000).ok();
    }

    @Override public boolean tapCenter(UiNode node) {
        if (node == null) return false;
        return tapPoint((node.left + node.right) / 2, (node.top + node.bottom) / 2);
    }

    @Override public boolean tapPoint(int x, int y) {
        int[] size = screenSize();
        if (size[0] <= 0) return false;
        return gestures.tap(x, y, size[0], size[1], 5000).ok();
    }

    @Override public boolean yieldForeground() {
        AppWindow.yieldDevice(450);
        return true;
    }

    @Override public OcrResult readImage() {
        ScreenTextOcr.Result result = ocr.read(current(), new AtomicBoolean(false),
                System.currentTimeMillis() + 20000);
        if (!"SCREEN_OCR".equals(result.code)) {
            return OcrResult.unavailable(result.code == null ? "OCR_FAILED" : result.code);
        }
        List<OcrItem> items = new ArrayList<>();
        for (ScreenTextBox box : result.boxes) {
            items.add(new OcrItem(box.text, box.left, box.top, box.right, box.bottom));
        }
        return new OcrResult(true, result.code, items);
    }

    @Override public boolean virtualDisplayAvailable() {
        // 没有接入虚拟显示：主循环按规范返回 ENVIRONMENT，不假装在后台操作。
        return false;
    }

    @Override public String resolvePackage(String visibleLabel) {
        if (visibleLabel == null || visibleLabel.isEmpty()) return null;
        String exact = GoalInterpreter.knownPackage(visibleLabel);
        if (exact != null) return installed(exact) ? exact : null;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> candidates = pm.queryIntentActivities(launcher, 0);
            String found = null;
            for (android.content.pm.ResolveInfo info : candidates) {
                if (info.activityInfo == null) continue;
                CharSequence label = info.loadLabel(pm);
                if (label == null || !visibleLabel.equals(label.toString().trim())) continue;
                if (found != null && !found.equals(info.activityInfo.packageName)) return null;
                found = info.activityInfo.packageName;
            }
            return found;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean installed(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private int[] screenSize() {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return new int[]{0, 0};
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }
}
