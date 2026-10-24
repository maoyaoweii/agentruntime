package com.agentruntime.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalFixtureControlTest {
    private static final String PKG = "com.agentruntime.agent";
    private static final int WINDOW = 7;

    private static LocalFixtureControl.Facts base(String label) {
        LocalFixtureControl.Facts f = new LocalFixtureControl.Facts();
        f.description = label;
        f.packageName = PKG;
        f.windowId = WINDOW;
        f.visible = f.enabled = f.boundsValid = true;
        return f;
    }

    private static LocalFixtureControl.Facts input(String value) {
        LocalFixtureControl.Facts f = base(LocalFixtureControl.INPUT_LABEL);
        f.text = value;
        f.className = "android.widget.EditText";
        f.editable = f.setTextAction = true;
        return f;
    }

    private static LocalFixtureControl.Facts check(boolean checked) {
        LocalFixtureControl.Facts f = base(LocalFixtureControl.CHECK_LABEL);
        f.className = "android.widget.CheckBox";
        f.checkable = f.clickable = f.clickAction = true;
        f.checked = checked;
        return f;
    }

    private static LocalFixtureControl.Facts scroll(boolean down) {
        LocalFixtureControl.Facts f = base(LocalFixtureControl.SCROLL_LABEL);
        f.className = "android.view.View";
        f.scrollable = true;
        f.forwardAction = down;
        f.backwardAction = !down;
        return f;
    }

    @Test public void actionVocabularyAndLabelsAreClosed() {
        String[] methods = {"text_fill", "text_clear", "check_on", "check_off", "scroll_down", "scroll_up"};
        String[] labels = {LocalFixtureControl.INPUT_LABEL, LocalFixtureControl.INPUT_LABEL,
                LocalFixtureControl.CHECK_LABEL, LocalFixtureControl.CHECK_LABEL,
                LocalFixtureControl.SCROLL_LABEL, LocalFixtureControl.SCROLL_LABEL};
        for (int i = 0; i < methods.length; i++) {
            assertTrue(LocalFixtureControl.isAction(methods[i]));
            assertEquals(labels[i], LocalFixtureControl.labelFor(methods[i]));
        }
        for (String bad : new String[]{null, "", "read", "text_fill_extra", "scroll", "click"}) {
            assertFalse(LocalFixtureControl.isAction(bad));
            assertNull(LocalFixtureControl.labelFor(bad));
        }
    }

    @Test public void everyActionAcceptsOnlyItsValidFacts() {
        LocalFixtureControl.Facts fill = input("");
        assertTrue(LocalFixtureControl.identity(fill, "text_fill"));
        assertEquals("NONE", LocalFixtureControl.rejection(fill, "text_fill", PKG, WINDOW));
        fill.text = LocalFixtureControl.INPUT_LABEL;
        assertEquals("NONE", LocalFixtureControl.rejection(fill, "text_fill", PKG, WINDOW));

        LocalFixtureControl.Facts clear = input(LocalFixtureControl.DUMMY);
        assertTrue(LocalFixtureControl.identity(clear, "text_clear"));
        assertEquals("NONE", LocalFixtureControl.rejection(clear, "text_clear", PKG, WINDOW));

        LocalFixtureControl.Facts on = check(false);
        assertTrue(LocalFixtureControl.identity(on, "check_on"));
        assertEquals("NONE", LocalFixtureControl.rejection(on, "check_on", PKG, WINDOW));
        LocalFixtureControl.Facts off = check(true);
        assertEquals("NONE", LocalFixtureControl.rejection(off, "check_off", PKG, WINDOW));

        LocalFixtureControl.Facts down = scroll(true);
        assertTrue(LocalFixtureControl.identity(down, "scroll_down"));
        assertEquals("NONE", LocalFixtureControl.rejection(down, "scroll_down", PKG, WINDOW));
        LocalFixtureControl.Facts up = scroll(false);
        assertEquals("NONE", LocalFixtureControl.rejection(up, "scroll_up", PKG, WINDOW));
    }

    @Test public void identityRequiresExactMethodLabelInTextOrDescription() {
        for (String method : new String[]{"text_fill", "text_clear", "check_on", "check_off", "scroll_down", "scroll_up"}) {
            String label = LocalFixtureControl.labelFor(method);
            LocalFixtureControl.Facts f = base(label);
            assertTrue(LocalFixtureControl.identity(f, method));
            f.description = "prefix" + label;
            assertFalse(LocalFixtureControl.identity(f, method));
            f.description = label + "suffix";
            assertFalse(LocalFixtureControl.identity(f, method));
            f.description = "";
            f.text = label;
            assertTrue(LocalFixtureControl.identity(f, method));
            f.text = label + " ";
            assertFalse(LocalFixtureControl.identity(f, method));
        }
        assertFalse(LocalFixtureControl.identity(null, "text_fill"));
        assertFalse(LocalFixtureControl.identity(base(LocalFixtureControl.INPUT_LABEL), "bad"));
    }

    @Test public void wrongClassPackageWindowAndVisibilityAreRejected() {
        LocalFixtureControl.Facts f = input("");
        f.className = "android.widget.TextView";
        assertEquals("UNSUPPORTED_INPUT_CLASS", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        f = input(""); f.packageName = "other.pkg";
        assertEquals("PACKAGE_MISMATCH", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        f = input("");
        assertEquals("WINDOW_MISMATCH", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW + 1));
        f = input(""); f.visible = false;
        assertEquals("NOT_VISIBLE", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        f = input(""); f.enabled = false;
        assertEquals("NOT_ENABLED", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        f = input(""); f.boundsValid = false;
        assertEquals("INVALID_BOUNDS", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        assertEquals("NULL_FACTS", LocalFixtureControl.rejection(null, "text_fill", PKG, WINDOW));
        assertEquals("INVALID_METHOD", LocalFixtureControl.rejection(f, "nope", PKG, WINDOW));
    }

    @Test public void inputValuesAndActionsAreStrict() {
        for (String bad : new String[]{LocalFixtureControl.DUMMY, "arbitrary", "CUE_FIXED_INPUT_V1x"})
            assertEquals("INVALID_INPUT", LocalFixtureControl.rejection(input(bad), "text_fill", PKG, WINDOW));
        assertEquals("INVALID_INPUT", LocalFixtureControl.rejection(input(""), "text_clear", PKG, WINDOW));
        assertEquals("INVALID_INPUT", LocalFixtureControl.rejection(input(LocalFixtureControl.INPUT_LABEL), "text_clear", PKG, WINDOW));
        LocalFixtureControl.Facts f = input(""); f.editable = false;
        assertEquals("NOT_EDITABLE", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
        f = input(""); f.setTextAction = false;
        assertEquals("NO_SET_TEXT_ACTION", LocalFixtureControl.rejection(f, "text_fill", PKG, WINDOW));
    }

    @Test public void checkboxAndScrollStateAndActionsAreStrict() {
        LocalFixtureControl.Facts f = check(true);
        assertEquals("INVALID_CHECK_STATE", LocalFixtureControl.rejection(f, "check_on", PKG, WINDOW));
        f = check(false);
        assertEquals("INVALID_CHECK_STATE", LocalFixtureControl.rejection(f, "check_off", PKG, WINDOW));
        f = check(false); f.checkable = false;
        assertEquals("NOT_CHECKABLE", LocalFixtureControl.rejection(f, "check_on", PKG, WINDOW));
        f = check(false); f.clickable = false;
        assertEquals("NOT_CLICKABLE", LocalFixtureControl.rejection(f, "check_on", PKG, WINDOW));
        f = check(false); f.clickAction = false;
        assertEquals("NO_CLICK_ACTION", LocalFixtureControl.rejection(f, "check_on", PKG, WINDOW));

        f = scroll(true); f.forwardAction = false;
        assertEquals("NO_FORWARD_ACTION", LocalFixtureControl.rejection(f, "scroll_down", PKG, WINDOW));
        f = scroll(false); f.backwardAction = false;
        assertEquals("NO_BACKWARD_ACTION", LocalFixtureControl.rejection(f, "scroll_up", PKG, WINDOW));
        f = scroll(true); f.className = "android.widget.ScrollView";
        assertEquals("NONE", LocalFixtureControl.rejection(f, "scroll_down", PKG, WINDOW));
        f = scroll(true); f.editable = true;
        assertEquals("EDITABLE", LocalFixtureControl.rejection(f, "scroll_down", PKG, WINDOW));
        f = scroll(true); f.scrollable = false;
        assertEquals("NOT_SCROLLABLE", LocalFixtureControl.rejection(f, "scroll_down", PKG, WINDOW));
        f = scroll(true); f.className = "android.widget.EditText";
        assertEquals("UNSUPPORTED_SCROLL_CLASS", LocalFixtureControl.rejection(f, "scroll_down", PKG, WINDOW));
    }
}
