package com.agentruntime.web;

import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置受控浏览器桥。
 *
 * <p>为什么不用系统的 Chrome：普通应用无法读取第三方浏览器的 DOM、无法执行脚本、
 * 无法管理标签页。要真的"控制浏览器"，只能自己持有一个 WebView。
 *
 * <p>为什么不做 evalJs(String script)：那等于把"执行任意代码"的口子重新打开，
 * 与本项目"设备操作只能通过白名单原语"的核心约束冲突。所以这里只暴露
 * <b>四个参数化模板</b>（读文本 / 点元素 / 读元素文本 / 滚动），
 * selector 经过转义与长度校验，注入不进任意代码。
 */
public final class BrowserBridge {

    public static final String EXTRA_URL = "browser_url";

    private static volatile WebView webView;
    private static volatile Context appContext;
    private static volatile boolean pageReady;

    private BrowserBridge() {
    }

    /** 由 BrowserActivity 在 onCreate 时调用。 */
    static void attach(Context context, WebView view) {
        appContext = context.getApplicationContext();
        webView = view;
        pageReady = false;
    }

    static void detach() {
        webView = null;
        pageReady = false;
    }

    static void markReady() {
        pageReady = true;
    }

    static void markLoading() {
        pageReady = false;
    }

    public static boolean isReady() {
        return webView != null && pageReady;
    }

    public static boolean isAttached() {
        return webView != null;
    }

    /** 结果封装。 */
    public static final class Result {
        public final boolean ok;
        public final String code;
        public final String value;
        public final long latencyMs;

        Result(boolean ok, String code, String value, long latencyMs) {
            this.ok = ok;
            this.code = code;
            this.value = value;
            this.latencyMs = latencyMs;
        }

        public static Result fail(String code, String value) {
            return new Result(false, code, value, 0);
        }
    }

