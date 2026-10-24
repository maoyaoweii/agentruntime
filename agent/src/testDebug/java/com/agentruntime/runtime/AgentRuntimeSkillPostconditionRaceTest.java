package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.device.SystemBridge;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExtendedCapabilities;
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

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimeSkillPostconditionRaceTest {

    @Test
    public void cancellationDuringSkillPostconditionPollTakesPriority() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String skillId = "test.skill_postcondition_cancel_race";
        String marker = "synthetic-condition-never-present-" + System.nanoTime();
        SkillManifest manifest = new SkillManifestFixture(skillId,
                new JSONArray().put(new JSONObject().put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 0))),
                new JSONArray().put("ui_contains:" + marker)).manifest();
        AgentRuntime runtime = runtime(context, manifest);
        TaskSpec task = task("skill-postcondition-cancel-race", skillId);
        CountDownLatch stepStarted = new CountDownLatch(1);
        AtomicReference<AgentRuntime.TaskResult> resultRef = new AtomicReference<>();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) {
                stepStarted.countDown();
            }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };
        Thread worker = new Thread(() -> resultRef.set(runtime.runTask(task, false, true, listener)),
                "skill-postcondition-cancel-race-test");
        worker.setDaemon(true);
        worker.start();

        boolean pollingCondition = false;
        boolean stoppedPromptly = false;
        try {
            assertTrue(stepStarted.await(2, TimeUnit.SECONDS));
            long pollDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < pollDeadline && worker.isAlive()) {
                if (isInConditionPoll(worker)) {
                    pollingCondition = true;
                    break;
                }
                Thread.sleep(5);
            }
            assertTrue("worker must reach the skill postcondition poll", pollingCondition);
            runtime.cancel();
            worker.join(1500);
            stoppedPromptly = !worker.isAlive();
        } finally {
            runtime.cancel();
            if (worker.isAlive()) worker.join(6000);
        }

        assertTrue("cancelled condition poll should stop promptly", stoppedPromptly);
        AgentRuntime.TaskResult result = resultRef.get();
        assertNotNull(result);
        assertEquals("CANCELLED", result.status);
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
        assertEquals("WAIT_MS_OK", step.getJSONArray("actions").getJSONObject(0)
                .getJSONObject("result").getString("code"));
    }

    @Test
    public void failedSkillActionSkipsPostconditionEvaluation() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String skillId = "test.skill_failed_action_postcondition";
        String marker = "synthetic-blocked-write-" + System.nanoTime();
        File target = new File(context.getCacheDir(), marker + ".txt");
        JSONArray actions = new JSONArray().put(new JSONObject().put("action", "write_file")
                .put("params", new JSONObject().put("path", target.getAbsolutePath())
                        .put("content", "synthetic-content")));
        SkillManifest manifest = new SkillManifestFixture(skillId, actions,
                new JSONArray().put("file_exists:" + target.getAbsolutePath())).manifest();
        AgentRuntime runtime = runtime(context, manifest);

        AgentRuntime.TaskResult result = runtime.runTask(task("skill-action-failure-postcondition", skillId),
                false, false, null);

        assertEquals("FAILED", result.status);
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("PATH_NOT_ALLOWED", step.getString("code"));
        assertEquals("PATH_NOT_ALLOWED", step.getJSONArray("actions").getJSONObject(0)
                .getJSONObject("result").getString("code"));
        assertFalse(target.exists());
    }

    private static boolean isInConditionPoll(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (ActionExecutor.class.getName().equals(frame.getClassName())
                    && "pollCondition".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    private static AgentRuntime runtime(Application context, SkillManifest manifest) throws Exception {
        SkillEngine skills = new SkillEngine(context);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(manifest.skillId, manifest);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        executor.attach(new ExtendedCapabilities(null, null, null, null, null,
                new SystemBridge(context)));
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        return new AgentRuntime(state, skills, executor, router, audit);
    }

    private static TaskSpec task(String taskId, String skillId) throws Exception {
        return new TaskSpec(taskId, "1.0", "verify skill postcondition ordering",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(skillId, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 30));
    }

    private static final class SkillManifestFixture {
        private final String skillId;
        private final JSONArray actions;
        private final JSONArray postconditions;

        SkillManifestFixture(String skillId, JSONArray actions, JSONArray postconditions) {
            this.skillId = skillId;
            this.actions = actions;
            this.postconditions = postconditions;
        }

        SkillManifest manifest() throws Exception {
            return SkillManifest.fromJson(new JSONObject().put("skill_id", skillId)
                    .put("steps", actions).put("postconditions", postconditions));
        }
    }
}
