package com.agentruntime.device;

import com.agentruntime.executor.ExecResult;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Accessibility DeviceBridge：读取可访问节点并执行适用界面动作。
 * 需用户在系统设置中手动启用服务；能力随服务状态变化。
 */
public final class AccessibilityBridge implements DeviceBridge {

    private final StateEngine stateEngine;

    public AccessibilityBridge(StateEngine stateEngine) {
        this.stateEngine = stateEngine;
    }

    @Override
    public String name() {
        return "accessibility";
    }

    @Override
    public boolean available() {
        return AgentAccessibilityService.isRunning();
    }

    @Override
    public JSONObject capabilityProbe() {
        JSONObject o = new JSONObject();
        try {
            o.put("bridge", name());
            o.put("available", available());
            o.put("can_read_ui_tree", available());
            o.put("can_click", available());
            o.put("can_input_text", available());
            o.put("can_scroll", available());
            o.put("can_global_back", available());
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public ExecResult performClick(UiNode target, long timeoutMs) {
        return performClick(target,timeoutMs,null);
    }

    public ExecResult performClick(UiNode target,long timeoutMs,String expectedPackage) {
        long start = System.currentTimeMillis();
        if (target == null) {
            return ExecResult.fail("NODE_NOT_FOUND", "null target", epoch(), elapsed(start));
        }
        if (!target.enabled || !target.visible) {
            return ExecResult.fail("NODE_NOT_INTERACTABLE",
                    "enabled=" + target.enabled + " visible=" + target.visible,
                    epoch(), elapsed(start));
        }
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        boolean ok = svc != null && svc.click(target, expectedPackage);
        if (!ok) {
            // 目标本身不可点时，服务已尝试父链兜底；仍失败才判定不可交互。
            return target.clickable
                    ? ExecResult.fail("CLICK_FAILED", "performAction returned false or node expired", epoch(), elapsed(start))
                    : ExecResult.fail("NODE_NOT_INTERACTABLE",
                            "目标与父链都没有可点节点，clickable=false", epoch(), elapsed(start));
        }
        return ExecResult.ok("CLICK_OK", "clicked " + target.resourceId + "/" + target.text
                + " @" + target.left + "," + target.top + "," + target.right + "," + target.bottom
                + (target.clickable ? "" : "（文字节点不可点，已点最近的可点父节点）"),
                epoch(), elapsed(start));
    }

    @Override
    public ExecResult performInput(UiNode target, String text, long timeoutMs) {
        long start = System.currentTimeMillis();
        if (target == null) {
            return ExecResult.fail("NODE_NOT_FOUND", "null target", epoch(), elapsed(start));
        }
        if (!target.editable) {
            return ExecResult.fail("NODE_NOT_EDITABLE", "target not editable", epoch(), elapsed(start));
        }
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        boolean ok = svc != null && svc.setText(target, text);
        if (!ok) return ExecResult.fail("INPUT_FAILED", "ACTION_SET_TEXT failed or node expired", epoch(), elapsed(start));
        return ExecResult.ok("INPUT_OK", "text set on " + target.resourceId, epoch(), elapsed(start));
    }

    @Override
    public ExecResult performScroll(UiNode target, String direction, long timeoutMs) {
        long start = System.currentTimeMillis();
        UiNode node = target != null ? target : firstScrollable();
        if (node == null) {
            return ExecResult.fail("NODE_NOT_FOUND", "no scrollable node", epoch(), elapsed(start));
        }
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        boolean ok = svc != null && svc.scroll(node, direction);
        if (!ok) return ExecResult.fail("SCROLL_FAILED", "scroll action returned false", epoch(), elapsed(start));
        return ExecResult.ok("SCROLL_OK", direction + " on " + node.resourceId, epoch(), elapsed(start));
    }

    @Override
    public ExecResult performGlobalBack() {
        long start = System.currentTimeMillis();
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        boolean ok = svc != null && svc.globalBack();
        if (!ok) return ExecResult.fail("BACK_FAILED", "global back failed", epoch(), elapsed(start));
        return ExecResult.ok("BACK_OK", "global back", epoch(), elapsed(start));
    }

    @Override
    public ExecResult performLaunchApp(String packageName, long timeoutMs) {
        return ExecResult.reject("USE_INTENT_BRIDGE", "launch_app is dispatched via IntentBridge", epoch());
    }

    private UiNode resolve(String nodeId) {
        WorldState s = stateEngine.current();
        if (s == null || s.root == null || nodeId == null) return null;
        for (UiNode n : s.root.flat()) {
            if (nodeId.equals(n.nodeId)) return n;
        }
        return null;
    }

    private UiNode firstScrollable() {
        WorldState s = stateEngine.current();
        if (s == null || s.root == null) return null;
        for (UiNode n : s.root.flat()) {
            if (n.scrollable) return n;
        }
        return null;
    }

    private long epoch() {
        return stateEngine.currentEpoch();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
