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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimeMultiActionSkillCancellationTest {

    private static final String SKILL_ID = "test.multi_action_cancel";
    private static final String WRITE_MARKER = "synthetic-second-action-must-not-run";

    @Test
    public void cancellationDuringFirstActionPreventsSecondActionInSameSkill() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File output = new File(context.getFilesDir(), "cancel-skill-" + UUID.randomUUID() + ".txt");
        SkillEngine skills = new SkillEngine(context);
        JSONArray actions = new JSONArray()
                .put(new JSONObject().put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 5000)))
                .put(new JSONObject().put("action", "write_file")
                        .put("params", new JSONObject().put("path", output.getAbsolutePath())
                                .put("content", WRITE_MARKER)));
        SkillManifest manifest = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", SKILL_ID).put("steps", actions));
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        executor.attach(new ExtendedCapabilities(null, null, null, null, null, new SystemBridge(context)));
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("cancel-multi-action", "1.0", "cancel between actions in one skill",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 10));

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
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<AgentRuntime.TaskResult> future = null;
        try {
            future = worker.submit(() -> runtime.runTask(task, false, true, listener));
            assertTrue("task step should start", stepStarted.await(3, TimeUnit.SECONDS));
            Thread.sleep(150);
            runtime.cancel();
            AgentRuntime.TaskResult result = future.get(2, TimeUnit.SECONDS);

            assertEquals("CANCELLED", result.status);
            assertEquals(0, replans.get());
            assertEquals(1, result.detail.getInt("executed_actions"));
            JSONArray stepLogs = result.detail.getJSONArray("steps");
            assertEquals(1, stepLogs.length());
            JSONObject step = stepLogs.getJSONObject(0);
            assertEquals("DEADLINE_OR_CANCEL", step.getString("code"));
            assertEquals("only the first skill action should be logged", 1,
                    step.getJSONArray("actions").length());
            assertFalse("second WRITE_FILE action must not be dispatched", output.exists());
        } finally {
            runtime.cancel();
            if (future != null && !future.isDone()) future.cancel(true);
            worker.shutdownNow();
            worker.awaitTermination(3, TimeUnit.SECONDS);
            if (output.exists()) assertTrue("remove unexpected second-action fixture", output.delete());
        }
    }
}
