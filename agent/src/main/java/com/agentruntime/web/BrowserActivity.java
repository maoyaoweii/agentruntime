package com.agentruntime.web;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 内置受控浏览器宿主。
 *
 * <p>它存在的唯一理由是让 Agent 能够真正读取页面内容并操作页面元素，
 * 而不是靠猜坐标去点第三方浏览器。
 *
 * <p>安全设置全部收紧：
 * <ul>
 *   <li>不注册 addJavascriptInterface —— 页面里的 JS 无法反向调用 App 能力；</li>
 *   <li>禁止 file:// 跨域访问与本地文件读取；</li>
 *   <li>不启用地理位置、不保存表单密码；</li>
 *   <li>只接受 http/https（由上层 BrowserBridge 与 DslParser 双重校验）。</li>
 * </ul>
 *
 * <p><b>未在设备验证</b>：本环境无模拟器与真机，WebView 行为只做到编译通过与逻辑自洽。
 */
public final class BrowserActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                BrowserBridge.markLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                BrowserBridge.markReady();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 只允许 http/https，其他 scheme 一律丢弃
                return url == null || !(url.startsWith("http://") || url.startsWith("https://"));
            }
        });

        BrowserBridge.attach(this, webView);

        String url = getIntent() != null ? getIntent().getStringExtra(BrowserBridge.EXTRA_URL) : null;
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onDestroy() {
        BrowserBridge.detach();
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
