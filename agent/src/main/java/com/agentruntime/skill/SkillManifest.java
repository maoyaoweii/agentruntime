package com.agentruntime.skill;

import com.agentruntime.dsl.ActionSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Skill Manifest：描述应用能力、适用版本、前置/后置条件、策略步骤、风险与恢复。
 * 步骤以 JSON 模板保存；含运行时变量的动作必须在 SkillEngine.resolve 中替换后再交给 DSL。
 */
public final class SkillManifest {

    public final String skillId;
    public final String version;
    public final String targetPackage;
    public final List<String> requiredArgs;
    /** 静态构造场景的兼容字段；内置 manifest 的模板步骤通过 stepTemplates() 访问。 */
    public final List<ActionSpec> steps;
    public final List<String> postconditions;
    public final ActionSpec.RiskLevel risk;
    public final String confirmation;
    public final int maxRetries;
    public final String recoveryHint;
    private final List<JSONObject> stepTemplates;

    public SkillManifest(String skillId, String version, String targetPackage,
                         List<String> requiredArgs, List<ActionSpec> steps,
                         List<String> postconditions, ActionSpec.RiskLevel risk,
                         String confirmation, int maxRetries, String recoveryHint) {
        this(skillId, version, targetPackage, requiredArgs, steps, null, postconditions,
                risk, confirmation, maxRetries, recoveryHint);
    }

    private SkillManifest(String skillId, String version, String targetPackage,
                          List<String> requiredArgs, List<ActionSpec> steps,
                          List<JSONObject> stepTemplates, List<String> postconditions,
                          ActionSpec.RiskLevel risk, String confirmation,
                          int maxRetries, String recoveryHint) {
        this.skillId = skillId;
        this.version = version;
        this.targetPackage = targetPackage;
        this.requiredArgs = requiredArgs != null ? requiredArgs : new ArrayList<>();
        this.steps = steps != null ? steps : new ArrayList<>();
        this.stepTemplates = stepTemplates != null ? stepTemplates : new ArrayList<>();
        this.postconditions = postconditions != null ? postconditions : new ArrayList<>();
        this.risk = risk != null ? risk : ActionSpec.RiskLevel.LOW;
        this.confirmation = confirmation;
        this.maxRetries = maxRetries;
        this.recoveryHint = recoveryHint;
    }

    public List<ActionSpec> steps() {
        return Collections.unmodifiableList(steps);
    }

    /** 返回原始动作模板的只读视图；调用方不得修改其中的 JSONObject。 */
    public List<JSONObject> stepTemplates() {
        return Collections.unmodifiableList(stepTemplates);
    }

    public static SkillManifest fromJson(JSONObject o) throws JSONException {
        if (o == null) throw new JSONException("skill manifest required");
        List<String> args = new ArrayList<>();
        JSONArray argsArr = o.optJSONArray("args");
        if (argsArr != null) {
            for (int i = 0; i < argsArr.length(); i++) args.add(argsArr.getString(i));
        }
        List<JSONObject> templates = new ArrayList<>();
        JSONArray stepsArr = o.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject step = stepsArr.optJSONObject(i);
                if (step == null) throw new JSONException("steps[" + i + "] must be an object");
                // 不在加载阶段调用 DslParser：模板值（例如 ${url}）尚不是最终 DSL 值。
                templates.add(new JSONObject(step.toString()));
            }
        }
        List<String> post = new ArrayList<>();
        JSONArray postArr = o.optJSONArray("postconditions");
        if (postArr != null) {
            for (int i = 0; i < postArr.length(); i++) post.add(postArr.getString(i));
        }
        return new SkillManifest(
                o.getString("skill_id"), o.optString("version", "1.0"),
                o.optString("target_package", null), args, new ArrayList<ActionSpec>(),
                templates, post, ActionSpec.RiskLevel.fromString(o.optString("risk", null)),
                o.optString("confirmation", null), o.optInt("max_retries", 1),
                o.optString("recovery", null));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("skill_id", skillId);
            o.put("version", version);
            o.put("target_package", targetPackage);
            o.put("risk", risk.name());
            JSONArray st = new JSONArray();
            if (!stepTemplates.isEmpty()) {
                for (JSONObject s : stepTemplates) st.put(new JSONObject(s.toString()));
            } else {
                for (ActionSpec s : steps) st.put(s.toJson());
            }
            o.put("steps", st);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
