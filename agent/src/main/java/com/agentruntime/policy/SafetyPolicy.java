package com.agentruntime.policy;

/**
 * 输入安全策略：URL 与 CSS 选择器的白名单校验。
 *
 * <p>单一事实来源。DSL 解析层（拒绝非法动作）与执行层（发请求前再校验一次）都引用这里，
 * 避免两份规则随迭代漂移——"解析时放过、执行时拦下"和反过来的坑都很难查。
 */
public final class SafetyPolicy {

    /** URL 最大长度。 */
    public static final int MAX_URL_LENGTH = 2048;

    /** 选择器最大长度。 */
    public static final int MAX_SELECTOR_LENGTH = 200;

    private SafetyPolicy() {
    }

    /**
     * URL 白名单：只允许 http/https。
     *
     * <p>拒绝 javascript: / file: / content: / intent: / data: ——它们能把"导航"
     * 变成"本地文件读取"或"启动任意组件"，是典型的越权入口。
     */
    public static boolean isSafeUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (u.isEmpty() || u.length() > MAX_URL_LENGTH) return false;
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        if (u.contains("javascript:") || u.contains("file://") || u.contains("content://")
                || u.contains("intent:") || u.contains("data:")) {
            return false;
        }
        return u.length() > "https://".length();
    }

    /**
     * CSS 选择器白名单。
     *
     * <p>威胁是"把 selector 变成拼接任意语句"。能实现这件事的字符是引号（闭合字符串）、
     * 分号（断句）与左尖括号（构造标签）。子选择器 {@code >} 与伪类括号是合法 CSS，
     * 不能误伤——误伤会让 nth-child、直接子元素这类常用选择器全部失效。
     */
    public static boolean isSafeSelector(String selector) {
        if (selector == null) return false;
        String s = selector.trim();
        if (s.isEmpty() || s.length() > MAX_SELECTOR_LENGTH) return false;
        if (s.contains("'") || s.contains("\"") || s.contains("`")) return false;
        if (s.contains(";")) return false;
        if (s.contains("<")) return false;
        return true;
    }
}
