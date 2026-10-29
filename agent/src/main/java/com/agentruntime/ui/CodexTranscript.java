package com.agentruntime.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.agent.R;

/**
 * Codex 式会话流水：把「用户说了什么 / 调了哪个技能 / 结果如何」按时间顺序堆成一份可滚动的记录。
 *
 * <p>为什么不用 XML 定义每种条目：条目形态有六七种，但结构都是「一行等宽文本 + 可能的缩进」。
 * 用代码拼装比维护七份 layout 更省事，也更容易做「文本 + 变色片段」这类混排
 * （例如把参数染成暗色、把状态染成绿/红）。
 *
 * <p>为什么要限制条数：一次任务几十条事件，跑一天就是几千个 View。
 * 超过上限从头丢，和终端的滚动缓冲区一个道理。
 */
public final class CodexTranscript {

    /** 超过这个条数就从头部丢弃，避免长时间运行后 View 数量失控。 */
    private static final int MAX_ROWS = 240;

    private final ScrollView scroller;
    private final LinearLayout host;
    private final Context ctx;

    private boolean autoScroll = true;

    public CodexTranscript(ScrollView scroller, LinearLayout host) {
        this.scroller = scroller;
        this.host = host;
        this.ctx = host.getContext();
        // 用户往上翻看历史时不要再强行拉到底部
        if (scroller != null) {
            scroller.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
                View child = scroller.getChildAt(0);
                int bottom = child == null ? 0 : child.getBottom() - scroller.getHeight();
                autoScroll = sy >= bottom - UiKit.dp(ctx, 48);
            });
        }
    }

    public void clear() {
        host.removeAllViews();
    }

    public int rowCount() {
        return host.getChildCount();
    }

    // ------------------------------------------------------------ 条目

    /** 用户发言：主导航靠左竖线 + 淡底，一眼能和机器输出区分开。 */
    public void addUser(String text) {
        TextView tv = mono(text, R.color.text_primary, 13.5f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT);
        tv.setTextSize(15f);
        tv.setBackgroundResource(R.drawable.bg_turn_user);
        tv.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10),
                UiKit.dp(ctx, 10), UiKit.dp(ctx, 10));
        add(tv, 10);
    }

    /** 一段说明性输出（规划器的解释、模型回答）。 */
    public void addAssistant(String text) {
        TextView tv = mono(text, R.color.text_primary, 13.5f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT);
        tv.setTextSize(15f);
        add(tv, 8);
    }

    /**
     * 工具调用：`● skill.id  args`，参数部分压暗。
     *
     * <p>参数单独染色是有用的：一眼能看出「这次调用带了什么」，而不用去翻日志。
     */
    public void addToolCall(String skillId, String args) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("\u25CF ");                       // ●
        sb.append(skillId);
        if (args != null && !args.isEmpty()) {
            sb.append("  ");
            int argStart = sb.length();
            sb.append(args);
            sb.setSpan(new ForegroundColorSpan(color(R.color.mono_dim)), argStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView tv = mono(sb, R.color.mono_accent, 13.5f);
        add(tv, 6);
    }

    /** 工具结果：缩进的 `↳ ✓ code · 123ms`。 */
    public void addToolResult(boolean ok, String code, long ms) {
        String text = "  \u21B3 " + (ok ? "\u2713 " : "\u2717 ") + code
                + (ms > 0 ? "  \u00B7  " + ms + "ms" : "");
        TextView tv = mono(text, ok ? R.color.ok : R.color.danger, 12.5f);
        add(tv, 10);
    }

    /** 分组标题，形如 `── 计划 ───────`。 */
    public void addSection(String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setTextColor(color(R.color.text_tertiary));
        tv.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        tv.setLetterSpacing(0.1f);
        add(tv, 12);
    }

    /** 普通状态行（`plan 规划完成 → t_xxx`）。 */
    public void addNote(String tag, String message) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int indent = Math.max(0, 6 - tag.length());
        sb.append(repeat(' ', indent)).append(tag).append("  ");
        int start = sb.length();
        sb.append(message == null ? "" : message);
        sb.setSpan(new ForegroundColorSpan(color(R.color.text_secondary)), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView tv = mono(sb, R.color.mono_dim, 12.5f);
        add(tv, 4);
    }

    public void addWarn(String message) {
        add(mono("\u26A0 " + message, R.color.warn, 12.5f), 8);
    }

    public void addError(String message) {
        add(mono("\u2717 " + message, R.color.danger, 13f), 8);
    }

    /** 任务收尾横幅：成功/失败 + 耗时，用一条横线包起来，视觉上是"这一段结束了"。 */
    public void addSummary(boolean ok, String text) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 8), UiKit.dp(ctx, 10), UiKit.dp(ctx, 8));
        int bg = ok ? R.color.ok_bg : R.color.danger_bg;
        box.setBackgroundColor(color(bg));

        TextView tv = mono((ok ? "\u2713 " : "\u2717 ") + text, ok ? R.color.ok : R.color.danger, 13f);
        box.addView(tv);
        add(box, 14);
    }

    /** 空态提示，只在没有任何条目时显示。 */
    public void showEmptyHint(String text) {
        TextView tv = mono(text, R.color.text_tertiary, 12.5f);
        tv.setGravity(Gravity.START);
        add(tv, 0);
    }

    // ------------------------------------------------------------ 内部

    private void add(View v, int marginBottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.dp(ctx, marginBottomDp);
        host.addView(v, lp);
        trim();
        if (autoScroll && scroller != null) {
            scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void trim() {
        while (host.getChildCount() > MAX_ROWS) {
            host.removeViewAt(0);
        }
    }

    private TextView mono(CharSequence text, int colorRes, float sizeSp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color(colorRes));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLineSpacing(UiKit.dp(ctx, 3), 1f);
        tv.setIncludeFontPadding(false);
        // 允许长文本被选择复制——排查问题时能把日志直接拷出来
        tv.setTextIsSelectable(true);
        return tv;
    }

    private int color(int res) {
        return ctx.getColor(res);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
