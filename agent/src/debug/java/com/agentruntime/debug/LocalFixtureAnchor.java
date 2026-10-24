package com.agentruntime.debug;

/** Internal fixed-fixture link guard. No caller text, selector, URL, ancestor or coordinates. */
final class LocalFixtureAnchor {
    static final String ACCESSIBLE_LABEL = "CUE_FIXED_LINK_ALLOWED_V1";
    static final class Facts {
        final String text, className, packageName, contentDescription;
        final int windowId;
        final boolean visible, enabled, clickable, editable, hasClickAction;
        Facts(String text, String className, String packageName, int windowId,
                boolean visible, boolean enabled, boolean clickable, boolean editable, boolean hasClickAction) {
            this(text, className, packageName, windowId, visible, enabled, clickable, editable, hasClickAction, "");
        }
        Facts(String text, String className, String packageName, int windowId,
                boolean visible, boolean enabled, boolean clickable, boolean editable, boolean hasClickAction,
                String contentDescription) {
            this.text = text;
            this.className = className;
            this.packageName = packageName;
            this.windowId = windowId;
            this.visible = visible;
            this.enabled = enabled;
            this.clickable = clickable;
            this.editable = editable;
            this.hasClickAction = hasClickAction;
            this.contentDescription = contentDescription;
        }
    }
    static boolean exactIdentity(String text, String description) {
        // These are two native representations of the same explicit aria-label, not a fallback to text/ancestors.
        return ACCESSIBLE_LABEL.equals(text) || ACCESSIBLE_LABEL.equals(description);
    }
    static String rejection(Facts f, String expectedPackage, int expectedWindow) {
        if (f == null) return "LABEL_NOT_FOUND";
        if (!exactIdentity(f.text, f.contentDescription)) return "ANCHOR_IDENTITY_CHANGED";
        // Chromium WebView may expose a real <a> as either exact platform class.
        if (!("android.view.View".equals(f.className) || "android.widget.TextView".equals(f.className)))
            return "UNSUPPORTED_LINK_CLASS";
        if (!f.visible) return "NOT_VISIBLE";
        if (!f.enabled) return "NOT_ENABLED";
        if (!f.clickable) return "NOT_CLICKABLE";
        if (f.editable) return "EDITABLE";
        if (expectedPackage == null || !expectedPackage.equals(f.packageName)) return "PACKAGE_CHANGED";
        if (expectedWindow < 0 || f.windowId != expectedWindow) return "WINDOW_CHANGED";
        if (!f.hasClickAction) return "NO_ACTION_CLICK";
        return null;
    }
    static void requireRefreshed(boolean refreshed, Facts facts, String pkg, int window) {
        if (!refreshed) throw new SecurityException("ALLOWLIST_LINK_UNAVAILABLE:REFRESH_FAILED");
        String reason = rejection(facts, pkg, window);
        if (reason != null) throw new SecurityException("ALLOWLIST_LINK_UNAVAILABLE:REFRESH_" + reason);
    }
    /** Only enums/booleans are emitted, never unknown text, package, URL or class values. */
    static String diagnostic(Facts f, String pkg, int window) {
        if (f == null) return "LABEL_NOT_FOUND";
        String type = "android.view.View".equals(f.className) ? "ANDROID_VIEW"
                : "android.widget.TextView".equals(f.className) ? "ANDROID_TEXT_VIEW" : "OTHER_CLASS_REDACTED";
        String reason = rejection(f, pkg, window);
        String source = ACCESSIBLE_LABEL.equals(f.text) ? (ACCESSIBLE_LABEL.equals(f.contentDescription) ? "BOTH" : "TEXT")
                : ACCESSIBLE_LABEL.equals(f.contentDescription) ? "DESCRIPTION" : "NONE";
        return "identity_source=" + source + ";class=" + type + ";visible=" + f.visible + ";enabled=" + f.enabled
                + ";clickable=" + f.clickable + ";editable=" + f.editable
                + ";same_package=" + (pkg != null && pkg.equals(f.packageName))
                + ";same_window=" + (window >= 0 && window == f.windowId)
                + ";action_click=" + f.hasClickAction + ";reject=" + (reason == null ? "NONE" : reason);
    }
    static final class Selection {
        private final String pkg;
        private final int window;
        private int labels;
        private Facts observed;
        Selection(String pkg, int window) { this.pkg = pkg; this.window = window; }
        boolean consider(Facts f) {
            if (f == null || !exactIdentity(f.text, f.contentDescription)) return false;
            // Reject duplicate exact labels even if one is disabled, hidden or the wrong class.
            if (++labels > 1) throw new SecurityException("AMBIGUOUS_LINK");
            observed = f;
            return rejection(f, pkg, window) == null;
        }
        String diagnostic() { return "exact_labels=" + labels + ";" + LocalFixtureAnchor.diagnostic(observed, pkg, window); }
    }
    private LocalFixtureAnchor() { }
}
