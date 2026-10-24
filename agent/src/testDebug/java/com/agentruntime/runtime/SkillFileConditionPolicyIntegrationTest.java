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

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SkillFileConditionPolicyIntegrationTest {

    private static final String PRIVATE_SKILL = "test.private_file_condition";
    private static final String OUTSIDE_SKILL = "test.outside_file_condition";

    @Test
    public void skillPostconditionsUseTheAppPrivateFilePolicy() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File privateFile = new File(context.getFilesDir(), "skill-condition-" + UUID.randomUUID() + ".txt");
        File outsideFile = new File(context.getCacheDir(), "skill-condition-" + UUID.randomUUID() + ".txt");
        try {
            Files.write(privateFile.toPath(), "private fixture".getBytes(StandardCharsets.UTF_8));
            Files.write(outsideFile.toPath(), "outside fixture".getBytes(StandardCharsets.UTF_8));

            SkillEngine skills = new SkillEngine(context);
            registerFileConditionSkill(skills, PRIVATE_SKILL, privateFile.getAbsolutePath());
            registerFileConditionSkill(skills, OUTSIDE_SKILL, outsideFile.getAbsolutePath());

            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);

            AgentRuntime.TaskResult privateResult = runtime.runTask(task(PRIVATE_SKILL), false, false, null);
            assertEquals("SUCCESS", privateResult.status);
            assertEquals("OK", privateResult.detail.getJSONArray("steps")
                    .getJSONObject(0).getString("code"));

            AgentRuntime.TaskResult outsideResult = runtime.runTask(task(OUTSIDE_SKILL), false, false, null);
            assertEquals("FAILED", outsideResult.status);
            assertEquals("SKILL_POSTCONDITION_FAILED", outsideResult.detail.getJSONArray("steps")
                    .getJSONObject(0).getString("code"));
        } finally {
            Files.deleteIfExists(privateFile.toPath());
            Files.deleteIfExists(outsideFile.toPath());
        }
    }

    private static void registerFileConditionSkill(SkillEngine skills, String skillId, String path)
            throws Exception {
        JSONObject manifestJson = new JSONObject()
                .put("skill_id", skillId)
                .put("steps", new JSONArray().put(new JSONObject().put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 0))))
                .put("postconditions", new JSONArray().put("file_exists:" + path));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        java.lang.reflect.Field field = SkillEngine.class.getDeclaredField("registry");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) field.get(skills);
        registry.put(skillId, manifest);
    }

    private static TaskSpec task(String skillId) throws Exception {
        return new TaskSpec("skill-file-policy-" + skillId, "1.0", "verify private file condition policy",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(skillId, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 60));
    }
}
