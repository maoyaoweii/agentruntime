package com.agentruntime.state;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * UI Tree 节点的结构化摘要。
 * nodeId 只在单个 State Epoch 内有效；epoch 重建后旧 nodeId 全部作废。
 */
public final class UiNode {

    public final String nodeId;
    public final String className;
    public final String text;
    public final String contentDescription;
    public final String resourceId;
    public final boolean clickable;
    public final boolean enabled;
    public final boolean visible;
    public final boolean focusable;
    public final boolean editable;
    public final boolean scrollable;
    public final boolean focused;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final List<UiNode> children;

    public UiNode(String nodeId, String className, String text, String contentDescription,
                  String resourceId, boolean clickable, boolean enabled, boolean visible,
                  boolean focusable, boolean editable, boolean scrollable, boolean focused,
                  int left, int top, int right, int bottom, List<UiNode> children) {
        this.nodeId = nodeId;
        this.className = className;
        this.text = text;
        this.contentDescription = contentDescription;
        this.resourceId = resourceId;
        this.clickable = clickable;
        this.enabled = enabled;
        this.visible = visible;
        this.focusable = focusable;
        this.editable = editable;
        this.scrollable = scrollable;
        this.focused = focused;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.children = children != null ? children : new ArrayList<>();
    }

    public boolean matchesResource(String resourceSuffix) {
        if (resourceId == null || resourceSuffix == null) return false;
        return resourceId.equals(resourceSuffix)
                || resourceId.endsWith(":id/" + resourceSuffix)
                || resourceId.endsWith("/id/" + resourceSuffix)
                || resourceId.endsWith("/" + resourceSuffix)
                || resourceId.endsWith(":" + resourceSuffix);
    }

    public boolean matchesText(String t) {
        if (t == null) return false;
        String q = t.toLowerCase(java.util.Locale.ROOT);
        return q.equals(lower(text)) || q.equals(lower(contentDescription));
    }

    public boolean containsText(String t) {
        if (t == null) return false;
        String q = t.toLowerCase(java.util.Locale.ROOT);
        return (text != null && lower(text).contains(q))
                || (contentDescription != null && lower(contentDescription).contains(q));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    /** 先序遍历收集匹配节点。 */
    public void collect(java.util.function.Predicate<UiNode> filter, List<UiNode> out) {
        if (filter.test(this)) out.add(this);
        for (UiNode c : children) c.collect(filter, out);
    }

    public List<UiNode> flat() {
        List<UiNode> out = new ArrayList<>();
        collect(n -> true, out);
        return out;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", nodeId);
            o.put("class", className);
            if (text != null) o.put("text", text);
            if (contentDescription != null) o.put("desc", contentDescription);
            if (resourceId != null) o.put("res", resourceId);
            o.put("clickable", clickable);
            o.put("enabled", enabled);
            o.put("visible", visible);
            o.put("editable", editable);
            o.put("scrollable", scrollable);
            o.put("focused", focused);
            JSONArray b = new JSONArray();
            b.put(left).put(top).put(right).put(bottom);
            o.put("bounds", b);
            JSONArray ch = new JSONArray();
            for (UiNode c : children) ch.put(c.toJson());
            o.put("children", ch);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 指纹：用于差分比较（含 bounds，可感知布局变化）。 */
    public String fingerprint() {
        return nz(className) + "|" + nz(resourceId) + "|" + nz(text) + "|" + nz(contentDescription)
                + "|" + clickable + "|" + enabled + "|" + visible
                + "|" + left + "," + top + "," + right + "," + bottom;
    }

    /** 身份键：用于存活节点匹配（不含 bounds/focus，IME 与布局重排期间仍稳定）。 */
    public String identityKey() {
        return nz(className) + "|" + nz(resourceId) + "|" + nz(text) + "|" + nz(contentDescription)
                + "|" + clickable + "|" + enabled + "|" + visible;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
