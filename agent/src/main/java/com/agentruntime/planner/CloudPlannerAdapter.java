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
    private volatile HttpURLConnection activePlan;

    public CloudPlannerAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        HttpURLConnection connection = activePlan;
        if (connection != null) new Thread(connection::disconnect, "cancel-cloud-plan").start();
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
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            // 推理型模型（如 spark-x2.6）先推理后回答，15s 不够，实测 ping 会被误判成"连接失败"。
            conn.setReadTimeout(45000);
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
        return plan(goal, skills, permissions, null);
    }

    /**
     * @param catalog 技能清单（含必填参数名，如 {@code sys.clipboard_copy(text)}）。
     *                只给技能名时真实模型无法知道要传哪些参数，实测会产出缺参步骤，
     *                在 SkillEngine 解析阶段直接 SKILL_RESOLVE_FAILED。
     */
    public TaskSpec plan(String goal, List<String> skills, List<String> permissions, String catalog)
            throws IOException, JSONException {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String baseUrl = sp.getString(KEY_BASE_URL, "").trim();
        String apiKey = sp.getString(KEY_API_KEY, "").trim();
        String model = sp.getString(KEY_MODEL, "gpt-4o-mini").trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) throw new IOException("cloud planner not configured");

        // 静态约束只有一份：PromptLibrary.PLANNER_SYSTEM。适配器只负责追加本机动态信息。
        StringBuilder systemBuilder = new StringBuilder(com.agentruntime.prompt.PromptLibrary.PLANNER_SYSTEM);
        // 设备上实际装了哪些第三方应用必须由本机告知：只靠模型猜包名会产出不存在的包，
        // launch_app 直接 APP_NOT_ALLOWED / 启动失败。
        String apps = installedLaunchableApps();
        if (!apps.isEmpty()) {
            systemBuilder.append("\n\n【设备上可启动的应用（应用名=包名，含系统应用）】").append(apps)
                    .append("。目标里说到某个应用时，必须使用这里给出的包名，不要凭猜测造包名；")
                    .append("清单里没有的应用就是没装，不要凭空编一个。");
        }
        String system = systemBuilder.toString();
        String user = "目标：" + goal + "\n可用 skill（括号内为必填参数名，args 必须给全）："
                + (catalog != null ? catalog : skills) + "\n设备权限：" + permissions
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
            activePlan = conn;
            conn.setInstanceFollowRedirects(false);
            if (Thread.currentThread().isInterrupted()) throw new IOException("planning cancelled");
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            // 推理型模型（如 spark-x2.6）先产出推理再产出 JSON：实测普通规划约 20s，
            // 长提示+多技能清单可超过 60s。超时过短会把成功响应判成 transport failure，
            // 表现为"配好了却规划不了"。
            conn.setReadTimeout(150000);
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
            if (code < 200 || code >= 300) throw new IOException("cloud planner http " + code);
            android.util.Log.i("CloudPlannerAdapter", "plan http " + code + " bytes=" + resp.length());

            JSONObject root = new JSONObject(resp);
            String content = root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            // 统一走 OutputFormat：抽取 → 修复 → 校验，不再各处自己 indexOf/lastIndexOf
            JSONObject taskJson = com.agentruntime.dsl.OutputFormat.parseObject(content);
            DslParser.ValidationResult vr = DslParser.validateTaskJson(taskJson);
            if (!vr.ok) throw new JSONException("cloud plan schema invalid: " + vr.error);
            android.util.Log.i("CloudPlannerAdapter", "validated steps=" + taskJson.getJSONArray("steps").length());
            return DslParser.taskFromJson(taskJson);
        } catch (IOException e) {
            android.util.Log.w("CloudPlannerAdapter", "plan transport failure: "
                    + e.getClass().getSimpleName());
            throw e;
        } finally {
            activePlan = null;
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 设备上「可以打开」的应用清单（应用名=包名）。
     *
     * <p>只列第三方是不够的：实测「打开日历」「打开文件管理」时模型会去猜
     * {@code org.fossify.calendar} 这种并不存在的包（那是上一轮装过的应用），
     * 因为系统应用一个都没告诉它。所以这里改为枚举**有启动器入口的应用**，
     * 第三方排在前面（它们才是大多数任务的目标），系统应用跟在后面。
     *
     * <p>用 launcher intent 而不是 {@code getInstalledApplications}：后者会带进
     * 一堆没有界面的 provider/服务，塞进提示词只会干扰模型。
     */
    private String installedLaunchableApps() {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.Intent launcher = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> ris =
                    pm.queryIntentActivities(launcher, 0);
            if (ris == null) return "";
            java.util.List<android.content.pm.ResolveInfo> ordered = new java.util.ArrayList<>(ris);
            ordered.sort((a, b) -> Boolean.compare(isSystemApp(a), isSystemApp(b)));

            StringBuilder sb = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int n = 0;
            for (android.content.pm.ResolveInfo ri : ordered) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (context.getPackageName().equals(pkg)) continue;
                if (!seen.add(pkg)) continue;
                if (n >= 18) break;
                CharSequence label = ri.loadLabel(pm);
                if (sb.length() > 0) sb.append('、');
                sb.append(label).append('=').append(pkg);
                n++;
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isSystemApp(android.content.pm.ResolveInfo ri) {
        return ri.activityInfo != null && ri.activityInfo.applicationInfo != null
                && (ri.activityInfo.applicationInfo.flags
                & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static String extractJson(String content) {
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) return content;
        return content.substring(s, e + 1);
    }

    private static String readAll(InputStream in) throws IOException {
        return com.agentruntime.network.ResponseBody.read(in);
    }
}
