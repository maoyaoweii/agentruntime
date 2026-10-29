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
    private final Context context;
    private final LocalRulePlanner localRulePlanner = new LocalRulePlanner();
    private final CloudPlannerAdapter cloudPlanner;
    private final LocalPlanAdapter localPlanner;
    private final com.agentruntime.intent.IntentPlanner intentPlanner;

    private final AtomicInteger cloudCalls = new AtomicInteger();
    private final AtomicInteger localCalls = new AtomicInteger();
    private final AtomicInteger intentHits = new AtomicInteger();
    private final AtomicInteger rulePlans = new AtomicInteger();
    private final AtomicInteger replans = new AtomicInteger();
    private final AtomicInteger compoundSkips = new AtomicInteger();

    public ModelRouter(Context context, SkillEngine skillEngine, AuditLogger auditLogger,
                       LocalModelRegistry localModels, EnvironmentProbe envProbe,
                       com.agentruntime.intent.IntentPlanner intentPlanner) {
        this.skillEngine = skillEngine;
        this.auditLogger = auditLogger;
        this.context = context.getApplicationContext();
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
        // 但它只做「单句 = 单技能」分类：复合目标（"…，然后…再…"）必须交给上层真实规划。
        // 实测把"设置剪贴板再读回来核对"判成 open_clock 并高置信度，直接执行必然错。
        // 同理，句子里带字面量载荷（标记串/引号内容）时也不能用无参技能应付：
        // "把 CLOUD_PLAN_MARK_7788 放进剪贴板" 同样被判成 open_clock（置信度 1.0）。
        boolean compound = isCompoundGoal(goal);
        boolean literal = hasLiteralPayload(goal);
        if (compound || literal) {
            compoundSkips.incrementAndGet();
            record(Level.L0_INTENT, goal, "intent_skipped:" + (compound ? "compound" : "literal"), start, false);
        } else if (intentPlanner != null && intentPlanner.model() != null) {
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
                TaskSpec t = sanitize(localPlanner.plan(goal, skillEngine.skillIds(), permissions));
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
                TaskSpec t = sanitize(cloudPlanner.plan(
                        goal, skillEngine.skillIds(), permissions, skillCatalog()));
                String missing = missingArgs(t);
                if (missing != null) {
                    // 模型常把 contact_alias 写成 recipient、file_alias 写成 file_path。
                    // 参数名不做语义猜测，直接把缺口回灌给它重规划一次（最多一次）。
                    t = sanitize(cloudPlanner.plan(
                            goal + "\n上次输出不可执行：" + missing
                                    + "。args 必须逐字使用括号内给出的参数名。",
                            skillEngine.skillIds(), permissions, skillCatalog()));
                    if (missingArgs(t) != null) {
                        throw new JSONException("cloud plan args invalid: " + missingArgs(t));
                    }
                }
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

    /**
     * 复合目标判定：一句话里出现顺序/并列连接词，说明是多个动作的目标。
     * 内置意图模型只输出单个意图，遇到复合目标必须让位给真实规划。
     */
    private static boolean isCompoundGoal(String goal) {
        if (goal == null) return false;
        String g = goal.replace("、", "，").replace(";", "，");
        return g.contains("，") || g.contains("然后") || g.contains("接着")
                || g.contains("再") || g.contains("并且") || g.contains("最后")
                || g.contains("之后") || g.contains("and then");
    }

    /**
     * 技能清单带必填参数名，交给云端规划器。
     * 只给技能名时模型不知道要传哪些参数，实测会产出缺参步骤，
     * 在 SkillEngine 解析阶段直接 SKILL_RESOLVE_FAILED。
     */
    private String skillCatalog() {
        StringBuilder sb = new StringBuilder();
        for (String id : skillEngine.skillIds()) {
            com.agentruntime.skill.SkillManifest m = skillEngine.manifest(id);
            // 设备上没装这个包，技能必然在 launch_app 阶段 APP_NOT_ALLOWED。
            // 实测模型会优先选"看起来更贴切"的专用技能（如 sys.open_clock），
            // 于是整个任务在第一步就失败，所以这类技能干脆不要出现在清单里。
            if (m != null && m.targetPackage != null && !m.targetPackage.trim().isEmpty()
                    && !installed(m.targetPackage)) {
                continue;
            }
            sb.append(id).append('(');
            if (m != null && m.requiredArgs != null && !m.requiredArgs.isEmpty()) {
                sb.append(String.join(",", m.requiredArgs));
            }
            sb.append(") ");
        }
        return sb.toString();
    }

    private boolean installed(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 剔除规划器自造的不可判定条件。
     * 模型常写 "clipboard_available" 这类自然语言断言，ConditionChecker 一律判 false，
     * 任务必然 PRECONDITION_FAILED / POSTCONDITION_FAILED 中止，等于没跑。
     */
    private TaskSpec sanitize(TaskSpec spec) throws JSONException {
        if (spec == null) return null;
        StringBuilder dropped = new StringBuilder();
        dropUnsupported(spec.preconditions, "precondition", dropped);
        dropUnsupported(spec.postconditions, "postcondition", dropped);
        // 执行前的 UI/前台包断言必然为假：任务开始时前台是 Agent 自己的界面。
        // 实测云端写出 "package_is: com.agenttestapp" 后任务直接 PRECONDITION_FAILED，
        // 因此只保留与当前界面无关的静态检查。
        java.util.Iterator<String> it = spec.preconditions.iterator();
        while (it.hasNext()) {
            String c = it.next().trim();
            if (!c.equals("test_account_ready") && !c.startsWith("file_exists:")) {
                if (dropped.length() > 0) dropped.append(", ");
                dropped.append("startup_precondition:").append(c);
                it.remove();
            }
        }
        if (dropped.length() > 0) {
            com.agentruntime.runtime.TaskBus.get()
                    .info("plan", "已剔除不可判定条件：" + dropped);
        }
        return clampLimits(spec);
    }

    /**
     * 夹紧模型给出的 limits。max_steps 是整任务的动作总预算，
     * 而一个技能内部通常有 4~6 个动作：实测模型写 max_steps=2，
     * 第二步技能执行到一半就 STEP_BUDGET_EXCEEDED，任务等于没跑。
     */
    private static TaskSpec clampLimits(TaskSpec spec) throws JSONException {
        int steps = spec.steps == null ? 0 : spec.steps.size();
        int minSteps = Math.min(64, Math.max(24, steps * 8));
        JSONObject root = spec.toJson();
        JSONObject lim = root.optJSONObject("limits");
        if (lim == null) lim = new JSONObject();
        if (lim.optInt("max_steps", 0) < minSteps) lim.put("max_steps", minSteps);
        if (lim.optInt("timeout_seconds", 0) < 180) lim.put("timeout_seconds", 180);
        root.put("limits", lim);
        return com.agentruntime.dsl.DslParser.taskFromJson(root);
    }

    /** 规划结果里是否有技能缺少必填参数；返回可读缺口，null 表示齐备。 */
    private String missingArgs(TaskSpec spec) {
        List<String> problems = new java.util.ArrayList<>();
        for (TaskSpec.TaskStep step : spec.steps) {
            com.agentruntime.skill.SkillManifest m = skillEngine.manifest(step.skillId);
            if (m == null) {
                problems.add(step.skillId + " 不是已知技能");
                continue;
            }
            for (String req : m.requiredArgs) {
                if (step.args == null || !step.args.has(req)) {
                    problems.add(step.skillId + " 缺参数 " + req);
                }
            }
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    private static void dropUnsupported(List<String> conditions, String kind, StringBuilder dropped) {
        java.util.Iterator<String> it = conditions.iterator();
        while (it.hasNext()) {
            String c = it.next();
            if (!com.agentruntime.executor.ConditionChecker.isSupported(c)) {
                if (dropped.length() > 0) dropped.append(", ");
                dropped.append(kind).append(':').append(c);
                it.remove();
            }
        }
    }

    /**
     * 目标里是否带字面量载荷：大写标记串、带引号的内容、URL 以外的数字串。
     * 内置意图模型只会选无参技能，遇到载荷必然丢信息，因此一律让位。
     */
    private static boolean hasLiteralPayload(String goal) {
        if (goal == null) return false;
        return LITERAL.matcher(goal).find();
    }

    private static final java.util.regex.Pattern LITERAL =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_\\-]{4,}|\"[^\"]{2,}\"|“|”");

    /** 异常重规划：同一目标允许有限次数重新规划，同样按成本从低到高。 */
    public TaskSpec replan(String goal, String failureReason, List<String> permissions) throws JSONException {
        replans.incrementAndGet();
        long start = System.currentTimeMillis();
        String withHint = goal + "（上次失败：" + failureReason + "）";

        if (localPlanner.isConfigured()) {
            try {
                TaskSpec t = sanitize(localPlanner.plan(withHint, skillEngine.skillIds(), permissions));
                localCalls.incrementAndGet();
                record(Level.L1_LOCAL_DECIDER, goal, "local_replan_ok", start, true);
                return t;
            } catch (JSONException e) {
                record(Level.L1_LOCAL_DECIDER, goal, "local_replan_failed", start, false);
            }
        }

        if (cloudPlanner.isConfigured()) {
            try {
                TaskSpec t = sanitize(cloudPlanner.plan(
                        withHint, skillEngine.skillIds(), permissions, skillCatalog()));
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
            o.put("compound_skips", compoundSkips.get());
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
