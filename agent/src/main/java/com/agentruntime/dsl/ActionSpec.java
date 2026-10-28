package com.agentruntime.dsl;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Action DSL：设备端可执行的最小动作原语。
 * 仅允许白名单动作类型，禁止任意 shell / 任意 URL / 未登记设备 API。
 */
public final class ActionSpec {

    public enum ActionType {
        FIND_NODE, CLICK_NODE, SCROLL, INPUT_TEXT, WAIT_FOR, LAUNCH_APP,
        READ_STATE, ASSERT_STATE, REQUEST_CONFIRMATION, CANCEL, RECOVER,
        PRESS_KEY, LONG_CLICK, CLEAR_TEXT, WAIT_MS, CLOSE_SYSTEM_DIALOGS,
        // --- V1.1 新增：Computer Use 式坐标动作与视觉通道 ---
        TAP_XY, SWIPE_XY, LONG_PRESS_XY, SCREENSHOT, VISUAL_TAP,
        // --- V1.1 新增：浏览器控制与页面读取 ---
        READ_PAGE, NAVIGATE, PAGE_CLICK, PAGE_FIND, OBSERVE,
        // --- V1.3 新增：系统底层工具 ---
        SET_VOLUME, GET_VOLUME, SET_BRIGHTNESS, CLIPBOARD_SET, CLIPBOARD_GET,
        MEDIA_KEY, VIBRATE, OPEN_SETTINGS_PAGE, LIST_APPS, READ_FILE, WRITE_FILE,
        // --- V1.8 新增：文件修改（追加）与删除 ---
        APPEND_FILE, DELETE_FILE,
        // --- V1.3 新增：输入法注入通道 ---
        IME_COMMIT, IME_READ, IME_KEY,
        // --- V1.9 新增：按文本点击（找不到自动滚动） ---
        TAP_TEXT,
        // --- V2.0 新增：Android 原生手势与系统通知 ---
        SWIPE, NOTIFICATIONS;

        public static ActionType fromString(String s) throws JSONException {
            if (s == null) throw new JSONException("action type is null");
            String key = s.trim().toLowerCase();
            switch (key) {
                case "find_node": return FIND_NODE;
                case "click_node": return CLICK_NODE;
                case "scroll": return SCROLL;
                case "input_text": return INPUT_TEXT;
                case "wait_for": return WAIT_FOR;
                case "launch_app": return LAUNCH_APP;
                case "read_state": return READ_STATE;
                case "assert_state": return ASSERT_STATE;
                case "request_confirmation": return REQUEST_CONFIRMATION;
                case "cancel": return CANCEL;
                case "recover": return RECOVER;
                case "press_key": return PRESS_KEY;
                case "long_click": return LONG_CLICK;
                case "clear_text": return CLEAR_TEXT;
                case "wait_ms": return WAIT_MS;
                case "close_system_dialogs": return CLOSE_SYSTEM_DIALOGS;
                case "tap_xy": return TAP_XY;
                case "swipe_xy": return SWIPE_XY;
                case "long_press_xy": return LONG_PRESS_XY;
                case "screenshot": return SCREENSHOT;
                case "visual_tap": return VISUAL_TAP;
                case "read_page": return READ_PAGE;
                case "navigate": return NAVIGATE;
                case "page_click": return PAGE_CLICK;
                case "page_find": return PAGE_FIND;
                case "observe": return OBSERVE;
                case "set_volume": return SET_VOLUME;
                case "get_volume": return GET_VOLUME;
                case "set_brightness": return SET_BRIGHTNESS;
                case "clipboard_set": return CLIPBOARD_SET;
                case "clipboard_get": return CLIPBOARD_GET;
                case "media_key": return MEDIA_KEY;
                case "vibrate": return VIBRATE;
                case "open_settings_page": return OPEN_SETTINGS_PAGE;
                case "list_apps": return LIST_APPS;
                case "read_file": return READ_FILE;
                case "write_file": return WRITE_FILE;
                case "append_file": return APPEND_FILE;
                case "delete_file": return DELETE_FILE;
                case "ime_commit": return IME_COMMIT;
                case "ime_read": return IME_READ;
                case "ime_key": return IME_KEY;
                case "tap_text": return TAP_TEXT;
                case "swipe": return SWIPE;
                case "notifications": return NOTIFICATIONS;
                default:
                    throw new JSONException("action type not in whitelist: " + s);
            }
        }

