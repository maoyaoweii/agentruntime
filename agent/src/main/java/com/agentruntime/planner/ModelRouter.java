package com.agentruntime.planner;

import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.env.EnvironmentProbe;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.skill.SkillEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Model Router：按成本从低到高选择规划器。
 *
 * <p>路由顺序与理由：
 * <ol>
 *   <li><b>L0 本地规则</b>：零延迟零成本，命中即用。绝大多数常见目标在这里解决。</li>
 *   <li><b>L1 本地决策模型</b>：屏幕内容不出设备，延迟 1~3 秒。输出不合规就往下走。</li>
 *   <li><b>L2/L3 云端模型</b>：能力最强但最慢最贵，只在前面都不行时才用。</li>
 *   <li><b>L4 视觉</b>：只用于单步元素定位，不承担规划。</li>
 * </ol>
 *
 * <p>关键设计：<b>本地失败要回退到云端，而不是直接失败</b>。小模型指令遵循能力弱，
 * 输出不合 Schema 是常态；把它当成"一次廉价尝试"而不是"唯一依赖"，整体成功率才高。
 */
public final class ModelRouter {

    public enum Level { L0_RULE, L0_INTENT, L1_LOCAL_DECIDER, L2_FAST_LLM, L3_STRONG_LLM, L4_VISION }

    private final SkillEngine skillEngine;
    private final AuditLogger auditLogger;
    private final LocalRulePlanner localRulePlanner = new LocalRulePlanner();
    private final CloudPlannerAdapter cloudPlanner;
    private final LocalPlanAdapter localPlanner;
    private final com.agentruntime.intent.IntentPlanner intentPlanner;

    private final AtomicInteger cloudCalls = new AtomicInteger();
    private final AtomicInteger localCalls = new AtomicInteger();
    private final AtomicInteger intentHits = new AtomicInteger();
    private final AtomicInteger rulePlans = new AtomicInteger();
    private final AtomicInteger replans = new AtomicInteger();

    public ModelRouter(Context context, SkillEngine skillEngine, AuditLogger auditLogger,
                       LocalModelRegistry localModels, EnvironmentProbe envProbe,
                       com.agentruntime.intent.IntentPlanner intentPlanner) {
        this.skillEngine = skillEngine;
        this.auditLogger = auditLogger;
        this.cloudPlanner = new CloudPlannerAdapter(context);
        this.localPlanner = new LocalPlanAdapter(localModels, envProbe);
        this.intentPlanner = intentPlanner;
    }

    public com.agentruntime.intent.IntentPlanner intent() {
        return intentPlanner;
    }

    public CloudPlannerAdapter cloud() {
        return cloudPlanner;
    }

    public LocalPlanAdapter local() {
        return localPlanner;
    }

    /** 规划入口：L0 规则 → L1 本地模型 → L3 云端。 */
    public TaskSpec plan(String goal, List<String> permissions) throws JSONException {
        long start = System.currentTimeMillis();

        if (localRulePlanner.canPlan(goal)) {
            rulePlans.incrementAndGet();
            record(Level.L0_RULE, goal, "local_rule_match", start, true);
            return localRulePlanner.plan(goal);
        }

        // L0.5：内置意图模型（17KB，随包安装，毫秒级）。规则命中不了换说法时由它兜住。
        if (intentPlanner != null && intentPlanner.model() != null) {
            try {
                com.agentruntime.intent.IntentModel.Prediction p = intentPlanner.classify(goal);
                TaskSpec t = intentPlanner.plan(goal);
                if (t != null) {
                    intentHits.incrementAndGet();
                    record(Level.L0_INTENT, goal, "intent_hit:" + p.describe(), start, true);
                    com.agentruntime.runtime.TaskBus.get()
                            .info("plan", "内置模型识别意图：" + p.describe());
                    return t;
                }
                if (p != null && p.usable) {
                    record(Level.L0_INTENT, goal, "intent_needs_args:" + p.intent, start, false);
                    com.agentruntime.runtime.TaskBus.get()
                            .info("plan", "意图 " + p.intent + " 需要更完整的规划，转交上层");
                }
            } catch (JSONException e) {
                record(Level.L0_INTENT, goal, "intent_failed", start, false);
            }
        }

        String localFailure = null;
        if (localPlanner.isConfigured()) {
            try {
                TaskSpec t = localPlanner.plan(goal, skillEngine.skillIds(), permissions);
                localCalls.incrementAndGet();
                record(Level.L1_LOCAL_DECIDER, goal, "local_plan_ok", start, true);
                return t;
            } catch (JSONException e) {
                localFailure = safeFailure(e, "local planner");
                record(Level.L1_LOCAL_DECIDER, goal, "local_plan_failed:" + localFailure, start, false);
            }
        }

        if (cloudPlanner.isConfigured()) {
            try {
                TaskSpec t = cloudPlanner.plan(goal, skillEngine.skillIds(), permissions);
                cloudCalls.incrementAndGet();
                record(Level.L3_STRONG_LLM, goal,
                        localFailure == null ? "cloud_plan_ok" : "cloud_plan_ok_after_local:" + localFailure,
                        start, true);
                return t;
            } catch (IOException | JSONException e) {
                String cloudFailure = safeFailure(e, "cloud planner");
                record(Level.L3_STRONG_LLM, goal, "cloud_plan_failed:" + cloudFailure, start, false);
                throw new JSONException("all planners failed (local: "
                        + (localFailure == null ? "unavailable" : localFailure)
                        + ", cloud: " + cloudFailure + ")");
            }
        }

        record(Level.L0_RULE, goal, "no_planner_available", start, false);
        throw new JSONException("no planner available for goal (local rules miss"
                + (localFailure != null ? ", local model: " + localFailure : ", local model unconfigured")
                + ", cloud unconfigured)");
    }

