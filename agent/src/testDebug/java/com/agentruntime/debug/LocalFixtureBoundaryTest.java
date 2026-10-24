package com.agentruntime.debug;

import android.app.Application;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class LocalFixtureBoundaryTest {
    private static android.webkit.WebResourceRequest request(String url, boolean main, String method) {
        return new android.webkit.WebResourceRequest() {
            @Override public android.net.Uri getUrl() { return android.net.Uri.parse(url); }
            @Override public boolean isForMainFrame() { return main; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return method; }
            @Override public java.util.Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
        };
    }
    private static String body(android.webkit.WebResourceResponse response) throws Exception {
        assertNotNull(response); // A null response would fall through to network and must never be returned.
        try (java.io.InputStream in = response.getData(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test public void fixtureIsOfflineAndCannotNavigateElsewhere() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            WebSettings s = a.page.getSettings();
            assertFalse(s.getJavaScriptEnabled());
            assertFalse(s.getAllowFileAccess());
            assertFalse(s.getAllowContentAccess());
            assertFalse(s.getDomStorageEnabled());
            assertFalse(s.getDatabaseEnabled());
            assertTrue(s.getBlockNetworkLoads());
            assertTrue(s.getBlockNetworkImage());
            assertEquals(WebSettings.LOAD_NO_CACHE, s.getCacheMode());
            WebViewClient client = a.page.getWebViewClient();
            assertFalse(client.shouldOverrideUrlLoading(a.page,
                    request(LocalFixturePolicy.URL + "#allowed", true, "GET")));
            for (String url : new String[]{"https://example.com", "file:///x", "content://x", "intent:x",
                    "javascript:alert(1)", LocalFixturePolicy.URL + "#other"}) {
                assertTrue(client.shouldOverrideUrlLoading(a.page, url));
                assertNotNull(client.shouldInterceptRequest(a.page, url));
            }
            assertNotNull(client.shouldInterceptRequest(a.page, LocalFixturePolicy.URL));
        }
    }
    @Test public void compiledHtmlUsesOneRealExactOfflineDocumentInsteadOfVirtualHistory() throws Exception {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            org.robolectric.shadows.ShadowWebView shadow = org.robolectric.Shadows.shadowOf(a.page);
            assertEquals(LocalFixturePolicy.URL, shadow.getLastLoadedUrl());
            assertNull(shadow.getLastLoadDataWithBaseURL());
            android.webkit.WebResourceResponse response = a.page.getWebViewClient().shouldInterceptRequest(a.page,
                    request(LocalFixturePolicy.URL, true, "GET"));
            assertEquals("text/html", response.getMimeType());
            assertEquals("UTF-8", response.getEncoding());
            assertEquals(LocalFixtureActivity.HTML, body(response));
            assertEquals("", body(a.page.getWebViewClient().shouldInterceptRequest(a.page, LocalFixturePolicy.URL)));
        }
    }
    @Test public void documentResponseRejectsEveryOtherResourceFrameAndMethod() throws Exception {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            WebViewClient client = a.page.getWebViewClient();
            for (String url : new String[]{LocalFixturePolicy.URL + "#allowed", LocalFixturePolicy.URL + "?x=1",
                    "https://synthetic.invalid:443/fixture", "https://synthetic.invalid/%66ixture",
                    "https://synthetic.invalid/fixture/", "https://example.com/fixture", "about:blank",
                    "file:///fixture", "data:text/html,fixture"}) {
                assertFalse(LocalFixtureActivity.servesCompiledDocument(url, true, "GET"));
                assertEquals(url, "", body(client.shouldInterceptRequest(a.page, request(url, true, "GET"))));
            }
            assertEquals("", body(client.shouldInterceptRequest(a.page, request(LocalFixturePolicy.URL, false, "GET"))));
            for (String method : new String[]{"POST", "HEAD", "get", "", null})
                assertEquals("", body(client.shouldInterceptRequest(a.page, request(LocalFixturePolicy.URL, true, method))));
            android.webkit.WebView other = new android.webkit.WebView(a);
            assertEquals("", body(client.shouldInterceptRequest(other, request(LocalFixturePolicy.URL, true, "GET"))));
        }
    }
    @Test public void onlyOneExactSamePageFragmentMayContinueNativeNavigation() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            WebViewClient client = a.page.getWebViewClient();
            String target = LocalFixturePolicy.URL + "#allowed";
            assertFalse(client.shouldOverrideUrlLoading(a.page, request(target, true, "GET")));
            assertTrue(client.shouldOverrideUrlLoading(a.page, target)); // Legacy metadata is insufficient.
            assertTrue(LocalFixtureActivity.allowFixedFragment(target, target, true, "GET")); // pending visible URL
            assertTrue(client.shouldOverrideUrlLoading(a.page, request(target, false, "GET")));
            assertTrue(client.shouldOverrideUrlLoading(a.page, request(target, true, "POST")));
            assertFalse(LocalFixtureActivity.allowFixedFragment("https://example.com", target, true, "GET"));
            for (String rejected : new String[]{LocalFixturePolicy.URL, target + "x", target + "?x=1",
                    LocalFixturePolicy.URL + "#other", LocalFixturePolicy.URL + "#%61llowed",
                    LocalFixturePolicy.URL + "?x=1#allowed", "https://synthetic.invalid:443/fixture#allowed",
                    "https://synthetic.invalid/%66ixture#allowed", "https://example.com/fixture#allowed",
                    "javascript:alert(1)", "about:blank", "file:///fixture#allowed"}) {
                assertTrue(rejected, client.shouldOverrideUrlLoading(a.page, rejected));
                assertTrue(rejected, client.shouldOverrideUrlLoading(a.page, request(rejected, true, "GET")));
            }
        }
    }
    @Test public void decisionsAndCallbacksNeverLoadOrFabricateTheReadbackUrl() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            WebViewClient client = a.page.getWebViewClient();
            assertFalse(client.shouldOverrideUrlLoading(a.page,
                    request(LocalFixturePolicy.URL + "#allowed", true, "GET")));
            client.onPageFinished(a.page, LocalFixturePolicy.URL + "#allowed");
            assertEquals("EXACT_ALLOWED_FRAGMENT", a.finishedUrlState);
            assertFalse(a.ready); // Callback and actual current URL disagree; fail closed.
            assertEquals(LocalFixturePolicy.URL, a.page.getUrl());
            assertEquals(LocalFixturePolicy.URL, org.robolectric.Shadows.shadowOf(a.page).getLastLoadedUrl());
            assertTrue(a.urlDiagnostics().contains("current=EXACT_BASE;"));
        }
    }
    @Test public void legacyNavigationNeverAssumesMainFrameOrGet() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            for (String url : new String[]{LocalFixturePolicy.URL, LocalFixturePolicy.URL + "#allowed", "about:blank"})
                assertTrue(a.page.getWebViewClient().shouldOverrideUrlLoading(a.page, url));
        }
    }
    @Test public void navigationStillRejectsBlankPortsQueriesAndEncodedAliases() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            WebViewClient client = a.page.getWebViewClient();
            for (String url : new String[]{"about:blank", "data:text/html,fixture",
                    "https://synthetic.invalid:443/fixture", "https://synthetic.invalid/fixture?x=1",
                    "https://synthetic.invalid/fixture/", "https://synthetic.invalid/%66ixture",
                    "https://synthetic.invalid/fixture#%61llowed", "https://synthetic.invalid/fixture#other"}) {
                assertTrue(url, client.shouldOverrideUrlLoading(a.page, url));
                assertNotNull(client.shouldInterceptRequest(a.page, url));
            }
            client.onPageFinished(a.page, "about:blank");
            assertFalse(a.ready);
            assertEquals("ABOUT_BLANK_REJECTED", a.finishedUrlState);
            client.onPageFinished(a.page, LocalFixturePolicy.URL);
            assertTrue(a.ready);
            assertEquals("EXACT_BASE", a.finishedUrlState);
            assertFalse(a.valid()); // A callback alone cannot bypass resume/focus/current URL checks.
        }
    }
    @Test public void pauseRevokesSessionAndDestroyDropsActivity() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class)
                .create().start().resume()) {
            String token = LocalFixtureProvider.POLICY.open(1);
            assertSame(c.get(), LocalFixtureActivity.current);
            c.pause();
            try { LocalFixtureProvider.POLICY.require(token, 2); fail("pause must revoke"); }
            catch (SecurityException expected) { }
        }
        assertNull(LocalFixtureActivity.current);
    }
    @Test public void ordinaryAppUidCannotInvokeProvider() {
        LocalFixtureProvider provider = new LocalFixtureProvider();
        try { provider.call("open", null, null); fail("must reject non-shell"); }
        catch (SecurityException expected) { assertEquals("LOCAL_AVD_SHELL_ONLY", expected.getMessage()); }
    }
    @Test public void providerDoesNotHoldLifecycleMonitorAcrossAccessibilityIpc() throws Exception {
        java.lang.reflect.Method call = LocalFixtureProvider.class.getDeclaredMethod(
                "call", String.class, String.class, android.os.Bundle.class);
        assertFalse(java.lang.reflect.Modifier.isSynchronized(call.getModifiers()));
    }
    @Test public void foregroundDiagnosticsDoNotBypassLifecycleOrFocusChecks() {
        try (ActivityController<LocalFixtureActivity> c = Robolectric.buildActivity(LocalFixtureActivity.class).create()) {
            LocalFixtureActivity a = c.get();
            assertEquals("NOT_RESUMED", a.invalidReason());
            assertFalse(a.valid());
            a.resumed = true;
            assertEquals("NO_WINDOW_FOCUS", a.invalidReason());
            assertFalse(a.valid());
        }
    }
    @Test public void fixtureContainsOnlyTheCompiledLink() {
        assertEquals(1, LocalFixtureActivity.HTML.split("<a ", -1).length - 1);
        assertTrue(LocalFixtureActivity.HTML.contains("href='" + LocalFixturePolicy.URL + "#allowed'"));
        assertFalse(LocalFixtureActivity.HTML.contains("<script"));
        assertFalse(LocalFixtureActivity.HTML.contains("<iframe"));
        assertFalse(LocalFixtureActivity.HTML.contains("<form"));
        assertFalse(LocalFixtureActivity.HTML.contains("<img"));
    }
    @Test public void uniqueNativeAnchorHasExactSemanticIdentityAndSamePageHref() {
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("<a ([^>]+)>([^<]+)</a>")
                .matcher(LocalFixtureActivity.HTML);
        assertTrue(a.find());
        String attrs = a.group(1);
        assertEquals(LocalFixturePolicy.LINK, a.group(2));
        assertTrue(attrs.contains("id='fixed-allowed-link'"));
        assertTrue(attrs.contains("role='link'"));
        assertTrue(attrs.contains("tabindex='0'"));
        assertTrue(attrs.contains("aria-label='" + LocalFixtureAnchor.ACCESSIBLE_LABEL + "'"));
        assertTrue(attrs.contains("style='display:inline-block'"));
        java.util.regex.Matcher href = java.util.regex.Pattern.compile("(?:^| )href='([^']*)'").matcher(attrs);
        assertTrue(href.find());
        assertEquals(LocalFixturePolicy.URL + "#allowed", href.group(1));
        assertTrue(LocalFixturePolicy.allowedUrl(href.group(1)));
        assertFalse(href.find());
        assertFalse(a.find());
        assertEquals(1, LocalFixtureActivity.HTML.split(LocalFixtureAnchor.ACCESSIBLE_LABEL, -1).length - 1);
    }
    @Test public void semanticAnchorAddsNoScriptHandlerOrAlternativeControl() {
        String html = LocalFixtureActivity.HTML.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : new String[]{"onclick", "onkeydown", "onchange", "javascript:", "<button",
                "<script", "<iframe", "aria-hidden", "hidden=", "display:none"})
            assertFalse(forbidden, html.contains(forbidden));
        assertFalse(LocalFixturePolicy.allowedUrl(LocalFixturePolicy.URL + "#other"));
        assertFalse(LocalFixturePolicy.allowedUrl(LocalFixturePolicy.URL + "#allowed?x=1"));
        assertFalse(LocalFixturePolicy.allowedUrl(LocalFixturePolicy.URL + "?x=1#allowed"));
    }
    @Test public void syntheticControlsAreUniqueFixedAndCannotSubmitOrLoadAnything() {
        String html = LocalFixtureActivity.HTML;
        assertEquals(2, html.split("<input ", -1).length - 1);
        assertEquals(1, html.split("type='text'", -1).length - 1);
        assertEquals(1, html.split("type='checkbox'", -1).length - 1);
        for (String label : new String[]{LocalFixtureControl.INPUT_LABEL, LocalFixtureControl.CHECK_LABEL, LocalFixtureControl.SCROLL_LABEL})
            assertEquals(1, html.split(label, -1).length - 1);
        assertTrue(html.contains("value='' maxlength='32' autocomplete='off' inputmode='none' spellcheck='false'"));
        assertFalse(html.contains(LocalFixtureControl.DUMMY)); // A later DUMMY read must come from actual input, not fixture text.
        assertTrue(html.contains("id='fixed-scroll-region' role='region'"));
        assertTrue(html.contains("height:80px;overflow-y:scroll"));
        assertTrue(html.contains("CUE_SCROLL_TOP_V1"));
        assertTrue(html.contains("CUE_SCROLL_BOTTOM_V1"));
        for (String forbidden : new String[]{"<form", "action=", "src=", "<script", "onclick=", "onchange=", "type='password'"})
            assertFalse(forbidden, html.contains(forbidden));
    }
}
