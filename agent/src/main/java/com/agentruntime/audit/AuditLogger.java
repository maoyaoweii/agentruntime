package com.agentruntime.audit;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 审计记录：任务 ID、动作 ID、Bridge、状态版本、结果码、时间。
 * 默认不保存无关正文 / 截图；每条为一行 JSON（JSONL）。
 */
public final class AuditLogger {

    private final File auditDir;
    private final Object lock = new Object();

    public AuditLogger(Context context) {
        auditDir = new File(context.getFilesDir(), "audit");
        //noinspection ResultOfMethodCallIgnored
        auditDir.mkdirs();
    }

    public File currentFile() {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return new File(auditDir, "audit-" + day + ".jsonl");
    }

    public void record(String taskId, String actionId, String phase, String bridge,
                       long stateEpoch, String resultCode, JSONObject detail) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());
            o.put("task_id", taskId);
            o.put("action_id", actionId);
            o.put("phase", phase);
            o.put("bridge", bridge);
            o.put("state_epoch", stateEpoch);
            o.put("result_code", resultCode);
            if (detail != null) o.put("detail", detail);
        } catch (JSONException ignored) {
        }
        append(o.toString());
    }

    public void recordRaw(JSONObject o) {
        append(o.toString());
    }

    private void append(String line) {
        synchronized (lock) {
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(currentFile(), true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write("\n");
            } catch (IOException ignored) {
            }
        }
    }
}