    /**
     * 确保浏览器已打开并加载指定 URL。
     *
     * <p>已附着时直接用 loadUrl；未附着时拉起内置浏览器 Activity 并等待页面就绪。
     */
    public static Result load(Context context, String url, long timeoutMs) {
        long start = System.currentTimeMillis();
        if (context != null) appContext = context.getApplicationContext();
        if (appContext == null) return Result.fail("NO_CONTEXT", "context unavailable");

        if (webView == null) {
            try {
                Intent intent = new Intent(appContext, BrowserActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(EXTRA_URL, url);
                appContext.startActivity(intent);
            } catch (Throwable t) {
                return Result.fail("LAUNCH_FAILED", String.valueOf(t.getMessage()));
            }
            long deadline = start + Math.max(3000, timeoutMs);
            while (System.currentTimeMillis() < deadline && !isReady()) {
                sleep(150);
            }
            return isReady()
                    ? new Result(true, "BROWSER_READY", url, System.currentTimeMillis() - start)
                    : Result.fail("BROWSER_TIMEOUT", "page not ready in " + timeoutMs + "ms");
        }

        if (url != null && !url.isEmpty()) {
            AtomicReference<String> done = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            runOnUi(() -> {
                try {
                    pageReady = false;
                    webView.loadUrl(url);
                    done.set("queued");
                } catch (Throwable t) {
                    done.set("error:" + t.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            await(latch, 3000);
            if (done.get() == null || done.get().startsWith("error")) {
                return Result.fail("LOAD_FAILED", String.valueOf(done.get()));
            }
            long deadline = start + Math.max(3000, timeoutMs);
            while (System.currentTimeMillis() < deadline && !isReady()) {
                sleep(150);
            }
            return isReady()
                    ? new Result(true, "PAGE_LOADED", url, System.currentTimeMillis() - start)
                    : Result.fail("PAGE_TIMEOUT", "page not ready in " + timeoutMs + "ms");
        }
        return new Result(true, "ALREADY_OPEN", "", System.currentTimeMillis() - start);
    }

    /** 读取页面可见文本。 */
    public static Result readText(int maxChars) {
        String script = "(function(){var t=document.body?document.body.innerText:'';"
                + "return t.replace(/\\s+/g,' ').trim().slice(0," + clamp(maxChars, 100, 8000) + ");})()";
        return evalString(script, "PAGE_TEXT");
    }

    /** 点击匹配 CSS 选择器的元素。 */
    public static Result clickSelector(String selector) {
        if (!isSafeSelector(selector)) {
            return Result.fail("BAD_SELECTOR", "selector rejected");
        }
        String script = "(function(){var el=document.querySelector('" + escapeJs(selector) + "');"
                + "if(!el){return '__NOT_FOUND__';}el.click();return 'clicked';})()";
        return evalString(script, "CLICK_OK");
    }

    /** 读取匹配元素（或其父节点）的文本。 */
    public static Result readSelector(String selector) {
        if (!isSafeSelector(selector)) {
            return Result.fail("BAD_SELECTOR", "selector rejected");
        }
        String script = "(function(){var el=document.querySelector('" + escapeJs(selector) + "');"
                + "if(!el){return '__NOT_FOUND__';}return (el.innerText||el.textContent||'').trim().slice(0,500);})()";
        return evalString(script, "SELECTOR_TEXT");
    }

    /** 按像素滚动页面。 */
    public static Result scrollPage(int dy) {
        int delta = Math.max(-5000, Math.min(5000, dy));
        String script = "(function(){window.scrollBy(0," + delta + ");"
                + "return 'y='+Math.round(window.scrollY);})()";
        return evalString(script, "SCROLL_OK");
    }

    /** 当前页面 URL 与标题。 */
    public static Result pageInfo() {
        String script = "(function(){return JSON.stringify({url:location.href,title:document.title});})()";
        return evalString(script, "PAGE_INFO");
    }

    // ------------------------------------------------------------ internal

    private static Result evalString(String script, String okCode) {
        long start = System.currentTimeMillis();
        WebView wv = webView;
        if (wv == null) return Result.fail("BROWSER_NOT_ATTACHED", "no webview");
        if (!pageReady) return Result.fail("PAGE_NOT_READY", "page still loading");

        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        runOnUi(() -> {
            try {
                wv.evaluateJavascript(script, value -> {
                    out.set(value);
                    latch.countDown();
                });
            } catch (Throwable t) {
                out.set("__ERROR__" + t.getMessage());
                latch.countDown();
            }
        });

        if (!await(latch, 5000) || out.get() == null) {
            return Result.fail("JS_TIMEOUT", "no callback in 5s");
        }
        String raw = out.get();
        long elapsed = System.currentTimeMillis() - start;

        if (raw.equals("null")) return Result.fail("JS_NULL_RESULT", "script returned null");
        if (raw.startsWith("__ERROR__")) return Result.fail("JS_ERROR", raw);
        String value = unquote(raw);
        if ("__NOT_FOUND__".equals(value)) return Result.fail("ELEMENT_NOT_FOUND", value);
        return new Result(true, okCode, value, elapsed);
    }

    private static void runOnUi(Runnable r) {
        WebView wv = webView;
        if (wv == null) {
            r.run();
            return;
        }
        wv.post(r);
    }

    /** 等待闩锁，返回是否在超时前完成。 */
    private static boolean await(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** evaluateJavascript 返回的是 JSON 编码字符串，这里去掉外层引号并反转义。 */
    public static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">");
    }

    /**
     * selector 白名单校验。
     *
     * <p>规则实现委托给 {@link com.agentruntime.policy.SafetyPolicy}，
     * 与 DSL 解析层共用同一份判断，避免"解析放过、执行拦下"这类不一致。
     */
    public static boolean isSafeSelector(String selector) {
        return com.agentruntime.policy.SafetyPolicy.isSafeSelector(selector);
    }

    /** 转义后就只剩标识符、类名、属性选择符，已无注入面。 */
    static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