    /** 异常重规划：同一目标允许有限次数重新规划，同样按成本从低到高。 */
    public TaskSpec replan(String goal, String failureReason, List<String> permissions) throws JSONException {
        replans.incrementAndGet();
        long start = System.currentTimeMillis();
        String withHint = goal + "（上次失败：" + failureReason + "）";

        if (localPlanner.isConfigured()) {
            try {
                TaskSpec t = localPlanner.plan(withHint, skillEngine.skillIds(), permissions);
                localCalls.incrementAndGet();
                record(Level.L1_LOCAL_DECIDER, goal, "local_replan_ok", start, true);
                return t;
            } catch (JSONException e) {
                record(Level.L1_LOCAL_DECIDER, goal, "local_replan_failed", start, false);
            }
        }

        if (cloudPlanner.isConfigured()) {
            try {
                TaskSpec t = cloudPlanner.plan(withHint, skillEngine.skillIds(), permissions);
                cloudCalls.incrementAndGet();
                record(Level.L3_STRONG_LLM, goal, "cloud_replan_ok", start, true);
                return t;
            } catch (IOException | JSONException e) {
                record(Level.L3_STRONG_LLM, goal, "cloud_replan_failed", start, false);
            }
        }

        record(Level.L0_RULE, goal, "replan_local_rule_retry", start, true);
        return localRulePlanner.plan(goal);
    }

    public int cloudCallCount() {
        return cloudCalls.get();
    }

    public int localCallCount() {
        return localCalls.get();
    }

    public int rulePlanCount() {
        return rulePlans.get();
    }

    public int replanCount() {
        return replans.get();
    }

    public JSONObject stats() {
        JSONObject o = new JSONObject();
        try {
            o.put("cloud_calls", cloudCalls.get());
            o.put("local_calls", localCalls.get());
            o.put("intent_hits", intentHits.get());
            o.put("rule_plans", rulePlans.get());
            o.put("replans", replans.get());
            o.put("cloud_configured", cloudPlanner.isConfigured());
            o.put("local_configured", localPlanner.isConfigured());
            o.put("local_model", localPlanner.isConfigured() ? localPlanner.modelId() : "");
            o.put("intent_model", intentPlanner != null && intentPlanner.model() != null);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Provider/parser exception text may contain echoed request material; never expose it in audit/UI. */
    private static String safeFailure(Throwable error, String planner) {
        String message = error == null ? null : error.getMessage();
        if ("cloud planner".equals(planner) && message != null
                && message.matches("cloud planner http [1-5][0-9]{2}")) {
            return message;
        }
        return planner + (error instanceof IOException ? " transport failure" : " response rejected");
    }

    private void record(Level level, String goal, String reason, long start, boolean ok) {
        try {
            JSONObject d = new JSONObject();
            d.put("level", level.name());
            d.put("goal_chars", goal == null ? 0 : goal.length());
            d.put("reason", reason);
            d.put("latency_ms", System.currentTimeMillis() - start);
            d.put("ok", ok);
            auditLogger.record("plan", "-", "model_router", level.name(), 0, ok ? "PLAN_OK" : "PLAN_FAIL", d);
        } catch (JSONException ignored) {
        }
    }
}
