package com.agentruntime.vision;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.agentruntime.service.AgentAccessibilityService;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 屏幕截图通道（Computer Use 式视觉输入）。
 *
 * <p>走 {@link AccessibilityService#takeScreenshot}，不需要 MediaProjection 授权弹窗——
 * 这是它相对录屏方案最大的优势：只要无障碍服务在跑，截图随时可得。
 *
 * <p>限制：API 30（Android 11）起才有该接口。低于此版本返回 null，
 * 上层必须能接受"没有视觉通道"并退回纯无障碍节点定位。
 *
 * <p><b>未在设备验证</b>：本环境无模拟器与真机，截图链路只做到编译通过与逻辑自洽。
 */
public final class ScreenCapturer {

    /** 送给模型的最长边：1024 是清晰度与传输量的折中，再大对定位精度没有明显收益。 */
    public static final int DEFAULT_MAX_EDGE = 1024;
    public static final int DEFAULT_QUALITY = 70;

    private static final long CAPTURE_TIMEOUT_MS = 5000;

    private static final String TAG = "AgentScreenCapturer";

    private final AgentAccessibilityService service;

    public ScreenCapturer(AgentAccessibilityService service) {
        this.service = service;
    }

    public static boolean supported() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        if (svc == null || svc.getServiceInfo() == null) return false;
        return (svc.getServiceInfo().getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }

    /** 默认参数截图。 */
    public ScreenFrame capture() {
        return capture(DEFAULT_MAX_EDGE, DEFAULT_QUALITY);
    }

    /**
     * 截图并缩放编码。
     *
     * @param maxEdge 缩放后最长边
     * @param quality JPEG 质量 0-100
     * @return 截图帧；不可用或失败时返回 null
     */
    public ScreenFrame capture(int maxEdge, int quality) {
        if (Build.VERSION.SDK_INT < 30) return null;
        AgentAccessibilityService svc = service != null
                ? service : AgentAccessibilityService.getInstance();
        if (svc == null) return null;

        long start = System.currentTimeMillis();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
        final AtomicInteger errorRef = new AtomicInteger(0);
        Handler main = new Handler(Looper.getMainLooper());

        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, main::post,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = null;
                            try {
                                buffer = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                                if (hw != null) {
                                    // 硬件位图不能直接 compress，拷贝成软件位图
                                    Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    bitmapRef.set(soft);
                                }
                            } catch (Throwable t) {
                                errorRef.set(-1);
                            } finally {
                                if (buffer != null) buffer.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            android.util.Log.w(TAG, "takeScreenshot onFailure errorCode=" + errorCode);
                            errorRef.set(errorCode);
                            latch.countDown();
                        }
                    });
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                android.util.Log.w(TAG, "takeScreenshot timed out after " + CAPTURE_TIMEOUT_MS + "ms");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            // 不静默：设备上截图失败必须可诊断，否则表现为"截图能力永远不可用"而查不到原因。
            android.util.Log.w(TAG, "takeScreenshot threw: " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }

        Bitmap source = bitmapRef.get();
        if (source == null || source.getWidth() <= 0) return null;

        try {
            int sw = source.getWidth();
            int sh = source.getHeight();
            int limit = Math.max(sw, sh);
            float scale = limit > maxEdge ? (float) maxEdge / (float) limit : 1f;
            int iw = Math.max(1, Math.round(sw * scale));
            int ih = Math.max(1, Math.round(sh * scale));
            Bitmap scaled = (iw == sw && ih == sh)
                    ? source
                    : Bitmap.createScaledBitmap(source, iw, ih, true);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, Math.max(10, Math.min(100, quality)), bos);
            byte[] bytes = bos.toByteArray();

            if (scaled != source) scaled.recycle();
            source.recycle();

            long elapsed = System.currentTimeMillis() - start;
            return new ScreenFrame(sw, sh, iw, ih, bytes, "image/jpeg",
                    System.currentTimeMillis(), elapsed);
        } catch (Throwable t) {
            try {
                source.recycle();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 读取真实屏幕尺寸（用于坐标映射基准）。 */
    public static int[] screenSize(android.content.Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                android.graphics.Point p = new android.graphics.Point();
                wm.getDefaultDisplay().getRealSize(p);
                if (p.x > 0 && p.y > 0) return new int[]{p.x, p.y};
            }
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }
}
