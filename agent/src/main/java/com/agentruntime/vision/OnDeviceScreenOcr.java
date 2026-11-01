package com.agentruntime.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Local OCR of the visible foreground app, with no image saved or sent to the model. */
public final class OnDeviceScreenOcr implements ScreenTextOcr {
    private static final long OCR_TIMEOUT_MS = 15000;
    private final Context context;
    private final ScreenCapturer capturer;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public OnDeviceScreenOcr(Context context, ScreenCapturer capturer) {
        this.context = context.getApplicationContext();
        this.capturer = capturer;
    }

    @Override public Result read(WorldState state, AtomicBoolean cancelled, long deadlineMs) {
        if (!ScreenOcrSettings.enabled(context)) return error("OCR_DISABLED");
        if (Build.VERSION.SDK_INT < 30) return error("SCREENSHOT_UNSUPPORTED");
        if (cancelled(cancelled)) return error("USER_CANCEL");
        if (!inFlight.compareAndSet(false, true)) return error("OCR_BUSY");

        boolean listenerOwnsResources = false;
        Bitmap source = null;
        Bitmap cropped = null;
        TextRecognizer recognizer = null;
        try {
            AgentAccessibilityService service = AgentAccessibilityService.getInstance();
            if (service == null) return error("SCREENSHOT_SERVICE_UNAVAILABLE");
            if (!sameWindow(service, state)) return error("SCREEN_CHANGED");
            if (cancelled(cancelled)) return error("USER_CANCEL");
            if (!ScreenOcrSettings.enabled(context)) return error("OCR_DISABLED");
            if (System.currentTimeMillis() >= deadlineMs) return error("OCR_TIMEOUT");
            ScreenCapturer.CaptureResult capture = capturer.captureDetailed(2560, 94);
            if (capture.frame == null || !capture.frame.valid()) return error(capture.code);
            if (cancelled(cancelled)) return error("USER_CANCEL");
            if (!ScreenOcrSettings.enabled(context)) return error("OCR_DISABLED");
            if (!sameWindow(service, state)) return error("SCREEN_CHANGED");
            ScreenFrame frame = capture.frame;
            source = BitmapFactory.decodeByteArray(frame.imageBytes, 0, frame.imageBytes.length);
            if (source == null) return error("SCREENSHOT_FAILED");
            int[] bounds = cropBounds(state.root, frame);
            if (bounds == null) return error("SCREEN_BOUNDS_UNAVAILABLE");
            cropped = Bitmap.createBitmap(source, bounds[0], bounds[1], bounds[2], bounds[3]);
            if (cropped != source) { source.recycle(); source = null; }
            if (cancelled(cancelled) || !ScreenOcrSettings.enabled(context)) return error("USER_CANCEL");
            if (System.currentTimeMillis() >= deadlineMs) return error("OCR_TIMEOUT");

            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Task<Text> task = recognizer.process(InputImage.fromBitmap(cropped, 0));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Result> result = new AtomicReference<>();
            Bitmap taskBitmap = cropped;
            TextRecognizer taskRecognizer = recognizer;
            task.addOnCompleteListener(Runnable::run, completed -> {
                try {
                    if (cancelled(cancelled) || !ScreenOcrSettings.enabled(context)) {
                        result.set(error("USER_CANCEL"));
                    } else if (!completed.isSuccessful() || completed.getResult() == null) {
                        result.set(error("OCR_FAILED"));
                    } else {
                        Text recognized = completed.getResult();
                        String text = recognized.getText();
                        java.util.List<ScreenTextBox> boxes = new java.util.ArrayList<>();
                        // 识别发生在裁切后的图上：先加回裁切原点，再按截图缩放还原到屏幕坐标。
                        float scale = frame.imageWidth / (float) Math.max(1, frame.screenWidth);
                        for (Text.TextBlock block : recognized.getTextBlocks()) {
                            android.graphics.Rect box = block.getBoundingBox();
                            if (box == null) continue;
                            int left = Math.round((box.left + bounds[0]) / scale);
                            int top = Math.round((box.top + bounds[1]) / scale);
                            int right = Math.round((box.right + bounds[0]) / scale);
                            int bottom = Math.round((box.bottom + bounds[1]) / scale);
                            boxes.add(new ScreenTextBox(block.getText(), left, top, right, bottom));
                        }
                        result.set(text == null || text.trim().isEmpty()
                                ? error("OCR_EMPTY") : new Result("SCREEN_OCR", text.trim(), boxes));
                    }
                } finally {
                    try { taskBitmap.recycle(); }
                    finally {
                        try { taskRecognizer.close(); }
                        finally { inFlight.set(false); done.countDown(); }
                    }
                }
            });
            listenerOwnsResources = true;
            long until = Math.min(deadlineMs, System.currentTimeMillis() + OCR_TIMEOUT_MS);
            while (true) {
                if (cancelled(cancelled)) return error("USER_CANCEL");
                if (!ScreenOcrSettings.enabled(context)) return error("OCR_DISABLED");
                long remaining = until - System.currentTimeMillis();
                if (remaining <= 0) return error("OCR_TIMEOUT");
                if (done.await(Math.min(remaining, 100), TimeUnit.MILLISECONDS)) break;
            }
            if (!sameWindow(service, state)) return error("SCREEN_CHANGED");
            Result value = result.get();
            return value == null ? error("OCR_FAILED") : value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("USER_CANCEL");
        } catch (Exception | LinkageError e) {
            return error("OCR_FAILED");
        } finally {
            if (!listenerOwnsResources) {
                if (cropped != null && cropped != source && !cropped.isRecycled()) cropped.recycle();
                if (source != null && !source.isRecycled()) source.recycle();
                if (recognizer != null) recognizer.close();
                inFlight.set(false);
            }
        }
    }

    private static boolean sameWindow(AgentAccessibilityService service, WorldState initial) {
        if (initial == null || initial.root == null || initial.foregroundPackage == null
                || service != AgentAccessibilityService.getInstance()) return false;
        WorldState now = service.snapshot("ocr-check");
        return now != null && now.root != null && now.epoch == initial.epoch
                && initial.foregroundPackage.equals(now.foregroundPackage);
    }

    /** Map the known app root to image pixels; do not silently fall back to the whole display. */
    static int[] cropBounds(UiNode root, ScreenFrame frame) {
        if (root == null || frame == null || !frame.valid()) return null;
        int left = Math.max(0, Math.min(frame.screenWidth, root.left));
        int top = Math.max(0, Math.min(frame.screenHeight, root.top));
        int right = Math.max(0, Math.min(frame.screenWidth, root.right));
        int bottom = Math.max(0, Math.min(frame.screenHeight, root.bottom));
        if (right <= left || bottom <= top) return null;
        int x = (int) ((long) left * frame.imageWidth / frame.screenWidth);
        int y = (int) ((long) top * frame.imageHeight / frame.screenHeight);
        int r = (int) Math.min(frame.imageWidth, ((long) right * frame.imageWidth + frame.screenWidth - 1) / frame.screenWidth);
        int b = (int) Math.min(frame.imageHeight, ((long) bottom * frame.imageHeight + frame.screenHeight - 1) / frame.screenHeight);
        return r > x && b > y ? new int[]{x, y, r - x, b - y} : null;
    }

    private static boolean cancelled(AtomicBoolean cancelled) { return cancelled != null && cancelled.get(); }
    private static Result error(String code) { return new Result(code, null); }
}
