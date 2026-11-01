package com.agentruntime.ui;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.BuildConfig;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.goal.DeviceGoalScreen;
import com.agentruntime.goal.GoalCard;
import com.agentruntime.goal.GoalMainLoop;
import com.agentruntime.goal.GoalPathStore;
import com.agentruntime.executor.AppLaunchPolicy;
import com.agentruntime.runtime.AgentRuntime;
import com.agentruntime.runtime.TaskBus;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务执行入口：UI、悬浮窗、QS 磁贴、adb 全部走这里。
 *
 * <p>进度统一挂在 {@link TaskBus} 上，而不是让每个调用方各自传监听器——
 * 入口只有一个，那么"任何一次执行都能被界面看到"这件事就只需要在一处保证。
 */
public final class TaskRunner {

    private static final AtomicReference<Execution> ACTIVE = new AtomicReference<>();
    private static final Object ADMISSION = new Object();

    private static final class Execution {
        final AtomicBoolean cancellation;
        final Thread thread = Thread.currentThread();
        Execution(AtomicBoolean cancellation) { this.cancellation=cancellation==null?new AtomicBoolean(false):cancellation; }
    }

    private interface TaskOperation {
        AgentRuntime.TaskResult run(AtomicBoolean cancellation) throws Exception;
    }

    private TaskRunner() {
    }

    public static AgentRuntime.TaskResult runGoal(String goal, boolean autoConfirm, boolean allowReplan) throws Exception {
        return runExclusive(autoConfirm, cancellation -> runGoalCard(goal, autoConfirm, allowReplan, cancellation));
    }

    /**
     * 目标模式先走主循环：它负责把一件事做完（读屏、换招、核对、存路径）。
     *
     * <p>只有"这不是一个可执行的请求"（提问、解释、没有可观察标准）才回落到模型规划，
     * 其余情况不允许主循环失败后偷偷换成另一套流程再报成功。
     */
    private static AgentRuntime.TaskResult runGoalCard(String goal, boolean autoConfirm,
                                                       boolean allowReplan, AtomicBoolean cancellation) throws Exception {
        AgentApp app = AgentApp.get();
        GoalMainLoop loop = new GoalMainLoop(
                new DeviceGoalScreen(app, app.stateEngine()),
                GoalPathStore.preferences(app),
                new GoalMainLoop.Listener() {
                    @Override public void onRound(int round, GoalMainLoop.Tactic tactic, String code) {
                        TaskBus.get().info("goal", "第 " + round + " 轮 · " + tactic + " · " + code);
                    }
                    @Override public void onState(GoalCard card) { }
                });
        GoalCard card = loop.run(goal);
        if (cancellation.get()) return cancelled();
        String code = card.failureCode();
        if (card.state() == GoalCard.State.FAILED
                && ("NOT_AN_ACTION".equals(code) || "NO_CRITERIA".equals(code))) {
            return planAndRun(goal, autoConfirm, allowReplan, cancellation);
        }
        String status = card.state() == GoalCard.State.SUCCEEDED ? "SUCCESS"
                : card.state() == GoalCard.State.HELD ? "HELD" : "FAILED";
        TaskBus.get().info("goal", "主循环结束：" + status + "（" + card.outcome().name() + "）");
        return new AgentRuntime.TaskResult(card.id(), status, card.toJson());
    }

    private static AgentRuntime.TaskResult planAndRun(String goal, boolean autoConfirm,
                                                      boolean allowReplan, AtomicBoolean cancellation) throws Exception {
        if (cancellation.get()) return cancelled();
        AgentApp app = AgentApp.get();
        TaskBus bus = TaskBus.get();

        bus.info("plan", "规划中…");
        TaskSpec task;
        try {
            task = app.modelRouter().plan(goal, Collections.singletonList("accessibility"));
        } catch (Exception e) {
            bus.error("plan", "规划失败：" + e.getMessage());
            throw e;
        }
        bus.info("plan", "规划完成 → " + task.taskId + " · " + task.steps.size() + " 步");
        // 把「想出来的计划」摊给用户看：只报 taskId 的话，浮窗上只有一串编号，
        // 看不出 Agent 到底打算调哪些技能。
        StringBuilder skills = new StringBuilder();
        for (TaskSpec.TaskStep step : task.steps) {
            if (skills.length() > 0) skills.append(" → ");
            skills.append(step.skillId);
        }
        if (skills.length() > 0) bus.info("plan", "计划 " + skills);
        return execute(task, autoConfirm, allowReplan, cancellation);
    }

    public static AgentRuntime.TaskResult runTaskJson(String taskJson, boolean autoConfirm, boolean allowReplan) throws Exception {
        return runExclusive(autoConfirm, cancellation -> {
            TaskSpec task = DslParser.parseTask(taskJson);
            return execute(task, autoConfirm, allowReplan, cancellation);
        });
    }

    public static boolean isBusy() {
        return ACTIVE.get() != null;
    }

    public static void cancel() {
        cancel(null);
    }

