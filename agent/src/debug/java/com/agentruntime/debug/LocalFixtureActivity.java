package com.agentruntime.debug;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;

/** Built-in synthetic HTML; never accepts caller HTML, URLs, or data. */
public final class LocalFixtureActivity extends Activity {
    static LocalFixtureActivity current; // main-thread only
    WebView page;
    boolean resumed;
    boolean ready;
    String finishedUrlState = "NO_CALLBACK";
    static final String HTML = "<!doctype html><html><head><meta name='viewport' content='width=device-width'>"
            + "<title>Local synthetic test</title><style>body{font-size:10px;line-height:12px;margin:2px}p{margin:2px}</style></head><body>"
            + "<h1 style='font-size:10px;line-height:12px;margin:2px;position:sticky;top:0;background:white'>" + LocalFixturePolicy.MARKER + "</h1>"
            + "<p>Synthetic content only. No account, messages, or payment data.</p>"
            + "<a id='fixed-allowed-link' role='link' tabindex='0' aria-label='"
            + LocalFixtureAnchor.ACCESSIBLE_LABEL + "' href='" + LocalFixturePolicy.URL
            + "#allowed' style='display:inline-block'>" + LocalFixturePolicy.LINK + "</a>"
            + "<p id='allowed'>Allowed local destination</p>"
            + "<p><label for='fixed-dummy-input'>Synthetic input</label>"
            + "<input id='fixed-dummy-input' type='text' aria-label='" + LocalFixtureControl.INPUT_LABEL
            + "' value='' maxlength='32' autocomplete='off' inputmode='none' spellcheck='false' style='width:80px'></p>"
            + "<p><input id='fixed-safe-check' type='checkbox' aria-label='" + LocalFixtureControl.CHECK_LABEL
            + "'><label for='fixed-safe-check'>Synthetic checkbox</label></p>"
            + "<div id='fixed-scroll-region' role='region' tabindex='0' aria-label='" + LocalFixtureControl.SCROLL_LABEL
            + "' style='height:80px;overflow-y:scroll;border:1px solid black'>"
            + "<p>CUE_SCROLL_TOP_V1</p><div style='height:70px'></div><p>CUE_SCROLL_BOTTOM_V1</p></div>"
            + "</body></html>";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        page = new WebView(this);
        WebSettings s = page.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true);
        s.setBlockNetworkImage(true);
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(page, false);
        final WebView fixturePage = page;
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                boolean allowed = view == fixturePage && allowFixedFragment(view.getUrl(), target,
                        request.isForMainFrame(), request.getMethod());
                android.util.Log.i("CueLocalFixture", "navigation target=" + LocalFixturePolicy.urlDiagnostic(target)
                        + ";current=" + LocalFixturePolicy.urlDiagnostic(view.getUrl())
                        + ";main=" + request.isForMainFrame() + ";allow=" + allowed
                        + ";t_ms=" + android.os.SystemClock.elapsedRealtime());
                return !allowed; // Continue native navigation. Never loadUrl(target) or synthesize a callback.
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // No main-frame/method metadata. Fail closed; this fixture requires API 30.
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                boolean compiled = view == fixturePage && servesCompiledDocument(request.getUrl().toString(),
                        request.isForMainFrame(), request.getMethod());
                android.util.Log.i("CueLocalFixture", "resource target="
                        + LocalFixturePolicy.urlDiagnostic(request.getUrl().toString())
                        + ";main=" + request.isForMainFrame() + ";compiled=" + compiled
                        + ";t_ms=" + android.os.SystemClock.elapsedRealtime());
                return compiled ? compiledResponse() : emptyResponse(); // Never null: never fall through to network.
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return emptyResponse(); // No main-frame metadata: fail closed on this legacy resource API.
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != page || current != LocalFixtureActivity.this) { ready = false; return; }
                finishedUrlState = LocalFixturePolicy.urlDiagnostic(url);
                ready = LocalFixturePolicy.allowedUrl(url) && url.equals(view.getUrl());
                android.util.Log.i("CueLocalFixture", "fixed page finished " + urlDiagnostics()
                        + ";t_ms=" + android.os.SystemClock.elapsedRealtime());
            }
        });
        setContentView(page);
        // A real, exact document URL, fulfilled only by compiledResponse above while network stays blocked.
        // loadDataWithBaseURL creates a data-document with a fixed virtual history URL on WebView 83;
        // that virtual URL can conceal native same-document fragment updates from getUrl().
        page.loadUrl(LocalFixturePolicy.URL); // Initial load only. The click path never calls loadUrl.
    }
    static boolean servesCompiledDocument(String url, boolean mainFrame, String method) {
        return mainFrame && "GET".equals(method) && LocalFixturePolicy.URL.equals(url);
    }
    static boolean allowFixedFragment(String currentUrl, String target, boolean mainFrame, String method) {
        // getUrl may expose the pending exact fragment during a navigation callback; both identities are fixed.
        return mainFrame && "GET".equals(method) && LocalFixturePolicy.allowedUrl(currentUrl)
                && (LocalFixturePolicy.URL + "#allowed").equals(target);
    }
    private static WebResourceResponse compiledResponse() {
        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(
                HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
    boolean valid() {
        return invalidReason() == null;
    }
    String invalidReason() {
        if (current != this) return "NOT_CURRENT";
        if (!resumed) return "NOT_RESUMED";
        if (!hasWindowFocus()) return "NO_WINDOW_FOCUS";
        if (!ready) return "PAGE_NOT_READY";
        if (page == null) return "NO_PAGE";
        if (!LocalFixturePolicy.allowedUrl(page.getUrl())) return "PAGE_URL_MISMATCH:" + urlDiagnostics();
        return null;
    }
    String urlDiagnostics() {
        if (page == null) return "NO_PAGE";
        android.webkit.WebBackForwardList history = page.copyBackForwardList();
        android.webkit.WebHistoryItem item = history == null ? null : history.getCurrentItem();
        return "expected=EXACT_BASE_OR_ALLOWED_FRAGMENT;callback=" + finishedUrlState
                + ";current=" + LocalFixturePolicy.urlDiagnostic(page.getUrl())
                + ";original=" + LocalFixturePolicy.urlDiagnostic(page.getOriginalUrl())
                + ";history=" + LocalFixturePolicy.urlDiagnostic(item == null ? null : item.getUrl());
    }
    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() {
        resumed = false;
        LocalFixtureProvider.POLICY.close();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (current == this) current = null;
        LocalFixtureProvider.POLICY.close();
        if (page != null) { page.stopLoading(); page.destroy(); page = null; }
        super.onDestroy();
    }
}
