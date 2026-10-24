package com.agentruntime.dsl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 模型输出的统一格式规范：抽取 → 修复 → 校验。
 *
 * <p>为什么需要单独一层：模型（尤其小模型）几乎不会规规矩矩只输出一段 JSON。
 * 常见畸形式样有：包在 markdown 代码块里、前后带解释文字、尾逗号、单引号、
 * 一次输出多个对象、字符串里含未转义花括号。每个适配器各写一套解析，
 * 就是三份会各自漂移的脆弱代码。
 *
 * <p>处理顺序是刻意的：<b>先抽取再修复，最后才校验</b>。
 * 抽取阶段不判断合法性，只负责把"可能的 JSON 片段"取出来；
 * 修复阶段只做安全的语法级修复（不臆造内容）；校验阶段严格拒绝。
 */
public final class OutputFormat {

    /** 单次模型输出的长度上限，防止异常输出撑爆内存。 */
    public static final int MAX_OUTPUT_CHARS = 64 * 1024;

    private OutputFormat() {
    }

    // ------------------------------------------------------------- 抽取

    /**
     * 从任意文本中抽出第一个完整的 JSON 对象。
     *
     * <p>用括号计数而不是 {@code indexOf('{') + lastIndexOf('}')}：
     * 后者在模型一次输出两个对象、或解释文字里带花括号时会切出非法片段。
     * 扫描时跳过字符串内部，避免被 {@code "}"} 这类内容误导。
     *
     * @return 抽出的片段；找不到返回 null
     */
    public static String extractObject(String raw) {
        return extract(raw, '{', '}');
    }

    /** 从任意文本中抽出第一个完整的 JSON 数组。 */
    public static String extractArray(String raw) {
        return extract(raw, '[', ']');
    }

    private static String extract(String raw, char open, char close) {
        if (raw == null) return null;
        String text = raw.length() > MAX_OUTPUT_CHARS
                ? raw.substring(0, MAX_OUTPUT_CHARS) : raw;

        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                if (depth == 0) start = i;
                depth++;
            } else if (c == close) {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------- 修复

    /**
     * 安全修复：只处理确定不会改变语义的语法问题。
     *
     * <p>明确不做的事：不补缺失的引号、不猜截断的字段、不修正错误的键名——
     * 那些"聪明"的修复会把错误数据变成看似合法的错误任务，比直接失败更危险。
     */
    public static String repair(String json) {
        if (json == null) return null;
        String s = json.trim();
        // 去掉行注释与块注释（有些模型会自作主张加注释）
        s = s.replaceAll("(?m)^\\s*//.*$", "");
        s = s.replaceAll("/\\*.*?\\*/", "");
        // 尾逗号：{"a":1,} / [1,2,]
        s = s.replaceAll(",(\\s*[}\\]])", "$1");
        // 中文引号包裹的键值（全角引号）
        s = s.replace('\u201c', '"').replace('\u201d', '"');
        // 单引号包裹的键名/字符串：只在不存在双引号时替换，避免破坏正常内容
        if (!s.contains("\"")) {
            s = s.replace('\'', '"');
        }
        return s.trim();
    }

    /**
     * 完整流程：抽对象 → 修复 → 解析。
     *
     * @throws JSONException 抽不到或修复后仍不合法
     */
    public static JSONObject parseObject(String raw) throws JSONException {
        String frag = extractObject(raw);
        if (frag == null) {
            throw new JSONException("no JSON object found in model output");
        }
        String repaired = repair(frag);
        try {
            return new JSONObject(repaired);
        } catch (JSONException e) {
            throw new JSONException("model output is not valid JSON: " + e.getMessage());
        }
    }

    public static JSONArray parseArray(String raw) throws JSONException {
        String frag = extractArray(raw);
        if (frag == null) {
            throw new JSONException("no JSON array found in model output");
        }
        return new JSONArray(repair(frag));
    }

    // ------------------------------------------------------------- 校验

    /** 校验结果。 */
    public static final class Validation {
        public final boolean ok;
        public final String error;

        private Validation(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static Validation pass() {
            return new Validation(true, null);
        }

        static Validation fail(String err) {
            return new Validation(false, err);
        }

        public String describe() {
            return ok ? "OK" : error;
        }
    }

    /**
     * 视觉定位输出的格式校验：{@code {"found":bool,"x":int,"y":int,"confidence":0~1}}。
     */
    public static Validation validateGrounding(JSONObject o) {
        if (o == null) return Validation.fail("null object");
        if (!o.has("found")) return Validation.fail("missing key: found");
        if (!(o.opt("found") instanceof Boolean)) return Validation.fail("found must be boolean");
        if (!o.optBoolean("found", false)) {
            return Validation.pass();
        }
        if (!o.has("x") || !o.has("y")) return Validation.fail("missing x/y when found=true");
        Object x = o.opt("x");
        Object y = o.opt("y");
        if (!(x instanceof Number)) return Validation.fail("x must be number");
        if (!(y instanceof Number)) return Validation.fail("y must be number");
        int xi = ((Number) x).intValue();
        int yi = ((Number) y).intValue();
        if (xi < 0 || yi < 0) return Validation.fail("coordinates must be non-negative");
        double conf = o.optDouble("confidence", 0.5);
        if (conf < 0 || conf > 1) return Validation.fail("confidence out of range 0..1");
        return Validation.pass();
    }

    /**
     * 动作输出的格式校验：{@code {"action":..,"params":{..}}}。
     */
    public static Validation validateAction(JSONObject o) {
        if (o == null) return Validation.fail("null object");
        String action = o.optString("action", null);
        if (action == null || action.trim().isEmpty()) return Validation.fail("missing key: action");
        if (!o.has("params")) return Validation.fail("missing key: params");
        if (!(o.opt("params") instanceof JSONObject)) return Validation.fail("params must be object");
        return Validation.pass();
    }

    /** 判断文本是否像一段"只含 JSON"的干净输出（用于决定是否需要额外修复）。 */
    public static boolean isCleanJson(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return false;
        try {
            new JSONObject(s);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
}
