package com.agentruntime.debug;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.agentruntime.agent.BuildConfig;
import com.agentruntime.service.AgentAccessibilityService;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** ADB shell-only local fixture interface, intentionally absent from release source sets. */
public final class LocalFixtureProvider extends ContentProvider {
    static final LocalFixturePolicy POLICY = new LocalFixturePolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalFixtureAccess accessGate = new LocalFixtureAccess();
    private static final long ACCESS_TIMEOUT_MS = 6000;
    private static final String[] CONTROL_ACTIONS = {"text_fill", "text_clear", "check_on", "check_off", "scroll_down", "scroll_up"};
    private long sessionGeneration; // Main-thread only, as are POLICY and Activity fields.
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // Check Binder identity BEFORE dispatching to main thread; never clear identity.
        LocalFixturePolicy.authorize(BuildConfig.DEBUG, Binder.getCallingUid(), Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0], Build.HARDWARE);
        getContext().enforceCallingPermission("android.permission.DUMP", "ADB shell only");
        if ((getContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            throw new SecurityException("NOT_DEBUGGABLE");
        LocalFixturePolicy.validateRequest(method, arg, extras != null && !extras.isEmpty());
        if ("open".equals(method) || "close".equals(method))
            return onMain(() -> lifecycle(method, arg));
        Guard guard = onMain(() -> guard(arg, null));
        // Never query this process's accessibility tree on its UI thread: Android must
        // be able to service accessibility IPC on that same UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) throw new SecurityException("BINDER_THREAD_REQUIRED");
        // Caller authorization stays on the original Binder thread; lifecycle remains main-only.
        return accessGate.run("capture".equals(method) ? 10000 : ACCESS_TIMEOUT_MS,
                lease -> access(method, arg, guard, lease));
    }
    private <T> T onMain(Callable<T> operation) {
        FutureTask<T> work = new FutureTask<>(operation);
        main.post(work);
        try { return work.get(3, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) throw (SecurityException) cause;
            throw new IllegalStateException("FIXTURE_INTERNAL_ERROR", cause);
        } catch (Exception e) {
            work.cancel(false);
            throw new IllegalStateException("FIXTURE_UI_TIMEOUT", e);
        }
    }
    private Bundle lifecycle(String method, String token) {
        Bundle out = new Bundle();
        if ("open".equals(method)) {
            if (LocalFixtureActivity.current != null) throw new SecurityException("FIXTURE_ALREADY_OPEN");
            if (AgentAccessibilityService.getInstance() == null) throw new SecurityException("ACCESSIBILITY_UNAVAILABLE");
            out.putString("session", POLICY.open(SystemClock.elapsedRealtime()));
            try {
                getContext().startActivity(new Intent(getContext(), LocalFixtureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) { POLICY.close(); throw e; }
            long generation = ++sessionGeneration;
            main.postDelayed(() -> {
                if (generation != sessionGeneration) return;
                POLICY.close();
                LocalFixtureActivity expired = LocalFixtureActivity.current;
                if (expired != null) expired.finish();
            }, LocalFixturePolicy.TTL_MS);
            out.putLong("expires_in_ms", LocalFixturePolicy.TTL_MS);
            return out;
        }
        POLICY.require(token, SystemClock.elapsedRealtime());
        POLICY.close();
        sessionGeneration++;
        LocalFixtureActivity activity = LocalFixtureActivity.current;
        if (activity != null) activity.finish();
        out.putBoolean("closed", true);
        return out;
    }
    private static final class Guard {
        final LocalFixtureActivity activity;
        final long generation;
        Guard(LocalFixtureActivity a, long g) { activity = a; generation = g; }
    }
    private Guard guard(String token, Guard expected) {
        POLICY.require(token, SystemClock.elapsedRealtime());
        LocalFixtureActivity activity = LocalFixtureActivity.current;
        String reason = activity == null ? "NO_ACTIVITY" : activity.invalidReason();
        if (reason != null) throw new SecurityException("NOT_FOREGROUND_FIXTURE:" + reason);
        if (expected != null && (activity != expected.activity || sessionGeneration != expected.generation))
            throw new SecurityException("FIXTURE_CHANGED");
        if (AgentAccessibilityService.getInstance() == null) throw new SecurityException("ACCESSIBILITY_UNAVAILABLE");
        return new Guard(activity, sessionGeneration);
    }
    private Bundle access(String method, String token, Guard expected, LocalFixtureAccess.Lease lease) {
        lease.stage("ROOT");
        android.util.Log.i("CueLocalFixture", "access stage=ROOT method=" + method);
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) throw new SecurityException("ACCESSIBILITY_UNAVAILABLE");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) throw new SecurityException("NO_ACTIVE_ROOT");
        List<AccessibilityNodeInfo> owned = new ArrayList<>();
        owned.add(root);
        try {
            String pkg = getContext().getPackageName();
            if (!pkg.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()))
                throw new SecurityException("WRONG_ACTIVE_PACKAGE");
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            lease.stage("TREE");
            android.util.Log.i("CueLocalFixture", "access stage=TREE method=" + method);
            collect(root, 0, false, pkg, root.getWindowId(), nodes, owned, lease);
            boolean marker = false;
            JSONArray text = new JSONArray();
            AccessibilityNodeInfo link = null;
            LocalFixtureAnchor.Selection selection = new LocalFixtureAnchor.Selection(pkg, root.getWindowId());
            int length = 0;
            for (AccessibilityNodeInfo n : nodes) {
                lease.requireActive();
                String value = n.getText() == null ? "" : n.getText().toString();
                String description = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
                if (LocalFixtureAnchor.exactIdentity(value, description) && selection.consider(linkFacts(n))) link = n;
                if (!n.isVisibleToUser()) continue;
                if (LocalFixturePolicy.MARKER.equals(value)) marker = true;
                length += value.length();
                if (length > 8192) throw new SecurityException("TEXT_LIMIT");
                if (!value.isEmpty()) text.put(value);
            }
            if (!marker) throw new SecurityException("FIXTURE_IDENTITY_MISMATCH");
            android.util.Log.i("CueLocalFixture", "fixed anchor " + selection.diagnostic());
            Bundle out = new Bundle();
            if ("read".equals(method)) {
                lease.stage("READBACK");
                String[] urlState = onMain(() -> {
                    lease.requireActive(); guard(token, expected);
                    String used = "|";
                    for (String action : CONTROL_ACTIONS) if (POLICY.consumed(action)) used += action + "|";
                    return new String[]{expected.activity.page.getUrl(), expected.activity.urlDiagnostics(), used};
                });
                lease.requireActive();
                out.putString("text_json", text.toString());
                out.putString("source", "AccessibilityService.getRootInActiveWindow");
                out.putString("fixture_url", urlState[0]);
                out.putString("fixed_page_url_diagnostic", urlState[1]);
                out.putString("fixed_link_diagnostic", selection.diagnostic());
                out.putString("controls_json", controlsState(nodes, pkg, root.getWindowId(), urlState[2]));
            } else if ("click".equals(method)) {
                lease.stage("LINK_REFRESH");
                if (link == null) throw new SecurityException("ALLOWLIST_LINK_UNAVAILABLE:SCAN:" + selection.diagnostic());
                boolean refreshed = link.refresh(); // Exactly one refresh, never fall back to another node.
                LocalFixtureAnchor.Facts fresh = refreshed ? linkFacts(link) : null;
                android.util.Log.i("CueLocalFixture", "fixed anchor refreshed=" + refreshed + ";"
                        + LocalFixtureAnchor.diagnostic(fresh, pkg, root.getWindowId()));
                LocalFixtureAnchor.requireRefreshed(refreshed, fresh, pkg, root.getWindowId());
                onMain(() -> {
                    lease.requireActive();
                    guard(token, expected);
                    POLICY.consumeClick(token, SystemClock.elapsedRealtime());
                    // Serialized with lifecycle on main and with timeout cancellation by the lease.
                    // Once committed, Android dispatch is not revocable; never retry an ambiguous result.
                    lease.commitClick();
                    return true;
                });
                lease.stage("CLICK_DISPATCH");
                boolean clicked = link.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                onMain(() -> { lease.requireActive(); guard(token, expected); return true; });
                out.putBoolean("clicked", clicked);
                out.putString("allowlisted_destination", LocalFixturePolicy.URL + "#allowed");
            } else if (LocalFixtureControl.isAction(method)) {
                AccessibilityNodeInfo target = uniqueControl(nodes, method);
                String reason = LocalFixtureControl.rejection(controlFacts(target), method, pkg, root.getWindowId());
                if (!"NONE".equals(reason)) throw new SecurityException("FIXED_CONTROL_REJECTED:" + reason);
                if (!target.refresh()) throw new SecurityException("FIXED_CONTROL_REFRESH_FAILED");
                reason = LocalFixtureControl.rejection(controlFacts(target), method, pkg, root.getWindowId());
                if (!"NONE".equals(reason)) throw new SecurityException("FIXED_CONTROL_CHANGED:" + reason);
                Bundle arguments = null;
                final int action;
                if ("text_fill".equals(method) || "text_clear".equals(method)) {
                    action = AccessibilityNodeInfo.ACTION_SET_TEXT;
                    arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "text_fill".equals(method) ? LocalFixtureControl.DUMMY : "");
                } else if ("check_on".equals(method) || "check_off".equals(method)) {
                    action = AccessibilityNodeInfo.ACTION_CLICK;
                } else {
                    action = "scroll_down".equals(method) ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                }
                commitFixed(token, expected, lease, method);
                boolean performed = target.performAction(action, arguments); // This node only. No focus/IME/ancestor/coordinate fallback.
                onMain(() -> { lease.requireActive(); guard(token, expected); return true; });
                out.putString("fixed_action", method);
                out.putBoolean("performed", performed);
                out.putString("source", "AccessibilityService.fixedNode.performAction");
                out.putBoolean("dispatch_only_post_read_required", true);
            } else if ("capture".equals(method)) {
                if (!com.agentruntime.vision.ScreenCapturer.supported()) throw new SecurityException("NATIVE_CAPTURE_UNSUPPORTED");
                commitFixed(token, expected, lease, method);
                com.agentruntime.vision.ScreenFrame frame = new com.agentruntime.vision.ScreenCapturer(service).capture(800, 70);
                onMain(() -> { lease.requireActive(); guard(token, expected); return true; });
                AccessibilityNodeInfo after = service.getRootInActiveWindow();
                if (after == null) throw new SecurityException("CAPTURE_ACTIVE_ROOT_LOST");
                try {
                    if (service != AgentAccessibilityService.getInstance() || !pkg.contentEquals(after.getPackageName())
                            || after.getWindowId() != root.getWindowId())
                        throw new SecurityException("CAPTURE_ACTIVE_WINDOW_CHANGED");
                } finally { after.recycle(); }
                if (frame == null || !frame.valid() || frame.imageBytes.length > 196608)
                    throw new SecurityException("NATIVE_CAPTURE_FAILED_OR_TOO_LARGE");
                out.putString("source", "AgentRuntime.ScreenCapturer/AccessibilityService.takeScreenshot");
                out.putString("mime", frame.mime);
                out.putInt("width", frame.imageWidth);
                out.putInt("height", frame.imageHeight);
                out.putInt("screen_width", frame.screenWidth);
                out.putInt("screen_height", frame.screenHeight);
                out.putFloat("scale", frame.scale);
                out.putString("capture_scope", "default_display_foreground_guarded_not_atomic_window_capture");
                out.putString("image_b64", android.util.Base64.encodeToString(frame.imageBytes, android.util.Base64.NO_WRAP));
            }
            lease.requireActive();
            return out;
        } finally { for (AccessibilityNodeInfo node : owned) node.recycle(); }
    }
    private void commitFixed(String token, Guard expected, LocalFixtureAccess.Lease lease, String method) {
        onMain(() -> {
            lease.requireActive(); guard(token, expected);
            POLICY.consumeFixed(token, SystemClock.elapsedRealtime(), method);
            lease.commitClick(); // Same cancellation/commit exclusion for every fixed native operation.
            return true;
        });
    }
    private static AccessibilityNodeInfo uniqueControl(List<AccessibilityNodeInfo> nodes, String method) {
        AccessibilityNodeInfo found = null;
        int count = 0;
        for (AccessibilityNodeInfo n : nodes) if (LocalFixtureControl.identity(controlFacts(n), method)) { found = n; count++; }
        if (count != 1) throw new SecurityException("FIXED_CONTROL_NOT_UNIQUE");
        return found;
    }
    private static LocalFixtureControl.Facts controlFacts(AccessibilityNodeInfo n) {
        LocalFixtureControl.Facts f = new LocalFixtureControl.Facts();
        f.text = n.getText() == null ? "" : n.getText().toString();
        f.description = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        f.hint = n.getHintText() == null ? "" : n.getHintText().toString();
        f.className = n.getClassName() == null ? "" : n.getClassName().toString();
        f.packageName = n.getPackageName() == null ? "" : n.getPackageName().toString();
        f.windowId = n.getWindowId(); f.visible = n.isVisibleToUser(); f.enabled = n.isEnabled();
        f.editable = n.isEditable(); f.checkable = n.isCheckable(); f.checked = n.isChecked();
        f.scrollable = n.isScrollable(); f.clickable = n.isClickable();
        android.graphics.Rect bounds = new android.graphics.Rect(); n.getBoundsInScreen(bounds); f.boundsValid = !bounds.isEmpty();
        List<AccessibilityNodeInfo.AccessibilityAction> actions = n.getActionList();
        f.setTextAction = actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
        f.clickAction = actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        f.forwardAction = actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        f.backwardAction = actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        return f;
    }
    private static String controlsState(List<AccessibilityNodeInfo> nodes, String pkg, int window, String consumed) {
        org.json.JSONObject state = new org.json.JSONObject();
        JSONArray available = new JSONArray();
        try {
            for (String method : CONTROL_ACTIONS) {
                int count = 0;
                LocalFixtureControl.Facts f = null;
                for (AccessibilityNodeInfo n : nodes) {
                    LocalFixtureControl.Facts candidate = controlFacts(n);
                    if (LocalFixtureControl.identity(candidate, method)) { count++; f = candidate; }
                }
                String reject = count == 1 ? LocalFixtureControl.rejection(f, method, pkg, window) : "NOT_UNIQUE";
                boolean used = consumed.contains("|" + method + "|");
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("matches", count); item.put("reject", reject); item.put("consumed", used);
                if (f != null) {
                    item.put("visible", f.visible); item.put("editable", f.editable); item.put("checked", f.checked);
                    item.put("checkable", f.checkable); item.put("scrollable", f.scrollable);
                    item.put("set_text_action", f.setTextAction); item.put("click_action", f.clickAction);
                    item.put("forward_action", f.forwardAction); item.put("backward_action", f.backwardAction);
                    item.put("exact_input_hint", LocalFixtureControl.INPUT_LABEL.equals(f.hint));
                    item.put("value_state", LocalFixtureControl.DUMMY.equals(f.text) ? "DUMMY"
                            : (f.text.isEmpty() || LocalFixtureControl.INPUT_LABEL.equals(f.text)) ? "EMPTY_OR_LABEL" : "OTHER_FIXED_CONTROL_TEXT");
                }
                state.put(method, item);
                if ("NONE".equals(reject) && !used) available.put(method);
            }
            boolean top = false, bottom = false;
            for (AccessibilityNodeInfo n : nodes) if (n.isVisibleToUser()) {
                top |= "CUE_SCROLL_TOP_V1".contentEquals(n.getText() == null ? "" : n.getText());
                bottom |= "CUE_SCROLL_BOTTOM_V1".contentEquals(n.getText() == null ? "" : n.getText());
            }
            state.put("top_visible", top); state.put("bottom_visible", bottom); state.put("available_actions", available);
            return state.toString();
        } catch (org.json.JSONException e) { throw new IllegalStateException("FIXED_CONTROL_STATE_ERROR"); }
    }
    private static LocalFixtureAnchor.Facts linkFacts(AccessibilityNodeInfo n) {
        return new LocalFixtureAnchor.Facts(
                n.getText() == null ? "" : n.getText().toString(),
                n.getClassName() == null ? "" : n.getClassName().toString(),
                n.getPackageName() == null ? "" : n.getPackageName().toString(), n.getWindowId(),
                n.isVisibleToUser(), n.isEnabled(), n.isClickable(), n.isEditable(),
                n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK),
                n.getContentDescription() == null ? "" : n.getContentDescription().toString());
    }
    private static void collect(AccessibilityNodeInfo n, int depth, boolean inWeb, String pkg,
            int windowId, List<AccessibilityNodeInfo> nodes, List<AccessibilityNodeInfo> owned,
            LocalFixtureAccess.Lease lease) {
        lease.requireActive();
        if (depth > 24 || owned.size() > 128) throw new SecurityException("TREE_LIMIT");
        if (!pkg.contentEquals(n.getPackageName() == null ? "" : n.getPackageName())
                || n.getWindowId() != windowId) throw new SecurityException("TREE_BOUNDARY");
        inWeb |= "android.webkit.WebView".contentEquals(n.getClassName() == null ? "" : n.getClassName());
        if (inWeb) nodes.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            lease.requireActive();
            AccessibilityNodeInfo child = n.getChild(i);
            if (child == null) continue;
            owned.add(child);
            collect(child, depth + 1, inWeb, pkg, windowId, nodes, owned, lease);
        }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) { throw denied(); }
    @Override public String getType(Uri u) { throw denied(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw denied(); }
    @Override public int delete(Uri u, String s, String[] a) { throw denied(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw denied(); }
    private static SecurityException denied() { return new SecurityException("CALL_ONLY"); }
}
