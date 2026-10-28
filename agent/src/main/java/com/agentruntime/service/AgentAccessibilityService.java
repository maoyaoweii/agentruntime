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
    private int nullRootStreak = 0;

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
        AccessibilityNodeInfo root = activeRoot();
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

    /**
     * 取当前活动窗口的根节点，取不到时用窗口列表兜底一次。
     *
     * <p>实测问题（C05）：宿主进程被 force-stop 后，无障碍连接被系统标记为仍 Bound，
     * 但 {@link #getRootInActiveWindow()} 持续返回 null，任务里所有 wait_for/按文本定位
     * 都变成 WAIT_TIMEOUT / NODE_NOT_FOUND，且没有任何线索。这里兜底读一次
     * {@link #getWindows()}（最上层应用窗口），并把连续失败次数打到日志，
     * 让"服务半死"能被看出来而不是伪装成找不到界面。
     */
    private AccessibilityNodeInfo activeRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            nullRootStreak = 0;
            return root;
        }
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                AccessibilityNodeInfo fallback = null;
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) fallback = r; // 后出现的窗口更靠上
                }
                if (fallback != null) {
                    nullRootStreak = 0;
                    return fallback;
                }
            }
        } catch (Throwable ignored) {
            // getWindows 在部分定制 ROM 上可能抛异常，兜底失败按 null 处理
        }
        nullRootStreak++;
        if (nullRootStreak == 1 || nullRootStreak % 20 == 0) {
            android.util.Log.w("A11ySnap", "root unavailable streak=" + nullRootStreak
                    + " windows=" + windowSummary());
        }
        return null;
    }

    private String windowSummary() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return "none";
            StringBuilder sb = new StringBuilder();
            for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                sb.append(w.getType()).append(w.isActive() ? "*" : "").append(' ');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
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
            // 锁屏与系统截图是平台全局动作，老系统没有对应常量入口，按版本诚实拒绝。
            case "lock_screen":
                return android.os.Build.VERSION.SDK_INT >= 28 ? GLOBAL_ACTION_LOCK_SCREEN : null;
            case "screenshot":
                return android.os.Build.VERSION.SDK_INT >= 30 ? GLOBAL_ACTION_TAKE_SCREENSHOT : null;
            default: return null;
        }
    }

    /** 该全局按键要求的最低 API；未知按键返回 -1。 */
    public static int minSdkOf(String key) {
        if (key == null) return -1;
        switch (key.trim().toLowerCase()) {
            case "back":
            case "home":
            case "recents":
            case "notifications":
            case "quick_settings":
            case "power_dialog":
                return 16;
            case "lock_screen":
                return 28;
            case "screenshot":
                return 30;
            default:
                return -1;
        }
    }

    private AccessibilityNodeInfo findLiveNode(UiNode target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || target == null) return null;
        // 先按完整身份（类名+resourceId+文本+描述+可点+可见）匹配，再按 resourceId 兜底。
        // 不能只用 resourceId：抽屉/列表的行常常共用同一个 id（实测 NewPipe 抽屉全是
        // design_menu_item_text），按 id 找会命中第一行。
        // 身份也可能重复：行容器本身无文本（文本在子节点），同构行身份一致，
        // 所以多命中时用坐标挑最接近快照位置的那个，避免"点 Settings 点成 History"。
        String identity = target.identityKey();
        java.util.List<AccessibilityNodeInfo> hits = new java.util.ArrayList<>();
        collectLive(root, n -> identity.equals(identityOf(n)), hits);
        if (!hits.isEmpty()) return nearestByBounds(hits, target);
        if (target.resourceId != null) {
            java.util.List<AccessibilityNodeInfo> byId = new java.util.ArrayList<>();
            collectLive(root, n -> target.resourceId.equals(n.getViewIdResourceName())
                    && (target.className == null
                    || target.className.equals(safe(n.getClassName()))), byId);
            if (!byId.isEmpty()) return nearestByBounds(byId, target);
        }
        return null;
    }

    /** 同一身份匹配到多个节点时，选屏幕位置最接近目标快照中心点的那个。 */
    private AccessibilityNodeInfo nearestByBounds(java.util.List<AccessibilityNodeInfo> hits, UiNode target) {
        if (hits.size() == 1) return hits.get(0);
        int tx = (target.left + target.right) / 2;
        int ty = (target.top + target.bottom) / 2;
        AccessibilityNodeInfo best = null;
        long bestDist = Long.MAX_VALUE;
        Rect b = new Rect();
        for (AccessibilityNodeInfo n : hits) {
            b.setEmpty();
            n.getBoundsInScreen(b);
            long dx = (long) ((b.left + b.right) / 2) - tx;
            long dy = (long) ((b.top + b.bottom) / 2) - ty;
            long d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best != null ? best : hits.get(0);
    }

    private void collectLive(AccessibilityNodeInfo node,
                             java.util.function.Predicate<AccessibilityNodeInfo> pred,
                             java.util.List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (pred.test(node)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectLive(node.getChild(i), pred, out);
        }
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
