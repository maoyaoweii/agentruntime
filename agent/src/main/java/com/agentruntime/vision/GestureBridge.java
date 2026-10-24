package com.agentruntime.vision;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.agentruntime.executor.ExecResult;
import com.agentruntime.service.AgentAccessibilityService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 坐标手势通道：直接向屏幕注入点击 / 滑动 / 长按。
 *
 * <p>这是 Computer Use 的另一半——视觉模型只看得到"大概在那"，最终要落到一个坐标上。
 * 无障碍节点点击适合结构清晰的控件；坐标手势适合自绘控件、Canvas 页面、
 * 以及节点树残缺的 App（WebView、游戏、Flutter）。
 *
 * <p>安全约束：坐标必须落在屏幕内才执行。模型给的越界坐标一律拒绝，
 * 而不是夹到边界上硬点——后者会误触边缘控件。
 *
 * <p><b>未在设备验证</b>：本环境无模拟器与真机。
 */
public final class GestureBridge {

    private static final long GESTURE_TIMEOUT_MS = 5000;

    private final AgentAccessibilityService service;

    public GestureBridge(AgentAccessibilityService service) {
        this.service = service;
    }

    public static boolean supported() {
        if (Build.VERSION.SDK_INT < 24) return false;
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        if (svc == null || svc.getServiceInfo() == null) return false;
        return (svc.getServiceInfo().getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
    }

    /** 单击。 */
    public ExecResult tap(int x, int y, int screenWidth, int screenHeight, long timeoutMs) {
        if (!CoordinateMapper.inScreen(x, y, screenWidth, screenHeight)) {
            return ExecResult.reject("COORD_OUT_OF_BOUNDS",
                    "tap " + x + "," + y + " outside " + screenWidth + "x" + screenHeight, 0);
        }
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(path, 0, 60, timeoutMs, "TAP_OK", x + "," + y);
    }

    /** 长按。 */
    public ExecResult longPress(int x, int y, int screenWidth, int screenHeight,
                                long holdMs, long timeoutMs) {
        if (!CoordinateMapper.inScreen(x, y, screenWidth, screenHeight)) {
            return ExecResult.reject("COORD_OUT_OF_BOUNDS", "long_press " + x + "," + y, 0);
        }
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(path, 0, Math.max(600, holdMs), timeoutMs, "LONG_PRESS_OK", x + "," + y);
    }

    /** 滑动（用于滚动列表、拖动进度条、翻页）。 */
    public ExecResult swipe(int x1, int y1, int x2, int y2,
                            int screenWidth, int screenHeight,
                            long durationMs, long timeoutMs) {
        if (!CoordinateMapper.withinScreen(x1, y1, x2, y2, screenWidth, screenHeight)) {
            return ExecResult.reject("COORD_OUT_OF_BOUNDS",
                    "swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2, 0);
        }
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long duration = durationMs <= 0 ? 300 : Math.min(3000, durationMs);
        return dispatch(path, 0, duration, timeoutMs, "SWIPE_OK",
                x1 + "," + y1 + "->" + x2 + "," + y2);
    }

    private ExecResult dispatch(Path path, long startDelay, long duration,
                                long timeoutMs, String okCode, String detail) {
        long start = System.currentTimeMillis();
        AgentAccessibilityService svc = service != null
                ? service : AgentAccessibilityService.getInstance();
        if (svc == null) {
            return ExecResult.fail("A11Y_UNAVAILABLE", "accessibility service not connected", 0, 0);
        }
        if (Build.VERSION.SDK_INT < 24) {
            return ExecResult.fail("UNSUPPORTED_SDK", "dispatchGesture requires API 24+", 0, 0);
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, startDelay, duration));
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        Handler main = new Handler(Looper.getMainLooper());

        try {
            boolean accepted = svc.dispatchGesture(builder.build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            completed.set(true);
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            completed.set(false);
                            latch.countDown();
                        }
                    }, main);
            if (!accepted) {
                return ExecResult.fail("GESTURE_REJECTED", "dispatchGesture returned false",
                        0, System.currentTimeMillis() - start);
            }
            long wait = timeoutMs > 0 ? timeoutMs : GESTURE_TIMEOUT_MS;
            if (!latch.await(wait, TimeUnit.MILLISECONDS)) {
                return ExecResult.fail("GESTURE_TIMEOUT", "gesture callback timeout",
                        0, System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.fail("GESTURE_INTERRUPTED", "interrupted",
                    0, System.currentTimeMillis() - start);
        } catch (Throwable t) {
            return ExecResult.fail("GESTURE_ERROR", String.valueOf(t.getMessage()),
                    0, System.currentTimeMillis() - start);
        }

        long elapsed = System.currentTimeMillis() - start;
        return completed.get()
                ? ExecResult.ok(okCode, detail, 0, elapsed)
                : ExecResult.fail("GESTURE_CANCELLED", detail, 0, elapsed);
    }
}
