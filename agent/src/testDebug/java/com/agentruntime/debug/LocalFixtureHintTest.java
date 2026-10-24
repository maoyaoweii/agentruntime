package com.agentruntime.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalFixtureHintTest {
    private static LocalFixtureControl.Facts input() {
        LocalFixtureControl.Facts f = new LocalFixtureControl.Facts();
        f.hint = LocalFixtureControl.INPUT_LABEL;
        f.className = "android.widget.EditText";
        f.packageName = "com.agentruntime.agent";
        f.windowId = 7;
        f.visible = f.enabled = f.editable = f.setTextAction = f.boundsValid = true;
        return f;
    }
    @Test public void exactNativeHintIdentifiesOnlyFixedEditableInput() {
        LocalFixtureControl.Facts f = input();
        assertTrue(LocalFixtureControl.identity(f, "text_fill"));
        assertEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
        f.text = LocalFixtureControl.DUMMY;
        assertEquals("NONE", LocalFixtureControl.rejection(f, "text_clear", f.packageName, 7));
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
    }
    @Test public void hintAliasesAndUnexpectedValuesNeverGrantControl() {
        for (String hint : new String[]{"", " " + LocalFixtureControl.INPUT_LABEL, LocalFixtureControl.INPUT_LABEL + " ",
                LocalFixtureControl.INPUT_LABEL + "x", "CUE_FIXED_INPUT_V2", LocalFixtureControl.CHECK_LABEL}) {
            LocalFixtureControl.Facts f = input(); f.hint = hint;
            assertFalse(LocalFixtureControl.identity(f, "text_fill"));
            assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
        }
        LocalFixtureControl.Facts f = input(); f.text = "arbitrary value";
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
        f.text = ""; f.description = LocalFixtureControl.INPUT_LABEL; f.hint = "unexpected hint";
        assertEquals("HINT_MISMATCH", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
    }
    @Test public void nativeHintCannotBypassClassWindowOrAction() {
        LocalFixtureControl.Facts f = input(); f.className = "android.view.View";
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
        f = input(); f.setTextAction = false;
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
        f = input();
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 8));
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", "other.package", 7));
        f.editable = false;
        assertNotEquals("NONE", LocalFixtureControl.rejection(f, "text_fill", f.packageName, 7));
    }
    @Test public void hintIsNotAFallbackForCheckboxOrScroll() {
        LocalFixtureControl.Facts f = input(); f.hint = LocalFixtureControl.CHECK_LABEL;
        assertFalse(LocalFixtureControl.identity(f, "check_on"));
        f.hint = LocalFixtureControl.SCROLL_LABEL;
        assertFalse(LocalFixtureControl.identity(f, "scroll_down"));
    }
}
