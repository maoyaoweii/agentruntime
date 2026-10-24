package com.agentruntime.web;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
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
public class BrowserControllerNavigationRedactionTest {

    @After
    public void detachBrowserBridge() {
        BrowserBridge.detach();
    }

    @Test
    public void rejectedUrlDoesNotEchoInput() {
        String marker = "synthetic-url-secret-" + System.nanoTime();
        String url = "file:///private/" + marker + "?token=" + marker;

        ExecResult result = new BrowserController(RuntimeEnvironment.getApplication())
                .navigate(url, true, 1000);

        assertEquals("URL_REJECTED", result.code);
        assertFalse(result.detail.contains(marker));
        assertFalse(result.detail.contains(url));
    }

    @Test
    public void internalNavigationSuccessDoesNotEchoQueryToken() {
        Application app = RuntimeEnvironment.getApplication();
        String marker = "synthetic-internal-token-" + System.nanoTime();
        String url = "https://example.com/page?token=" + marker;
        BrowserBridge.attach(app, new ReadyWebView(app));

        ExecResult result = new BrowserController(app).navigate(url, true, 1000);

        assertEquals("PAGE_LOADED", result.code);
        assertEquals("navigation completed", result.detail);
        assertFalse(result.detail.contains(marker));
        assertFalse(result.detail.contains(url));
    }

    @Test
    public void externalLaunchFailureDoesNotEchoUrlFromException() {
        Application app = RuntimeEnvironment.getApplication();
        String marker = "synthetic-external-token-" + System.nanoTime();
        String url = "https://example.com/page?token=" + marker;
        ContextWrapper failingContext = new ContextWrapper(app) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public void startActivity(Intent intent) {
                throw new ActivityNotFoundException("synthetic failure for " + intent.getDataString());
            }
        };

        ExecResult result = new BrowserController(failingContext).navigate(url, false, 1000);

        assertEquals("NO_BROWSER", result.code);
        assertTrue(result.detail.contains("ActivityNotFoundException"));
        assertFalse(result.detail.contains(marker));
        assertFalse(result.detail.contains(url));
    }

    private static final class ReadyWebView extends WebView {
        ReadyWebView(Application context) {
            super(context);
        }

        @Override
        public boolean post(Runnable action) {
            action.run();
            return true;
        }

        @Override
        public void loadUrl(String url) {
            BrowserBridge.markReady();
        }
    }
}
