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
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentRuntimeRiskRejectionTest {

    @Test
    public void explicitConfirmationRejectionDoesNotReplanOrPromptAgain() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        AtomicInteger promptCount = new AtomicInteger();
        RiskGate riskGate = new RiskGate();
        riskGate.setConfirmationChannel((actionId, summary) -> {
            promptCount.incrementAndGet();
            return false;
        });

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), riskGate, audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = DslParser.parseTask("{"
                + "\"task_version\":\"1.0\","
                + "\"task_id\":\"confirmation_reject_test\","
                + "\"goal\":\"send synthetic test text\","
                + "\"allowed_apps\":[],"
                + "\"preconditions\":[],"
                + "\"steps\":[{\"skill\":\"agent_test.send_text\","
                + "\"args\":{\"text\":\"synthetic approval fixture\"}}],"
                + "\"postconditions\":[],"
                + "\"limits\":{\"max_steps\":10,\"max_replans\":2,\"timeout_seconds\":30}"
                + "}");
        AtomicInteger replanCount = new AtomicInteger();
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override public void onTaskStart(TaskSpec ignored) { }
            @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) { }
            @Override public void onStepEnd(int index, TaskSpec.TaskStep step, boolean ok,
                                            String code, long durationMs) { }
            @Override public void onReplan(String reason) { replanCount.incrementAndGet(); }
            @Override public void onFinish(AgentRuntime.TaskResult result) { }
        };

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, listener);

        assertEquals("FAILED", result.status);
        assertEquals("explicit rejection must not spend the replan budget", 0, replanCount.get());
        assertEquals("explicit rejection must not prompt repeatedly", 1, promptCount.get());
        assertEquals(1, result.detail.getInt("executed_actions"));
        assertEquals(1, result.detail.getJSONArray("steps").length());
        JSONObject step = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals("RISK_REJECTED", step.getString("code"));
        assertEquals(1, step.getJSONArray("actions").length());
        assertEquals("RISK_REJECTED", step.getJSONArray("actions")
                .getJSONObject(0).getJSONObject("result").getString("code"));
    }
}
