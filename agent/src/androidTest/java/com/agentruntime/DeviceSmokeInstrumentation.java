package com.agentruntime;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import ai.onnxruntime.OrtEnvironment;

import com.agentruntime.device.SystemBridge;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.vision.GestureBridge;
import com.agentruntime.vision.vlm.SmolVlmStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实设备 API smoke runner；每项失败都会记录并继续执行。 */
public final class DeviceSmokeInstrumentation extends Instrumentation {
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private long startedAt;
    private boolean ocrOnly;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        startedAt = System.currentTimeMillis();
        ocrOnly = "true".equals(arguments.getString("ocr_only"));
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (started.compareAndSet(false, true)) {
            new Thread(ocrOnly ? this::runOcrSmoke : this::runSmoke, "device-smoke-instrumentation").start();
        }
    }

    /** Run only on an isolated emulator with the synthetic companion app installed. */
    private void runOcrSmoke() {
        JSONObject report = new JSONObject();
        JSONArray tests = new JSONArray();
        Context context = getTargetContext();
        try {
            report.put("runner", "synthetic_screen_ocr");
            report.put("tests", tests);
            long until = System.currentTimeMillis() + 30000;
            while (com.agentruntime.service.AgentAccessibilityService.getInstance() == null
                    && System.currentTimeMillis() < until) Thread.sleep(200);
            if (com.agentruntime.service.AgentAccessibilityService.getInstance() == null)
                throw new AssertionError("accessibility service did not connect");
            until = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < until) {
                com.agentruntime.state.WorldState screen =
                        com.agentruntime.service.AgentAccessibilityService.getInstance().snapshot("ocr-test-wait");
                if (screen != null && "com.agenttestapp".equals(screen.foregroundPackage)) break;
                Thread.sleep(200);
            }
            Thread.sleep(1000);
            if (!com.agentruntime.vision.ScreenOcrSettings.setEnabled(context, true))
                throw new AssertionError("could not enable test OCR");
            run(tests, "drawn_screen_two_pages", () -> {
                AgentApp app = (AgentApp) context.getApplicationContext();
                com.agentruntime.dsl.ActionSpec read = new com.agentruntime.dsl.ActionSpec(
                        com.agentruntime.dsl.ActionSpec.ActionType.READ_SCREEN, new JSONObject(), 22000, 0,
                        com.agentruntime.dsl.ActionSpec.RiskLevel.LOW, false, null);
                ExecResult first = app.actionExecutor().execute("ocr-test", "first", "read", read);
                if (!"SCREEN_OCR".equals(first.code) || !first.detail.contains("SCREEN")
                        || !first.detail.contains("测试"))
                    throw new AssertionError("first page OCR: " + first.toJson());
                Thread.sleep(1500);
                com.agentruntime.dsl.ActionSpec swipe = new com.agentruntime.dsl.ActionSpec(
                        com.agentruntime.dsl.ActionSpec.ActionType.SWIPE,
                        new JSONObject().put("direction", "up"), 8000, 0,
                        com.agentruntime.dsl.ActionSpec.RiskLevel.LOW, false, null);
                ExecResult moved = app.actionExecutor().execute("ocr-test", "swipe", "swipe", swipe);
                if (!moved.ok()) throw new AssertionError("swipe: " + moved.toJson());
                Thread.sleep(1500);
                ExecResult second = app.actionExecutor().execute("ocr-test", "second", "read", read);
                if (!"SCREEN_OCR".equals(second.code) || !second.detail.contains("NEXT"))
                    throw new AssertionError("second page OCR: " + second.toJson());
                return detail("two_synthetic_pages_recognized", true);
            });
            report.put("passed", countFailures(tests) == 0);
        } catch (Throwable t) {
            putFatal(report, t);
            try { report.put("passed", false); } catch (Exception ignored) { }
        } finally {
            com.agentruntime.vision.ScreenOcrSettings.setEnabled(context, false);
            finishOnce(report);
        }
    }

    private void runSmoke() {
        waitForIdleSync();
        JSONObject report = new JSONObject();
        JSONArray tests = new JSONArray();
        Context context = getTargetContext();
        AgentApp app = context.getApplicationContext() instanceof AgentApp
                ? (AgentApp) context.getApplicationContext() : null;
        try {
            report.put("runner", getClass().getName());
            report.put("started_at_ms", startedAt);
            report.put("tests", tests);
        } catch (Exception e) {
            putFatal(report, e);
        }

        run(tests, "onnx_native_runtime", () -> {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            String version = env.getVersion();
            if (env == null || version == null || version.trim().isEmpty())
                throw new AssertionError("OrtEnvironment/version unavailable");
            return detail("initialized", version);
        });
        run(tests, "private_file_boundary", () -> {
            SystemBridge bridge = requireApp(app).systemBridge();
            String name = "device_smoke_" + startedAt + ".txt";
            File privateFile = new File(context.getFilesDir(), name);
            String text = "device-smoke-private-only";
            ExecResult write = bridge.writeFile(privateFile.getPath(), text);
            ExecResult read = bridge.readFile(privateFile.getPath(), 4096);
            ExecResult dotdot = bridge.writeFile(
                    new File(context.getFilesDir(), "../device_smoke_escape.txt").getPath(), text);
            ExecResult absolute = bridge.readFile("/data/local/tmp/device_smoke_escape.txt", 4096);
            try {
                if (!write.ok() || !read.ok() || !text.equals(read.detail))
                    throw new AssertionError("private file read/write failed: " + write.toJson());
                if (!"PATH_NOT_ALLOWED".equals(dotdot.code)
                        || !"PATH_NOT_ALLOWED".equals(absolute.code))
                    throw new AssertionError("path escape was not rejected");
                return detail("private_rw_and_rejected_paths", true);
            } finally {
                // 只清理本测试文件，绝不触碰用户数据。
                privateFile.delete();
            }
        });
        run(tests, "volume_read_set_restore", () -> {
            SystemBridge bridge = requireApp(app).systemBridge();
            ExecResult before = bridge.getVolume("music");
            if (!before.ok()) throw new AssertionError(before.toJson().toString());
            int current = parseCurrentVolume(before.detail);
            ExecResult set = bridge.setVolume("music", current);
            ExecResult restore = bridge.setVolume("music", current);
            if (!set.ok() || !restore.ok())
                throw new AssertionError(set.toJson() + " / " + restore.toJson());
            return detail("read_set_restore", current);
        });
        run(tests, "brightness_permission_boundary", () -> {
            ExecResult result = requireApp(app).systemBridge().setBrightness(50);
            if (!"NEED_PERMISSION".equals(result.code))
                throw new AssertionError("expected NEED_PERMISSION without granting: " + result.toJson());
            return detail("NEED_PERMISSION", true);
        });
        run(tests, "clipboard_round_trip", () -> {
            String marker = "device-smoke-clipboard-" + startedAt;
            SystemBridge bridge = requireApp(app).systemBridge();
            ExecResult set = bridge.setClipboard(marker);
            ExecResult get = bridge.getClipboard();
            if (!set.ok() || !get.ok() || !marker.equals(get.detail))
                throw new AssertionError(set.toJson() + " / " + get.toJson());
            return detail("round_trip", true);
        });
        run(tests, "gesture_out_of_bounds", () -> {
            ExecResult result = new GestureBridge(null).tap(-1, 0, 1080, 1920, 1000);
            if (!"COORD_OUT_OF_BOUNDS".equals(result.code))
                throw new AssertionError("expected explicit rejection: " + result.toJson());
            return detail("COORD_OUT_OF_BOUNDS", true);
        });
        run(tests, "smolvlm_no_weights_not_ready", () -> {
            SmolVlmStore.Status status = SmolVlmStore.check(context);
            if (status.installed)
                throw new AssertionError("SmolVLM weights unexpectedly installed: " + status.toJson());
            return detail("not_ready_no_weights", status.toJson());
        });

        try {
            report.put("finished_at_ms", System.currentTimeMillis());
            report.put("duration_ms", System.currentTimeMillis() - startedAt);
            report.put("passed", countFailures(tests) == 0 && !report.has("fatal"));
            writeReport(context, report);
        } catch (Throwable fatal) {
            putFatal(report, fatal);
            writeReport(context, report);
        }
        finishOnce(report);
    }

    private static AgentApp requireApp(AgentApp app) {
        if (app == null) throw new IllegalStateException("target application is not AgentApp");
        return app;
    }

    private static void run(JSONArray tests, String name, Checked checked) {
        JSONObject item = new JSONObject();
        try {
            item.put("name", name);
            item.put("status", "PASS");
            item.put("detail", checked.run());
        } catch (Throwable t) {
            try {
                item.put("name", name);
                item.put("status", "FAIL");
                item.put("error", t.toString());
            } catch (Exception ignored) { }
        }
        tests.put(item);
    }

    private void finishOnce(JSONObject report) {
        if (!finished.compareAndSet(false, true)) return;
        Bundle result = new Bundle();
        result.putString("device_instrumentation_json", report.toString());
        finish(report.optBoolean("passed", false) ? 0 : 1, result);
    }

    private static void writeReport(Context context, JSONObject report) {
        try (FileOutputStream out = new FileOutputStream(
                new File(context.getFilesDir(), "device_instrumentation.json"), false)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    private static void putFatal(JSONObject report, Throwable t) {
        try { report.put("fatal", t.toString()); } catch (Exception ignored) { }
    }

    private static int countFailures(JSONArray tests) {
        int n = 0;
        for (int i = 0; i < tests.length(); i++) {
            JSONObject item = tests.optJSONObject(i);
            if (item != null && "FAIL".equals(item.optString("status"))) n++;
        }
        return n;
    }

    private static int parseCurrentVolume(String detail) {
        int eq = detail.indexOf('=');
        int slash = detail.indexOf('/', eq + 1);
        if (eq < 0 || slash < 0) throw new IllegalArgumentException("bad volume detail: " + detail);
        return Integer.parseInt(detail.substring(eq + 1, slash));
    }

    private static JSONObject detail(String key, Object value) throws Exception {
        JSONObject o = new JSONObject();
        o.put(key, value);
        return o;
    }

    private interface Checked { JSONObject run() throws Exception; }
}
