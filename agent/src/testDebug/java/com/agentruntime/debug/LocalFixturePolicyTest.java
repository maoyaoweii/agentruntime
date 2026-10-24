package com.agentruntime.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalFixturePolicyTest {
    private static void denied(Runnable r) {
        try { r.run(); fail("must deny"); } catch (SecurityException expected) { }
    }
    @Test public void onlyDebugShellApi30X86Avd() {
        LocalFixturePolicy.authorize(true, 2000, 30, "x86_64", "ranchu");
        LocalFixturePolicy.authorize(true, 2000, 30, "x86_64", "goldfish");
        denied(() -> LocalFixturePolicy.authorize(false, 2000, 30, "x86_64", "ranchu"));
        for (int uid : new int[]{0, 1000, 10001, 99999})
            denied(() -> LocalFixturePolicy.authorize(true, uid, 30, "x86_64", "ranchu"));
        denied(() -> LocalFixturePolicy.authorize(true, 2000, 31, "x86_64", "ranchu"));
        denied(() -> LocalFixturePolicy.authorize(true, 2000, 30, "arm64-v8a", "ranchu"));
        denied(() -> LocalFixturePolicy.authorize(true, 2000, 30, "x86_64", "physical"));
    }
    @Test public void exactUrlOnly() {
        assertTrue(LocalFixturePolicy.allowedUrl(LocalFixturePolicy.URL));
        assertTrue(LocalFixturePolicy.allowedUrl(LocalFixturePolicy.URL + "#allowed"));
        for (String url : new String[]{null, "https://example.com", "http://127.0.0.1", "file:///x",
                "content://x", "javascript:alert(1)", "intent:x", "https://synthetic.invalid.evil/fixture",
                LocalFixturePolicy.URL + "?x=1", LocalFixturePolicy.URL + "/", LocalFixturePolicy.URL + "#other",
                "https://user@synthetic.invalid/fixture", "https://synthetic.invalid:443/fixture"})
            assertFalse(String.valueOf(url), LocalFixturePolicy.allowedUrl(url));
    }
    @Test public void rejectsSchemeAuthorityPathQueryAndFragmentVariants() {
        String[] urls = {
            "about:blank", "about:blank#allowed", "data:text/html,fixed", "file:///fixture",
            "content://synthetic.invalid/fixture", "http://synthetic.invalid/fixture",
            "HTTPS://synthetic.invalid/fixture", "//synthetic.invalid/fixture",
            "https:synthetic.invalid/fixture", "https:///synthetic.invalid/fixture",
            "https://SYNTHETIC.INVALID/fixture", "https://synthetic.invalid./fixture",
            "https://synthetic.invalid.evil/fixture", "https://evil.synthetic.invalid/fixture",
            "https://synthetic.invalid@evil.invalid/fixture", "https://user@synthetic.invalid/fixture",
            "https://user:password@synthetic.invalid/fixture", "https://synthetic.invalid:/fixture",
            "https://synthetic.invalid:443/fixture", "https://synthetic.invalid:0443/fixture",
            "https://synthetic.invalid:80/fixture", "https://synthetic.invalid/",
            "https://synthetic.invalid/fixture/", "https://synthetic.invalid/fixture/extra",
            "https://synthetic.invalid//fixture", "https://synthetic.invalid/./fixture",
            "https://synthetic.invalid/other/../fixture", "https://synthetic.invalid/Fixture",
            "https://synthetic.invalid/fixture?", "https://synthetic.invalid/fixture?x=1",
            "https://synthetic.invalid/fixture?x=1#allowed", "https://synthetic.invalid/fixture#",
            "https://synthetic.invalid/fixture#other", "https://synthetic.invalid/fixture#ALLOWED",
            "https://synthetic.invalid/fixture#allowed/", "https://synthetic.invalid/fixture#allowed?x=1",
            "https://synthetic.invalid/fixture#allowed#allowed", " https://synthetic.invalid/fixture",
            "https://synthetic.invalid/fixture ", "https://synthetic.invalid/fixture\n"
        };
        for (String url : urls) assertFalse(url, LocalFixturePolicy.allowedUrl(url));
    }
    @Test public void rejectsEncodedAndUnicodeAliasesWithoutNormalization() {
        for (String url : new String[]{
                "https://%73ynthetic.invalid/fixture", "https://synthetic%2einvalid/fixture",
                "https://synthetic.invalid/%66ixture", "https://synthetic.invalid/fixture%2F",
                "https://synthetic.invalid/%2e/fixture", "https://synthetic.invalid/fixture%3F",
                "https://synthetic.invalid/fixture#%61llowed", "https://synthetic.invalid/fixture#allowed%20",
                "https://synthetic.invalid/fixture%23allowed", "https://synthetic.invalid\\fixture",
                "https://synthetic.invalid/fixtur\u0435", "https://synthetic\u3002invalid/fixture"})
            assertFalse(url, LocalFixturePolicy.allowedUrl(url));
    }
    @Test public void diagnosticsAreFixedClassificationsNotAnUrlEchoOrPermission() {
        assertEquals("EXACT_BASE", LocalFixturePolicy.urlDiagnostic(LocalFixturePolicy.URL));
        assertEquals("EXACT_ALLOWED_FRAGMENT", LocalFixturePolicy.urlDiagnostic(LocalFixturePolicy.URL + "#allowed"));
        assertEquals("ABOUT_BLANK_REJECTED", LocalFixturePolicy.urlDiagnostic("about:blank"));
        assertFalse(LocalFixturePolicy.allowedUrl("about:blank"));
        assertEquals("NO_URL", LocalFixturePolicy.urlDiagnostic(null));
        assertEquals("UNEXPECTED_URL_REDACTED", LocalFixturePolicy.urlDiagnostic("https://private.invalid/?secret=hidden"));
    }
    @Test public void fixedMethodsNoExtraControls() {
        String token = new LocalFixturePolicy().open(0);
        LocalFixturePolicy.validateRequest("open", null, false);
        for (String method : new String[]{"read", "click", "close"})
            LocalFixturePolicy.validateRequest(method, token, false);
        for (String method : new String[]{"tap", "setText", "launch", "navigate", "query", "READ", "", null})
            denied(() -> LocalFixturePolicy.validateRequest(method, token, false));
        denied(() -> LocalFixturePolicy.validateRequest("open", "https://example.com", false));
        denied(() -> LocalFixturePolicy.validateRequest("read", token, true));
        denied(() -> LocalFixturePolicy.validateRequest("click", "#other", false));
        denied(() -> LocalFixturePolicy.validateRequest("read", null, false));
    }
    @Test public void nonceExpiryRotationAndClose() {
        LocalFixturePolicy p = new LocalFixturePolicy();
        String first = p.open(100);
        assertTrue(first.matches("[a-f0-9]{64}"));
        p.require(first, 100);
        p.require(first, 100 + LocalFixturePolicy.TTL_MS - 1);
        denied(() -> p.require(first, 100 + LocalFixturePolicy.TTL_MS));
        denied(() -> p.require(null, 101));
        denied(() -> p.require("0".repeat(64), 101));
        String second = p.open(200);
        assertNotEquals(first, second);
        denied(() -> p.require(first, 201));
        p.require(second, 201);
        p.close();
        denied(() -> p.require(second, 202));
    }
    @Test public void clickAtMostOnceButReadingRemainsAllowed() {
        LocalFixturePolicy p = new LocalFixturePolicy();
        String token = p.open(1);
        denied(() -> p.consumeClick("bad", 2));
        p.consumeClick(token, 2);
        p.require(token, 3);
        denied(() -> p.consumeClick(token, 3));
        String next = p.open(4);
        p.consumeClick(next, 5);
    }
    @Test public void extendedControlsAreFixedMethodsWithoutCallerValuesOrTargets() {
        LocalFixturePolicy p = new LocalFixturePolicy();
        String token = p.open(0);
        for (String method : new String[]{"text_fill", "text_clear", "check_on", "check_off", "scroll_down", "scroll_up", "capture"}) {
            LocalFixturePolicy.validateRequest(method, token, false);
            denied(() -> LocalFixturePolicy.validateRequest(method, token, true));
            denied(() -> LocalFixturePolicy.validateRequest(method, "caller text or selector", false));
        }
        for (String method : new String[]{"input", "scroll", "screenshot", "set_text", "capture_external", "text_fill_extra"})
            denied(() -> LocalFixturePolicy.validateRequest(method, token, false));
    }
    @Test public void everyFixedOperationHasOneNonRetryablePermitPerSession() {
        LocalFixturePolicy p = new LocalFixturePolicy();
        String token = p.open(10);
        for (String method : new String[]{"text_fill", "text_clear", "check_on", "check_off", "scroll_down", "scroll_up", "capture"}) {
            assertFalse(p.consumed(method));
            p.consumeFixed(token, 11, method);
            assertTrue(p.consumed(method));
            denied(() -> p.consumeFixed(token, 12, method));
        }
        denied(() -> p.consumeFixed(token, 13, "launch"));
        String next = p.open(20);
        assertFalse(p.consumed("text_fill"));
        denied(() -> p.consumeFixed(token, 21, "text_fill"));
        denied(() -> p.consumeFixed(next, 20 + LocalFixturePolicy.TTL_MS, "text_fill"));
        p.close();
        denied(() -> p.consumeFixed(next, 21, "text_fill"));
    }
}
