package com.agentruntime.runtime;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.AppLaunchPolicy;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent Runtime：任务执行状态机。
 * 步骤循环 -> Skill 展开 -> Action Executor 执行 -> 后置断言 -> 失败恢复/重规划。
 * 模型输出不等于执行授权；Executor 是唯一动作入口。
 */
public final class AgentRuntime {

    /** 单个动作回给模型的观察内容上限；足够放下屏文字或网页摘要，又不会撑爆上下文。 */
    private static final int ACTION_DETAIL_LIMIT = 6000;

    /**
     * 执行过程监听器：把内部进度暴露给 UI / 悬浮窗 / 通知。
     *
     * <p>全部回调运行在任务线程上，订阅方需要自行切主线程（{@link TaskBus} 已代劳）。
     * 实现类不得抛异常——那会打断任务执行。
     */
    public interface Listener {
        void onTaskStart(TaskSpec task);

        void onStepStart(int index, int total, TaskSpec.TaskStep step);

        void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok, String code, long durationMs);

        void onReplan(String reason);

        void onFinish(TaskResult result);
    }

    public static final class TaskResult {
        public final String taskId;
        public final String status;
        public final JSONObject detail;

        public TaskResult(String taskId, String status, JSONObject detail) {
            this.taskId = taskId;
            this.status = status;
            this.detail = detail;
        }
    }

    private final StateEngine stateEngine;
    private final SkillEngine skillEngine;
    private final ActionExecutor actionExecutor;
    private final ModelRouter modelRouter;
    private final AuditLogger auditLogger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public AgentRuntime(StateEngine stateEngine, SkillEngine skillEngine,
                        ActionExecutor actionExecutor, ModelRouter modelRouter,
                        AuditLogger auditLogger) {
        this.stateEngine = stateEngine;
        this.skillEngine = skillEngine;
        this.actionExecutor = actionExecutor;
        this.modelRouter = modelRouter;
        this.auditLogger = auditLogger;
    }

    public void cancel() {
        cancelRequested.set(true);
    }

    public TaskResult runTask(TaskSpec task, boolean autoConfirm, boolean allowReplan) {
        return runTask(task, autoConfirm, allowReplan, null);
    }

    /** 带进度回调的执行入口。 */
    public TaskResult runTask(TaskSpec task, boolean autoConfirm, boolean allowReplan, Listener listener) {
        return runTask(task, autoConfirm, allowReplan, listener, new AtomicBoolean(false));
    }

    /** Shares cancellation with planning so a stop request cannot be lost at execution start. */
    public TaskResult runTask(TaskSpec task, boolean autoConfirm, boolean allowReplan,
                              Listener listener, AtomicBoolean cancellation) {
        return runTask(task, autoConfirm, allowReplan, listener, cancellation, false);
    }

    /**
     * @param includeObservations 是否把动作观察内容（屏文字、网页正文、文件内容）带回 TaskResult。
     *        只在"结果要交给模型继续决策"的链路（DSH 的 run_skill）里打开；默认关闭，
     *        这样落库的 TaskResult 仍然不含敏感正文，历史脱敏测试也继续成立。
     */
    public TaskResult runTask(TaskSpec task, boolean autoConfirm, boolean allowReplan,
                              Listener listener, AtomicBoolean cancellation,
                              boolean includeObservations) {
        if (!running.compareAndSet(false, true)) {
            return new TaskResult(task.taskId, "BUSY", new JSONObject());
        }
        cancelRequested = cancellation;
        try {
            if (listener != null) {
                try {
                    listener.onTaskStart(task);
                } catch (RuntimeException ignored) {
                    // 监听器故障不得影响任务
                }
            }
            TaskResult result = doRunTask(task, autoConfirm, allowReplan, listener);
            if (!includeObservations) {
                result = new TaskResult(result.taskId, result.status, withoutObservations(result.detail));
            }
            if (listener != null) {
                try {
                    listener.onFinish(result);
                } catch (RuntimeException ignored) {
                }
            }
            return result;
        } finally {
            running.set(false);
        }
    }

    private TaskResult doRunTask(TaskSpec task, boolean autoConfirm, boolean allowReplan, Listener listener) {
        long start = System.currentTimeMillis();
        long deadline = start + task.limits.timeoutSeconds * 1000;
        int executedActions = 0;
        int succeededSteps = 0;
        int failedSteps = 0;
        boolean launchedAnyApp = false;
        ReplanBudget replanBudget = new ReplanBudget(task.limits.maxReplans);
        JSONArray stepLog = new JSONArray();

        try {
            AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
            if (svc != null) svc.snapshot("task_start");

            // Task-level preconditions gate every step; they must never be silently ignored.
            for (int pi = 0; pi < task.preconditions.size(); pi++) {
                if (cancelRequested.get()) {
                    return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                if (System.currentTimeMillis() >= deadline) {
                    return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                boolean ok = actionExecutorCondition(task.preconditions.get(pi), deadline);
                if (cancelRequested.get()) {
                    return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                if (System.currentTimeMillis() >= deadline) {
                    return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                JSONObject preconditionLog = new JSONObject();
                preconditionLog.put("precondition_index", pi);
                preconditionLog.put("ok", ok);
                stepLog.put(preconditionLog);
                if (!ok) {
                    return finish(task, "PRECONDITION_FAILED", start, stepLog,
                            executedActions, succeededSteps, failedSteps);
                }
            }

            for (int si = 0; si < task.steps.size(); si++) {
                if (cancelRequested.get()) return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
                if (System.currentTimeMillis() >= deadline) return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);

                TaskSpec.TaskStep step = task.steps.get(si);
                if (listener != null) {
                    try {
                        listener.onStepStart(si + 1, task.steps.size(), step);
                    } catch (RuntimeException ignored) {
                    }
                }
                StepOutcome outcome = runStep(task, step, autoConfirm, deadline,
                        Math.max(0, task.limits.maxSteps - executedActions));
                if (listener != null) {
                    try {
                        listener.onStepEnd(si + 1, step, outcome.ok, outcome.code, outcome.durationMs);
                    } catch (RuntimeException ignored) {
                    }
                }
                executedActions += outcome.actionsExecuted;
                launchedAnyApp |= outcome.launchedApp;
                stepLog.put(outcome.log);
                if (outcome.ok) {
                    succeededSteps++;
                } else {
                    failedSteps++;
                    if ("TASK_TIMEOUT".equals(outcome.code)
                            || "DEADLINE_OR_CANCEL".equals(outcome.code)) {
                        return finish(task, cancelRequested.get() ? "CANCELLED" : "TIMEOUT",
                                start, stepLog, executedActions, succeededSteps, failedSteps);
                    }
                    if ("STEP_BUDGET_EXCEEDED".equals(outcome.code)) {
                        return finish(task, "STEP_BUDGET_EXCEEDED", start, stepLog,
                                executedActions, succeededSteps, failedSteps);
                    }
                    // 原样重试只留给超时抖动；其余失败要么真重规划（换技能/换参数），要么带着证据失败。
                    if (isTimeoutJitter(outcome.code) && allowReplan && replanBudget.tryConsume()) {
                        if (listener != null) {
                            try {
                                listener.onReplan("步骤超时 " + outcome.code + "，原样重试一次");
                            } catch (RuntimeException ignored) {
                            }
                        }
                        AgentAccessibilityService s2 = AgentAccessibilityService.getInstance();
                        if (s2 != null) s2.snapshot("recover");
                        StepOutcome retry = runStep(task, step, autoConfirm, deadline,
                                Math.max(0, task.limits.maxSteps - executedActions));
                        if (listener != null) {
                            try {
                                listener.onStepEnd(si + 1, step, retry.ok, retry.code, retry.durationMs);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        executedActions += retry.actionsExecuted;
                        launchedAnyApp |= retry.launchedApp;
                        stepLog.put(retry.log);
                        if (retry.ok) {
                            succeededSteps++;
                            failedSteps--;
                        } else {
                            if ("TASK_TIMEOUT".equals(retry.code) || "DEADLINE_OR_CANCEL".equals(retry.code)) {
                                return finish(task, cancelRequested.get() ? "CANCELLED" : "TIMEOUT",
                                        start, stepLog, executedActions, succeededSteps, failedSteps);
                            }
                            if ("STEP_BUDGET_EXCEEDED".equals(retry.code)) {
                                return finish(task, "STEP_BUDGET_EXCEEDED", start, stepLog,
                                        executedActions, succeededSteps, failedSteps);
                            }
                            return finish(task, "FAILED", start, stepLog, executedActions, succeededSteps, failedSteps);
                        }
                    } else if (allowReplan && replanAllowed(outcome.code) && replanBudget.tryConsume()) {
                        if (listener != null) {
                            try {
                                listener.onReplan("步骤失败 " + outcome.code + "，重新规划");
                            } catch (RuntimeException ignored) {
                            }
                        }
                        StepOutcome replanned = replanAndRun(task, step, outcome, autoConfirm, deadline,
                                Math.max(0, task.limits.maxSteps - executedActions));
                        executedActions += replanned.actionsExecuted;
                        launchedAnyApp |= replanned.launchedApp;
                        stepLog.put(replanned.log);
                        if (replanned.ok) {
                            succeededSteps++;
                            failedSteps--;
                        } else {
                            return finish(task, "FAILED", start, stepLog, executedActions, succeededSteps, failedSteps);
                        }
                    } else {
                        return finish(task, "FAILED", start, stepLog, executedActions, succeededSteps, failedSteps);
                    }
                }
                if (executedActions > task.limits.maxSteps) {
                    return finish(task, "STEP_BUDGET_EXCEEDED", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
            }

            if (cancelRequested.get()) {
                return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            if (System.currentTimeMillis() >= deadline) {
                return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
            }

            // 任务级目标核验：计划里允许的应用，与任务结束时实际停留的应用必须一致。
            // 实测 B03/B04：目标是「打开 Aurora 商店 / F-Droid」，实际执行的是 sys.open_clock，
            // 步骤级断言 package_is:com.android.deskclock 自己通过了，任务仍报 SUCCESS——
            // 「跑错了却报成功」是最危险的失败方式，所以这一条不接受任何后置断言替代。
            if (launchedAnyApp && !task.allowedApps.isEmpty()) {
                AgentAccessibilityService sTarget = AgentAccessibilityService.getInstance();
                com.agentruntime.state.WorldState finalState =
                        sTarget != null ? sTarget.snapshot("task_target") : stateEngine.current();
                String foreground = finalState == null ? null : finalState.foregroundPackage;
                boolean reached = AppLaunchPolicy.targetReached(task.allowedApps, foreground);
                JSONObject targetLog = new JSONObject();
                targetLog.put("target_check", "foreground_in_allowed_apps");
                targetLog.put("foreground", foreground == null ? "" : foreground);
                targetLog.put("allowed", new JSONArray(task.allowedApps));
                targetLog.put("ok", reached);
                stepLog.put(targetLog);
                if (!reached) {
                    return finish(task, "TASK_TARGET_MISMATCH", start, stepLog,
                            executedActions, succeededSteps, failedSteps);
                }
            }

            // 任务级后置断言
            AgentAccessibilityService s3 = AgentAccessibilityService.getInstance();
            if (s3 != null) s3.snapshot("task_verify");
            if (cancelRequested.get()) {
                return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            if (System.currentTimeMillis() >= deadline) {
                return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            for (String cond : task.postconditions) {
                if (cancelRequested.get()) {
                    return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                if (System.currentTimeMillis() >= deadline) {
                    return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                boolean ok = actionExecutorCondition(cond, deadline);
                if (cancelRequested.get()) {
                    return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                if (System.currentTimeMillis() >= deadline) {
                    return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
                }
                JSONObject c = new JSONObject();
                c.put("postcondition", cond);
                c.put("ok", ok);
                stepLog.put(c);
                if (!ok) return finish(task, "POSTCONDITION_FAILED", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            if (cancelRequested.get()) {
                return finish(task, "CANCELLED", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            if (System.currentTimeMillis() >= deadline) {
                return finish(task, "TIMEOUT", start, stepLog, executedActions, succeededSteps, failedSteps);
            }
            return finish(task, "SUCCESS", start, stepLog, executedActions, succeededSteps, failedSteps);
        } catch (JSONException e) {
            JSONObject err = new JSONObject();
            try {
                err.put("error", "runtime_json_error");
            } catch (JSONException ignored) {
            }
            stepLog.put(err);
            return finish(task, "ERROR", start, stepLog, executedActions, succeededSteps, failedSteps);
        }
    }

    /** 只有"等一等就好"的超时才原样重试；语义失败重试同一步没有意义。 */
    private static boolean isTimeoutJitter(String code) {
        return "TASK_TIMEOUT".equals(code) || "EXEC_TIMEOUT".equals(code)
                || "TIMEOUT".equals(code) || "STEP_TIMEOUT".equals(code);
    }

    /** 用户/策略拒绝不重规划：再问一次还是同一个答案。 */
    private static boolean replanAllowed(String code) {
        return !("APP_NOT_ALLOWED".equals(code) || "RISK_REJECTED".equals(code)
                || "USER_REJECTED".equals(code) || "CONFIRMATION_NOT_OBTAINED".equals(code)
                || "STEP_BUDGET_EXCEEDED".equals(code) || "DEADLINE_OR_CANCEL".equals(code));
    }

    /**
     * 真重规划：让规划器在失败上下文里换技能或换参数，然后执行新计划。
     *
     * <p>旧实现是把同一步原样再跑一遍——参数没变、技能没变，失败码自然也没变。
     * 这里禁止"重规划成完全相同的单步"，并把失败码、前台包名、最后一屏摘要一路带下去。
     */
    private StepOutcome replanAndRun(TaskSpec task, TaskSpec.TaskStep failed, StepOutcome failure,
                                     boolean autoConfirm, long deadline, int actionBudget) throws JSONException {
        StepOutcome out = new StepOutcome();
        JSONObject log = new JSONObject();
        log.put("replan", true);
        log.put("failed_skill", failed.skillId);
        log.put("failure_code", failure.code);
        JSONArray steps = new JSONArray();
        log.put("steps", steps);
        out.log = log;
        out.code = "REPLAN_FAILED";

        String foreground = "";
        String screen = "";
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        com.agentruntime.state.WorldState state =
                service != null ? service.snapshot("replan") : stateEngine.current();
        if (state != null) {
            foreground = state.foregroundPackage == null ? "" : state.foregroundPackage;
            screen = visibleSummary(state);
        }
        log.put("foreground", foreground);
        log.put("last_screen", screen);

        String hint = "失败码 " + failure.code
                + (foreground.isEmpty() ? "" : "；前台包名 " + foreground)
                + (screen.isEmpty() ? "" : "；最后一屏 " + screen);
        TaskSpec replanned = null;
        try {
            replanned = modelRouter.replan(task.goal, hint, java.util.Collections.singletonList("accessibility"));
        } catch (Exception e) {
            log.put("replan_error", "planner_unavailable");
        }
        if (replanned == null || replanned.steps.isEmpty()) {
            if (!log.has("replan_error")) log.put("replan_error", "no_plan");
            return out;
        }
        boolean identical = replanned.steps.size() == 1
                && replanned.steps.get(0).skillId.equals(failed.skillId)
                && String.valueOf(replanned.steps.get(0).args).equals(String.valueOf(failed.args));
        if (identical) {
            log.put("replan_error", "identical_step");
            return out;
        }
        int budget = actionBudget;
        boolean allOk = true;
        String lastCode = "OK";
        for (TaskSpec.TaskStep step : replanned.steps) {
            if (budget <= 0) {
                allOk = false;
                lastCode = "STEP_BUDGET_EXCEEDED";
                break;
            }
            StepOutcome one = runStep(task, step, autoConfirm, deadline, budget);
            steps.put(one.log);
            budget -= one.actionsExecuted;
            out.actionsExecuted += one.actionsExecuted;
            out.launchedApp |= one.launchedApp;
            lastCode = one.code;
            if (!one.ok) {
                allOk = false;
                break;
            }
        }
        out.ok = allOk;
        out.code = allOk ? "REPLAN_OK" : lastCode;
        return out;
    }

    private static String visibleSummary(com.agentruntime.state.WorldState state) {
        if (state == null || state.root == null) return "";
        StringBuilder text = new StringBuilder();
        for (com.agentruntime.state.UiNode node : state.root.flat()) {
            String label = node.text != null && !node.text.trim().isEmpty()
                    ? node.text.trim() : node.contentDescription;
            if (label == null || label.trim().isEmpty()) continue;
            if (text.length() + label.length() > 160) break;
            if (text.length() > 0) text.append(" / ");
            text.append(label.trim());
        }
        return text.toString();
    }

    private static final class StepOutcome {
        boolean ok;
        boolean launchedApp;
        int actionsExecuted;
        String code = "OK";
        long durationMs;
        JSONObject log;
    }

    private StepOutcome runStep(TaskSpec task, TaskSpec.TaskStep step, boolean autoConfirm,
                                long deadline, int actionBudget) throws JSONException {
        StepOutcome out = new StepOutcome();
        JSONObject log = new JSONObject();
        log.put("skill", step.skillId);
        log.put("arg_count", step.args.length());
        JSONArray actionLog = new JSONArray();
        log.put("actions", actionLog);
        long t0 = System.currentTimeMillis();
        com.agentruntime.executor.RiskGate.StepScope scope = null;
        try {
            List<ActionSpec> actions = skillEngine.resolve(step.skillId, step.args);
            scope = actionExecutor.confirmationScope(step.skillId, step.args, actions);
            SkillManifest manifest = skillEngine.manifest(step.skillId);
            boolean stepOk = true;
            String lastCode = "OK";
            for (ActionSpec action : actions) {
                if (cancelRequested.get()) {
                    stepOk = false;
                    lastCode = "DEADLINE_OR_CANCEL";
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    stepOk = false;
                    lastCode = "TASK_TIMEOUT";
                    break;
                }
                if (out.actionsExecuted >= actionBudget) {
                    stepOk = false;
                    lastCode = "STEP_BUDGET_EXCEEDED";
                    break;
                }
                if (action.type == ActionSpec.ActionType.WAIT_MS) {
                    long requestedWaitMs = action.params.optLong("ms", 0);
                    long remainingTaskMs = deadline - System.currentTimeMillis();
                    if (remainingTaskMs <= 0 || requestedWaitMs >= remainingTaskMs) {
                        stepOk = false;
                        lastCode = "TASK_TIMEOUT";
                        break;
                    }
                }
                ExecResult r = actionExecutor.execute(task.taskId, step.skillId,
                        stepSubGoal(step), action, task.allowedApps, cancelRequested, deadline);
                out.actionsExecuted++;
                if (action.type == ActionSpec.ActionType.LAUNCH_APP && r.ok()) {
                    out.launchedApp = true;
                }
                JSONObject ar = new JSONObject();
                ar.put("action", action.type.wireName());
                // 模型需要"看到了什么"才可能做下一步；审计另走 toLogJson，屏内容不入盘。
                ar.put("result", r.toToolJson(ACTION_DETAIL_LIMIT));
                actionLog.put(ar);
                if (cancelRequested.get()) {
                    stepOk = false;
                    lastCode = "DEADLINE_OR_CANCEL";
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    stepOk = false;
                    lastCode = "TASK_TIMEOUT";
                    break;
                }
                if (!r.ok()) {
                    stepOk = false;
                    lastCode = r.code;
                    break;
                }
            }
            // Skill 级后置条件
            if (stepOk && manifest != null) {
                for (String cond : manifest.postconditions) {
                    String expanded = cond;
                    java.util.Iterator<String> argKeys = step.args.keys();
                    while (argKeys.hasNext()) {
                        String k = argKeys.next();
                        expanded = expanded.replace("${" + k + "}", step.args.optString(k, ""));
                    }
                    if (cancelRequested.get()) {
                        stepOk = false;
                        lastCode = "DEADLINE_OR_CANCEL";
                        break;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        stepOk = false;
                        lastCode = "TASK_TIMEOUT";
                        break;
                    }
                    boolean conditionOk = actionExecutorCondition(expanded, deadline);
                    if (cancelRequested.get()) {
                        stepOk = false;
                        lastCode = "DEADLINE_OR_CANCEL";
                        break;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        stepOk = false;
                        lastCode = "TASK_TIMEOUT";
                        break;
                    }
                    if (!conditionOk) {
                        stepOk = false;
                        lastCode = "SKILL_POSTCONDITION_FAILED";
                        break;
                    }
                }
            }
            out.ok = stepOk;
            out.code = lastCode;
            out.durationMs = System.currentTimeMillis() - t0;
            log.put("ok", stepOk);
            log.put("code", lastCode);
            log.put("duration_ms", out.durationMs);
        } catch (JSONException e) {
            out.ok = false;
            out.code = "SKILL_RESOLVE_FAILED";
            out.durationMs = System.currentTimeMillis() - t0;
            log.put("ok", false);
            log.put("code", "SKILL_RESOLVE_FAILED");
            log.put("error", "skill_resolution_rejected");
        } finally {
            if (scope != null) scope.close();
        }
        out.log = log;
        return out;
    }

    private boolean actionExecutorCondition(String cond, long deadline) {
        // 与 ActionExecutor 的 ConditionChecker 同语义：通过一次 assert_state 动作验证
        try {
            JSONObject params = new JSONObject();
            params.put("condition", cond);
            JSONObject a = new JSONObject();
            a.put("action", "assert_state");
            a.put("params", params);
            ExecResult r = actionExecutor.execute("verify", "assert", cond,
                    com.agentruntime.dsl.DslParser.parseAction(a),
                    java.util.Collections.emptyList(), cancelRequested, deadline);
            return r.ok();
        } catch (JSONException e) {
            return false;
        }
    }

    private static String stepSubGoal(TaskSpec.TaskStep step) {
        return step.skillId + " " + step.args.toString();
    }

    private TaskResult finish(TaskSpec task, String status, long start, JSONArray stepLog,
                              int executedActions, int succeededSteps, int failedSteps) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("task_id", task.taskId);
            detail.put("status", status);
            detail.put("duration_ms", System.currentTimeMillis() - start);
            detail.put("executed_actions", executedActions);
            detail.put("succeeded_steps", succeededSteps);
            detail.put("failed_steps", failedSteps);
            detail.put("steps", stepLog);
            detail.put("model_router", modelRouter.stats());
            detail.put("state_epoch", stateEngine.currentEpoch());
        } catch (JSONException ignored) {
        }
        auditLogger.record(task.taskId, "-", "task_finish", "runtime",
                stateEngine.currentEpoch(), status, withoutObservations(detail));
        return new TaskResult(task.taskId, status, detail);
    }

    /**
     * 审计副本：保留状态码与耗时，去掉动作观察内容（屏幕文字、网页正文、文件内容）。
     * 观察内容只在本次会话内存里给模型用，不写进持久化审计。
     */
    private static JSONObject withoutObservations(JSONObject detail) {
        try {
            JSONObject copy = new JSONObject(detail.toString());
            JSONArray steps = copy.optJSONArray("steps");
            if (steps != null) for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                JSONArray actions = step == null ? null : step.optJSONArray("actions");
                if (actions == null) continue;
                for (int j = 0; j < actions.length(); j++) {
                    JSONObject action = actions.optJSONObject(j);
                    JSONObject result = action == null ? null : action.optJSONObject("result");
                    if (result != null) result.remove("detail");
                }
            }
            return copy;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
