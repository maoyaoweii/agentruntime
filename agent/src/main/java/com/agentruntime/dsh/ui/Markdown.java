package com.agentruntime.dsh.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown 渲染。
 *
 * <p>模型回复天然带 **粗体**、`代码`、列表和表格。之前整段当纯文本塞进 TextView，
 * 用户看到的是满屏星号和竖线（"**已完成:** | 步骤 | 结果 |"），既难读也显得像程序崩溃。
 * 这里做一层不依赖第三方库的转换：粗体/斜体/行内代码/代码块/标题/列表/引用/表格。
 * 只处理展示，不改动原文存储。
 */
final class Markdown {
    private static final int CODE_BG = 0xfff0f2f8;
    private static final int CODE_INK = 0xff2b3550;
    private static final int LINK_INK = 0xff3555d8;
    private static final int QUOTE_INK = 0xff6b7488;

    private Markdown() { }

    static CharSequence render(Context context, String source) {
        if (source == null) return "";
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        SpannableStringBuilder out = new SpannableStringBuilder();
        List<String> table = new ArrayList<>();
        boolean inCode = false;

        for (String raw : lines) {
            String line = raw;
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                flushTable(out, table);
                inCode = !inCode;
                if (inCode && out.length() > 0) out.append('\n');
                if (!inCode) out.append('\n');
                continue;
            }
            if (inCode) {
                appendMono(out, line, 0.92f);
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                table.add(trimmed);
                continue;
            }
            flushTable(out, table);

            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String title = trimmed.substring(Math.min(level, trimmed.length())).trim();
                int start = out.length();
                inline(out, title);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (level <= 2) out.setSpan(new RelativeSizeSpan(1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith(">")) {
                out.append("│ ");
                int start = out.length();
                inline(out, trimmed.substring(1).trim());
                out.setSpan(new ForegroundColorSpan(QUOTE_INK), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                out.append("•  ");
                inline(out, trimmed.substring(2));
                out.append('\n');
                continue;
            }
            if (trimmed.matches("^\\d{1,2}[.)]\\s+.*")) {
                int dot = 0;
                while (dot < trimmed.length() && (Character.isDigit(trimmed.charAt(dot)) || trimmed.charAt(dot) == '.' || trimmed.charAt(dot) == ')')) dot++;
                out.append(trimmed, 0, dot).append("  ");
                inline(out, trimmed.substring(dot).trim());
                out.append('\n');
                continue;
            }
            if (trimmed.matches("^[-*_]{3,}$")) {
                out.append("──────────────\n");
                continue;
            }
            inline(out, line.trim());
            out.append('\n');
        }
        if (inCode) out.append('\n');
        flushTable(out, table);
        // 去掉结尾多余空行，最多留一个换行
        while (out.length() > 1 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    /** 行内语法：粗体、斜体、行内代码、删除线、链接。 */
    private static void inline(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    mono(out, start, out.length(), 0.94f);
                    i = end + 1;
                    continue;
                }
            }
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    inline(out, text.substring(i + 2, end));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (text.startsWith("~~", i)) {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    out.append(text, i + 2, end);
                    out.setSpan(new android.text.style.StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > close) {
                        int start = out.length();
                        inline(out, text.substring(i + 1, close));
                        out.setSpan(new ForegroundColorSpan(LINK_INK), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            if (c == '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i + 1 && end + 1 < text.length() && !Character.isSpaceChar(text.charAt(i + 1))) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    private static void appendMono(SpannableStringBuilder out, String line, float scale) {
        int start = out.length();
        out.append(line);
        mono(out, start, out.length(), scale);
    }

    private static void mono(SpannableStringBuilder out, int start, int end, float scale) {
        if (end <= start) return;
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(CODE_INK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 表格：按显示宽度对齐成等宽块。
     *
     * <p>聊天气泡里塞真正的表格控件成本太高；等宽 + 对齐已经能看清「步骤 / 结果」这类两列对照。
     */
    private static void flushTable(SpannableStringBuilder out, List<String> table) {
        if (table.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        for (String row : table) {
            String body = row.substring(1, row.length() - 1);
            String[] cells = body.split("\\|", -1);
            boolean separator = true;
            for (String cell : cells) {
                if (!cell.trim().matches("^:?-{1,}:?$")) { separator = false; break; }
            }
            if (separator) continue;
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
            rows.add(cells);
        }
        table.clear();
        if (rows.isEmpty()) return;

        int columns = 0;
        for (String[] row : rows) columns = Math.max(columns, row.length);
        int[] width = new int[columns];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) width[i] = Math.max(width[i], displayWidth(row[i]));
        }
        int start = out.length();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) line.append("  ");
                line.append(row[i]);
                if (i < row.length - 1) {
                    for (int pad = displayWidth(row[i]); pad < width[i]; pad++) line.append(' ');
                }
            }
            out.append(line).append('\n');
        }
        out.setSpan(new TypefaceSpan("monospace"), start, Math.max(start, out.length() - 1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.88f), start, Math.max(start, out.length() - 1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    /** 中文按 2 列计算，避免表格在等宽字体里错位。 */
    private static int displayWidth(String value) {
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            width += (c >= 0x1100 && (c <= 0x115f || (c >= 0x2e80 && c <= 0xa4cf)
                    || (c >= 0xac00 && c <= 0xd7a3) || (c >= 0xf900 && c <= 0xfaff)
                    || (c >= 0xfe30 && c <= 0xfe6f) || (c >= 0xff00 && c <= 0xff60)
                    || (c >= 0xffe0 && c <= 0xffe6))) ? 2 : 1;
        }
        return width;
    }
}
