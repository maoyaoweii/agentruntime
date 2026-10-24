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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class TaskPreconditionPolicyIntegrationTest {

    private static final String PROBE_SKILL = "test.task_precondition_probe";

    @Test
    public void taskPreconditionsGateStepsAndDoNotLogConditionPaths() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String nonce = UUID.randomUUID().toString();
        File privateFile = new File(context.getFilesDir(), "task-precondition-private-" + nonce + ".txt");
        File outsideFile = new File(context.getCacheDir(), "task-precondition-outside-" + nonce + ".txt");
        try {
            Files.write(privateFile.toPath(), "private fixture".getBytes(StandardCharsets.UTF_8));
            Files.write(outsideFile.toPath(), "outside fixture".getBytes(StandardCharsets.UTF_8));

            SkillEngine skills = new SkillEngine(context);
            registerProbeSkill(skills);
            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
            AtomicInteger stepStarts = new AtomicInteger();
            AtomicInteger replans = new AtomicInteger();
            AgentRuntime.Listener listener = new AgentRuntime.Listener() {
                @Override public void onTaskStart(TaskSpec task) { }
                @Override public void onStepStart(int index, int total, TaskSpec.TaskStep step) {
                    stepStarts.incrementAndGet();
                }
                @Override public void onStepEnd(int index, TaskSpec.TaskStep step,
                                                boolean ok, String code, long durationMs) { }
                @Override public void onReplan(String reason) { replans.incrementAndGet(); }
                @Override public void onFinish(AgentRuntime.TaskResult result) { }
            };

            AgentRuntime.TaskResult allowed = runtime.runTask(
                    task("task-precondition-private", privateFile), false, true, listener);
            assertEquals("SUCCESS", allowed.status);
            assertEquals(1, allowed.detail.getInt("executed_actions"));
            assertEquals(1, stepStarts.get());

            AgentRuntime.TaskResult blocked = runtime.runTask(
                    task("task-precondition-outside", outsideFile), false, true, listener);
            assertEquals("PRECONDITION_FAILED", blocked.status);
            assertEquals("a failed precondition must not execute any step", 0,
                    blocked.detail.getInt("executed_actions"));
            assertEquals("a failed precondition must not start the step", 1, stepStarts.get());
            assertEquals("a failed precondition is not a replan-eligible step failure", 0, replans.get());
            JSONObject loggedCondition = blocked.detail.getJSONArray("steps").getJSONObject(0);
            assertEquals(0, loggedCondition.getInt("precondition_index"));
            assertFalse(loggedCondition.getBoolean("ok"));
            assertFalse("runtime details must not echo a condition path",
                    blocked.detail.toString().contains(outsideFile.getAbsolutePath()));

            File auditDir = new File(context.getFilesDir(), "audit");
            File[] auditFiles = auditDir.listFiles();
            if (auditFiles != null) {
                for (File auditFile : auditFiles) {
                    String auditText = new String(Files.readAllBytes(auditFile.toPath()), StandardCharsets.UTF_8);
                    assertFalse("persistent audit must not echo a condition path",
                            auditText.contains(outsideFile.getAbsolutePath()));
                }
            }
            assertTrue("outside fixture exists so the failure verifies path policy, not absence",
                    outsideFile.exists());
        } finally {
            Files.deleteIfExists(privateFile.toPath());
            Files.deleteIfExists(outsideFile.toPath());
        }
    }

    private static TaskSpec task(String taskId, File conditionFile) throws Exception {
        return new TaskSpec(taskId, "1.0", "verify task precondition policy",
                Collections.emptyList(),
                Collections.singletonList("file_exists:" + conditionFile.getAbsolutePath()),
                Collections.singletonList(new TaskSpec.TaskStep(PROBE_SKILL, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 2, 60));
    }

    private static void registerProbeSkill(SkillEngine skills) throws Exception {
        JSONObject manifestJson = new JSONObject()
                .put("skill_id", PROBE_SKILL)
                .put("steps", new JSONArray().put(new JSONObject().put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 0))));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        java.lang.reflect.Field field = SkillEngine.class.getDeclaredField("registry");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) field.get(skills);
        registry.put(PROBE_SKILL, manifest);
    }
}
