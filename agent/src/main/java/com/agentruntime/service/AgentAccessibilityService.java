package com.agentruntime.service;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.agentruntime.AgentApp;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility 通道：事件采集 -> State Engine；并提供节点定位/点击/输入/滚动能力。
 * 只请求必要事件；节点 ID 仅在单个 State Epoch 内有效。
 */
public final class AgentAccessibilityService extends AccessibilityService {

    private static final int MAX_NODES = 1200;
    private static final int MAX_DEPTH = 48;
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 150;

    /** 音量下键长按判定阈值。低于它一律放行，保证正常调音量不受影响。 */
    private static final long VOLUME_HOLD_MS = 800;

    private long volumeDownAt = 0;
    private boolean volumeConsumed = false;

    private static volatile AgentAccessibilityService instance;

    private long nodeIdSeq = 0;
    private long lastSnapshotTs = 0;

    public static AgentAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        snapshot("service_connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        StateEngine engine = engine();
        if (engine == null) return;
        StateEngine.StateEvent.Type type = mapType(event.getEventType());
        engine.onEvent(new StateEngine.StateEvent(
                type,
                event.getPackageName() != null ? event.getPackageName().toString() : null,
                System.currentTimeMillis(),
                event.getEventTime()));
        long now = System.currentTimeMillis();
        if (type == StateEngine.StateEvent.Type.WINDOW_CHANGED || now - lastSnapshotTs >= SNAPSHOT_MIN_INTERVAL_MS) {
            snapshot("event:" + type.name());
        }
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 音量下键长按作为全局唤起快捷键。
     *
     * <p>实现刻意保守：<b>只有长按超过 800ms 才消费事件</b>，短按一律放行，
     * 这样正常调音量完全不受影响。消费时启动悬浮球服务（未授权则忽略）。
     *
     * <p>为什么选音量下键：它是极少数在任意应用里都能被无障碍服务看到、
     * 且不与系统手势冲突的物理键。电源键与 Home 键涉及系统保留行为，不碰。
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        if (event == null || event.getKeyCode() != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                volumeDownAt = System.currentTimeMillis();
                volumeConsumed = false;
                return false;
            }
            if (!volumeConsumed && System.currentTimeMillis() - volumeDownAt >= VOLUME_HOLD_MS) {
                volumeConsumed = true;
                launchQuickPanel();
                return true;
            }
        }
        return false;
    }

    private void launchQuickPanel() {
        try {
            android.content.Context ctx = getApplicationContext();
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                return;
            }
            android.content.Intent i = new android.content.Intent(ctx,
                    com.agentruntime.overlay.OverlayService.class)
                    .setAction(com.agentruntime.overlay.OverlayService.ACTION_START);
            ctx.startService(i);
        } catch (Throwable ignored) {
            // 唤起失败不影响正常使用
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** 全量快照重建：将当前可访问树写入 State Engine。 */
    public WorldState snapshot(String source) {
        StateEngine engine = engine();
        if (engine == null) return null;
        lastSnapshotTs = System.currentTimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return engine.rebuildSnapshot(null, null, null, null, source);
        }
        nodeIdSeq = 0;
        UiNode tree = buildNode(root, 0);
        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : null;
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        String focusId = null;
        if (focus != null) {
            UiNode match = findByFingerprint(tree, fingerprintOf(focus));
            focusId = match != null ? match.nodeId : null;
        }
        return engine.rebuildSnapshot(pkg, pkg, tree, focusId, source);
    }

    /** 语义定位后在存活树上执行点击。 */
    public boolean click(UiNode target) {
        AccessibilityNodeInfo live = findLiveNode(target);
        if (live == null) return false;
        boolean ok = live.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        live.recycle();
        return ok;
    }

    public boolean setText(UiNode target, String text) {
        AccessibilityNodeInfo live = findLiveNode(target);
        if (live == null) return false;
        live.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        live.recycle();
        return ok;
    }

    public boolean scroll(UiNode target, String direction) {
        AccessibilityNodeInfo live = findLiveNode(target);
        if (live == null) return false;
        int action = "backward".equalsIgnoreCase(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        boolean ok = live.performAction(action);
        live.recycle();
        return ok;
    }

    public boolean globalBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean longClick(UiNode target) {
        AccessibilityNodeInfo live = findLiveNode(target);
        if (live == null) return false;
        boolean ok = live.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        live.recycle();
        return ok;
    }

    public boolean clearText(UiNode target) {
        AccessibilityNodeInfo live = findLiveNode(target);
        if (live == null) return false;
        live.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        boolean ok = live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        live.recycle();
        return ok;
    }

    public boolean pressGlobalKey(String key) {
        Integer action = globalActionOf(key);
        return action != null && performGlobalAction(action);
    }

    public static Integer globalActionOf(String key) {
        if (key == null) return null;
        switch (key.trim().toLowerCase()) {
            case "back": return GLOBAL_ACTION_BACK;
            case "home": return GLOBAL_ACTION_HOME;
            case "recents": return GLOBAL_ACTION_RECENTS;
            case "notifications": return GLOBAL_ACTION_NOTIFICATIONS;
            case "quick_settings": return GLOBAL_ACTION_QUICK_SETTINGS;
            case "power_dialog": return GLOBAL_ACTION_POWER_DIALOG;
            default: return null;
        }
    }

    private AccessibilityNodeInfo findLiveNode(UiNode target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || target == null) return null;
        if (target.resourceId != null) {
            AccessibilityNodeInfo byId = findLiveBy(root, n ->
                    target.resourceId.equals(n.getViewIdResourceName())
                            && (target.className == null
                            || target.className.equals(safe(n.getClassName()))));
            if (byId != null) return byId;
        }
        String identity = target.identityKey();
        return findLiveBy(root, n -> identity.equals(identityOf(n)));
    }

    private AccessibilityNodeInfo findLiveBy(AccessibilityNodeInfo node,
                                             java.util.function.Predicate<AccessibilityNodeInfo> pred) {
        if (node == null) return null;
        if (pred.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo hit = findLiveBy(child, pred);
            if (hit != null) return hit;
        }
        return null;
    }

    private UiNode findByFingerprint(UiNode node, String fp) {
        if (node == null) return null;
        if (fp.equals(node.fingerprint())) return node;
        for (UiNode c : node.children) {
            UiNode hit = findByFingerprint(c, fp);
            if (hit != null) return hit;
        }
        return null;
    }

    private String identityOf(AccessibilityNodeInfo n) {
        return safe(n.getClassName()) + "|" + safe(n.getViewIdResourceName()) + "|"
                + safe(n.getText()) + "|" + safe(n.getContentDescription()) + "|"
                + n.isClickable() + "|" + n.isEnabled() + "|" + n.isVisibleToUser();
    }

    private String fingerprintOf(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return safe(n.getClassName()) + "|" + safe(n.getViewIdResourceName()) + "|"
                + safe(n.getText()) + "|" + safe(n.getContentDescription()) + "|"
                + n.isClickable() + "|" + n.isEnabled() + "|" + n.isVisibleToUser() + "|"
                + b.left + "," + b.top + "," + b.right + "," + b.bottom;
    }

    private UiNode buildNode(AccessibilityNodeInfo info, int depth) {
        if (info == null || depth > MAX_DEPTH || nodeIdSeq > MAX_NODES) return null;
        Rect b = new Rect();
        info.getBoundsInScreen(b);
        List<UiNode> children = new ArrayList<>();
        int count = info.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = info.getChild(i);
            if (child == null) continue;
            UiNode cn = buildNode(child, depth + 1);
            if (cn != null) children.add(cn);
        }
        String id = "n" + (nodeIdSeq++);
        return new UiNode(
                id,
                safe(info.getClassName()),
                toStr(info.getText()),
                toStr(info.getContentDescription()),
                info.getViewIdResourceName(),
                info.isClickable(),
                info.isEnabled(),
                info.isVisibleToUser(),
                info.isFocusable(),
                info.isEditable(),
                info.isScrollable(),
                info.isFocused(),
                b.left, b.top, b.right, b.bottom,
                children);
    }

    private StateEngine engine() {
        AgentApp app = AgentApp.get();
        return app != null ? app.stateEngine() : null;
    }

    private static StateEngine.StateEvent.Type mapType(int eventType) {
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                return StateEngine.StateEvent.Type.WINDOW_CHANGED;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                return StateEngine.StateEvent.Type.FOCUS_CHANGED;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                return StateEngine.StateEvent.Type.TEXT_CHANGED;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                return StateEngine.StateEvent.Type.SCROLL;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                return StateEngine.StateEvent.Type.NOTIFICATION;
            default:
                return StateEngine.StateEvent.Type.CONTENT_CHANGED;
        }
    }

    private static String toStr(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String safe(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
