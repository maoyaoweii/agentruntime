package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimeLaunchPolicyTest {

    @Test
    public void missingTaskAllowlistFailsOnceWithoutUsingReplanBudget() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_allowlist_test\","
                + "\"goal\":\"open the clock\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"sys.open_clock\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("FAILED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject actionResult = result.detail.getJSONArray("steps")
                .getJSONObject(0).getJSONArray("actions").getJSONObject(0)
                .getJSONObject("result");
        assertEquals("APP_NOT_ALLOWED", actionResult.getString("code"));
    }

    @Test
    public void waitLongerThanRemainingTaskDeadlineIsNotDispatched() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.long_wait");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 3000)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        // Test-only skill uses a harmless wait action; it never targets a real app or device setting.
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_timeout_test\","
                + "\"goal\":\"respect the task deadline\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.long_wait\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":2}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("TIMEOUT", result.status);
        assertEquals(0, replans.get());
        assertEquals(0, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("TASK_TIMEOUT", step.getString("code"));
        assertEquals(0, step.getJSONArray("actions").length());
    }

    @Test
    public void cancellationAfterStepPreventsLaterStepDispatch() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.cancel_wait");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        // Test-only WAIT_MS(0) avoids real app, account, or system interactions.
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_cancel_test\","
                + "\"goal\":\"stop after the first step\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":["
                + "{\"skill\":\"test.cancel_wait\",\"args\":{}},"
                + "{\"skill\":\"test.cancel_wait\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":10}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AtomicInteger stepEnds = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) {
                stepEnds.incrementAndGet();
                if (index == 1) runtime.cancel();
            }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, stepEnds.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        assertEquals(1, result.detail.getJSONArray("steps").length());
    }

    @Test
    public void cancellationInterruptsActiveWaitPromptly() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.cancel_active_wait");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 5000)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        // The synthetic wait keeps this race test away from real apps and system state.
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_active_cancel_test\","
                + "\"goal\":\"cancel during a wait\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.cancel_active_wait\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        java.util.concurrent.CountDownLatch stepStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) {
                stepStarted.countDown();
            }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };
        java.util.concurrent.atomic.AtomicReference<AgentRuntime.TaskResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "agent-runtime-active-cancel-test");
        worker.setDaemon(true);
        worker.start();

        boolean stoppedPromptly = false;
        long cancelLatencyMs = Long.MAX_VALUE;
        try {
            org.junit.Assert.assertTrue(stepStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long waitForSleepDeadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < waitForSleepDeadline) {
                Thread.sleep(5);
            }
            org.junit.Assert.assertEquals(Thread.State.TIMED_WAITING, worker.getState());

            long cancelAt = System.nanoTime();
            runtime.cancel();
            worker.join(1500);
            stoppedPromptly = !worker.isAlive();
            cancelLatencyMs = (System.nanoTime() - cancelAt) / 1_000_000L;
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }

        org.junit.Assert.assertTrue("active wait should stop promptly", stoppedPromptly);
        org.junit.Assert.assertTrue("cancel latency was " + cancelLatencyMs + "ms", cancelLatencyMs < 1500);
        org.junit.Assert.assertNotNull(resultRef.get());
        AgentRuntime.TaskResult result = resultRef.get();
        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
        assertEquals("USER_CANCEL", step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result").getString("code"));
    }

    @Test
    public void cancellationInterruptsActiveWaitForPromptly() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.cancel_active_wait_for");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_for")
                .put("params", new JSONObject().put("package", "com.example.never_installed"))
                .put("timeout_ms", 20000));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        // This impossible foreground-package condition remains side-effect free.
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_wait_for_cancel_test\","
                + "\"goal\":\"cancel while waiting for an impossible package\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.cancel_active_wait_for\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        java.util.concurrent.CountDownLatch stepStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) {
                stepStarted.countDown();
            }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };
        java.util.concurrent.atomic.AtomicReference<AgentRuntime.TaskResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "agent-runtime-active-wait-for-cancel-test");
        worker.setDaemon(true);
        worker.start();

        boolean stoppedPromptly = false;
        long cancelLatencyMs = Long.MAX_VALUE;
        try {
            org.junit.Assert.assertTrue(stepStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long waitForSleepDeadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < waitForSleepDeadline) {
                Thread.sleep(5);
            }
            org.junit.Assert.assertEquals(Thread.State.TIMED_WAITING, worker.getState());

            long cancelAt = System.nanoTime();
            runtime.cancel();
            worker.join(1500);
            stoppedPromptly = !worker.isAlive();
            cancelLatencyMs = (System.nanoTime() - cancelAt) / 1_000_000L;
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }

        org.junit.Assert.assertTrue("active WAIT_FOR should stop promptly", stoppedPromptly);
        org.junit.Assert.assertTrue("cancel latency was " + cancelLatencyMs + "ms", cancelLatencyMs < 1500);
        org.junit.Assert.assertNotNull(resultRef.get());
        AgentRuntime.TaskResult result = resultRef.get();
        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
        assertEquals("USER_CANCEL", step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result").getString("code"));
    }

    @Test
    public void waitForStopsAtTaskDeadlineBeforeActionTimeout() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.wait_for_task_deadline");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_for")
                .put("params", new JSONObject().put("package", "com.example.never_installed"))
                .put("timeout_ms", 10000));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_wait_for_deadline_test\","
                + "\"goal\":\"respect global task deadline while waiting\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.wait_for_task_deadline\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":1}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("TIMEOUT", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("TASK_TIMEOUT", step.getString("code"));
        JSONObject actionResult = step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result");
        assertEquals("TIMEOUT", actionResult.getString("status"));
        assertEquals("TASK_TIMEOUT", actionResult.getString("code"));
    }

    @Test
    public void cancellationInterruptsTaskPostconditionPollingPromptly() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.postcondition_seed");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_postcondition_cancel_test\","
                + "\"goal\":\"cancel while checking final condition\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.postcondition_seed\",\"args\":{}}],"
                + "\"postconditions\":[\"ui_contains:__cue_never_present__\"],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        java.util.concurrent.CountDownLatch stepEnded = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { stepEnded.countDown(); }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };
        java.util.concurrent.atomic.AtomicReference<AgentRuntime.TaskResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "agent-runtime-postcondition-cancel-test");
        worker.setDaemon(true);
        worker.start();

        boolean stoppedPromptly = false;
        long cancelLatencyMs = Long.MAX_VALUE;
        try {
            org.junit.Assert.assertTrue(stepEnded.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long waitForPollDeadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < waitForPollDeadline) {
                Thread.sleep(5);
            }
            org.junit.Assert.assertEquals(Thread.State.TIMED_WAITING, worker.getState());

            long cancelAt = System.nanoTime();
            runtime.cancel();
            worker.join(1500);
            stoppedPromptly = !worker.isAlive();
            cancelLatencyMs = (System.nanoTime() - cancelAt) / 1_000_000L;
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }

        org.junit.Assert.assertTrue("task condition poll should stop promptly", stoppedPromptly);
        org.junit.Assert.assertTrue("cancel latency was " + cancelLatencyMs + "ms", cancelLatencyMs < 1500);
        org.junit.Assert.assertNotNull(resultRef.get());
        AgentRuntime.TaskResult result = resultRef.get();
        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        assertEquals(1, result.detail.getJSONArray("steps").length());
    }

    @Test(timeout = 5000)
    public void taskPostconditionStopsAtGlobalDeadlineBeforeActionTimeout() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.deadline_postcondition_seed");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_postcondition_deadline_test\","
                + "\"goal\":\"enforce the global deadline during final condition polling\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.deadline_postcondition_seed\",\"args\":{}}],"
                + "\"postconditions\":[\"ui_contains:__cue_deadline_marker_missing__\"],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":1}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        long startedAt = System.nanoTime();
        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("TIMEOUT", result.status);
        org.junit.Assert.assertTrue("global deadline was exceeded by " + elapsedMs + "ms", elapsedMs < 3000);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        assertEquals(1, result.detail.getJSONArray("steps").length());
    }

    @Test(timeout = 5000)
    public void perActionPostconditionStopsAtGlobalDeadlineBeforeActionTimeout() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.deadline_action_postcondition");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0))
                .put("timeout_ms", 10000)
                .put("postcondition", "ui_contains:__cue_action_deadline_missing__"));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_action_postcondition_deadline_test\","
                + "\"goal\":\"enforce the global deadline during action verification\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.deadline_action_postcondition\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":1}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        long startedAt = System.nanoTime();
        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("TIMEOUT", result.status);
        org.junit.Assert.assertTrue("global deadline was exceeded by " + elapsedMs + "ms", elapsedMs < 3000);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("TASK_TIMEOUT", step.getString("code"));
        JSONObject actionResult = step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result");
        assertEquals("TIMEOUT", actionResult.getString("status"));
        assertEquals("TASK_TIMEOUT", actionResult.getString("code"));
    }

    @Test
    public void cancellationInterruptsPerActionPostconditionPollingPromptly() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.action_postcondition_cancel");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0))
                .put("timeout_ms", 20000)
                .put("postcondition", "ui_contains:__cue_action_postcondition_missing__"));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_action_postcondition_cancel_test\","
                + "\"goal\":\"cancel while verifying an action postcondition\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.action_postcondition_cancel\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        java.util.concurrent.CountDownLatch stepStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) {
                stepStarted.countDown();
            }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };
        java.util.concurrent.atomic.AtomicReference<AgentRuntime.TaskResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "agent-runtime-action-postcondition-cancel-test");
        worker.setDaemon(true);
        worker.start();

        boolean stoppedPromptly = false;
        long cancelLatencyMs = Long.MAX_VALUE;
        try {
            org.junit.Assert.assertTrue(stepStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long waitForPollDeadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < waitForPollDeadline) {
                Thread.sleep(5);
            }
            org.junit.Assert.assertEquals(Thread.State.TIMED_WAITING, worker.getState());

            long cancelAt = System.nanoTime();
            runtime.cancel();
            worker.join(1500);
            stoppedPromptly = !worker.isAlive();
            cancelLatencyMs = (System.nanoTime() - cancelAt) / 1_000_000L;
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }

        org.junit.Assert.assertTrue("action postcondition poll should stop promptly", stoppedPromptly);
        org.junit.Assert.assertTrue("cancel latency was " + cancelLatencyMs + "ms", cancelLatencyMs < 1500);
        org.junit.Assert.assertNotNull(resultRef.get());
        AgentRuntime.TaskResult result = resultRef.get();
        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
        JSONObject actionResult = step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result");
        assertEquals("CANCELLED", actionResult.getString("status"));
        assertEquals("USER_CANCEL", actionResult.getString("code"));
    }

    @Test
    public void maxStepsStopsBeforeDispatchingNextExpandedAction() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject();
        fixtureJson.put("skill_id", "test.wait_pair");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0)));
        actions.put(new JSONObject().put("action", "wait_ms")
                .put("params", new JSONObject().put("ms", 0)));
        fixtureJson.put("steps", actions);
        SkillManifest fixture = SkillManifest.fromJson(fixtureJson);

        // Inject a test-only, side-effect-free skill without adding a production asset or runtime hook.
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(fixture.skillId, fixture);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"runtime_action_budget_test\","
                + "\"goal\":\"run only the allowed action\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.wait_pair\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":1,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("STEP_BUDGET_EXCEEDED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("STEP_BUDGET_EXCEEDED", step.getString("code"));
        JSONArray actionLogs = step.getJSONArray("actions");
        assertEquals(1, actionLogs.length());
        JSONObject actionResult = actionLogs.getJSONObject(0).getJSONObject("result");
        assertEquals("WAIT_MS_OK", actionResult.getString("code"));
    }
}
