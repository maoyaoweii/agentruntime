package com.agentruntime.intent;

import com.agentruntime.dsl.TaskSpec;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class IntentPlannerGoalModeAllowlistTest {

    @Test
    public void openClockGoalProducesOnlyClockAllowlist() throws Exception {
        TaskSpec task = plannerFor("open_clock", "sys.open_clock").plan("打开时钟");

        assertNotNull(task);
        assertEquals("sys.open_clock", task.steps.get(0).skillId);
        assertEquals(Collections.singletonList("com.android.deskclock"), task.allowedApps);
    }

    @Test
    public void toolSmokeGoalAllowsOnlyItsDeclaredFixtureApps() throws Exception {
        TaskSpec task = plannerFor("smoke", "sys.tool_smoke").plan("运行工具冒烟测试");

        assertNotNull(task);
        assertEquals("sys.tool_smoke", task.steps.get(0).skillId);
        assertEquals(Arrays.asList("org.fossify.notes", "com.android.deskclock"), task.allowedApps);
    }

    @Test
    public void lowConfidenceGoalAbstainsInsteadOfCreatingExecutableTask() throws Exception {
        JSONObject ambiguousWeights = new JSONObject()
                .put("classes", new JSONArray().put("open_clock").put("smoke"))
                .put("skills", new JSONArray().put("sys.open_clock").put("sys.tool_smoke"))
                .put("prior", new JSONArray().put(0).put(0))
                .put("default_logp", new JSONArray().put(0).put(0))
                .put("tokens", new JSONArray())
                .put("weights", new JSONObject());
        IntentPlanner planner = new IntentPlanner(IntentModel.fromJson(ambiguousWeights.toString()));
        String goal = "打开应用";

        IntentModel.Prediction prediction = planner.classify(goal);
        assertEquals(0.5f, prediction.confidence, 0.0001f);
        assertFalse(prediction.usable);
        assertNull("low confidence should hand off to a stronger planner", planner.plan(goal));
    }

    private static IntentPlanner plannerFor(String intent, String skill) throws Exception {
        JSONObject weights = new JSONObject()
                .put("classes", new JSONArray().put(intent))
                .put("skills", new JSONArray().put(skill))
                .put("prior", new JSONArray().put(0))
                .put("default_logp", new JSONArray().put(0))
                .put("tokens", new JSONArray())
                .put("weights", new JSONObject());
        return new IntentPlanner(IntentModel.fromJson(weights.toString()));
    }
}
