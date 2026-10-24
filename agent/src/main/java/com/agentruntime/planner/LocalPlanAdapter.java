package com.agentruntime.planner;

import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.env.EnvironmentProbe;
import com.agentruntime.env.EnvironmentSnapshot;
import com.agentruntime.local.LocalModelConfig;
import com.agentruntime.local.LocalModelProvider;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.local.LocalModelReply;
import com.agentruntime.local.LocalModelRequest;
import com.agentruntime.prompt.PromptBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 本地模型规划适配器：用设备上的小模型把目标变成 Task DSL。
 *
 * <p>与 {@link CloudPlannerAdapter} 完全对称——同样的提示词框架、同样的 Schema 校验、
 * 同样的失败语义。区别只在于模型跑在哪。
 *
 * <p>为什么值得单独做一层：本地模型的指令遵循能力弱于云端大模型，
 * 所以提示词必须更严格（见 {@link com.agentruntime.prompt.PromptLibrary}），
 * 输出校验也不能放松。任何一层放松都会让"本地跑得动"变成"本地乱执行"。
 */
public final class LocalPlanAdapter {

    private final LocalModelRegistry registry;
    private final EnvironmentProbe envProbe;

    public LocalPlanAdapter(LocalModelRegistry registry, EnvironmentProbe envProbe) {
        this.registry = registry;
        this.envProbe = envProbe;
    }

    /** 本地决策模型是否可用。 */
    public boolean isConfigured() {
        return registry != null && registry.available();
    }

    /** 当前模型的标识，写进审计。 */
    public String modelId() {
        LocalModelConfig cfg = registry.config();
        return cfg.deciderModel;
    }

    /**
     * 生成任务。
     *
     * @throws JSONException 模型不可用、输出不合规、或无法解析
     */
    public TaskSpec plan(String goal, List<String> skills, List<String> allowedApps)
            throws JSONException {
        if (!isConfigured()) {
            throw new JSONException("local planner not available");
        }
        LocalModelProvider provider = registry.provider();
        EnvironmentSnapshot env = envProbe == null ? null : envProbe.snapshot();

        PromptBuilder.Bundle prompt = PromptBuilder.buildPlanner(goal, env, skills, allowedApps, null);

        LocalModelRequest req = LocalModelRequest.builder(registry.config().deciderModel)
                .system(prompt.systemPrompt)
                .user(prompt.userPrompt)
                .maxTokens(registry.config().maxTokens)
                .temperature(0.1)
                .timeout(registry.config().timeoutMs)
                .build();

        LocalModelReply reply = provider.complete(req);
        if (!reply.usable()) {
            throw new JSONException("local planner call failed: " + reply.code);
        }

        JSONObject taskJson;
        try {
            taskJson = com.agentruntime.dsl.OutputFormat.parseObject(reply.text);
        } catch (JSONException e) {
            throw new JSONException("local planner output is not JSON: " + e.getMessage());
        }

        // 模型可以显式认输：{"error": "..."}，这比编造步骤好
        if (taskJson.has("error") && !taskJson.has("steps")) {
            throw new JSONException("local planner refused: " + taskJson.optString("error"));
        }

        DslParser.ValidationResult vr = DslParser.validateTaskJson(taskJson);
        if (!vr.ok) {
            throw new JSONException("local plan schema invalid: " + vr.error);
        }
        return DslParser.taskFromJson(taskJson);
    }

    /** 提示词预算与可疑注入的统计，供审计。 */
    public PromptBuilder.Bundle lastPrompt(String goal, List<String> skills, List<String> apps) {
        return PromptBuilder.buildPlanner(goal,
                envProbe == null ? null : envProbe.snapshot(), skills, apps, null);
    }

    static String extractJson(String content) {
        if (content == null) return "";
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) return content.trim();
        return content.substring(s, e + 1);
    }
}
