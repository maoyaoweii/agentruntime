package com.agentruntime.planner;

import com.agentruntime.dsl.TaskSpec;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * L0 确定性规划基线：把常见自然语言目标映射为经 Schema 校验的 Task DSL。
 * 云 Planner 不可用时的离线回退路径；不覆盖的目标交由云端处理。
 */
public final class LocalRulePlanner {

    public static final String TASK_SEND_DOC = "send_test_document";
    public static final String TASK_READ_WIFI = "read_wifi_state";
    public static final String TASK_RECOVER_DIALOG = "recover_after_dialog";
    public static final String TASK_BROWSE_PAGE = "browse_local_page";
    public static final String TASK_CREATE_NOTE = "create_note";
    public static final String TASK_OPEN_CLOCK = "open_clock";

    public boolean canPlan(String goal) {
        return planOrNull(goal) != null;
    }

    public TaskSpec plan(String goal) throws JSONException {
        TaskSpec t = planOrNull(goal);
        if (t == null) throw new JSONException("local planner cannot handle goal: " + goal);
        return t;
    }

    public TaskSpec planOrNull(String goal) {
        if (goal == null) return null;
        String g = goal.toLowerCase();
        try {
            // V1.1：目标里带 http(s) 链接时优先走内置浏览器，避免被"网页"关键词误导到本地测试页
            String url = extractUrl(goal);
            if (url != null) {
                return openUrlAndRead(goal, url);
            }
            if (g.contains("观察") || g.contains("设备信息") || g.contains("什么手机")
                    || g.contains("屏幕多大") || g.contains("observe")) {
                return observeEnvironment(goal);
            }
            if (g.contains("截图") || g.contains("screenshot")) {
                return screenshotTask(goal);
            }
            if ((g.contains("发送") || g.contains("发给") || g.contains("send"))
                    && (g.contains("report.pdf") || g.contains("文件") || g.contains("文档"))) {
                return sendTestDocument(goal);
            }
            if (g.contains("wifi") || g.contains("wi-fi") || (g.contains("设置") && g.contains("状态"))) {
                return readWifiState(goal);
            }
            if (g.contains("弹窗") || g.contains("对话框") || g.contains("dialog")) {
                return recoverAfterDialog(goal);
            }
            if (g.contains("浏览器") || g.contains("browser") || g.contains("网页") || g.contains("browse")) {
                return browseLocalPage(goal);
            }
            if (g.contains("笔记") || g.contains("note")) {
                return createNote(goal);
            }
            if (g.contains("时钟") || g.contains("闹钟") || g.contains("clock") || g.contains("alarm")) {
                return openClock(goal);
            }
        } catch (JSONException e) {
            return null;
        }
        return null;
    }

