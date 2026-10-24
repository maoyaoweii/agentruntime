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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimeWaitForCancellationTest {

    @Test(timeout = 12000)
    public void cancelStopsActiveWaitForWithoutReplanning() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        JSONObject fixtureJson = new JSONObject()
                .put("skill_id", "test.wait_for_cancel")
                .put("steps", new JSONArray().put(new JSONObject()
                        .put("action", "wait_for")
                        .put("timeout_ms", 60000)
                        .put("params", new JSONObject().put("ui_contains", "never-visible-node"))));
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
                + "\"task_id\":\"wait_for_cancel_test\","
                + "\"goal\":\"cancel while waiting for a UI condition\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"test.wait_for_cancel\",\"args\":{}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":5,\"max_replans\":1,\"timeout_seconds\":30}"
                + "}");
        CountDownLatch stepStarted = new CountDownLatch(1);
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
        AtomicReference<AgentRuntime.TaskResult> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "agent-runtime-wait-for-cancel-test");
        worker.setDaemon(true);
        worker.start();

        try {
            assertTrue("task should reach WAIT_FOR", stepStarted.await(2, TimeUnit.SECONDS));
            long waitDeadline = System.currentTimeMillis() + 2000;
            while (worker.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5);
            }
            assertEquals("worker should be in the cancellable poll sleep",
                    Thread.State.TIMED_WAITING, worker.getState());

            runtime.cancel();
            worker.join(1500);
            assertFalse("WAIT_FOR should stop promptly after cancel", worker.isAlive());
            AgentRuntime.TaskResult result = resultRef.get();
            assertNotNull(result);
            assertEquals("CANCELLED", result.status);
            assertEquals(0, replans.get());
            assertEquals(1, result.detail.getInt("executed_actions"));
            JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
            assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
            JSONObject actionResult = step.getJSONArray("actions").getJSONObject(0).getJSONObject("result");
            assertEquals("USER_CANCEL", actionResult.getString("code"));
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }
    }
}
