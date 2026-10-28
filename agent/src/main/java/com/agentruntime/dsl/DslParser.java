package com.agentruntime.dsl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task DSL / Action DSL 解析与 Schema 校验。
 * 校验失败立即拒绝，不允许“尽量执行”。
 */
public final class DslParser {

    public static final class ValidationResult {
        public final boolean ok;
        public final String error;

        private ValidationResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String error) {
            return new ValidationResult(false, error);
        }
    }

    private static final Set<String> TASK_KEYS = new HashSet<>(Arrays.asList(
            "task_version", "task_id", "goal", "allowed_apps", "preconditions",
            "steps", "postconditions", "limits"));

    private static final int MAX_STEP_BUDGET = 64;
    private static final int MAX_REPLAN_BUDGET = 5;
    private static final long MAX_TASK_TIMEOUT_SECONDS = 3600;

    private DslParser() {
    }

    /** 解析并校验 Task DSL JSON 字符串。 */
    public static TaskSpec parseTask(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) throw new JSONException("empty task json");
        JSONObject root = new JSONObject(json);
        ValidationResult vr = validateTaskJson(root);
        if (!vr.ok) throw new JSONException("task schema invalid: " + vr.error);
        return taskFromJson(root);
    }

    public static TaskSpec taskFromJson(JSONObject root) throws JSONException {
        List<String> allowed = jsonToStringList(root.optJSONArray("allowed_apps"));
        List<String> pre = jsonToStringList(root.optJSONArray("preconditions"));
        List<String> post = jsonToStringList(root.optJSONArray("postconditions"));
        List<TaskSpec.TaskStep> steps = new ArrayList<>();
        JSONArray stepsArr = root.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject s = stepsArr.getJSONObject(i);
                steps.add(new TaskSpec.TaskStep(
                        s.getString("skill"),
                        s.optJSONObject("args"),
                        s.optString("confirmation", null)));
            }
        }
        TaskSpec.Limits limits = TaskSpec.Limits.defaults();
        JSONObject lim = root.optJSONObject("limits");
        if (lim != null) {
            limits = new TaskSpec.Limits(
                    lim.optInt("max_steps", 24),
                    lim.optInt("max_replans", 2),
                    lim.optLong("timeout_seconds", 180));
        }
        return new TaskSpec(
                root.getString("task_id"),
                root.optString("task_version", TaskSpec.TASK_VERSION),
                root.optString("goal", ""),
                allowed, pre, steps, post, limits);
    }

    /** Task DSL 结构校验。 */
    public static ValidationResult validateTaskJson(JSONObject root) {
        if (root == null) return ValidationResult.fail("null json");
        for (String key : TASK_KEYS) {
            // 仅检查未知键，必填键另行检查
        }
        java.util.Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (!TASK_KEYS.contains(key)) return ValidationResult.fail("unknown key: " + key);
        }
        if (isEmpty(root.optString("task_id", null))) return ValidationResult.fail("task_id required");
        String ver = root.optString("task_version", TaskSpec.TASK_VERSION);
        if (!TaskSpec.TASK_VERSION.equals(ver)) return ValidationResult.fail("unsupported task_version: " + ver);
        JSONArray steps = root.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return ValidationResult.fail("steps required");
        if (steps.length() > MAX_STEP_BUDGET) return ValidationResult.fail("step budget exceeded");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s;
            try {
                s = steps.getJSONObject(i);
            } catch (JSONException e) {
                return ValidationResult.fail("steps[" + i + "] not object");
            }
            if (isEmpty(s.optString("skill", null))) return ValidationResult.fail("steps[" + i + "].skill required");
            String conf = s.optString("confirmation", null);
            if (conf != null && !isKnownConfirmation(conf)) {
                return ValidationResult.fail("steps[" + i + "].confirmation unknown: " + conf);
            }
        }
        JSONObject lim = root.optJSONObject("limits");
        if (lim != null) {
            int maxSteps = lim.optInt("max_steps", 24);
            if (maxSteps <= 0 || maxSteps > MAX_STEP_BUDGET) return ValidationResult.fail("limits.max_steps out of range");
            int maxReplans = lim.optInt("max_replans", 2);
            if (maxReplans < 0 || maxReplans > MAX_REPLAN_BUDGET) return ValidationResult.fail("limits.max_replans out of range 0.." + MAX_REPLAN_BUDGET);
            long timeoutSeconds = lim.optLong("timeout_seconds", 180);
            if (timeoutSeconds < 1 || timeoutSeconds > MAX_TASK_TIMEOUT_SECONDS) return ValidationResult.fail("limits.timeout_seconds out of range 1.." + MAX_TASK_TIMEOUT_SECONDS);
        }
        return ValidationResult.pass();
    }

    /** Action DSL 解析：参数经白名单与类型校验。 */
    public static ActionSpec parseAction(JSONObject o) throws JSONException {
        if (o == null) throw new JSONException("action json required");
        ActionSpec.ActionType type = ActionSpec.ActionType.fromString(o.optString("action", null));
        JSONObject params = o.optJSONObject("params");
        if (params == null) params = new JSONObject();
        validateActionParams(type, params);
        long timeout = o.optLong("timeout_ms", 10000);
        if (timeout <= 0 || timeout > 120000) throw new JSONException("timeout_ms out of range: " + timeout);
        int retries = o.optInt("max_retries", 0);
        if (retries < 0 || retries > 5) throw new JSONException("max_retries out of range: " + retries);
        return new ActionSpec(
                type,
                params,
                timeout,
                retries,
                ActionSpec.RiskLevel.fromString(o.optString("risk", null)),
                o.optBoolean("requires_confirmation", false),
                o.optString("postcondition", null));
    }

    private static void validateActionParams(ActionSpec.ActionType type, JSONObject params) throws JSONException {
        switch (type) {
            case CLICK_NODE:
            case FIND_NODE:
                if (!params.has("find")) throw new JSONException(type.wireName() + " requires params.find");
                break;
            case INPUT_TEXT:
                if (!params.has("find")) throw new JSONException("input_text requires params.find");
                if (!params.has("text")) throw new JSONException("input_text requires params.text");
                break;
            case WAIT_FOR:
                if (!params.has("package") && !params.has("ui_contains") && !params.has("timeout_ms")) {
                    throw new JSONException("wait_for requires package/ui_contains");
                }
                break;
            case READ_STATE:
                if (!params.has("find")) throw new JSONException("read_state requires params.find");
                break;
            case LAUNCH_APP:
                if (isEmpty(params.optString("package", null))) throw new JSONException("launch_app requires params.package");
                break;
            case ASSERT_STATE:
                if (isEmpty(params.optString("condition", null))) throw new JSONException("assert_state requires params.condition");
                break;
            case SCROLL:
                if (isEmpty(params.optString("direction", null))) throw new JSONException("scroll requires params.direction");
                break;
            case PRESS_KEY:
                if (!isKnownPressKey(params.optString("key", null))) {
                    throw new JSONException("press_key requires params.key in whitelist (back/home/recents/notifications/quick_settings/power_dialog/lock_screen/screenshot)");
                }
                break;
            case LONG_CLICK:
            case CLEAR_TEXT:
                if (!params.has("find")) throw new JSONException(type.wireName() + " requires params.find");
                break;
            case WAIT_MS: {
                long ms = params.optLong("ms", -1);
                if (ms < 0 || ms > 60000) throw new JSONException("wait_ms requires params.ms in range 0..60000");
                break;
            }
            case CLOSE_SYSTEM_DIALOGS:
                break;
            case TAP_XY: {
                requireCoordinate(params, "x", "tap_xy");
                requireCoordinate(params, "y", "tap_xy");
                break;
            }
            case LONG_PRESS_XY: {
                requireCoordinate(params, "x", "long_press_xy");
                requireCoordinate(params, "y", "long_press_xy");
                long hold = params.optLong("hold_ms", 800);
                if (hold < 300 || hold > 10000) {
                    throw new JSONException("long_press_xy hold_ms out of range 300..10000");
                }
                break;
            }
            case SWIPE_XY: {
                requireCoordinate(params, "x1", "swipe_xy");
                requireCoordinate(params, "y1", "swipe_xy");
                requireCoordinate(params, "x2", "swipe_xy");
                requireCoordinate(params, "y2", "swipe_xy");
                long dur = params.optLong("duration_ms", 300);
                if (dur < 50 || dur > 3000) {
                    throw new JSONException("swipe_xy duration_ms out of range 50..3000");
                }
                break;
            }
            case SCREENSHOT: {
                int maxEdge = params.optInt("max_edge", 1024);
                if (maxEdge < 256 || maxEdge > 2048) {
                    throw new JSONException("screenshot max_edge out of range 256..2048");
                }
                int quality = params.optInt("quality", 70);
                if (quality < 20 || quality > 95) {
                    throw new JSONException("screenshot quality out of range 20..95");
                }
                break;
            }
            case VISUAL_TAP: {
                if (isEmpty(params.optString("target", null))) {
                    throw new JSONException("visual_tap requires params.target");
                }
                break;
            }
            case READ_PAGE: {
                int maxChars = params.optInt("max_chars", 2000);
                if (maxChars < 100 || maxChars > 8000) {
                    throw new JSONException("read_page max_chars out of range 100..8000");
                }
                break;
            }
            case NAVIGATE: {
                if (!isSafeUrl(params.optString("url", null))) {
                    throw new JSONException("navigate requires params.url with http/https scheme only");
                }
                break;
            }
            case PAGE_CLICK: {
                String selector = params.optString("selector", null);
                if (selector == null || selector.trim().isEmpty()) {
                    throw new JSONException("page_click requires params.selector");
                }
                if (!isSafeSelector(selector)) {
                    throw new JSONException("page_click selector rejected by whitelist");
                }
                break;
            }
            case PAGE_FIND: {
                if (isEmpty(params.optString("text", null))) {
                    throw new JSONException("page_find requires params.text");
                }
                break;
            }
            case OBSERVE:
                break;
            case SET_VOLUME: {
                int level = params.optInt("level", -1);
                if (level < 0 || level > 100) {
                    throw new JSONException("set_volume requires params.level in 0..100");
                }
                break;
            }
            case GET_VOLUME:
                break;
            case SET_BRIGHTNESS: {
                int percent = params.optInt("percent", -1);
                if (percent < 1 || percent > 100) {
                    throw new JSONException("set_brightness requires params.percent in 1..100");
                }
                break;
            }
            case CLIPBOARD_SET: {
                if (!params.has("text")) {
                    throw new JSONException("clipboard_set requires params.text");
                }
                break;
            }
            case CLIPBOARD_GET:
                break;
            case MEDIA_KEY: {
                String key = params.optString("key", null);
                if (isEmpty(key)) throw new JSONException("media_key requires params.key");
                break;
            }
            case VIBRATE: {
                int ms = params.optInt("ms", -1);
                if (ms < 20 || ms > 2000) {
                    throw new JSONException("vibrate requires params.ms in 20..2000");
                }
                break;
            }
            case OPEN_SETTINGS_PAGE: {
                if (isEmpty(params.optString("page", null))) {
                    throw new JSONException("open_settings_page requires params.page");
                }
                break;
            }
            case LIST_APPS: {
                int limit = params.optInt("limit", 50);
                if (limit < 1 || limit > 200) {
                    throw new JSONException("list_apps params.limit out of range 1..200");
                }
                break;
            }
            case READ_FILE: {
                if (isEmpty(params.optString("path", null))) {
                    throw new JSONException("read_file requires params.path");
                }
                break;
            }
            case WRITE_FILE:
            case APPEND_FILE: {
                if (isEmpty(params.optString("path", null))) {
                    throw new JSONException(type.wireName() + " requires params.path");
                }
                if (!params.has("content")) {
                    throw new JSONException(type.wireName() + " requires params.content");
                }
                break;
            }
            case DELETE_FILE: {
                if (isEmpty(params.optString("path", null))) {
                    throw new JSONException("delete_file requires params.path");
                }
                break;
            }
            case IME_COMMIT: {
                if (!params.has("text")) {
                    throw new JSONException("ime_commit requires params.text");
                }
                break;
            }
            case IME_READ: {
                int max = params.optInt("max_chars", 4096);
                if (max < 1 || max > 16384) {
                    throw new JSONException("ime_read params.max_chars out of range 1..16384");
                }
                break;
            }
            case IME_KEY: {
                if (!isKnownImeKey(params.optString("key", null))) {
                    throw new JSONException("ime_key requires params.key in (enter/delete/search)");
                }
                break;
            }
            case TAP_TEXT: {
                if (isEmpty(params.optString("text", null))) {
                    throw new JSONException("tap_text requires params.text");
                }
                int maxScrolls = params.optInt("max_scrolls", 2);
                if (maxScrolls < 0 || maxScrolls > 5) {
                    throw new JSONException("tap_text params.max_scrolls out of range 0..5");
                }
                String dir = params.optString("direction", "down");
                if (!dir.equals("down") && !dir.equals("up")
                        && !dir.equals("left") && !dir.equals("right")) {
                    throw new JSONException("tap_text params.direction must be down/up/left/right");
                }
                break;
            }
            case SWIPE: {
                String dir = params.optString("direction", "up");
                if (!dir.equals("up") && !dir.equals("down")
                        && !dir.equals("left") && !dir.equals("right")) {
                    throw new JSONException("swipe params.direction must be up/down/left/right");
                }
                int pct = params.optInt("distance_pct", 60);
                if (pct < 10 || pct > 90) {
                    throw new JSONException("swipe params.distance_pct out of range 10..90");
                }
                long dur = params.optLong("duration_ms", 300);
                if (dur < 50 || dur > 3000) {
                    throw new JSONException("swipe duration_ms out of range 50..3000");
                }
                if (params.has("x")) requireCoordinate(params, "x", "swipe");
                if (params.has("y")) requireCoordinate(params, "y", "swipe");
                break;
            }
            case NOTIFICATIONS: {
                int limit = params.optInt("limit", 20);
                if (limit < 1 || limit > 50) {
                    throw new JSONException("notifications params.limit out of range 1..50");
                }
                break;
            }
            default:
                break;
        }
    }

    private static boolean isKnownImeKey(String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase();
        return k.equals("enter") || k.equals("delete") || k.equals("search");
    }

    /** 坐标必须是整数且在合理范围内；真实屏幕边界在执行期校验。 */
    private static void requireCoordinate(JSONObject params, String key, String action) throws JSONException {
        if (!params.has(key)) throw new JSONException(action + " requires params." + key);
        Object raw = params.opt(key);
        int v;
        if (raw instanceof Number) {
            v = ((Number) raw).intValue();
        } else {
            try {
                v = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new JSONException(action + " params." + key + " must be an integer");
            }
        }
        if (v < 0 || v > 20000) {
            throw new JSONException(action + " params." + key + " out of range 0..20000");
        }
    }

    /**
     * URL 白名单：只允许 http/https。
     *
     * <p>实现委托给 {@link com.agentruntime.policy.SafetyPolicy}，保证与执行层同一套规则。
     */
    public static boolean isSafeUrl(String url) {
        return com.agentruntime.policy.SafetyPolicy.isSafeUrl(url);
    }

    /** 选择器白名单，同样委托 SafetyPolicy。 */
    public static boolean isSafeSelector(String selector) {
        return com.agentruntime.policy.SafetyPolicy.isSafeSelector(selector);
    }

    private static boolean isKnownPressKey(String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase();
        return k.equals("back") || k.equals("home") || k.equals("recents")
                || k.equals("notifications") || k.equals("quick_settings") || k.equals("power_dialog")
                || k.equals("lock_screen") || k.equals("screenshot");
    }

    private static boolean isKnownConfirmation(String conf) {
        return "before_external_send".equals(conf)
                || "before_payment".equals(conf)
                || "before_delete".equals(conf)
                || "always".equals(conf)
                || "never".equals(conf);
    }

    private static List<String> jsonToStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, null);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
