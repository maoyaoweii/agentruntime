package com.agentruntime.state;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldState：带 epoch / version / 时间戳 / 来源的设备当前状态摘要。
 * 节点 ID 仅在本 epoch 内有效。
 */
public final class WorldState {

    public static final class UiDiff {
        public final List<UiNode> added = new ArrayList<>();
        public final List<UiNode> removed = new ArrayList<>();
        public final List<UiNode> changed = new ArrayList<>();
        public boolean focusChanged;
        public boolean windowChanged;

        public int total() {
            return added.size() + removed.size() + changed.size();
        }

        public String summary() {
            return "added=" + added.size() + " removed=" + removed.size()
                    + " changed=" + changed.size()
                    + " focus=" + focusChanged + " window=" + windowChanged;
        }
    }

    public final long epoch;
    public final long version;
    public final long timestampMs;
    public final String foregroundPackage;
    public final String windowTitle;
    public final String source;
    public final UiNode root;
    public final String focusNodeId;
    public final long eventSeq;

    public WorldState(long epoch, long version, long timestampMs, String foregroundPackage,
                      String windowTitle, String source, UiNode root, String focusNodeId, long eventSeq) {
        this.epoch = epoch;
        this.version = version;
        this.timestampMs = timestampMs;
        this.foregroundPackage = foregroundPackage;
        this.windowTitle = windowTitle;
        this.source = source;
        this.root = root;
        this.focusNodeId = focusNodeId;
        this.eventSeq = eventSeq;
    }

    public boolean isValid() {
        return root != null;
    }

    public List<UiNode> findNodes(java.util.function.Predicate<UiNode> filter) {
        List<UiNode> out = new ArrayList<>();
        if (root != null) root.collect(filter, out);
        return out;
    }

    public UiNode findByResource(String resourceSuffix) {
        List<UiNode> hits = findNodes(n -> n.matchesResource(resourceSuffix));
        return hits.isEmpty() ? null : hits.get(0);
    }

    public UiNode findByText(String text) {
        List<UiNode> hits = findNodes(n -> n.matchesText(text));
        return hits.isEmpty() ? null : hits.get(0);
    }

    public boolean containsText(String text) {
        if (text == null) return false;
        // 与 VisualGrounder 的节点兜底保持一致：允许 "A|B|C" 多候选，命中任一即可。
        // 中文目标对英文界面时，单个字面量几乎必然匹配不到。
        for (String raw : text.split("\\|")) {
            String needle = raw.trim();
            if (needle.isEmpty()) continue;
            if (!findNodes(n -> n.containsText(needle)).isEmpty()) return true;
        }
        return false;
    }

    public int nodeCount() {
        return root == null ? 0 : root.flat().size();
    }

    public JSONObject toSummaryJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("epoch", epoch);
            o.put("version", version);
            o.put("ts", timestampMs);
            o.put("package", foregroundPackage);
            o.put("window", windowTitle);
            o.put("source", source);
            o.put("node_count", nodeCount());
            o.put("focus", focusNodeId);
            o.put("event_seq", eventSeq);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public JSONObject toJson() {
        JSONObject o = toSummaryJson();
        try {
            o.put("root", root == null ? null : root.toJson());
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 节点级差分（按先序指纹对齐）。 */
    public static UiDiff diff(WorldState before, WorldState after) {
        UiDiff d = new UiDiff();
        if (after == null) return d;
        if (before == null || before.epoch != after.epoch || before.root == null) {
            if (after.root != null) d.added.addAll(after.root.flat());
            d.windowChanged = before == null || !eq(before.foregroundPackage, after.foregroundPackage);
            d.focusChanged = true;
            return d;
        }
        d.windowChanged = !eq(before.foregroundPackage, after.foregroundPackage)
                || !eq(before.windowTitle, after.windowTitle);
        d.focusChanged = !eq(before.focusNodeId, after.focusNodeId);

        List<UiNode> oldFlat = before.root.flat();
        List<UiNode> newFlat = after.root.flat();
        java.util.Map<String, Integer> oldFp = new java.util.HashMap<>();
        for (UiNode n : oldFlat) oldFp.put(n.fingerprint(), oldFp.getOrDefault(n.fingerprint(), 0) + 1);
        java.util.Map<String, Integer> newFp = new java.util.HashMap<>();
        for (UiNode n : newFlat) newFp.put(n.fingerprint(), newFp.getOrDefault(n.fingerprint(), 0) + 1);

        for (UiNode n : newFlat) {
            String fp = n.fingerprint();
            Integer c = oldFp.get(fp);
            if (c == null || c == 0) {
                // 位置/属性变化或新增：按 resourceId 归为 changed，否则 added
                boolean sameRes = n.resourceId != null && !before.findNodes(
                        o -> o.resourceId != null && o.resourceId.equals(n.resourceId)).isEmpty();
                if (sameRes) d.changed.add(n);
                else d.added.add(n);
            } else {
                oldFp.put(fp, c - 1);
            }
        }
        for (UiNode n : oldFlat) {
            String fp = n.fingerprint();
            Integer c = newFp.get(fp);
            if (c == null || c == 0) {
                boolean sameRes = n.resourceId != null && !after.findNodes(
                        o -> o.resourceId != null && o.resourceId.equals(n.resourceId)).isEmpty();
                if (!sameRes) d.removed.add(n);
            } else {
                newFp.put(fp, c - 1);
            }
        }
        return d;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
