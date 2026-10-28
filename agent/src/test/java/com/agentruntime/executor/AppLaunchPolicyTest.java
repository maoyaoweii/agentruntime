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

    @Test
    public void taskTargetCheckRejectsWrongForegroundApp() {
        // 实测 B03/B04：目标应用是商店，实际停在时钟，任务却报 SUCCESS。
        assertFalse(AppLaunchPolicy.targetReached(
                Arrays.asList("com.aurora.store", "org.fdroid.fdroid"), "com.android.deskclock"));
        assertTrue(AppLaunchPolicy.targetReached(
                Arrays.asList("com.aurora.store", "org.fdroid.fdroid"), "com.aurora.store"));
    }

    @Test
    public void taskTargetCheckIsSkippedForAppFreeTasks() {
        // 纯 Agent 内部任务（截图/观测）不涉及外部应用，不能因此判失败。
        assertTrue(AppLaunchPolicy.targetReached(Collections.<String>emptyList(), "com.android.launcher3"));
        assertTrue(AppLaunchPolicy.targetReached(null, null));
        // 允许集合非空但读不到前台包时，按未达成处理，不允许"读不到就算成功"。
        assertFalse(AppLaunchPolicy.targetReached(Collections.singletonList("com.aurora.store"), null));
    }

    private static ActionSpec launch(String packageName) throws Exception {
        JSONObject params = new JSONObject();
        params.put("package", packageName);
        return new ActionSpec(ActionSpec.ActionType.LAUNCH_APP, params,
                1000, 0, ActionSpec.RiskLevel.LOW, false, null);
    }
}
