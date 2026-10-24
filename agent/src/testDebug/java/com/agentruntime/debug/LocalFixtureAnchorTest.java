package com.agentruntime.debug;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LocalFixtureAnchorTest {
    private static final String PKG = "com.agentruntime.agent";
    private static final int WINDOW = 7;
    private static LocalFixtureAnchor.Facts facts(String text, String cls, String pkg, int window,
            boolean visible, boolean enabled, boolean clickable, boolean editable, boolean action) {
        return new LocalFixtureAnchor.Facts(text, cls, pkg, window, visible, enabled, clickable, editable, action);
    }
    private static LocalFixtureAnchor.Facts good(String cls) {
        return facts(LocalFixtureAnchor.ACCESSIBLE_LABEL, cls, PKG, WINDOW, true, true, true, false, true);
    }
    private static LocalFixtureAnchor.Facts[] invalidFacts() {
        String l = LocalFixtureAnchor.ACCESSIBLE_LABEL, c = "android.widget.TextView";
        return new LocalFixtureAnchor.Facts[]{
            facts(l + "!", c, PKG, WINDOW, true, true, true, false, true),
            facts(l, "android.widget.Button", PKG, WINDOW, true, true, true, false, true),
            facts(l, c, PKG, WINDOW, false, true, true, false, true),
            facts(l, c, PKG, WINDOW, true, false, true, false, true),
            facts(l, c, PKG, WINDOW, true, true, false, false, true),
            facts(l, c, PKG, WINDOW, true, true, true, true, true),
            facts(l, c, "other.application", WINDOW, true, true, true, false, true),
            facts(l, c, PKG, WINDOW + 1, true, true, true, false, true),
            facts(l, c, PKG, WINDOW, true, true, true, false, false)
        };
    }
    private static void fakeSubmit(LocalFixturePolicy policy, String token, boolean refreshed,
            LocalFixtureAnchor.Facts fresh, AtomicInteger submissions) {
        LocalFixtureAnchor.requireRefreshed(refreshed, fresh, PKG, WINDOW);
        policy.consumeClick(token, 1);
        submissions.incrementAndGet(); // Fake ACTION_CLICK; no Android device used.
    }
    private static void assertDenied(Runnable work) {
        try { work.run(); fail("must reject"); } catch (SecurityException expected) { }
    }

    @Test public void exactViewAndTextViewAreEligibleOnlyForTheFixedLabel() {
        for (String cls : new String[]{"android.view.View", "android.widget.TextView"}) {
            LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
            assertTrue(s.consider(good(cls)));
            assertNull(LocalFixtureAnchor.rejection(good(cls), PKG, WINDOW));
            assertTrue(s.diagnostic().contains("exact_labels=1"));
            assertTrue(s.diagnostic().endsWith("reject=NONE"));
        }
    }
    @Test public void scanRejectsEachInvalidPropertyAndWrongClass() {
        for (LocalFixtureAnchor.Facts f : invalidFacts()) {
            LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
            assertFalse(s.consider(f));
            assertNotNull(LocalFixtureAnchor.rejection(f, PKG, WINDOW));
        }
        for (String cls : new String[]{"android.webkit.WebView", "android.widget.EditText", "android.widget.Button", "", null})
            assertEquals("UNSUPPORTED_LINK_CLASS", LocalFixtureAnchor.rejection(good(cls), PKG, WINDOW));
        assertEquals("WINDOW_CHANGED", LocalFixtureAnchor.rejection(good("android.view.View"), PKG, -1));
    }
    @Test public void duplicateExactLabelsDenyEvenIfSecondIsHiddenDisabledOrWrongClass() {
        for (LocalFixtureAnchor.Facts second : new LocalFixtureAnchor.Facts[]{good("android.view.View"),
                facts(LocalFixtureAnchor.ACCESSIBLE_LABEL, "android.widget.TextView", PKG, WINDOW, false, false, false, false, false),
                facts(LocalFixtureAnchor.ACCESSIBLE_LABEL, "android.widget.Button", PKG, WINDOW, true, true, true, false, true)}) {
            LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
            assertTrue(s.consider(good("android.widget.TextView")));
            assertDenied(() -> s.consider(second));
        }
    }
    @Test public void missingLabelOrAncestorLikeNodeNeverCreatesCandidate() {
        LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
        assertFalse(s.consider(facts("container", "android.view.View", PKG, WINDOW, true, true, true, false, true)));
        assertFalse(s.consider(null));
        assertEquals("exact_labels=0;LABEL_NOT_FOUND", s.diagnostic());
    }
    @Test public void explicitAriaLabelMayUseTextOrDescriptionButSameNodeCountsOnce() {
        String id = LocalFixtureAnchor.ACCESSIBLE_LABEL;
        for (LocalFixtureAnchor.Facts f : new LocalFixtureAnchor.Facts[]{
                good("android.view.View"),
                new LocalFixtureAnchor.Facts(LocalFixturePolicy.LINK, "android.view.View", PKG, WINDOW,
                        true, true, true, false, true, id),
                new LocalFixtureAnchor.Facts(id, "android.view.View", PKG, WINDOW,
                        true, true, true, false, true, id)}) {
            LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
            assertTrue(s.consider(f));
            assertTrue(s.diagnostic().startsWith("exact_labels=1;"));
            assertNull(LocalFixtureAnchor.rejection(f, PKG, WINDOW));
        }
    }
    @Test public void duplicatesAcrossTextAndDescriptionAreRejected() {
        LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
        assertTrue(s.consider(good("android.view.View")));
        LocalFixtureAnchor.Facts second = new LocalFixtureAnchor.Facts(LocalFixturePolicy.LINK,
                "android.view.View", PKG, WINDOW, true, true, true, false, true, LocalFixtureAnchor.ACCESSIBLE_LABEL);
        assertDenied(() -> s.consider(second));
    }
    @Test public void ordinaryLinkTextAndMarkerVariantsNeverSelectAnyNode() {
        String id = LocalFixtureAnchor.ACCESSIBLE_LABEL;
        for (String value : new String[]{LocalFixturePolicy.LINK, "container", id + "!", " " + id,
                id + " ", id.toLowerCase(java.util.Locale.ROOT), "prefix:" + id, id + ":suffix", ""}) {
            LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
            assertFalse(s.consider(facts(value, "android.view.View", PKG, WINDOW, true, true, true, false, true)));
            assertFalse(s.consider(new LocalFixtureAnchor.Facts("", "android.view.View", PKG, WINDOW,
                    true, true, true, false, true, value)));
        }
    }
    @Test public void explicitLabelDoesNotMakeNonActionableTextClickable() {
        LocalFixtureAnchor.Facts f = new LocalFixtureAnchor.Facts(LocalFixturePolicy.LINK,
                "android.widget.TextView", PKG, WINDOW, true, true, false, false, false,
                LocalFixtureAnchor.ACCESSIBLE_LABEL);
        assertEquals("NOT_CLICKABLE", LocalFixtureAnchor.rejection(f, PKG, WINDOW));
        AtomicInteger submitted = new AtomicInteger();
        LocalFixturePolicy policy = new LocalFixturePolicy();
        String token = policy.open(0);
        assertDenied(() -> fakeSubmit(policy, token, true, f, submitted));
        assertEquals(0, submitted.get());
    }
    @Test public void refreshFailureCannotSubmitAction() {
        AtomicInteger submitted = new AtomicInteger();
        LocalFixturePolicy policy = new LocalFixturePolicy();
        String token = policy.open(0);
        assertDenied(() -> fakeSubmit(policy, token, false, good("android.widget.TextView"), submitted));
        assertEquals(0, submitted.get());
    }
    @Test public void everyRefreshedAttributeChangePreventsAction() {
        for (LocalFixtureAnchor.Facts changed : invalidFacts()) {
            AtomicInteger submitted = new AtomicInteger();
            LocalFixturePolicy policy = new LocalFixturePolicy();
            String token = policy.open(0);
            assertDenied(() -> fakeSubmit(policy, token, true, changed, submitted));
            assertEquals(0, submitted.get());
        }
    }
    @Test public void refreshedValidNodeCanSubmitExactlyOncePerSession() {
        AtomicInteger submitted = new AtomicInteger();
        LocalFixturePolicy policy = new LocalFixturePolicy();
        String token = policy.open(0);
        LocalFixtureAnchor.Selection s = new LocalFixtureAnchor.Selection(PKG, WINDOW);
        assertTrue(s.consider(good("android.widget.TextView")));
        fakeSubmit(policy, token, true, good("android.widget.TextView"), submitted);
        assertEquals(1, submitted.get());
        assertDenied(() -> fakeSubmit(policy, token, true, good("android.widget.TextView"), submitted));
        assertEquals(1, submitted.get());
    }
    @Test public void diagnosticCannotEchoUnexpectedTextPackageOrClass() {
        String diag = LocalFixtureAnchor.diagnostic(facts("private text", "private.Class", "private.package",
                99, true, true, true, false, true), PKG, WINDOW);
        assertTrue(diag.contains("OTHER_CLASS_REDACTED"));
        assertFalse(diag.contains("private"));
        assertTrue(diag.contains("same_package=false"));
        assertTrue(diag.contains("same_window=false"));
    }
}
