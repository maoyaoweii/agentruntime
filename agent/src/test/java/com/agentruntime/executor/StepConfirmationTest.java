package com.agentruntime.executor;

import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.DslParser;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StepConfirmationTest {
    @Test public void confirmsOncePerStepAndExpiresBeforeNextStep() throws Exception {
        RiskGate gate = new RiskGate();
        AtomicInteger prompts = new AtomicInteger();
        gate.setConfirmationChannel((id, summary) -> {
            assertTrue(summary.contains("fixture payload"));
            prompts.incrementAndGet();
            return true;
        });
        ActionSpec risky = risky();
        try (RiskGate.StepScope ignored = gate.openStep("fixture payload A")) {
            assertTrue(gate.check("input", risky, "input").allow);
            assertTrue(gate.check("send", risky, "send").allow);
            assertTrue(gate.check("verify", risky, "verify").allow);
        }
        assertEquals(1, prompts.get());
        try (RiskGate.StepScope ignored = gate.openStep("fixture payload B")) {
            assertTrue(gate.check("send", risky, "send").allow);
        }
        assertEquals(2, prompts.get());
        gate.setConfirmationChannel(null);
        assertFalse(gate.check("outside-scope", risky, "send").allow);
    }

    @Test public void rejectionNeverGrantsStepApproval() throws Exception {
        RiskGate gate = new RiskGate();
        gate.setConfirmationChannel((id, summary) -> false);
        try (RiskGate.StepScope ignored = gate.openStep("fixture payload")) {
            assertFalse(gate.check("send", risky(), "send").allow);
        }
    }

    @Test public void theResolvedSensitiveTargetIsConfirmedEvenAfterStepApproval() throws Exception {
        RiskGate gate=new RiskGate(); AtomicInteger prompts=new AtomicInteger();
        gate.setConfirmationChannel((id,summary)->{prompts.incrementAndGet(); return true;});
        try(RiskGate.StepScope ignored=gate.openStep("generic action")) {
            assertTrue(gate.check("step",risky(),"step").allow);
            assertTrue(gate.confirmSensitiveTap("tap","resolved target: 发送").allow);
        }
        assertEquals(2,prompts.get());
    }

    @Test public void displaysCompleteLongPayloadAndResolvedActionsBeforeApproval() throws Exception {
        RiskGate gate = new RiskGate();
        String payload = "a".repeat(513) + "tail-that-must-be-reviewed";
        gate.setConfirmationChannel((id, summary) -> {
            assertTrue(summary.contains(payload));
            assertTrue(summary.contains("delete_file"));
            assertTrue(summary.contains("fixture.txt"));
            return false;
        });
        ActionExecutor executor = new ActionExecutor(null, null, null, null, null, gate, null);
        try (RiskGate.StepScope ignored = executor.confirmationScope("fixture",
                new JSONObject().put("text", payload), java.util.Collections.singletonList(risky()))) {
            assertFalse(gate.check("send", risky(), "send").allow);
        }
    }

    private ActionSpec risky() throws Exception {
        return DslParser.parseAction(new JSONObject().put("action", "delete_file")
                .put("params", new JSONObject().put("path", "fixture.txt")).put("risk", "HIGH"));
    }
}
