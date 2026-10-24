package com.agentruntime.skill;

import android.content.Context;
import android.util.Log;

import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.DslParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App Skill Engine：加载内置 Skill 清单，将步骤模板展开为 Action DSL 序列。 */
public final class SkillEngine {
    private static final String TAG = "SkillEngine";
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private final Map<String, SkillManifest> registry = new HashMap<>();
    private final List<String> loadErrors = new ArrayList<>();

    public SkillEngine(Context context) {
        loadBuiltins(context);
    }

    private void loadBuiltins(Context context) {
        try {
            String[] files = context.getAssets().list("skills");
            if (files == null) return;
            for (String f : files) {
                if (!f.endsWith(".json")) continue;
                try (InputStream in = context.getAssets().open("skills/" + f)) {
                    SkillManifest m = SkillManifest.fromJson(new JSONObject(readAll(in)));
                    registry.put(m.skillId, m);
                } catch (Exception e) {
                    // 只记录文件名、异常类型和不含输入内容的摘要，绝不把技能 JSON/参数写入日志。
                    String error = f + ":" + e.getClass().getSimpleName();
                    loadErrors.add(error);
                    Log.w(TAG, "Rejected builtin skill " + error);
                }
            }
        } catch (IOException | RuntimeException e) {
            String error = "skills-directory:" + e.getClass().getSimpleName();
            loadErrors.add(error);
            Log.w(TAG, "Cannot enumerate builtin skills " + error);
        }
    }

    public boolean hasSkill(String skillId) { return registry.containsKey(skillId); }
    public SkillManifest manifest(String skillId) { return registry.get(skillId); }
    public List<String> skillIds() { return new ArrayList<>(registry.keySet()); }
    /** 加载失败摘要；不包含技能正文、变量值或异常敏感参数。 */
    public List<String> loadErrors() { return new ArrayList<>(loadErrors); }

    /** 展开 Skill 步骤：替换 ${arg} 模板并在最终值上执行完整 DSL/SafetyPolicy 校验。 */
    public List<ActionSpec> resolve(String skillId, JSONObject args) throws JSONException {
        SkillManifest m = registry.get(skillId);
        if (m == null) throw new JSONException("unknown skill: " + skillId);
        return resolveManifest(m, args);
    }

    /* Package-private so JVM regression tests can exercise malformed manifests without assets. */
    List<ActionSpec> resolveManifest(SkillManifest m, JSONObject args) throws JSONException {
        JSONObject a = args != null ? args : new JSONObject();
        for (String required : m.requiredArgs) {
            if (!a.has(required) || a.isNull(required) || a.optString(required, null) == null) {
                throw new JSONException("skill " + m.skillId + " missing arg: " + required);
            }
        }
        List<ActionSpec> out = new ArrayList<>();
        if (!m.stepTemplates().isEmpty()) {
            for (JSONObject template : m.stepTemplates()) out.add(resolveTemplate(template, a, m));
        } else {
            for (ActionSpec step : m.steps) out.add(substitute(step, a, m.confirmation));
        }
        return out;
    }

    private ActionSpec resolveTemplate(JSONObject template, JSONObject args, SkillManifest m) throws JSONException {
        JSONObject substituted = substituteJson(template, args, m.requiredArgs);
        ActionSpec.RiskLevel stepRisk = ActionSpec.RiskLevel.fromString(substituted.optString("risk", null));
        if (m.risk.ordinal() > stepRisk.ordinal()) {
            substituted.put("risk", m.risk.name());
        }
        boolean confirm = substituted.optBoolean("requires_confirmation", false)
                || "before_external_send".equals(m.confirmation)
                || "always".equals(m.confirmation);
        substituted.put("requires_confirmation", confirm);
        // 这是唯一允许进入 DSL 的路径：因此 URL/selector 等最终值仍由公共安全校验拒绝。
        return DslParser.parseAction(substituted);
    }

    private ActionSpec substitute(ActionSpec step, JSONObject args, String skillConfirmation) throws JSONException {
        JSONObject params = substituteJson(step.params, args, null);
        JSONObject wrapper = new JSONObject();
        wrapper.put("action", step.type.wireName());
        wrapper.put("params", params);
        wrapper.put("timeout_ms", step.timeoutMs);
        wrapper.put("max_retries", step.maxRetries);
        wrapper.put("risk", step.risk.name());
        wrapper.put("requires_confirmation", step.requiresConfirmation
                || "before_external_send".equals(skillConfirmation) || "always".equals(skillConfirmation));
        if (step.postcondition != null) wrapper.put("postcondition", substituteString(step.postcondition, args, null));
        return DslParser.parseAction(wrapper);
    }

    private JSONObject substituteJson(JSONObject o, JSONObject args, List<String> declared) throws JSONException {
        JSONObject out = new JSONObject();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object v = o.get(key);
            if (v instanceof JSONObject) out.put(key, substituteJson((JSONObject) v, args, declared));
            else if (v instanceof JSONArray) out.put(key, substituteArray((JSONArray) v, args, declared));
            else if (v instanceof String) out.put(key, substituteString((String) v, args, declared));
            else out.put(key, v);
        }
        return out;
    }

    private JSONArray substituteArray(JSONArray array, JSONObject args, List<String> declared)
            throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof JSONObject) out.put(substituteJson((JSONObject) item, args, declared));
            else if (item instanceof JSONArray) out.put(substituteArray((JSONArray) item, args, declared));
            else if (item instanceof String) out.put(substituteString((String) item, args, declared));
            else out.put(item);
        }
        return out;
    }

    private String substituteString(String s, JSONObject args, List<String> declared) throws JSONException {
        Matcher matcher = VARIABLE.matcher(s);
        StringBuffer out = new StringBuffer();
        int cursor = 0;
        while (matcher.find()) {
            if (s.indexOf("${", cursor) >= 0 && s.indexOf("${", cursor) < matcher.start()) {
                throw new JSONException("malformed skill variable template");
            }
            String key = matcher.group(1);
            if (declared != null && !declared.contains(key)) {
                throw new JSONException("skill template uses undeclared arg: " + key);
            }
            if (!args.has(key) || args.isNull(key)) throw new JSONException("skill template missing arg: " + key);
            Object value = args.get(key);
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new JSONException("skill arg must be scalar: " + key);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
            cursor = matcher.end();
        }
        if (s.indexOf("${", cursor) >= 0 || (s.indexOf('}') >= 0 && s.indexOf("${") >= 0 && s.indexOf('}') < s.indexOf("${"))) {
            throw new JSONException("malformed skill variable template");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        byte[] buf = new byte[8192]; StringBuilder sb = new StringBuilder(); int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        return sb.toString();
    }
}