        public String wireName() {
            switch (this) {
                case FIND_NODE: return "find_node";
                case CLICK_NODE: return "click_node";
                case SCROLL: return "scroll";
                case INPUT_TEXT: return "input_text";
                case WAIT_FOR: return "wait_for";
                case LAUNCH_APP: return "launch_app";
                case READ_STATE: return "read_state";
                case ASSERT_STATE: return "assert_state";
                case REQUEST_CONFIRMATION: return "request_confirmation";
                case CANCEL: return "cancel";
                case PRESS_KEY: return "press_key";
                case LONG_CLICK: return "long_click";
                case CLEAR_TEXT: return "clear_text";
                case WAIT_MS: return "wait_ms";
                case CLOSE_SYSTEM_DIALOGS: return "close_system_dialogs";
                case TAP_XY: return "tap_xy";
                case SWIPE_XY: return "swipe_xy";
                case LONG_PRESS_XY: return "long_press_xy";
                case SCREENSHOT: return "screenshot";
                case VISUAL_TAP: return "visual_tap";
                case READ_PAGE: return "read_page";
                case NAVIGATE: return "navigate";
                case PAGE_CLICK: return "page_click";
                case PAGE_FIND: return "page_find";
                case OBSERVE: return "observe";
                case SET_VOLUME: return "set_volume";
                case GET_VOLUME: return "get_volume";
                case SET_BRIGHTNESS: return "set_brightness";
                case CLIPBOARD_SET: return "clipboard_set";
                case CLIPBOARD_GET: return "clipboard_get";
                case MEDIA_KEY: return "media_key";
                case VIBRATE: return "vibrate";
                case OPEN_SETTINGS_PAGE: return "open_settings_page";
                case LIST_APPS: return "list_apps";
                case READ_FILE: return "read_file";
                case WRITE_FILE: return "write_file";
                case APPEND_FILE: return "append_file";
                case DELETE_FILE: return "delete_file";
                case IME_COMMIT: return "ime_commit";
                case IME_READ: return "ime_read";
                case IME_KEY: return "ime_key";
                case TAP_TEXT: return "tap_text";
                case SWIPE: return "swipe";
                case NOTIFICATIONS: return "notifications";
                default: return "recover";
            }
        }
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH;

        public static RiskLevel fromString(String s) {
            if (s == null) return RiskLevel.LOW;
            String key = s.trim().toUpperCase();
            if (key.equals("HIGH")) return RiskLevel.HIGH;
            if (key.equals("MEDIUM")) return RiskLevel.MEDIUM;
            return RiskLevel.LOW;
        }
    }

    public final ActionType type;
    public final JSONObject params;
    public final long timeoutMs;
    public final int maxRetries;
    public final RiskLevel risk;
    public final boolean requiresConfirmation;
    public final String postcondition;

    public ActionSpec(ActionType type, JSONObject params, long timeoutMs, int maxRetries,
                      RiskLevel risk, boolean requiresConfirmation, String postcondition) throws JSONException {
        if (type == null) throw new JSONException("action type required");
        this.type = type;
        this.params = params != null ? params : new JSONObject();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 10000;
        this.maxRetries = Math.max(0, maxRetries);
        this.risk = risk != null ? risk : RiskLevel.LOW;
        this.requiresConfirmation = requiresConfirmation;
        this.postcondition = postcondition;
    }

    public String param(String key) {
        return params.optString(key, null);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("action", type.wireName());
            o.put("params", params);
            o.put("timeout_ms", timeoutMs);
            o.put("max_retries", maxRetries);
            o.put("risk", risk.name());
            o.put("requires_confirmation", requiresConfirmation);
            if (postcondition != null) o.put("postcondition", postcondition);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
