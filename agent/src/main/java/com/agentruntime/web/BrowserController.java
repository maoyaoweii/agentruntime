package com.agentruntime.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.agentruntime.dsl.DslParser;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import java.util.List;

/**
 * 浏览器控制能力封装。
 *
 * <p>两条路径，能力不同，必须让调用方显式选择而不是混为一谈：
 * <ul>
 *   <li><b>内置浏览器</b>（internal=true）：我们自己的 WebView，能读 DOM、点 CSS 选择器、
 *       滚动页面、拿准确文本。能力完整。</li>
 *   <li><b>外部浏览器</b>（internal=false）：只能通过 ACTION_VIEW 打开 URL，
 *       之后靠无障碍树读文本、靠坐标手势滚动。读不到 DOM，也读不到未渲染的内容。</li>
 * </ul>
 *
 * <p>这个区分是诚实的边界：很多"AI 控制浏览器"的宣传默认把这两件事混着说。
 */
public final class BrowserController {

    private final Context context;

    public BrowserController(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 打开 URL。 */
    public ExecResult navigate(String url, boolean internal, long timeoutMs) {
        long start = System.currentTimeMillis();
        if (!DslParser.isSafeUrl(url)) {
            return ExecResult.reject("URL_REJECTED", "only http/https URLs are allowed", 0);
        }
        if (internal) {
            BrowserBridge.Result r = BrowserBridge.load(context, url, Math.max(5000, timeoutMs));
            return r.ok
                    ? ExecResult.ok(r.code, "navigation completed", 0, elapsed(start))
                    : ExecResult.fail(r.code, "navigation failed", 0, elapsed(start));
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return ExecResult.ok("EXTERNAL_OPEN", "external browser open requested", 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("NO_BROWSER",
                    "external browser launch failed (" + t.getClass().getSimpleName() + ")",
                    0, elapsed(start));
        }
    }

    /**
     * 读取页面文本。
     *
     * <p>内置浏览器走 DOM innerText；外部浏览器退化为无障碍树文本聚合。
     */
    public ExecResult readPage(int maxChars, WorldState state) {
        long start = System.currentTimeMillis();
        boolean internalBrowserAttached = BrowserBridge.isAttached();
        String domReadFailure = internalBrowserAttached ? "PAGE_NOT_READY" : null;
        if (BrowserBridge.isReady()) {
            BrowserBridge.Result r = BrowserBridge.readText(maxChars);
            if (r.ok) {
                if (r.value != null && !r.value.trim().isEmpty()) {
                    return ExecResult.ok("PAGE_TEXT", r.value, 0, elapsed(start));
                }
                domReadFailure = "EMPTY_DOM_TEXT";
            } else {
                // Keep only the stable error code; the page/error detail may contain content.
                domReadFailure = r.code == null ? "DOM_READ_FAILED" : r.code;
            }
        }
        String fromTree = extractTextFromState(state, maxChars);
        if (fromTree.isEmpty()) {
            String detail = internalBrowserAttached
                    ? "internal browser DOM read failed (" + domReadFailure
                            + ") and accessibility tree empty"
                    : "external browser: DOM unavailable, accessibility tree empty";
            return ExecResult.fail("NO_PAGE_TEXT",
                    detail, 0, elapsed(start));
        }
        return ExecResult.ok("PAGE_TEXT_A11Y", fromTree, 0, elapsed(start));
    }

    /** 页内查找文本。 */
    public ExecResult findOnPage(String needle, WorldState state) {
        long start = System.currentTimeMillis();
        if (needle == null || needle.trim().isEmpty()) {
            return ExecResult.reject("NEEDLE_EMPTY", "text required", 0);
        }
        if (BrowserBridge.isReady()) {
            BrowserBridge.Result r = BrowserBridge.readText(8000);
            if (r.ok) {
                boolean hit = r.value.contains(needle);
                return hit
                        ? ExecResult.ok("FOUND", "dom contains: " + needle, 0, elapsed(start))
                        : ExecResult.fail("NOT_FOUND", "dom has no: " + needle, 0, elapsed(start));
            }
        }
        if (state != null && state.containsText(needle)) {
            return ExecResult.ok("FOUND_A11Y", "tree contains: " + needle, 0, elapsed(start));
        }
        return ExecResult.fail("NOT_FOUND", "neither DOM nor tree contains: " + needle, 0, elapsed(start));
    }

    /** 点击 CSS 选择器（仅内置浏览器）。 */
    public ExecResult clickSelector(String selector) {
        long start = System.currentTimeMillis();
        if (!BrowserBridge.isSafeSelector(selector)) {
            return ExecResult.reject("BAD_SELECTOR", "selector rejected: " + selector, 0);
        }
        BrowserBridge.Result r = BrowserBridge.clickSelector(selector);
        return r.ok
                ? ExecResult.ok(r.code, r.value, 0, elapsed(start))
                : ExecResult.fail(r.code, r.value, 0, elapsed(start));
    }

    /** 读取元素文本（仅内置浏览器）。 */
    public ExecResult readSelector(String selector) {
        long start = System.currentTimeMillis();
        if (!BrowserBridge.isSafeSelector(selector)) {
            return ExecResult.reject("BAD_SELECTOR", "selector rejected: " + selector, 0);
        }
        BrowserBridge.Result r = BrowserBridge.readSelector(selector);
        return r.ok
                ? ExecResult.ok(r.code, r.value, 0, elapsed(start))
                : ExecResult.fail(r.code, r.value, 0, elapsed(start));
    }

    /** 滚动页面（仅内置浏览器，走 window.scrollBy）。 */
    public ExecResult scrollPage(int dy) {
        long start = System.currentTimeMillis();
        BrowserBridge.Result r = BrowserBridge.scrollPage(dy);
        return r.ok
                ? ExecResult.ok(r.code, r.value, 0, elapsed(start))
                : ExecResult.fail(r.code, r.value, 0, elapsed(start));
    }

    public ExecResult pageInfo() {
        long start = System.currentTimeMillis();
        BrowserBridge.Result r = BrowserBridge.pageInfo();
        return r.ok
                ? ExecResult.ok(r.code, r.value, 0, elapsed(start))
                : ExecResult.fail(r.code, r.value, 0, elapsed(start));
    }

    public boolean internalReady() {
        return BrowserBridge.isReady();
    }

    /** 从无障碍树聚合文本：外部浏览器唯一可用的页面读取方式。 */
    public static String extractTextFromState(WorldState state, int maxChars) {
        if (state == null || state.root == null) return "";
        StringBuilder sb = new StringBuilder();
        List<UiNode> flat = state.root.flat();
        for (UiNode n : flat) {
            if (!n.visible) continue;
            String t = n.text != null ? n.text : n.contentDescription;
            if (t == null || t.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.trim());
            if (sb.length() >= maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
