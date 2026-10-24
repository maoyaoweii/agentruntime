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
                AgentAccessibilityService keySvc = AgentAccessibilityService.getInstance();
                boolean ok = keySvc != null && keySvc.pressGlobalKey(key);
                result = ok ? ExecResult.ok("KEY_OK", key, epoch, elapsed(start))
                        : ExecResult.fail("KEY_FAILED", "global action failed for key=" + key, epoch, elapsed(start));
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
                        : sys.openSettingsPage(spec.param("page"));
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
        return ExecResult.ok("SHOT_OK", frame.describe() + " path=" + path, epoch, elapsed(start));
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
                    return ExecResult.timeout("WAIT_TIMEOUT",
                            "pkg=" + pkg + " ui=" + uiContains + " tree=" + compactDump(stateEngine.current()),
                            epoch, elapsed(start));
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
                    return ExecResult.timeout("WAIT_TIMEOUT",
                            "pkg=" + pkg + " ui=" + uiContains + " tree=" + compactDump(stateEngine.current()),
                            epoch, elapsed(start));
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
                            return ExecResult.timeout("WAIT_TIMEOUT",
                                    "pkg=" + pkg + " ui=" + uiContains + " tree=" + compactDump(st),
                                    epoch, elapsed(start));
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
