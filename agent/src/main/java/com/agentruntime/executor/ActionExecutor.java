package com.agentruntime.executor;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;
import com.agentruntime.system1.System1;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Action Executor：设备操作唯一入口。
 * Preflight -> 定位 -> Risk Gate -> 执行 -> Verify -> Audit。
 * 不执行模型生成的原始代码；仅接受 Action DSL 白名单原语。
 */
public final class ActionExecutor {

    private final StateEngine stateEngine;
    private final AccessibilityBridge accessibilityBridge;
    private final IntentBridge intentBridge;
    private final System1 system1;
    private final RiskGate riskGate;
    private final AuditLogger auditLogger;
    private final ConditionChecker conditionChecker;
    private final android.content.Context appContext;
    private volatile ExtendedCapabilities capabilities;

    public ActionExecutor(android.content.Context context, StateEngine stateEngine,
                          AccessibilityBridge accessibilityBridge, IntentBridge intentBridge,
                          System1 system1, RiskGate riskGate, AuditLogger auditLogger) {
        this.stateEngine = stateEngine;
        this.accessibilityBridge = accessibilityBridge;
        this.intentBridge = intentBridge;
        this.system1 = system1;
        this.riskGate = riskGate;
        this.auditLogger = auditLogger;
        this.conditionChecker = new ConditionChecker(context);
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    /** 注入 V1.1 扩展能力（坐标手势 / 截图 / 视觉定位 / 浏览器 / 环境采集）。 */
    public void attach(ExtendedCapabilities caps) {
        this.capabilities = caps;
    }

    public ExtendedCapabilities capabilities() {
        return capabilities;
    }

    public RiskGate.StepScope confirmationScope(String skillId, JSONObject args, List<ActionSpec> actions) {
        StringBuilder summary = new StringBuilder("执行技能：").append(skillId);
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String label = key.equals("text") ? "内容" : key.equals("path") ? "文件路径"
                    : key.equals("contact_alias") ? "联系人" : key.equals("file_alias") ? "文件"
                    : key.equals("app_package") ? "目标应用" : key;
            summary.append('\n').append(label).append("：").append(confirmationValue(args.optString(key, "")));
        }
        summary.append("\n\n本次操作清单：");
        for (int i = 0; i < actions.size(); i++) {
            ActionSpec action = actions.get(i);
            summary.append('\n').append(i + 1).append(". ").append(action.type.wireName())
                    .append(' ').append(confirmationValue(action.toJson().toString()));
        }
        return riskGate.openStep(summary.toString());
    }

