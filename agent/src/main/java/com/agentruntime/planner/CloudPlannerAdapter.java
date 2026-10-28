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

        String system = "你是移动设备任务规划器。只输出符合 Task DSL v1.0 的 JSON，不要输出其他文本。"
                + "task_version 必须是 1.0；steps 只能使用下面列出的 skill，禁止自造 skill 名；"
                + "args 的键名必须与括号内给出的参数名逐字一致（例如 contact_alias 不能写成 recipient 或 contact_name，"
                + "file_alias 不能写成 file_path），并给出真实值，缺参会直接执行失败；"
                + "allowed_apps 只能填 Android 包名（如 com.agenttestapp、org.fossify.notes、org.fossify.clock），不能填类别名或虚构名；"
                // 实测「找到 Admin 给他发消息」被规划成 sys.observe + web.find_text，网页技能去点原生 App 必然 NOT_FOUND
                + "web.* 只能用于目标明确要求浏览网页/本地页面时；目标提到 App、应用、联系人、会话、发消息时，"
                + "必须使用 agent_test.* 技能，禁止用 web.find_text/web.click_selector 代替原生 UI 操作；"
                + "sys.* 只用于设备级动作（剪贴板/截图/时钟/媒体/观察）；sys.ui_launch_tap 是通用应用内原语，不在此限；"
                + "其余 sys.* 不能代替应用内操作；"
                // AgentTestApp 有专用技能，其它应用没有：只能用通用「启动+按可见文字点击」原语
                + "非 AgentTestApp 的应用没有专用技能时，"
                + "用 sys.ui_launch_tap：args 为 app_package（该 App 的包名）与 text（界面上真实可见的文字），"
                // 系统设置是唯一有专用技能的系统应用，别让模型用网页技能去模拟设置页
                + "目标是系统设置里的某一页（网络/Wi-Fi/蓝牙/显示/电池/应用/隐私/开发者…）时，"
                + "必须用 sys.open_settings_page（args: page），禁止用 web.* 打开本地 html 去模拟设置界面；"
                + "web.* 只用于目标明确要求浏览网页或本地页面；"
                // 目标常是中文、第三方 App 界面常是英文：单个字面量匹配不到，必须给多候选
                + "text 请写成 3~5 个候选、用 | 分隔（该 App 可能的中英文标签都写上，"
                + "例如 新建|New note|Create a new note），执行时命中任一候选即可；"
                // 实测模型会把「打开应用」拆成单独一步去点应用名/标题栏，而标题栏往往没有对应文字节点，
                // 该步必然找不到目标；沉积到后续可点击按钮上才是正确做法
                + "sys.ui_launch_tap 本身已经包含启动应用，不要为『打开应用』单独加一步，"
                + "也不要把应用名/标题栏当作点击目标（应用名通常不在视图树里）；"
                + "text 候选必须是应用内真实存在的按钮或标签文字（如新建按钮 Create a new note、秒表标签 Stopwatch）；"
                + "并且 allowed_apps 必须包含该包名，否则启动会被拒绝；"
                // 实测模型会把普通会话误选成 *_with_dialog 变体，导致找不到弹窗节点
                + "open_chat 用于进入与联系人的会话；open_chat_with_dialog 只在目标明确提到弹窗/对话框时使用；"
                + "send_text / send_file 必须排在 open_chat 之后；"
                + "steps[].confirmation 只能取 before_external_send / before_payment / before_delete / always / never 之一（无需要时可省略该字段）；"
                + "高风险发送步骤必须带 confirmation: before_external_send；"
                // 任务开始时前台是 Agent 自己的界面，任何 UI/前台包断言都必然失败，实测直接 PRECONDITION_FAILED
                + "preconditions 一律输出空数组 []；"
                + "postconditions 只允许 ui_contains: / text_message_visible: / package_is: 三种可判定断言，"
                + "不要写自然语言描述，无法确定时留空数组 []；"
                // 一个 skill 内部常有 4~6 个动作，模型给 max_steps=2 会导致中途 STEP_BUDGET_EXCEEDED
                + "limits.max_steps 至少 24，timeout_seconds 至少 180。";
        // 设备上实际装了哪些第三方应用必须由本机告知：只靠模型猜包名会产出不存在的包，
        // launch_app 直接 APP_NOT_ALLOWED / 启动失败。
        String apps = installedLaunchableApps();
        if (!apps.isEmpty()) {
            system += "\n设备上可启动的应用（应用名=包名，含系统应用）：" + apps
                    + "。目标里说到某个应用时，必须使用这里给出的包名，不要凭猜测造包名；"
                    + "清单里没有的应用就是没装，不要凭空编一个。";
        }
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
            if (code >= 400) throw new IOException("cloud planner http " + code);
            android.util.Log.i("CloudPlannerAdapter", "plan http " + code + " bytes=" + resp.length());

            JSONObject root = new JSONObject(resp);
            String content = root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            // 统一走 OutputFormat：抽取 → 修复 → 校验，不再各处自己 indexOf/lastIndexOf
            JSONObject taskJson = com.agentruntime.dsl.OutputFormat.parseObject(content);
            DslParser.ValidationResult vr = DslParser.validateTaskJson(taskJson);
            if (!vr.ok) throw new JSONException("cloud plan schema invalid: " + vr.error);
            // 记录模型给出的步骤与参数：规划错误（包名写错、text 猜错）只能从这里看。
            android.util.Log.i("CloudPlannerAdapter", "plan steps " + taskJson.optJSONArray("steps"));
            return DslParser.taskFromJson(taskJson);
        } catch (IOException e) {
            android.util.Log.w("CloudPlannerAdapter", "plan transport failure: "
                    + e.getClass().getName() + ": " + e.getMessage());
            throw e;
        } finally {
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
