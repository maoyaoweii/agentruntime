package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimePostconditionCancellationPriorityTest {

    private static final String SKILL_ID = "test.postcondition_cancel_priority";

    @Test
    public void cancellationAtStepEndWinsOverTaskPostconditionFailure() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        SkillManifest manifest = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(new JSONObject().put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 0)))));
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
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("postcondition-cancel-priority", "1.0",
                "cancellation should take priority at task verification",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.singletonList("ui_contains:synthetic-condition-that-is-not-present"),
                new TaskSpec.Limits(5, 0, 10));
        AtomicInteger replans = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) {
                runtime.cancel();
            }
            @Override public void onReplan(String reason) { replans.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("CANCELLED", result.status);
        assertEquals(0, replans.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        JSONArray taskLog = result.detail.getJSONArray("steps");
        assertEquals(1, taskLog.length());
        assertFalse("cancelled postcondition must not be persisted as a failure",
                taskLog.getJSONObject(0).has("postcondition"));
    }
}
