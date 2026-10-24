package com.agentruntime.state;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State Engine：事件驱动维护 WorldState，支持增量差分与全量重同步。
 * 事件做过滤、去重、合并与短暂防抖，避免高频事件触发重复决策。
 */
public final class StateEngine {

    public static final class StateEvent {
        public enum Type { WINDOW_CHANGED, CONTENT_CHANGED, FOCUS_CHANGED, TEXT_CHANGED, SCROLL, NOTIFICATION, SNAPSHOT }

        public final Type type;
        public final String packageName;
        public final long timestampMs;
        public final long seq;

        public StateEvent(Type type, String packageName, long timestampMs, long seq) {
            this.type = type;
            this.packageName = packageName;
            this.timestampMs = timestampMs;
            this.seq = seq;
        }
    }

    public interface Listener {
        void onStateChanged(WorldState state, WorldState.UiDiff diff);
    }

    private static final long DEBOUNCE_MS = 120;

    private final Object lock = new Object();
    private WorldState current;
    private long epoch = 1;
    private long version = 0;
    private long eventSeq = 0;
    private long lastEventTs = 0;
    private StateEvent.Type lastEventType = null;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public WorldState current() {
        synchronized (lock) {
            return current;
        }
    }

    /** 全量快照重建路径：树不可读 / Activity 重建 / 窗口切换后调用。 */
    public WorldState rebuildSnapshot(String foregroundPackage, String windowTitle,
                                      UiNode root, String focusNodeId, String source) {
        WorldState.UiDiff diff;
        WorldState next;
        synchronized (lock) {
            if (current != null && (!eq(current.foregroundPackage, foregroundPackage) || current.root == null)) {
                epoch++;
            }
            version++;
            next = new WorldState(epoch, version, System.currentTimeMillis(),
                    foregroundPackage, windowTitle, source, root, focusNodeId, eventSeq);
            diff = WorldState.diff(current, next);
            current = next;
        }
        notifyListeners(next, diff);
        return next;
    }

    /** 事件入口：过滤/去重/防抖后合并为新状态版本。 */
    public void onEvent(StateEvent event) {
        if (event == null) return;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (event.type != StateEvent.Type.WINDOW_CHANGED
                    && event.type != StateEvent.Type.NOTIFICATION
                    && event.type == lastEventType
                    && now - lastEventTs < DEBOUNCE_MS) {
                lastEventTs = now;
                return; // 合并高频同类事件
            }
            lastEventTs = now;
            lastEventType = event.type;
            eventSeq++;
            if (event.type == StateEvent.Type.WINDOW_CHANGED) {
                epoch++;
                current = null; // 节点 ID 失效，等待快照重建
            }
        }
    }

    /** 增量差分：基于两次快照（同 epoch）计算 UI Delta。 */
    public WorldState.UiDiff diffSince(long seenVersion, WorldState seenSnapshot, WorldState now) {
        return WorldState.diff(seenSnapshot, now != null ? now : current());
    }

    /** 等待条件成立（轮询式，供 Executor 的 wait_for / 定位使用）。 */
    public WorldState waitFor(java.util.function.Predicate<WorldState> condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WorldState s = current();
            if (s != null && condition.test(s)) return s;
            Thread.sleep(50);
        }
        return null;
    }

    public long currentEpoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    public long eventSeq() {
        synchronized (lock) {
            return eventSeq;
        }
    }

    private void notifyListeners(WorldState state, WorldState.UiDiff diff) {
        for (Listener l : listeners) {
            try {
                l.onStateChanged(state, diff);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
