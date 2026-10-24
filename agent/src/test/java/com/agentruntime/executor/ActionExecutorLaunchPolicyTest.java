package com.agentruntime.executor;

import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ActionExecutorLaunchPolicyTest {

    @Test
    public void launchWithoutTaskAllowlistIsRejectedBeforeIntentDispatch() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), new AuditLogger(context));
        JSONObject params = new JSONObject();
        params.put("package", "com.android.deskclock");
        ActionSpec launch = new ActionSpec(ActionSpec.ActionType.LAUNCH_APP, params,
                1000, 0, ActionSpec.RiskLevel.LOW, false, null);

        ExecResult result = executor.execute("policy-test", "sys.open_clock", "open clock", launch);

        assertEquals("APP_NOT_ALLOWED", result.code);
    }
}
