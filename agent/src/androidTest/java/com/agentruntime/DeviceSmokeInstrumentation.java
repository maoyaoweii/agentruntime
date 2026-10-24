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

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        startedAt = System.currentTimeMillis();
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (started.compareAndSet(false, true)) {
            new Thread(this::runSmoke, "device-smoke-instrumentation").start();
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
