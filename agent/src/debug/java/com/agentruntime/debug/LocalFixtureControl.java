package com.agentruntime.debug;

/** Fixed, semantic-only controls exposed by the debug fixture page. */
final class LocalFixtureControl {
    static final String INPUT_LABEL = "CUE_FIXED_INPUT_V1";
    static final String CHECK_LABEL = "CUE_FIXED_CHECK_V1";
    static final String SCROLL_LABEL = "CUE_FIXED_SCROLL_V1";
    static final String DUMMY = "CUE_SYNTHETIC_DUMMY_V1";

    private LocalFixtureControl() { }

    static boolean isAction(String method) {
        return "text_fill".equals(method) || "text_clear".equals(method)
                || "check_on".equals(method) || "check_off".equals(method)
                || "scroll_down".equals(method) || "scroll_up".equals(method);
    }

    static String labelFor(String method) {
        if ("text_fill".equals(method) || "text_clear".equals(method)) return INPUT_LABEL;
        if ("check_on".equals(method) || "check_off".equals(method)) return CHECK_LABEL;
        if ("scroll_down".equals(method) || "scroll_up".equals(method)) return SCROLL_LABEL;
        return null;
    }

    static final class Facts {
        String text = "", description = "", hint = "", className = "", packageName = "";
        int windowId;
        boolean visible, enabled, editable, checkable, checked, scrollable, clickable;
        boolean setTextAction, clickAction, forwardAction, backwardAction, boundsValid;
    }

    static boolean identity(Facts facts, String method) {
        String label = labelFor(method);
        return facts != null && label != null
                && (label.equals(facts.text) || label.equals(facts.description)
                    || (("text_fill".equals(method) || "text_clear".equals(method)) && label.equals(facts.hint)));
    }

    /** Returns a fixed classification only; it never includes node or caller data. */
    static String rejection(Facts f, String method, String expectedPkg, int expectedWindow) {
        String label = labelFor(method);
        if (label == null) return "INVALID_METHOD";
        if (f == null) return "NULL_FACTS";
        if (!identity(f, method)) return "IDENTITY_MISMATCH";
        if (expectedPkg == null || !expectedPkg.equals(f.packageName)) return "PACKAGE_MISMATCH";
        if (f.windowId != expectedWindow) return "WINDOW_MISMATCH";
        if (!f.visible) return "NOT_VISIBLE";
        if (!f.enabled) return "NOT_ENABLED";
        if (!f.boundsValid) return "INVALID_BOUNDS";
        if (!(f.text.isEmpty() || label.equals(f.text) || DUMMY.equals(f.text)))
            return "INVALID_INPUT";

        if ("text_fill".equals(method) || "text_clear".equals(method)) {
            if (!f.hint.isEmpty() && !label.equals(f.hint)) return "HINT_MISMATCH";
            if (!"android.widget.EditText".equals(f.className)) return "UNSUPPORTED_INPUT_CLASS";
            if (!f.editable) return "NOT_EDITABLE";
            if (!f.setTextAction) return "NO_SET_TEXT_ACTION";
            if ("text_fill".equals(method)) {
                if (!(f.text.isEmpty() || label.equals(f.text))) return "INVALID_INPUT";
            } else if (!DUMMY.equals(f.text)) {
                return "INVALID_INPUT";
            }
            return "NONE";
        }
        if ("check_on".equals(method) || "check_off".equals(method)) {
            if (!"android.widget.CheckBox".equals(f.className)) return "UNSUPPORTED_CHECK_CLASS";
            if (!f.checkable) return "NOT_CHECKABLE";
            if (!f.clickable) return "NOT_CLICKABLE";
            if (!f.clickAction) return "NO_CLICK_ACTION";
            if ("check_on".equals(method) ? f.checked : !f.checked)
                return "INVALID_CHECK_STATE";
            return "NONE";
        }
        if (!("android.view.View".equals(f.className)
                || "android.widget.ScrollView".equals(f.className))) return "UNSUPPORTED_SCROLL_CLASS";
        if (!f.scrollable) return "NOT_SCROLLABLE";
        if (f.editable) return "EDITABLE";
        if ("scroll_down".equals(method) && !f.forwardAction) return "NO_FORWARD_ACTION";
        if ("scroll_up".equals(method) && !f.backwardAction) return "NO_BACKWARD_ACTION";
        return "NONE";
    }
}
