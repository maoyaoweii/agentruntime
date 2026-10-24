package com.agentruntime.executor;

import com.agentruntime.dsl.ActionSpec;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppLaunchPolicyTest {

    @Test
    public void onlyExactTaskAllowlistMemberIsAllowed() {
        assertTrue(AppLaunchPolicy.allows(
                Collections.singletonList("com.example.clock"), "com.example.clock"));
        assertFalse(AppLaunchPolicy.allows(
                Collections.singletonList("com.example.clock"), "com.example.notes"));
        assertFalse(AppLaunchPolicy.allows(Collections.<String>emptyList(), "com.example.clock"));
        assertFalse(AppLaunchPolicy.allows(
                Collections.singletonList("com.example.clock"), " "));
    }

    @Test
    public void collectsUniqueLaunchTargetsInActionOrder() throws Exception {
        ActionSpec first = launch("org.example.notes");
        ActionSpec wait = new ActionSpec(ActionSpec.ActionType.WAIT_MS,
                new JSONObject(), 1000, 0, ActionSpec.RiskLevel.LOW, false, null);
        ActionSpec second = launch("com.android.deskclock");

        assertEquals(Arrays.asList("org.example.notes", "com.android.deskclock"),
                AppLaunchPolicy.declaredLaunchTargets(Arrays.asList(first, wait, second, first)));
        assertEquals(Collections.emptyList(), AppLaunchPolicy.declaredLaunchTargets(null));
    }

    private static ActionSpec launch(String packageName) throws Exception {
        JSONObject params = new JSONObject();
        params.put("package", packageName);
        return new ActionSpec(ActionSpec.ActionType.LAUNCH_APP, params,
                1000, 0, ActionSpec.RiskLevel.LOW, false, null);
    }
}
