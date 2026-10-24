package com.agentruntime.skill;

import android.app.Application;
import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SkillRiskGateIntegrationTest {

    @Test(timeout = 10000)
    public void manifestHighRiskIsEnforcedByExecutorUnlessChannelApproves() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        SkillManifest manifest = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.high_risk_wait")
                .put("risk", "HIGH")
                .put("steps", new JSONArray().put(new JSONObject()
                        .put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 25)))));
        List<ActionSpec> actions = skills.resolveManifest(manifest, new JSONObject());
        assertEquals(1, actions.size());
        ActionSpec action = actions.get(0);
        assertEquals(ActionSpec.RiskLevel.HIGH, action.risk);
        assertFalse(action.requiresConfirmation);

        AtomicInteger rejectedPromptCount = new AtomicInteger();
        RiskGate rejectingGate = new RiskGate();
        rejectingGate.setConfirmationChannel((actionId, summary) -> {
            rejectedPromptCount.incrementAndGet();
            return false;
        });
        ExecResult rejected = executor(context, rejectingGate)
                .execute("risk-test", "high-risk-step", "synthetic high risk", action);
        assertEquals(ExecResult.Status.REJECTED, rejected.status);
        assertEquals("RISK_REJECTED", rejected.code);
        assertEquals(1, rejectedPromptCount.get());
        assertEquals("user_rejected", rejected.detail);
        assertEquals("rejected action is never dispatched", 0L, rejected.durationMs);

        AtomicInteger approvedPromptCount = new AtomicInteger();
        RiskGate approvingGate = new RiskGate();
        approvingGate.setConfirmationChannel((actionId, summary) -> {
            approvedPromptCount.incrementAndGet();
            return true;
        });
        ExecResult approved = executor(context, approvingGate)
                .execute("risk-test", "high-risk-step-approved", "synthetic high risk", action);
        assertEquals(ExecResult.Status.OK, approved.status);
        assertEquals("WAIT_MS_OK", approved.code);
        assertEquals(1, approvedPromptCount.get());
        assertTrue("approved no-op wait should actually run", approved.durationMs > 0);
    }

    private static ActionExecutor executor(Context context, RiskGate riskGate) {
        StateEngine state = new StateEngine();
        return new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), riskGate, new AuditLogger(context));
    }
}