    /** A mobile assistant may only cancel the nested skill that owns its token. */
    public static void cancel(AtomicBoolean expectedOwner) {
        synchronized(ADMISSION) {
        Execution execution = ACTIVE.get();
        if(expectedOwner!=null&&(execution==null||execution.cancellation!=expectedOwner)) return;
        if (execution != null) {
            execution.cancellation.set(true);
            execution.thread.interrupt();
        }
        AgentApp app = AgentApp.get();
        if (app != null) {
            app.agentRuntime().cancel();
            app.modelRouter().cloud().cancel();
            app.confirmations().cancelPending();
        }
        }
    }

    private static AgentRuntime.TaskResult runExclusive(boolean autoConfirm, TaskOperation operation) throws Exception {
        return runExclusive(autoConfirm,operation,null);
    }

    private static AgentRuntime.TaskResult runExclusive(boolean autoConfirm, TaskOperation operation,AtomicBoolean owner) throws Exception {
        Execution execution = new Execution(owner);
        AtomicBoolean cancellation = execution.cancellation;
        synchronized(ADMISSION) { if (!ACTIVE.compareAndSet(null, execution)) {
            throw new IllegalStateException("已有任务正在规划或执行，请先停止或等待完成");
        } }
        AgentApp app = AgentApp.get();
        try {
            app.riskGate().setTestModeAutoConfirm(BuildConfig.DEBUG && autoConfirm);
            TaskBus.get().planningStarted();
            return operation.run(cancellation);
        } catch (Exception e) {
            if (cancellation.get()) {
                return cancelled();
            }
            TaskBus.get().planningFailed();
            throw e;
        } finally {
            if (app != null) app.riskGate().setTestModeAutoConfirm(false);
            synchronized(ADMISSION) { ACTIVE.compareAndSet(execution, null); }
        }
    }

    private static AgentRuntime.TaskResult cancelled() {
        AgentRuntime.TaskResult result = new AgentRuntime.TaskResult("planning", "CANCELLED", new JSONObject());
        TaskBus.get().runtimeListener().onFinish(result);
        return result;
    }

    private static AgentRuntime.TaskResult execute(TaskSpec task, boolean autoConfirm,
                                                    boolean allowReplan, AtomicBoolean cancellation) {
        return execute(task, autoConfirm, allowReplan, cancellation, false);
    }

    private static AgentRuntime.TaskResult execute(TaskSpec task, boolean autoConfirm,
                                                    boolean allowReplan, AtomicBoolean cancellation,
                                                    boolean includeObservations) {
        return AgentApp.get().agentRuntime().runTask(task, BuildConfig.DEBUG && autoConfirm,
                allowReplan, TaskBus.get().runtimeListener(), cancellation, includeObservations);
    }

    /** 直接按技能 ID 执行内置技能（快捷面板用，跳过自然语言规划）。 */
    public static AgentRuntime.TaskResult runSkill(String skillId, JSONObject args,
                                                   boolean autoConfirm) throws Exception {
        return runSkill(skillId,args,autoConfirm,null);
    }

    public static AgentRuntime.TaskResult runSkill(String skillId,JSONObject args,
                                                   boolean autoConfirm,AtomicBoolean owner) throws Exception {
        AgentApp app = AgentApp.get();
        JSONObject skillArgs = args == null ? new JSONObject() : args;
        List<ActionSpec> declaredActions = app.skillEngine().resolve(skillId, skillArgs);
        org.json.JSONArray allowedApps = new org.json.JSONArray();
        for (String packageName : AppLaunchPolicy.declaredLaunchTargets(declaredActions)) {
            allowedApps.put(packageName);
        }

        JSONObject taskJson = new JSONObject();
        taskJson.put("task_version", "1.0");
        taskJson.put("task_id", "quick_" + skillId.replace('.', '_'));
        taskJson.put("goal", "快捷执行 " + skillId);
        taskJson.put("allowed_apps", allowedApps);
        taskJson.put("preconditions", new org.json.JSONArray());
        org.json.JSONArray steps = new org.json.JSONArray();
        JSONObject step = new JSONObject();
        step.put("skill", skillId);
        step.put("args", skillArgs);
        steps.put(step);
        taskJson.put("steps", steps);
        taskJson.put("postconditions", new org.json.JSONArray());
        JSONObject limits = new JSONObject();
        limits.put("max_steps", 24);
        limits.put("max_replans", 1);
        limits.put("timeout_seconds", 300);
        taskJson.put("limits", limits);

        // 曾经写成 owner==null：DSH 传了 cancellation，于是重规划永远是关的。
        // 真正的闸门在 AgentRuntime：RISK_REJECTED / APP_NOT_ALLOWED / 确认失败仍然不重规划。
        return runExclusive(autoConfirm,cancellation -> execute(DslParser.parseTask(taskJson.toString()),
                autoConfirm,true,cancellation,true),owner);
    }

    public static File writeResult(com.agentruntime.AgentApp app, JSONObject result) throws IOException {
        File out = new File(app.getFilesDir(), "last_result.json");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out, false), StandardCharsets.UTF_8)) {
            w.write(result.toString());
        }
        return out;
    }
}