    private static String confirmationValue(String value) {
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || (c >= '\u202a' && c <= '\u202e')
                    || (c >= '\u2066' && c <= '\u2069')) {
                visible.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            } else visible.append(c);
        }
        return visible.toString();
    }

    public ExecResult execute(String taskId, String stepId, String subGoal, ActionSpec spec) {
        return execute(taskId, stepId, subGoal, spec, java.util.Collections.emptyList());
    }

    public ExecResult execute(String taskId, String stepId, String subGoal, ActionSpec spec,
                              List<String> allowedApps) {
        return execute(taskId, stepId, subGoal, spec, allowedApps, null, Long.MAX_VALUE);
    }

    /** 执行动作，并在可中断等待期间观察任务取消与总 deadline。 */
    public ExecResult execute(String taskId, String stepId, String subGoal, ActionSpec spec,
                              List<String> allowedApps, AtomicBoolean cancelRequested,
                              long taskDeadlineMs) {
        String actionId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();
        long epoch = stateEngine.currentEpoch();

        // Preflight
        if (cancelRequested != null && cancelRequested.get()) {
            audit(taskId, actionId, "preflight", "none", epoch, "CANCELLED", null);
            return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
        }
        if (System.currentTimeMillis() >= taskDeadlineMs) {
            audit(taskId, actionId, "preflight", "none", epoch, "TASK_TIMEOUT", null);
            return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached", epoch, elapsed(start));
        }
        if (spec.type == ActionSpec.ActionType.CANCEL) {
            audit(taskId, actionId, "preflight", "none", epoch, "CANCELLED", null);
            return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
        }

        WorldState before = stateEngine.current();
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        if (svc != null) before = svc.snapshot("preflight");

        if (spec.type == ActionSpec.ActionType.LAUNCH_APP) {
            String pkg = spec.param("package");
            boolean allowed = AppLaunchPolicy.allows(allowedApps, pkg);
            if (!allowed) {
                audit(taskId, actionId, "preflight", "intent", epoch, "APP_NOT_ALLOWED", null);
                return ExecResult.reject("APP_NOT_ALLOWED", pkg, epoch);
            }
            ExecResult r = intentBridge.performLaunchApp(pkg, spec.timeoutMs);
            audit(taskId, actionId, "execute", "intent", epoch, r.code, r.toLogJson());
            return r;
        }

        // 屏幕类动作只对"别人的应用"有意义。聊天界面在前台时，滑动、坐标点击、找文字
        // 全都作用在 DSH 自己身上——真机上表现为"滑动已经生效"，其实滑的是聊天气泡。
        if (touchesScreen(spec.type) && isOwnWindow(before)) {
            audit(taskId, actionId, "preflight", "accessibility", epoch, "SELF_WINDOW_FOCUSED", null);
            return ExecResult.fail("SELF_WINDOW_FOCUSED",
                    "当前前台是 DSH 自己（聊天界面），不是要操作的应用。"
                            + "请先把目标应用切到前台；聊天让位后会自动重试成功。", epoch, elapsed(start));
        }

        // 定位
        UiNode target = null;
        String locateInfo = null;
        boolean needLocate = spec.type == ActionSpec.ActionType.CLICK_NODE
                || spec.type == ActionSpec.ActionType.INPUT_TEXT
                || spec.type == ActionSpec.ActionType.READ_STATE
                || spec.type == ActionSpec.ActionType.FIND_NODE
                || spec.type == ActionSpec.ActionType.LONG_CLICK
                || spec.type == ActionSpec.ActionType.CLEAR_TEXT
                || (spec.type == ActionSpec.ActionType.SCROLL && spec.params.has("find"));
        if (needLocate) {
            LocateResult locate = locate(taskId, actionId, subGoal, spec, before);
            if (!locate.ok) {
                JSONObject d = new JSONObject();
                try {
                    d.put("message", "no_matching_node");
                    d.put("node_count", before == null ? 0 : before.nodeCount());
                } catch (JSONException ignored) {
                }
                audit(taskId, actionId, "locate", "accessibility", epoch, locate.code, d);
                return ExecResult.fail(locate.code, locate.detail, epoch, elapsed(start));
            }
            target = locate.node;
            locateInfo = locate.detail;
            if (spec.type == ActionSpec.ActionType.FIND_NODE) {
                JSONObject nodeSummary = new JSONObject();
                try {
                    nodeSummary.put("node_id", target.nodeId);
                    nodeSummary.put("class", target.className);
                    nodeSummary.put("clickable", target.clickable);
                    nodeSummary.put("editable", target.editable);
                } catch (JSONException ignored) {
                }
                audit(taskId, actionId, "locate", "accessibility", epoch, "NODE_FOUND", nodeSummary);
                return ExecResult.ok("NODE_FOUND", target.nodeId, epoch, elapsed(start));
            }
        }

        // Risk Gate
        RiskGate.Decision decision = riskGate.check(actionId, spec,
                spec.type.wireName() + " " + (target != null ? (target.text + "/" + target.resourceId) : ""));
        if (!decision.allow) {
            audit(taskId, actionId, "risk_gate", "none", epoch, "REJECTED_" + decision.reason, null);
            return ExecResult.reject("RISK_REJECTED", decision.reason, epoch);
        }
        if (decision.autoConfirmed) {
            audit(taskId, actionId, "risk_gate", "none", epoch, "AUTO_CONFIRMED_TEST_MODE", null);
        }

        // A confirmation can outlive a cancellation or deadline. Recheck before any side effect.
        if (cancelRequested != null && cancelRequested.get()) {
            return ExecResult.cancelled("USER_CANCEL", "cancel requested before execution", epoch);
        }
        if (System.currentTimeMillis() >= taskDeadlineMs) {
            return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached during confirmation", epoch, elapsed(start));
        }

        // 执行
        ExecResult result;
        switch (spec.type) {
            case CLICK_NODE:
                result = accessibilityBridge.performClick(target, spec.timeoutMs);
                break;
            case INPUT_TEXT:
                result = accessibilityBridge.performInput(target, spec.param("text"), spec.timeoutMs);
                break;
            case SCROLL: {
                UiNode scrollTarget = target != null ? target : null;
                result = accessibilityBridge.performScroll(scrollTarget, spec.param("direction"), spec.timeoutMs);
                break;
            }
            case WAIT_FOR: {
                result = waitForCondition(spec, cancelRequested, taskDeadlineMs);
                break;
            }
            case ASSERT_STATE: {
                boolean ok = pollCondition(spec.param("condition"), spec.timeoutMs,
                        cancelRequested, taskDeadlineMs);
                if (cancelRequested != null && cancelRequested.get()) {
                    result = ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
                } else if (System.currentTimeMillis() >= taskDeadlineMs) {
                    result = ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while checking condition",
                            epoch, elapsed(start));
                } else {
                    result = ok ? ExecResult.ok("ASSERT_OK", spec.param("condition"), epoch, elapsed(start))
                            : ExecResult.fail("ASSERT_FAILED", spec.param("condition"), epoch, elapsed(start));
                }
                break;
            }
            case RECOVER: {
                AgentAccessibilityService recoverSvc = AgentAccessibilityService.getInstance();
                boolean ok = recoverSvc != null && recoverSvc.globalBack();
                result = ok ? ExecResult.ok("RECOVER_BACK", "global back", epoch, elapsed(start))
                        : ExecResult.fail("RECOVER_FAILED", "global back failed", epoch, elapsed(start));
                break;
            }
            case PRESS_KEY: {
                String key = spec.param("key");
                int minSdk = AgentAccessibilityService.minSdkOf(key);
                if (minSdk < 0) {
                    result = ExecResult.reject("BAD_KEY", "unknown press_key: " + key, epoch);
                } else if (android.os.Build.VERSION.SDK_INT < minSdk) {
                    result = ExecResult.reject("UNSUPPORTED_SDK",
                            "press_key " + key + " requires API " + minSdk
                                    + ", device is " + android.os.Build.VERSION.SDK_INT, epoch);
                } else {
                    AgentAccessibilityService keySvc = AgentAccessibilityService.getInstance();
                    boolean ok = keySvc != null && keySvc.pressGlobalKey(key);
                    result = ok ? ExecResult.ok("KEY_OK", key, epoch, elapsed(start))
                            : ExecResult.fail("KEY_FAILED", "global action failed for key=" + key, epoch, elapsed(start));
                }
                break;
            }
            case LONG_CLICK: {
                AgentAccessibilityService lcSvc = AgentAccessibilityService.getInstance();
                boolean ok = lcSvc != null && target != null && lcSvc.longClick(target);
                result = ok ? ExecResult.ok("LONG_CLICK_OK", String.valueOf(target.resourceId), epoch, elapsed(start))
                        : ExecResult.fail("LONG_CLICK_FAILED", "long click failed", epoch, elapsed(start));
                break;
            }
            case CLEAR_TEXT: {
                AgentAccessibilityService ctSvc = AgentAccessibilityService.getInstance();
                boolean ok = ctSvc != null && target != null && ctSvc.clearText(target);
                result = ok ? ExecResult.ok("CLEAR_TEXT_OK", String.valueOf(target.resourceId), epoch, elapsed(start))
                        : ExecResult.fail("CLEAR_TEXT_FAILED", "clear text failed", epoch, elapsed(start));
                break;
            }
            case WAIT_MS: {
                long ms = Math.min(Math.max(spec.params.optLong("ms", 0), 0), 60000);
                long waitDeadlineMs = System.currentTimeMillis() + ms;
                try {
                    while (true) {
                        if (cancelRequested != null && cancelRequested.get()) {
                            result = ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
                            break;
                        }
                        long now = System.currentTimeMillis();
                        if (now >= taskDeadlineMs) {
                            result = ExecResult.timeout("TASK_TIMEOUT", "task deadline reached",
                                    epoch, elapsed(start));
                            break;
                        }
                        long waitRemainingMs = waitDeadlineMs - now;
                        if (waitRemainingMs <= 0) {
                            result = ExecResult.ok("WAIT_MS_OK", ms + "ms", epoch, elapsed(start));
                            break;
                        }
                        long taskRemainingMs = taskDeadlineMs - now;
                        long sleepMs = Math.min(waitRemainingMs, Math.min(50L, taskRemainingMs));
                        if (sleepMs <= 0) {
                            result = ExecResult.timeout("TASK_TIMEOUT", "task deadline reached",
                                    epoch, elapsed(start));
                            break;
                        }
                        Thread.sleep(sleepMs);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    result = ExecResult.cancelled("INTERRUPTED", "wait_ms interrupted", epoch);
                }
                break;
            }
            case CLOSE_SYSTEM_DIALOGS: {
                result = intentBridge.closeSystemDialogs();
                break;
            }
            case TAP_TEXT: {
                result = tapText(spec, cancelRequested, taskDeadlineMs, epoch, start);
                break;
            }
            case SWIPE: {
                result = doSwipe(spec, epoch, start);
                break;
            }
            case NOTIFICATIONS: {
                if (!com.agentruntime.notify.AgentNotificationListener.connected()) {
                    result = ExecResult.reject("NEED_NOTIFICATION_ACCESS",
                            "通知使用权未授权：在系统设置中为 AgentRuntime 打开“通知使用权”后重试"
                                    + "（page=notification_access）", epoch);
                } else {
                    org.json.JSONObject snap = com.agentruntime.notify.AgentNotificationListener
                            .snapshot(spec.params.optInt("limit", 20), spec.param("package"));
                    // 审计里不落通知正文（隐私），只留条数，便于区分"没有通知"和"监听没连上"。
                    android.util.Log.i("AgentNotify", "listed count=" + snap.optInt("count")
                            + " cached=" + snap.optInt("total_cached"));
                    result = ExecResult.ok("NOTIFICATIONS_LISTED", snap.toString(), epoch, elapsed(start));
                }
                break;
            }
            case TAP_XY: {
                result = doTapXy(spec, epoch, start);
                break;
            }
            case SWIPE_XY: {
                result = doSwipeXy(spec, epoch, start);
                break;
            }
            case LONG_PRESS_XY: {
                result = doLongPressXy(spec, epoch, start);
                break;
            }
            case SCREENSHOT: {
                result = doScreenshot(spec, epoch, start);
                break;
            }
            case VISUAL_TAP: {
                result = doVisualTap(spec, epoch, start);
                break;
            }
            case READ_PAGE: {
                ExtendedCapabilities caps = this.capabilities;
                if (caps == null || caps.browser == null) {
                    result = ExecResult.reject("CAPABILITY_MISSING", "browser controller not attached", epoch);
                } else {
                    result = caps.browser.readPage(spec.params.optInt("max_chars", 2000), stateEngine.current());
                }
                break;
            }
            case NAVIGATE: {
                ExtendedCapabilities caps = this.capabilities;
                if (caps == null || caps.browser == null) {
                    result = ExecResult.reject("CAPABILITY_MISSING", "browser controller not attached", epoch);
                } else {
                    boolean internal = spec.params.optBoolean("internal", true);
                    result = caps.browser.navigate(spec.param("url"), internal, spec.timeoutMs);
                }
                break;
            }
            case PAGE_CLICK: {
                ExtendedCapabilities caps = this.capabilities;
                if (caps == null || caps.browser == null) {
                    result = ExecResult.reject("CAPABILITY_MISSING", "browser controller not attached", epoch);
                } else {
                    result = caps.browser.clickSelector(spec.param("selector"));
                }
                break;
            }
            case PAGE_FIND: {
                ExtendedCapabilities caps = this.capabilities;
                if (caps == null || caps.browser == null) {
                    result = ExecResult.reject("CAPABILITY_MISSING", "browser controller not attached", epoch);
                } else {
                    result = caps.browser.findOnPage(spec.param("text"), stateEngine.current());
                }
                break;
            }
            case OBSERVE: {
                ExtendedCapabilities caps = this.capabilities;
                if (caps == null || caps.envProbe == null) {
                    result = ExecResult.reject("CAPABILITY_MISSING", "environment probe not attached", epoch);
                } else {
                    com.agentruntime.env.EnvironmentSnapshot snap = caps.envProbe.refresh();
                    result = ExecResult.ok("OBSERVE_OK", snap.toJson().toString(), epoch, elapsed(start));
                }
                break;
            }
            // ------------------------------------------ V1.3 系统底层工具
            case SET_VOLUME: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.setVolume(spec.param("stream"), spec.params.optInt("level", 50));
                break;
            }
            case GET_VOLUME: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.getVolume(spec.param("stream"));
                break;
            }
            case SET_BRIGHTNESS: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.setBrightness(spec.params.optInt("percent", 50));
                break;
            }
            case CLIPBOARD_SET: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.setClipboard(spec.param("text"));
                break;
            }
            case CLIPBOARD_GET: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.getClipboard();
                break;
            }
            case MEDIA_KEY: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.mediaKey(spec.param("key"));
                break;
            }
            case VIBRATE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.vibrate(spec.params.optLong("ms", 100));
                break;
            }
            case OPEN_SETTINGS_PAGE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.openSettingsPage(spec.param("page"), spec.param("package"));
                break;
            }
            case LIST_APPS: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.listApps(spec.param("filter"), spec.params.optInt("limit", 50));
                break;
            }
            case READ_FILE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.readFile(spec.param("path"), spec.params.optInt("max_bytes", 65536));
                break;
            }
            case WRITE_FILE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.writeFile(spec.param("path"), spec.param("content"));
                break;
            }
            case APPEND_FILE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.appendFile(spec.param("path"), spec.param("content"));
                break;
            }
            case DELETE_FILE: {
                com.agentruntime.device.SystemBridge sys = systemBridge();
                result = sys == null
                        ? ExecResult.reject("CAPABILITY_MISSING", "system bridge not attached", epoch)
                        : sys.deleteFile(spec.param("path"));
                break;
            }
            // ------------------------------------------ V1.3 输入法注入通道
            case IME_COMMIT: {
                result = doImeCommit(spec, epoch, start);
                break;
            }
            case IME_READ: {
                if (!com.agentruntime.ime.AgentImeService.isRunning()) {
                    result = ExecResult.reject("IME_NOT_ACTIVE",
                            "Agent 输入法未启用（设置里启用并切换后可用）", epoch);
                } else {
                    String text = com.agentruntime.ime.AgentImeService.readText(
                            spec.params.optInt("max_chars", 4096));
                    result = text == null
                            ? ExecResult.fail("IME_NO_CONNECTION", "当前没有输入框获得焦点", epoch, elapsed(start))
                            : ExecResult.ok("IME_TEXT", text, epoch, elapsed(start));
                }
                break;
            }
            case IME_KEY: {
                if (!com.agentruntime.ime.AgentImeService.isRunning()) {
                    result = ExecResult.reject("IME_NOT_ACTIVE", "Agent 输入法未启用", epoch);
                } else {
                    String err = com.agentruntime.ime.AgentImeService.sendEditAction(spec.param("key"));
                    result = err == null
                            ? ExecResult.ok("IME_KEY_OK", spec.param("key"), epoch, elapsed(start))
                            : ExecResult.fail(err, "ime_key failed", epoch, elapsed(start));
                }
                break;
            }
            case READ_STATE: {
                String value = target.text != null ? target.text : target.contentDescription;
                result = ExecResult.ok("STATE_READ", value, epoch, elapsed(start));
                break;
            }
            case READ_SCREEN: {
                result = readScreen(before, spec, epoch, start);
                break;
            }
            case INPUT_FOCUSED: {
                result = inputFocused(before, spec, epoch, start);
                break;
            }
            case REQUEST_CONFIRMATION: {
                result = decision.allow ? ExecResult.ok("CONFIRMED", decision.reason, epoch, elapsed(start))
                        : ExecResult.reject("CONFIRM_REJECTED", decision.reason, epoch);
                break;
            }
            default:
                result = ExecResult.reject("UNSUPPORTED", spec.type.wireName(), epoch);
        }

        // Verify
        if (result.ok() && spec.postcondition != null) {
            boolean verified = verifyPostcondition(spec.postcondition, spec.timeoutMs,
                    cancelRequested, taskDeadlineMs);
            if (cancelRequested != null && cancelRequested.get()) {
                result = ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
            } else if (System.currentTimeMillis() >= taskDeadlineMs) {
                result = ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while verifying",
                        epoch, elapsed(start));
            } else if (!verified) {
                result = ExecResult.fail("POSTCONDITION_FAILED", spec.postcondition, epoch, elapsed(start));
            }
        }
        if (locateInfo != null && result != null) {
            result = new ExecResult(result.status, result.code,
                    (result.detail != null ? result.detail + " [" + locateInfo + "]" : locateInfo),
                    result.stateEpoch, result.durationMs);
        }

        audit(taskId, actionId, "execute", "accessibility", stateEngine.currentEpoch(), result.code, result.toLogJson());
        return result;
    }

    // ------------------------------------------------- V1.1 坐标与视觉动作

    /** 屏幕尺寸：优先取真实显示尺寸，其次用环境快照，最后兜底 0（调用方据此拒绝执行）。 */
    private int[] screenSize() {
        if (appContext != null) {
            int[] size = com.agentruntime.vision.ScreenCapturer.screenSize(appContext);
            if (size[0] > 0 && size[1] > 0) return size;
        }
        ExtendedCapabilities c = capabilities;
        if (c != null && c.envProbe != null) {
            com.agentruntime.env.EnvironmentSnapshot s = c.envProbe.snapshot();
            if (s.hasValidScreen()) return new int[]{s.screenWidth, s.screenHeight};
        }
        return new int[]{0, 0};
    }

    private com.agentruntime.vision.GestureBridge gestureBridge() {
        ExtendedCapabilities c = capabilities;
        if (c == null || c.gestures == null) return null;
        return c.gestures;
    }

    private com.agentruntime.device.SystemBridge systemBridge() {
        ExtendedCapabilities c = capabilities;
        return c == null ? null : c.system;
    }

    /** 通过 Agent 输入法注入文本：WebView / 自绘控件里唯一可靠的通道。 */
    private ExecResult doImeCommit(ActionSpec spec, long epoch, long start) {
        if (!com.agentruntime.ime.AgentImeService.isRunning()) {
            return ExecResult.reject("IME_NOT_ACTIVE",
                    "Agent 输入法未启用：设置里启用后，用系统输入法选择器切换过来", epoch);
        }
        String text = spec.param("text");
        String err = com.agentruntime.ime.AgentImeService.commitText(text);
        if (err == null) {
            return ExecResult.ok("IME_COMMIT_OK",
                    (text == null ? 0 : text.length()) + " chars", epoch, elapsed(start));
        }
        if ("NO_INPUT_CONNECTION".equals(err)) {
            return ExecResult.fail("IME_NO_CONNECTION",
                    "没有输入框获得焦点，先点一下目标输入框", epoch, elapsed(start));
        }
        return ExecResult.fail(err, "ime commit failed", epoch, elapsed(start));
    }

    private ExecResult doTapXy(ActionSpec spec, long epoch, long start) {
        com.agentruntime.vision.GestureBridge g = gestureBridge();
        if (g == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "gesture bridge not attached", epoch);
        }
        int[] size = screenSize();
        if (size[0] <= 0) {
            return ExecResult.fail("NO_SCREEN_SIZE", "cannot resolve screen size", epoch, elapsed(start));
        }
        int x = spec.params.optInt("x", -1);
        int y = spec.params.optInt("y", -1);
        return g.tap(x, y, size[0], size[1], spec.timeoutMs);
    }

    private ExecResult doSwipeXy(ActionSpec spec, long epoch, long start) {
        com.agentruntime.vision.GestureBridge g = gestureBridge();
        if (g == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "gesture bridge not attached", epoch);
        }
        int[] size = screenSize();
        if (size[0] <= 0) {
            return ExecResult.fail("NO_SCREEN_SIZE", "cannot resolve screen size", epoch, elapsed(start));
        }
        return g.swipe(
                spec.params.optInt("x1", -1), spec.params.optInt("y1", -1),
                spec.params.optInt("x2", -1), spec.params.optInt("y2", -1),
                size[0], size[1],
                spec.params.optLong("duration_ms", 300), spec.timeoutMs);
    }

    /**
     * 方向滑动：模型只给方向，不必自己算像素。
     *
     * <p>起点默认屏幕中心，行程按屏幕长边比例换算，终点与起点关于中心对称，
     * 这样既不会把边缘手势区（返回/回桌面）误触发，也不会点偏到导航栏。
     */
    private ExecResult doSwipe(ActionSpec spec, long epoch, long start) {
        com.agentruntime.vision.GestureBridge g = gestureBridge();
        if (g == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "gesture bridge not attached", epoch);
        }
        int[] size = screenSize();
        if (size[0] <= 0 || size[1] <= 0) {
            return ExecResult.fail("NO_SCREEN_SIZE", "cannot resolve screen size", epoch, elapsed(start));
        }
        String dir = spec.param("direction");
        if (dir == null || dir.isEmpty()) dir = "up";
        int pct = Math.max(10, Math.min(90, spec.params.optInt("distance_pct", 60)));
        boolean vertical = dir.equals("up") || dir.equals("down");
        int span = vertical ? size[1] : size[0];
        int half = Math.max(20, Math.round(span * pct / 200f));
        int cx = spec.params.has("x") ? spec.params.optInt("x", size[0] / 2) : size[0] / 2;
        int cy = spec.params.has("y") ? spec.params.optInt("y", size[1] / 2) : size[1] / 2;
        int x1 = cx, y1 = cy, x2 = cx, y2 = cy;
        switch (dir) {
            case "up":
                y1 = cy + half;
                y2 = cy - half;
                break;
            case "down":
                y1 = cy - half;
                y2 = cy + half;
                break;
            case "left":
                x1 = cx + half;
                x2 = cx - half;
                break;
            default: // right
                x1 = cx - half;
                x2 = cx + half;
                break;
        }
        return g.swipe(x1, y1, x2, y2, size[0], size[1],
                spec.params.optLong("duration_ms", 300), spec.timeoutMs);
    }

    private ExecResult doLongPressXy(ActionSpec spec, long epoch, long start) {
        com.agentruntime.vision.GestureBridge g = gestureBridge();
        if (g == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "gesture bridge not attached", epoch);
        }
        int[] size = screenSize();
        if (size[0] <= 0) {
            return ExecResult.fail("NO_SCREEN_SIZE", "cannot resolve screen size", epoch, elapsed(start));
        }
        return g.longPress(
                spec.params.optInt("x", -1), spec.params.optInt("y", -1),
                size[0], size[1],
                spec.params.optLong("hold_ms", 800), spec.timeoutMs);
    }

    /**
     * 把当前界面的可见文字读成一段文本，供模型直接决策。
     *
     * <p>之前只有 sys.observe（设备电量/网络），模型想看界面就只能截图，而截图又拿不到像素，
     * 于是"能点不能读"。这里用无障碍树本身作为读通道：文字、可点击位置、可输入框、滚动容器。
     * 读不到根节点时明确报 A11Y_ROOT_UNAVAILABLE，而不是返回一条空成功。
     */
    private ExecResult readScreen(WorldState state, ActionSpec spec, long epoch, long start) {
        if (state == null || state.root == null) {
            return ExecResult.fail("A11Y_ROOT_UNAVAILABLE",
                    "无障碍服务在运行，但没有返回界面节点。请到 系统设置 → 无障碍 → DSH Mobile 确认开关为开启；"
                            + "部分厂商系统（小米/OPPO/vivo/华为）还需要允许「受限设置」，或把目标应用切到前台后重试。",
                    epoch, elapsed(start));
        }
        int maxChars = spec.params.optInt("max_chars", 4000);
        if (maxChars < 400) maxChars = 400;
        if (maxChars > 8000) maxChars = 8000;

        // 读到自己头上是最隐蔽的失败：聊天气泡里也有"热门""发送"，点下去当然不可交互。
        String own = appContext == null ? null : appContext.getPackageName();
        if (own != null && own.equals(state.foregroundPackage)) {
            return ExecResult.fail("SELF_WINDOW_FOCUSED",
                    "当前前台是 DSH 自己的聊天界面，不是要操作的应用。请先把目标应用切到前台，再读屏。",
                    epoch, elapsed(start));
        }

        StringBuilder body = new StringBuilder();
        int shown = 0;
        java.util.List<UiNode> nodes = state.root.flat();
        for (UiNode node : nodes) {
            String label = node.text != null && !node.text.trim().isEmpty()
                    ? node.text.trim() : node.contentDescription;
            boolean hasLabel = label != null && !label.trim().isEmpty();
            if (!hasLabel && !node.editable && !node.scrollable) continue;
            if (!node.visible) continue;

            StringBuilder line = new StringBuilder();
            line.append(hasLabel ? "「" + clipText(label.trim(), 120) + "」" : "[" + simpleClass(node.className) + "]");
            if (node.editable) line.append(node.focused ? " 输入框(已聚焦)" : " 输入框");
            if (node.clickable) line.append(" 可点");
            if (node.scrollable) line.append(" 可滚动");
            if (node.left >= 0 && node.bottom > node.top) {
                line.append(" @").append(node.left).append(',').append(node.top)
                        .append('-').append(node.right).append(',').append(node.bottom);
            }
            if (body.length() + line.length() + 1 > maxChars) {
                body.append("\n…（已达 ").append(maxChars).append(" 字上限，先滚动或缩小范围再读）");
                break;
            }
            body.append('\n').append(line);
            shown++;
        }
        if (shown == 0) {
            return ExecResult.ok("SCREEN_EMPTY",
                    "界面读到了，但没有可读文字或可点节点（package=" + state.foregroundPackage
                            + "，节点数=" + state.nodeCount() + "）。可能是自绘界面、启动广告页或正在加载。",
                    epoch, elapsed(start));
        }
        String header = "package=" + state.foregroundPackage
                + " window=" + (state.windowTitle == null ? "-" : clipText(state.windowTitle, 40))
                + " 节点=" + state.nodeCount() + " 可读=" + shown;
        return ExecResult.ok("SCREEN_TEXT", header + body, epoch, elapsed(start));
    }

    /**
     * 往当前聚焦/第一个可编辑输入框写字。
     *
     * <p>真实应用（微信、短信、搜索框）几乎都靠"先点输入框，再打字"。
     * 让模型每次都先 read_screen 找 node id 再 input_text 太脆；这里直接用焦点状态落笔，
     * 与真人操作顺序一致：tap_text 点输入框 → type_text 写字。
     */
    private ExecResult inputFocused(WorldState state, ActionSpec spec, long epoch, long start) {
        if (state == null || state.root == null) {
            return ExecResult.fail("A11Y_ROOT_UNAVAILABLE", "读不到界面节点，无法确定输入框", epoch, elapsed(start));
        }
        UiNode field = null;
        java.util.List<UiNode> nodes = state.root.flat();
        if (state.focusNodeId != null) {
            for (UiNode node : nodes) if (state.focusNodeId.equals(node.nodeId) && node.editable) { field = node; break; }
        }
        if (field == null) for (UiNode node : nodes) if (node.editable && node.visible) { field = node; break; }
        if (field == null) {
            return ExecResult.fail("NO_INPUT_FIELD",
                    "当前界面没有可编辑输入框。先点一下要输入的框（sys.tap_text），再调用 sys.type_text。",
                    epoch, elapsed(start));
        }
        ExecResult result = accessibilityBridge.performInput(field, spec.param("text"), spec.timeoutMs);
        return result;
    }

    /** 这些动作都在"当前屏幕"上生效，前台是谁决定了它打到谁身上。 */
    private static boolean touchesScreen(ActionSpec.ActionType type) {
        switch (type) {
            case TAP_TEXT: case READ_SCREEN: case SCREENSHOT: case VISUAL_TAP:
            case TAP_XY: case SWIPE_XY: case LONG_PRESS_XY: case SWIPE:
            case CLICK_NODE: case LONG_CLICK: case FIND_NODE: case READ_STATE:
            case INPUT_TEXT: case INPUT_FOCUSED: case CLEAR_TEXT: case WAIT_FOR:
            case SCROLL:
                return true;
            default:
                return false;
        }
    }

    private boolean isOwnWindow(WorldState state) {
        if (state == null || appContext == null) return false;
        String own = appContext.getPackageName();
        return own != null && own.equals(state.foregroundPackage);
    }

    private static String simpleClass(String className) {
        if (className == null) return "node";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String clipText(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 截图并落盘留证：审计里只存路径与体积，不存二进制。 */
    private ExecResult doScreenshot(ActionSpec spec, long epoch, long start) {
        ExtendedCapabilities c = capabilities;
        if (c == null || c.capturer == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "screen capturer not attached", epoch);
        }
        com.agentruntime.vision.ScreenFrame frame =
                c.capturer.capture(spec.params.optInt("max_edge", 1024),
                        spec.params.optInt("quality", 70));
        if (frame == null || !frame.valid()) {
            return ExecResult.fail("CAPTURE_FAILED",
                    "screenshot unavailable (API<30 or a11y not connected)", epoch, elapsed(start));
        }
        String path = "";
        try {
            if (appContext != null) {
                java.io.File dir = new java.io.File(appContext.getFilesDir(), "shots");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(frame.imageBytes);
                }
                path = f.getAbsolutePath();
            }
        } catch (Throwable t) {
            path = "save_failed:" + t.getClass().getSimpleName();
        }
        return ExecResult.ok("SHOT_OK", frame.describe() + " path=" + path
                + "\n说明：截图只保存在本机用于留证，当前会话不会把像素回传给模型，"
                + "所以不要靠截图读内容；要读界面文字请调用 sys.read_screen。", epoch, elapsed(start));
    }

    /**
     * 视觉定位后点击：视觉模型优先，节点兜底，两者都失败才报错。
     * 定位来源会写进 detail，便于事后判断是"看的"还是"找的"。
     */
    private ExecResult doVisualTap(ActionSpec spec, long epoch, long start) {
        ExtendedCapabilities c = capabilities;
        if (c == null || c.grounder == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "visual grounder not attached", epoch);
        }
        com.agentruntime.vision.GestureBridge g = gestureBridge();
        if (g == null) {
            return ExecResult.reject("CAPABILITY_MISSING", "gesture bridge not attached", epoch);
        }
        int[] size = screenSize();
        if (size[0] <= 0) {
            return ExecResult.fail("NO_SCREEN_SIZE", "cannot resolve screen size", epoch, elapsed(start));
        }
        String target = spec.param("target");
        boolean allowVision = spec.params.optBoolean("allow_vision", true);
        com.agentruntime.vision.VisualGrounder.GroundResult gr =
                c.grounder.ground(target, stateEngine.current(), allowVision);
        if (!gr.ok) {
            return ExecResult.fail(gr.code, gr.detail, epoch, elapsed(start));
        }
        ExecResult tap = g.tap(gr.x, gr.y, size[0], size[1], spec.timeoutMs);
        if (tap.ok()) {
            return ExecResult.ok(tap.code, tap.detail + " [" + gr.code + " " + gr.detail + "]",
                    epoch, elapsed(start));
        }
        return tap;
    }

    private static final class LocateResult {
        boolean ok;
        UiNode node;
        String code;
        String detail;
        JSONObject dump;
    }

    /**
     * 按可见文本点击：当前界面找不到就滚动再找。
     *
     * <p>真实应用的入口常藏在抽屉或首屏之外（实测 NewPipe 的「设置」在导航抽屉里，
     * 用 click_node 定位只会 NODE_NOT_FOUND，用 wait_for 等只会 WAIT_TIMEOUT）。
     * 「找 + 滚动 + 点」三件事必须是一个原子动作，否则规划器要为每种应用拼手势。
     *
     * <p>匹配顺序：精确文本可点击 → 包含文本可点击 → 精确文本 → 包含文本；
     * 命中的若是不可点击的文本节点，改点"包含该节点且面积最小的可点击祖先"。
     */
    private ExecResult tapText(ActionSpec spec, AtomicBoolean cancelRequested,
                               long taskDeadlineMs, long epoch, long start) {
        String query = spec.param("text");
        if (query == null || query.trim().isEmpty()) {
            return ExecResult.fail("NO_TEXT", "params.text missing", epoch, elapsed(start));
        }
        boolean allowScroll = !"false".equalsIgnoreCase(spec.params.optString("scroll", "true"));
        int maxScrolls = Math.max(0, Math.min(5, spec.params.optInt("max_scrolls", 2)));
        String direction = spec.params.optString("direction", "down");
        long settleMs = Math.max(300, Math.min(3000, spec.params.optLong("settle_ms", 800)));

        String dismissed = null;
        for (int attempt = 0; attempt <= maxScrolls; attempt++) {
            if (cancelRequested != null && cancelRequested.get()) {
                return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
            }
            if (System.currentTimeMillis() >= taskDeadlineMs) {
                return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while tapping text",
                        epoch, elapsed(start));
            }
            WorldState state = snapshotWithGrace("tap_text", 10000);
            if (state == null || state.root == null) {
                // 读不到任何窗口树：这是无障碍连接的问题，不是"文字不存在"。
                // 报 NODE_NOT_FOUND 会让人以为要换文案，实际要重新绑定无障碍服务。
                logTap("root-unavailable");
                return ExecResult.fail("A11Y_ROOT_UNAVAILABLE",
                        "accessibility root unavailable (service bound but no window tree);"
                                + " rebind accessibility service", epoch, elapsed(start));
            }
            String own = appContext == null ? null : appContext.getPackageName();
            if (own != null && own.equals(state.foregroundPackage)) {
                // 在自己的聊天界面里找"热门"，命中的是气泡文字，点了必然不可交互。
                logTap("self-window");
                return ExecResult.fail("SELF_WINDOW_FOCUSED",
                        "当前前台是 DSH 自己的聊天界面，不是目标应用；先把目标应用切到前台再点",
                        epoch, elapsed(start));
            }
            UiNode node = pickByText(state, query);
            logTap("attempt=" + attempt + " root=" + rootBounds(state)
                    + " nodes=" + (state == null || state.root == null ? 0 : state.root.flat().size())
                    + " hit=" + (node == null ? "-" : node.nodeId));
            if (node != null) {
                ExecResult r = accessibilityBridge.performClick(node, spec.timeoutMs);
                if (r.ok()) {
                    return ExecResult.ok("TAP_TEXT_OK",
                            query + " -> " + node.nodeId + " (scrolls=" + attempt
                                    + (dismissed == null ? "" : ", dismissed=" + dismissed) + ")",
                            epoch, elapsed(start));
                }
                return r;
            }
            // 目标被模态弹窗挡住（首启公告/更新提示）时，先关掉它再重新找；只看一次，避免反复点按钮。
            if (dismissed == null) {
                UiNode close = dismissBlockingDialog(state);
                logTap("dismiss-candidate=" + (close != null));
                if (close != null) {
                    dismissed = close.text != null && !close.text.trim().isEmpty()
                            ? close.text.trim() : close.contentDescription;
                    boolean clicked = accessibilityBridge.performClick(close, spec.timeoutMs).ok();
                    logTap("dismiss-click ok=" + clicked);
                    if (clicked) {
                        if (!sleepQuietly(settleMs)) {
                            return ExecResult.cancelled("INTERRUPTED", "tap_text interrupted", epoch);
                        }
                        continue;
                    }
                }
            }
            if (!allowScroll || attempt == maxScrolls) break;
            // 多个可滚动容器时优先滚"最上层"的那个（抽屉/弹层是后加入树的），
            // 否则容易选中背景里的 ViewPager 并得到 SCROLL_FAILED（NewPipe 实测）。
            List<UiNode> scrollables = bottomUpScrollables(state);
            if (scrollables.isEmpty()) break;
            UiNode target = scrollables.get(Math.min(attempt, scrollables.size() - 1));
            ExecResult scroll = accessibilityBridge.performScroll(target, direction, spec.timeoutMs);
            if (!scroll.ok()) break;
            if (!sleepQuietly(settleMs)) {
                return ExecResult.cancelled("INTERRUPTED", "tap_text interrupted", epoch);
            }
        }
        return ExecResult.fail("NODE_NOT_FOUND",
                "text not found after " + maxScrolls + " scroll(s): " + query
                        + (dismissed == null ? "" : " (dismissed=" + dismissed + ")"),
                epoch, elapsed(start));
    }

    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 挡路弹窗里允许自动点击的按钮文案：只认无副作用的确认/关闭，绝不包含授权类。 */
    private static final List<String> SAFE_DIALOG_LABELS = java.util.Arrays.asList(
            "ok", "okay", "got it", "continue", "close", "dismiss", "no thanks",
            "确定", "知道了", "明白了", "关闭", "继续");

    static boolean isSafeDialogLabel(String label) {
        if (label == null) return false;
        String l = label.trim().toLowerCase(java.util.Locale.ROOT);
        return !l.isEmpty() && SAFE_DIALOG_LABELS.contains(l);
    }

    /**
     * 当前窗口是不是一个平台对话框（首启公告、更新提示这类模态窗）。
     *
     * <p>判据是<b>结构标记</b>而不是"窗口比屏幕小"：实测 AVD 上应用可见区域本身就只有
     * 720x1012（去掉状态栏/导航栏），任何普通前台界面都会被"非全屏"误判成对话框。
     * 平台 AlertDialog 的 android:id/parentPanel、button1 等 id 应用自己是不会用的。
     */
    static boolean isDialogWindow(UiNode root) {
        if (root == null) return false;
        for (UiNode n : root.flat()) {
            String cls = n.className == null ? "" : n.className.toLowerCase(java.util.Locale.ROOT);
            if (cls.contains("dialog")) return true;
            String rid = n.resourceId;
            if (rid == null) continue;
            if (rid.endsWith(":id/parentPanel") || rid.endsWith(":id/buttonPanel")
                    || rid.endsWith(":id/alertTitle") || rid.endsWith(":id/button1")
                    || rid.endsWith(":id/button2")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目标文本找不到、且当前窗口是挡路对话框时，返回可安全点击的关闭按钮；没有则 null。
     *
     * <p>只点 OK/Got it/Continue/Close 这类确认，授权类按钮（Allow/允许）必须由人点，
     * 因此这里用白名单精确匹配，而不是"随便点一个按钮"。
     */
    static UiNode dismissBlockingDialog(WorldState state) {
        if (state == null || state.root == null) return null;
        if (!isDialogWindow(state.root)) return null;
        for (UiNode n : state.root.flat()) {
            if (!n.visible || !n.clickable || !n.enabled) continue;
            if (isSafeDialogLabel(n.text) || isSafeDialogLabel(n.contentDescription)) return n;
        }
        return null;
    }

    /** 可滚动节点，按"最上层优先"返回（树序靠后的是后加入的抽屉/弹层）。 */
    static List<UiNode> bottomUpScrollables(WorldState state) {
        List<UiNode> out = new ArrayList<>();
        if (state == null || state.root == null) return out;
        for (UiNode n : state.root.flat()) {
            if (n.scrollable && n.visible) out.add(n);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** 设备端排查用：tap_text 每一步快照/命中/关闭弹窗都留一条日志（logcat -s TapText）。 */
    private static void logTap(String msg) {
        try {
            android.util.Log.i("TapText", msg);
        } catch (Throwable ignored) {
        }
    }

    private static String rootBounds(WorldState state) {
        if (state == null || state.root == null) return "null";
        return "[" + state.root.left + "," + state.root.top + ","
                + state.root.right + "," + state.root.bottom + "]";
    }

    /** 在整棵树里挑最像用户想点的那个节点（不依赖坐标，也不做模糊的语义猜测）。 */
    /**
     * 按可见文字挑节点，支持 {@code "A|B"} 多候选（中文界面/英文界面的同一入口）。
     *
     * <p>系统设置这类页面在不同 ROM 上叫法不同（强行停止 / Force stop），
     * 只认一个写法等于把技能绑死在一种语言上。
     */
    static UiNode pickByText(WorldState state, String query) {
        if (state == null || state.root == null) return null;
        List<String> needles = new ArrayList<>();
        if (query != null) for (String raw : query.split("\\|")) {
            String needle = raw.trim();
            if (!needle.isEmpty()) needles.add(needle);
        }
        if (needles.isEmpty()) return null;
        List<UiNode> clickables = new ArrayList<>();
        UiNode exactClickable = null;
        UiNode exactAny = null;
        UiNode containsClickable = null;
        UiNode containsAny = null;
        for (UiNode n : state.root.flat()) {
            if (!n.visible) continue;
            if (n.clickable) clickables.add(n);
            boolean exact = false, contains = false;
            for (String needle : needles) {
                if (n.matchesText(needle)) { exact = true; break; }
                if (n.containsText(needle)) contains = true;
            }
            if (!exact && !contains) continue;
            if (exact && n.clickable) {
                if (exactClickable == null) exactClickable = n;
            } else if (exact) {
                if (exactAny == null) exactAny = n;
            } else if (n.clickable) {
                if (containsClickable == null) containsClickable = n;
            } else if (containsAny == null) {
                containsAny = n;
            }
        }
        UiNode chosen = exactClickable != null ? exactClickable
                : containsClickable != null ? containsClickable
                : exactAny != null ? exactAny : containsAny;
        if (chosen == null || chosen.clickable) return chosen;
        return smallestClickableContaining(clickables, chosen);
    }

    /** 文本节点自己不可点时，点包含它的最小可点击容器。 */
    private static UiNode smallestClickableContaining(List<UiNode> clickables, UiNode target) {
        UiNode best = null;
        long bestArea = Long.MAX_VALUE;
        for (UiNode c : clickables) {
            if (c.left > target.left || c.top > target.top
                    || c.right < target.right || c.bottom < target.bottom) {
                continue;
            }
            long area = (long) (c.right - c.left) * (c.bottom - c.top);
            if (area <= 0 || area >= bestArea) continue;
            bestArea = area;
            best = c;
        }
        return best != null ? best : target;
    }

    private LocateResult locate(String taskId, String actionId, String subGoal, ActionSpec spec, WorldState state) {
        LocateResult r = new LocateResult();
        JSONObject find = spec.params.optJSONObject("find");
        if (find == null) {
            r.code = "NO_FIND_SPEC";
            r.detail = "params.find missing";
            return r;
        }
        String resId = find.optString("resource_id", null);
        String text = find.optString("text", null);
        String desc = find.optString("desc", null);
        String contains = find.optString("contains", null);
        String className = find.optString("class_name", null);
        boolean needClickable = find.optBoolean("clickable", false);
        boolean needEditable = find.optBoolean("editable", false);

        List<UiNode> hits = new ArrayList<>();
        if (state != null && state.root != null) {
            for (UiNode n : state.root.flat()) {
                if (!matchesStructure(n, resId, text, desc, contains, className, needClickable, needEditable)) continue;
                hits.add(n);
            }
        }

        String locateSource = "primary";
        if (hits.isEmpty()) {
            // 候选替代定位（Self-Healing）：放宽身份约束（resId/text 可能因页面改版失效），保留结构约束
            if (text != null && state != null) {
                for (UiNode n : state.findNodes(x -> x.matchesText(text) && x.visible
                        && matchesStructure(x, null, null, null, null, className, needClickable, needEditable))) {
                    hits.add(n);
                }
                if (!hits.isEmpty()) locateSource = "fallback_text";
            }
        }
        if (hits.isEmpty()) {
            if (contains != null && state != null) {
                for (UiNode n : state.findNodes(x -> x.containsText(contains) && x.visible
                        && matchesStructure(x, null, null, null, null, className, needClickable, needEditable))) {
                    hits.add(n);
                }
                if (!hits.isEmpty()) locateSource = "fallback_contains";
            }
        }
        if (hits.isEmpty()) {
            r.code = "NODE_NOT_FOUND";
            r.detail = "no matching node";
            return r;
        }
        if (hits.size() == 1) {
            r.ok = true;
            r.node = hits.get(0);
            r.detail = locateSource;
            return r;
        }

        // 多候选：System-1 在受限候选集上按语义目标选择
        String decisionTarget = text != null ? text : (contains != null ? contains : subGoal);
        List<System1.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            UiNode n = hits.get(i);
            String label = n.text != null ? n.text : (n.contentDescription != null ? n.contentDescription : n.resourceId);
            candidates.add(new System1.Candidate("c" + i, n.nodeId, label));
        }
        System1.Decision d = system1.decide(decisionTarget, state, candidates);
        if (d.choiceId == null || d.confidence < com.agentruntime.system1.RuleBasedSystem1.LOW_CONFIDENCE) {
            r.code = "LOW_CONFIDENCE";
            r.detail = "candidates=" + hits.size() + " confidence=" + d.confidence;
            return r;
        }
        int idx = Integer.parseInt(d.choiceId.substring(1));
        r.ok = true;
        r.node = hits.get(idx);
        r.detail = locateSource + "/system1:" + d.choiceId + "@" + String.format(java.util.Locale.US, "%.2f", d.confidence);
        return r;
    }

    private static boolean matchesStructure(UiNode n, String resId, String text, String desc,
                                            String contains, String className,
                                            boolean needClickable, boolean needEditable) {
        if (resId != null && !n.matchesResource(resId)) return false;
        if (text != null && !n.matchesText(text)) return false;
        if (desc != null && !n.matchesText(desc)) return false;
        if (contains != null && !n.containsText(contains)) return false;
        if (className != null && (n.className == null || !n.className.contains(className))) return false;
        if (needClickable && !n.clickable) return false;
        if (needEditable && !n.editable) return false;
        return n.visible;
    }

    private static JSONObject dumpTree(WorldState state) {
        JSONObject o = new JSONObject();
        try {
            if (state == null || state.root == null) {
                o.put("empty", true);
                return o;
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            java.util.List<UiNode> flat = state.root.flat();
            o.put("count", flat.size());
            for (int i = 0; i < flat.size() && i < 60; i++) {
                UiNode n = flat.get(i);
                String t = n.text != null ? n.text : (n.contentDescription != null ? n.contentDescription : "");
                if (t.length() > 16) t = t.substring(0, 16);
                String cls = n.className != null ? n.className : "";
                int dot = cls.lastIndexOf('.');
                arr.put((dot >= 0 ? cls.substring(dot + 1) : cls)
                        + "|" + (n.resourceId != null ? n.resourceId : "")
                        + "|" + t + "|c" + (n.clickable ? 1 : 0) + "v" + (n.visible ? 1 : 0));
            }
            o.put("nodes", arr);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * 读一次快照；读不到窗口树时在 graceMs 内重试。
     *
     * <p>窗口切换瞬间 root 可能为空，属于正常抖动；但无障碍连接半死时
     * （实测：宿主进程被 force-stop 后服务仍显示 Bound，root 持续为 null）
     * 重试也无效，由调用方按 A11Y_ROOT_UNAVAILABLE 报错。
     */
    private WorldState snapshotWithGrace(String source, long graceMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, graceMs);
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        while (true) {
            WorldState st = svc != null ? svc.snapshot(source) : stateEngine.current();
            if (st != null && st.root != null) return st;
            if (System.currentTimeMillis() >= deadline) return st;
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return st;
            }
        }
    }

    private ExecResult waitForCondition(ActionSpec spec, AtomicBoolean cancelRequested,
                                        long taskDeadlineMs) {
        long start = System.currentTimeMillis();
        long timeout = spec.timeoutMs;
        String pkg = spec.param("package");
        String uiContains = spec.param("ui_contains");
        long epoch = stateEngine.currentEpoch();
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        long actionDeadlineMs = start + timeout;
        try {
            while (true) {
                if (cancelRequested != null && cancelRequested.get()) {
                    return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
                }
                long now = System.currentTimeMillis();
                if (now >= taskDeadlineMs) {
                    return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while waiting",
                            epoch, elapsed(start));
                }
                if (now >= actionDeadlineMs) {
                    return waitTimeoutResult(pkg, uiContains, epoch, start);
                }
                if (svc != null) svc.snapshot("wait_for");
                if (cancelRequested != null && cancelRequested.get()) {
                    return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
                }
                now = System.currentTimeMillis();
                if (now >= taskDeadlineMs) {
                    return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while waiting",
                            epoch, elapsed(start));
                }
                if (now >= actionDeadlineMs) {
                    return waitTimeoutResult(pkg, uiContains, epoch, start);
                }
                WorldState st = stateEngine.current();
                if (st != null) {
                    boolean pkgOk = pkg == null || pkg.equals(st.foregroundPackage);
                    boolean uiOk = uiContains == null || st.containsText(uiContains);
                    if (pkgOk && uiOk) {
                        if (cancelRequested != null && cancelRequested.get()) {
                            return ExecResult.cancelled("USER_CANCEL", "cancel requested", epoch);
                        }
                        long completedAt = System.currentTimeMillis();
                        if (completedAt >= taskDeadlineMs) {
                            return ExecResult.timeout("TASK_TIMEOUT", "task deadline reached while waiting",
                                    epoch, elapsed(start));
                        }
                        if (completedAt >= actionDeadlineMs) {
                            return waitTimeoutResult(pkg, uiContains, epoch, start);
                        }
                        return ExecResult.ok("WAIT_OK", "pkg=" + st.foregroundPackage, epoch, elapsed(start));
                    }
                }
                long remainingMs = Math.min(actionDeadlineMs - System.currentTimeMillis(),
                        taskDeadlineMs - System.currentTimeMillis());
                if (remainingMs > 0) Thread.sleep(Math.min(50L, remainingMs));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.cancelled("INTERRUPTED", "wait_for interrupted", epoch);
        }
    }

    /**
     * wait_for 超时时的回执：区分"等不到界面"和"根本读不到窗口"。
     *
     * <p>无障碍连接半死时（服务仍显示 Bound，但 getRootInActiveWindow 持续返回 null），
     * 报 WAIT_TIMEOUT 会误导排查方向；这里回 A11Y_ROOT_UNAVAILABLE 并给出处置办法。
     */
    private ExecResult waitTimeoutResult(String pkg, String uiContains, long epoch, long start) {
        WorldState st = stateEngine.current();
        if (st == null || st.root == null) {
            return ExecResult.fail("A11Y_ROOT_UNAVAILABLE",
                    "accessibility root unavailable for the whole wait (service bound but no window tree);"
                            + " rebind accessibility service (pkg=" + pkg + ")",
                    epoch, elapsed(start));
        }
        return ExecResult.timeout("WAIT_TIMEOUT",
                "pkg=" + pkg + " ui=" + uiContains + " tree=" + compactDump(st),
                epoch, elapsed(start));
    }

    private static String compactDump(WorldState state) {
        if (state == null || state.root == null) return "empty";
        StringBuilder sb = new StringBuilder("pkg=" + state.foregroundPackage + " nodes=" + state.nodeCount() + ":");
        java.util.List<UiNode> flat = state.root.flat();
        for (int i = 0; i < flat.size() && i < 12; i++) {
            UiNode n = flat.get(i);
            String cls = n.className != null ? n.className : "";
            int dot = cls.lastIndexOf('.');
            String t = n.text != null ? n.text : "";
            if (t.length() > 10) t = t.substring(0, 10);
            sb.append('[').append(dot >= 0 ? cls.substring(dot + 1) : cls)
                    .append(',').append(n.resourceId != null ? n.resourceId : "")
                    .append(',').append(t).append(']');
        }
        return sb.toString();
    }

    private boolean verifyPostcondition(String condition, long timeoutMs,
                                        AtomicBoolean cancelRequested, long taskDeadlineMs) {
        return pollCondition(condition, Math.min(timeoutMs, 30000), cancelRequested, taskDeadlineMs);
    }

    /** 条件在超时窗口内成立即通过（适配慢速 UI 转场）。 */
    private boolean pollCondition(String condition, long timeoutMs,
                                  AtomicBoolean cancelRequested, long taskDeadlineMs) {
        long actionDeadlineMs = System.currentTimeMillis() + Math.max(timeoutMs, 1000);
        while (true) {
            if (cancelRequested != null && cancelRequested.get()) return false;
            long now = System.currentTimeMillis();
            if (now >= taskDeadlineMs || now >= actionDeadlineMs) return false;
            AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
            if (svc != null) svc.snapshot("verify");
            if (cancelRequested != null && cancelRequested.get()) return false;
            now = System.currentTimeMillis();
            if (now >= taskDeadlineMs || now >= actionDeadlineMs) return false;
            if (conditionChecker.evaluate(condition, stateEngine.current())) {
                if (cancelRequested != null && cancelRequested.get()) return false;
                now = System.currentTimeMillis();
                return now < taskDeadlineMs && now < actionDeadlineMs;
            }
            try {
                long remainingMs = Math.min(actionDeadlineMs - System.currentTimeMillis(),
                        taskDeadlineMs - System.currentTimeMillis());
                if (remainingMs > 0) Thread.sleep(Math.min(50L, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void audit(String taskId, String actionId, String phase, String bridge,
                       long epoch, String code, JSONObject detail) {
        auditLogger.record(taskId, actionId, phase, bridge, epoch, code, detail);
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