    private TaskSpec browseLocalPage(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_BROWSE_PAGE + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"org.chromium.webview_shell\"],"
                + "\"preconditions\":[],"
                + "\"steps\":["
                + "  {\"skill\":\"web.browse_local_page\",\"args\":{\"url\":\"file:///sdcard/testpage.html\",\"marker\":\"AGENT_BROWSER_TEST_PAGE\"}}"
                + "],"
                + "\"postconditions\":[\"ui_contains:AGENT_BROWSER_TEST_PAGE\"],"
                + "\"limits\":{\"max_steps\":12,\"max_replans\":1,\"timeout_seconds\":300}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    private TaskSpec createNote(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_CREATE_NOTE + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"org.fossify.notes\"],"
                + "\"preconditions\":[],"
                + "\"steps\":["
                + "  {\"skill\":\"notes.create_note\",\"args\":{\"title\":\"AGENT_NOTE_MARK_2026\",\"content\":\"BODY_MARK_42\"}}"
                + "],"
                + "\"postconditions\":[\"ui_contains:AGENT_NOTE_MARK_2026\",\"ui_contains:BODY_MARK_42\"],"
                + "\"limits\":{\"max_steps\":16,\"max_replans\":1,\"timeout_seconds\":300}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    private TaskSpec openClock(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_OPEN_CLOCK + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"com.android.deskclock\"],"
                + "\"preconditions\":[],"
                + "\"steps\":["
                + "  {\"skill\":\"sys.open_clock\",\"args\":{}}"
                + "],"
                + "\"postconditions\":[\"package_is:com.android.deskclock\"],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":180}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    private TaskSpec sendTestDocument(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_SEND_DOC + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"com.agenttestapp\"],"
                + "\"preconditions\":[\"test_account_ready\"],"
                + "\"steps\":["
                + "  {\"skill\":\"agent_test.open_chat\",\"args\":{\"contact_alias\":\"TestUser\"}},"
                + "  {\"skill\":\"agent_test.send_file\",\"args\":{\"contact_alias\":\"TestUser\",\"file_alias\":\"report.pdf\"},\"confirmation\":\"before_external_send\"},"
                + "  {\"skill\":\"agent_test.send_text\",\"args\":{\"text\":\"文件已发给你，请查收。\"},\"confirmation\":\"before_external_send\"}"
                + "],"
                + "\"postconditions\":[\"file_message_visible\",\"text_message_visible:文件已发给你，请查收。\"],"
                + "\"limits\":{\"max_steps\":24,\"max_replans\":2,\"timeout_seconds\":480}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    private TaskSpec readWifiState(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_READ_WIFI + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"com.agenttestapp\"],"
                + "\"preconditions\":[\"test_account_ready\"],"
                + "\"steps\":["
                + "  {\"skill\":\"agent_test.open_settings\",\"args\":{}},"
                + "  {\"skill\":\"agent_test.read_wifi\",\"args\":{}}"
                + "],"
                + "\"postconditions\":[\"wifi_state_read\"],"
                + "\"limits\":{\"max_steps\":12,\"max_replans\":1,\"timeout_seconds\":240}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    private TaskSpec recoverAfterDialog(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"" + TASK_RECOVER_DIALOG + "\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"com.agenttestapp\"],"
                + "\"preconditions\":[\"test_account_ready\"],"
                + "\"steps\":["
                + "  {\"skill\":\"agent_test.open_chat_with_dialog\",\"args\":{\"contact_alias\":\"TestUser\"}},"
                + "  {\"skill\":\"agent_test.handle_dialog\",\"args\":{\"policy\":\"confirm\"}},"
                + "  {\"skill\":\"agent_test.send_text\",\"args\":{\"text\":\"弹窗已处理，任务继续。\"},\"confirmation\":\"never\"}"
                + "],"
                + "\"postconditions\":[\"text_message_visible:弹窗已处理，任务继续。\"],"
                + "\"limits\":{\"max_steps\":18,\"max_replans\":1,\"timeout_seconds\":360}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    // ------------------------------------------------------- V1.1 新增规则

    /** 用内置浏览器打开链接并读取页面文本。 */
    private TaskSpec openUrlAndRead(String goal, String url) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"open_url_and_read\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[\"com.agentruntime.agent\"],"
                + "\"preconditions\":[],"
                + "\"steps\":["
                + "  {\"skill\":\"web.open_and_read\",\"args\":{\"url\":" + JSONObject.quote(url)
                + ",\"max_chars\":\"2000\"}}"
                + "],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":8,\"max_replans\":1,\"timeout_seconds\":120}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    /** 环境观测：设备/屏幕/网络/无障碍状态。 */
    private TaskSpec observeEnvironment(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"observe_environment\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"sys.observe\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":4,\"max_replans\":0,\"timeout_seconds\":60}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    /** 截图留证。 */
    private TaskSpec screenshotTask(String goal) throws JSONException {
        String json = "{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"screenshot\","
                + "\"goal\":" + JSONObject.quote(goal) + ","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"sys.screenshot\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":4,\"max_replans\":0,\"timeout_seconds\":60}"
                + "}";
        return com.agentruntime.dsl.DslParser.parseTask(json);
    }

    /**
     * 从目标里抽取第一个 http(s) 链接。
     *
     * <p>按字符扫描而不是按空格切分：中文里"打开https://x.com"是不带空格的，
     * 按 token 切会整段取不到。终止条件覆盖空白、中文标点与常见闭合符号。
     */
    public static String extractUrl(String goal) {
        if (goal == null) return null;
        int start = goal.indexOf("https://");
        if (start < 0) start = goal.indexOf("http://");
        if (start < 0) return null;

        int end = start;
        while (end < goal.length()) {
            char c = goal.charAt(end);
            if (Character.isWhitespace(c)) break;
            if (c == '。' || c == '，' || c == '、' || c == '；'
                    || c == '）' || c == ')' || c == '」' || c == '"' || c == '\'') {
                break;
            }
            end++;
        }
        String candidate = goal.substring(start, end);
        return com.agentruntime.policy.SafetyPolicy.isSafeUrl(candidate) ? candidate : null;
    }
}
