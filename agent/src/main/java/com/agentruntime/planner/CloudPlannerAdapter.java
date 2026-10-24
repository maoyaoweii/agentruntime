package com.agentruntime.planner;

import android.content.Context;
import android.content.SharedPreferences;

import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 云端 Planner 适配器（OpenAI 兼容 Chat API）。
 * 模型输出必须通过 Task DSL Schema 校验，否则拒绝执行；
 * 未配置供应商时不可用，路由器安全回退到本地规划，不静默切换未授权供应商。
 */
public final class CloudPlannerAdapter {

    public static final String PREFS = "model_router";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";

    private final Context context;

    public CloudPlannerAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isConfigured() {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !sp.getString(KEY_BASE_URL, "").trim().isEmpty()
                && !sp.getString(KEY_API_KEY, "").trim().isEmpty();
    }

    /** 配置入口：参数为 null 表示保留该项原值（api_key 脱敏不可回读，部分更新时务必显式传入）。 */
    public void configure(String baseUrl, String apiKey, String model) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        if (baseUrl != null) e.putString(KEY_BASE_URL, baseUrl);
        if (apiKey != null) e.putString(KEY_API_KEY, apiKey);
        if (model != null) e.putString(KEY_MODEL, model);
        e.apply();
    }

    /** 当前配置（api_key 脱敏）。 */
    public JSONObject configInfo() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String key = sp.getString(KEY_API_KEY, "");
            String normalizedKey = key.trim();
            o.put("base_url", sp.getString(KEY_BASE_URL, ""));
            o.put("model", sp.getString(KEY_MODEL, "gpt-4o-mini"));
            o.put("api_key_set", !normalizedKey.isEmpty());
            o.put("api_key_masked", normalizedKey.isEmpty() ? ""
                    : normalizedKey.length() <= 16 ? "****"
                    : normalizedKey.substring(0, 4) + "****"
                            + normalizedKey.substring(normalizedKey.length() - 4));
            o.put("configured", isConfigured());
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 连通性自检：最小 chat 请求验证 base_url/api_key/model 可用。 */
    public JSONObject ping() {
        JSONObject out = new JSONObject();
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String baseUrl = sp.getString(KEY_BASE_URL, "").trim();
            String apiKey = sp.getString(KEY_API_KEY, "").trim();
            String model = sp.getString(KEY_MODEL, "gpt-4o-mini").trim();
            out.put("configured", isConfigured());
            if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                out.put("ok", false);
                out.put("error", "not configured");
                return out;
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 1);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "ping"));
            body.put("messages", msgs);

            conn = (HttpURLConnection) new URL(baseUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            out.put("http_code", code);
            out.put("ok", code >= 200 && code < 300);
            if (code >= 400) out.put("error", "HTTP " + code);
        } catch (IOException | JSONException e) {
            try {
                out.put("ok", false);
                out.put("error", e instanceof IOException ? "连接失败" : "请求构造失败");
            } catch (JSONException ignored) {
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        try {
            out.put("latency_ms", System.currentTimeMillis() - start);
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** 请求云端生成 Task DSL；返回经校验的 TaskSpec，失败抛异常。 */
    public TaskSpec plan(String goal, List<String> skills, List<String> permissions) throws IOException, JSONException {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String baseUrl = sp.getString(KEY_BASE_URL, "").trim();
        String apiKey = sp.getString(KEY_API_KEY, "").trim();
        String model = sp.getString(KEY_MODEL, "gpt-4o-mini").trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) throw new IOException("cloud planner not configured");

        String system = "你是移动设备任务规划器。只输出符合 Task DSL v1.0 的 JSON，不要输出其他文本。"
                + "task_version 必须是 1.0；steps 只能使用可用 skill；高风险发送步骤必须带 confirmation: before_external_send。";
        String user = "目标：" + goal + "\n可用 skill：" + skills + "\n设备权限：" + permissions
                + "\n输出 JSON 字段：task_version, task_id, goal, allowed_apps, preconditions, steps[{skill,args,confirmation}], postconditions, limits{max_steps,max_replans,timeout_seconds}";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream responseStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try (InputStream in = responseStream) {
                resp = readAll(in);
            }
            if (code >= 400) throw new IOException("cloud planner http " + code);

            JSONObject root = new JSONObject(resp);
            String content = root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            // 统一走 OutputFormat：抽取 → 修复 → 校验，不再各处自己 indexOf/lastIndexOf
            JSONObject taskJson = com.agentruntime.dsl.OutputFormat.parseObject(content);
            DslParser.ValidationResult vr = DslParser.validateTaskJson(taskJson);
            if (!vr.ok) throw new JSONException("cloud plan schema invalid: " + vr.error);
            return DslParser.taskFromJson(taskJson);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractJson(String content) {
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) return content;
        return content.substring(s, e + 1);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
