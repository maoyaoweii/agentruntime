package com.agentruntime.web;

import android.app.Application;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import com.agentruntime.executor.ExecResult;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class BrowserControllerReadPageDiagnosticTest {

    @After
    public void detachBrowserBridge() {
        BrowserBridge.detach();
    }

    @Test
    public void missingDomCallbackPreservesStableFailureCode() {
        attachReadyWebView(null);

        ExecResult result = new BrowserController(RuntimeEnvironment.getApplication()).readPage(2000, null);

        assertEquals("NO_PAGE_TEXT", result.code);
        assertTrue(result.detail.contains("JS_TIMEOUT"));
        assertTrue(result.detail.contains("accessibility tree empty"));
        assertFalse(result.detail.contains("__ERROR__"));
    }

    @Test
    public void emptyDomTextIsNotReportedAsSuccessfulPageRead() {
        attachReadyWebView("\"\"");

        ExecResult result = new BrowserController(RuntimeEnvironment.getApplication()).readPage(2000, null);

        assertEquals("NO_PAGE_TEXT", result.code);
        assertTrue(result.detail.contains("EMPTY_DOM_TEXT"));
    }

    private void attachReadyWebView(String callbackValue) {
        BrowserBridge.attach(RuntimeEnvironment.getApplication(), new ImmediateWebView(
                RuntimeEnvironment.getApplication(), callbackValue));
        BrowserBridge.markReady();
    }

    private static final class ImmediateWebView extends WebView {
        private final String callbackValue;

        ImmediateWebView(Application context, String callbackValue) {
            super(context);
            this.callbackValue = callbackValue;
        }

        @Override
        public boolean post(Runnable action) {
            action.run();
            return true;
        }

        @Override
        public void evaluateJavascript(String script, ValueCallback<String> callback) {
            callback.onReceiveValue(callbackValue);
        }
    }
}
