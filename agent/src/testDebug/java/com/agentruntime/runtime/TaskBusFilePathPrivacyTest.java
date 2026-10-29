package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.device.SystemBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExtendedCapabilities;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class TaskBusFilePathPrivacyTest {

    private static final String SKILL_ID = "test.taskbus_read_file_outside";

    @Test
    public void taskBusPreservesReadFailureCodeWithoutEchoingPathOrContent() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String marker = "synthetic-taskbus-read-path-" + UUID.randomUUID();
        File outsideFile = new File(context.getCacheDir(), marker + ".txt");
        String content = "synthetic-taskbus-content-" + marker;
        Files.write(outsideFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        try {
            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            executor.attach(new ExtendedCapabilities(null, null, null, null, null,
                    new SystemBridge(context)));

            ActionSpec read = new ActionSpec(ActionSpec.ActionType.READ_FILE,
                    new JSONObject().put("path", outsideFile.getAbsolutePath()).put("max_bytes", 1024),
                    3000, 0, ActionSpec.RiskLevel.LOW, false, null);
            JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                    .put("steps", new JSONArray().put(read.toJson()));
            SkillManifest manifest = SkillManifest.fromJson(manifestJson);
            SkillEngine skills = new SkillEngine(context);
            Field registryField = SkillEngine.class.getDeclaredField("registry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
            registry.put(SKILL_ID, manifest);

            Constructor<TaskBus> busConstructor = TaskBus.class.getDeclaredConstructor();
            busConstructor.setAccessible(true);
            TaskBus bus = busConstructor.newInstance();
            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
            TaskSpec task = new TaskSpec("taskbus-read-path", "1.0", "read synthetic fixture",
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                    Collections.emptyList(), new TaskSpec.Limits(5, 0, 10));

            AgentRuntime.TaskResult result = runtime.runTask(task, false, false, bus.runtimeListener());
            assertEquals("FAILED", result.status);
            assertEquals(TaskBus.State.FAILED, bus.state());
            assertEquals("执行失败", bus.progressText());
            assertEquals(1, bus.stepIndex());
            assertEquals(1, bus.stepTotal());

            List<TaskBus.Event> events = bus.recent();
            StringBuilder displayText = new StringBuilder();
            for (TaskBus.Event event : events) displayText.append(event.message).append('\n');
            assertTrue("UI log should retain a useful policy error code",
                    displayText.toString().contains("PATH_NOT_ALLOWED"));
            assertTrue("UI log should report terminal failure", displayText.toString().contains("任务结束：FAILED"));
            assertFalse("UI progress must not echo the rejected path", displayText.toString().contains(marker));
            assertFalse("UI progress must not echo file contents", displayText.toString().contains(content));
            assertFalse(result.detail.toString().contains(marker));
        } finally {
            Files.deleteIfExists(outsideFile.toPath());
        }
    }
}
