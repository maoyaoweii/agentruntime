package com.agentruntime.goal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把用户这一句变成"可观察的完成标准 + 目标"。
 *
 * <p>主循环第 1 步：没有标准就不许动手。这里只做确定性解析（常见应用名、动作词、付费词），
 * 解析不出来就让主循环以 NO_CRITERIA 结束，而不是让模型自由发挥。
 */
public final class GoalInterpreter {

    /** 常见应用名 → 包名。微信是 com.tencent.mm。 */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("微信", "com.tencent.mm");
        KNOWN.put("哔哩哔哩", "tv.danmaku.bili");
        KNOWN.put("B站", "tv.danmaku.bili");
        KNOWN.put("b站", "tv.danmaku.bili");
        KNOWN.put("设置", "com.android.settings");
        KNOWN.put("支付宝", "com.eg.android.AlipayGphone");
        KNOWN.put("抖音", "com.ss.android.ugc.aweme");
        KNOWN.put("QQ", "com.tencent.mobileqq");
        KNOWN.put("微博", "com.sina.weibo");
        KNOWN.put("淘宝", "com.taobao.taobao");
    }

    private static final String[] ACTIONS = {
            "打开", "启动", "进入", "看看", "看一下", "搜索", "搜一下", "发送", "发给",
            "填写", "输入", "点开", "关掉", "关闭"
    };

    private static final String[] PAYMENT = {"付款", "支付", "转账", "下单", "购买", "充值", "打赏", "发红包"};
    /** 只读意图：读消息、看看列表、有什么新内容。 */
    private static final String[] READ_WORDS = {"读一下", "读读", "读消息", "看看", "看一下", "有什么消息", "新消息", "消息记录"};
    /** 有这些词就不是只读，要做动作。 */
    private static final String[] WRITE_WORDS = {"发送", "发给", "回复", "填写", "输入", "点开", "关掉", "删除", "付款", "转账"};
    private static final String[] QUESTION = {"怎么", "为什么", "能不能", "可不可以", "是否", "是什么", "会不会", "吗？", "吗?"};

    public static final class Plan {
        public final String criteria;
        public final String targetPackage;
        public final String targetLabel;
        public final String key;
        public final boolean question;
        public final boolean payment;
        /** 只要读，不要点：读消息、看看聊天列表都属于这一类。 */
        public final boolean readOnly;

        Plan(String criteria, String targetPackage, String targetLabel, String key,
             boolean question, boolean payment, boolean readOnly) {
            this.criteria = criteria;
            this.targetPackage = targetPackage;
            this.targetLabel = targetLabel;
            this.key = key;
            this.question = question;
            this.payment = payment;
            this.readOnly = readOnly;
        }
    }

    /** 常见应用名对应的包名；没登记返回 null。 */
    public static String knownPackage(String label) {
        return label == null ? null : KNOWN.get(label.trim());
    }

    public Plan interpret(String request, GoalScreen screen) {
        String text = request == null ? "" : request.trim();
        boolean payment = containsAny(text, PAYMENT);
        boolean action = containsAny(text, ACTIONS);
        boolean question = !action && containsAny(text, QUESTION);

        String label = null;
        String pkg = null;
        for (Map.Entry<String, String> entry : KNOWN.entrySet()) {
            if (text.contains(entry.getKey())) {
                label = entry.getKey();
                pkg = entry.getValue();
                break;
            }
        }
        if (label == null) {
            label = guessAppName(text);
            if (label != null && screen != null) pkg = screen.resolvePackage(label);
        } else if (screen != null) {
            String resolved = screen.resolvePackage(label);
            if (resolved != null) pkg = resolved;
        }

        boolean readOnly = containsAny(text, READ_WORDS) && !containsAny(text, WRITE_WORDS);
        String criteria = "";
        if (readOnly) criteria = "从「" + (label == null ? "当前界面" : label) + "」读到可见文字";
        else if (pkg != null) criteria = "前台包名是 " + pkg;
        else if (label != null) criteria = "离开桌面后，屏幕上出现「" + label + "」";
        String key = pkg != null ? "app:" + pkg : label != null ? "label:" + label : "request:" + text;
        return new Plan(criteria, pkg, label, key, question, payment, readOnly);
    }

    /** "打开小米商城" 这类：取动作词后面的 2~6 个字当候选名。 */
    private static String guessAppName(String text) {
        for (String verb : ACTIONS) {
            int at = text.indexOf(verb);
            if (at < 0) continue;
            String rest = text.substring(at + verb.length()).trim();
            rest = rest.replaceAll("^[「『\"']", "");
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if ("，。,.！!？? 、".indexOf(c) >= 0) {
                    end = i;
                    break;
                }
            }
            rest = rest.substring(0, Math.min(end, 6)).trim();
            if (rest.length() >= 2) return rest;
        }
        return null;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
