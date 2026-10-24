package com.agentruntime.device;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExtendedCapabilities;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.runtime.AgentRuntime;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ReadFilePathRedactionTest {

    private static final String PRIVATE_SKILL = "test.read_file_private";
    private static final String OUTSIDE_SKILL = "test.read_file_outside";

    @Test
    public void readErrorsDoNotEchoPathsAndSuccessfulContentsStayOutOfLogs() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String marker = "synthetic-read-file-" + UUID.randomUUID();
        File privateFile = new File(context.getFilesDir(), marker + "-private.txt");
        File outsideFile = new File(context.getCacheDir(), marker + "-outside.txt");
        File missingFile = new File(context.getFilesDir(), marker + "-missing.txt");
        String privateContent = "synthetic-private-content-" + marker;
        String outsideContent = "synthetic-outside-content-" + marker;
        try {
            Files.write(privateFile.toPath(), privateContent.getBytes(StandardCharsets.UTF_8));
            Files.write(outsideFile.toPath(), outsideContent.getBytes(StandardCharsets.UTF_8));
            SystemBridge system = new SystemBridge(context);

            ExecResult missing = system.readFile(missingFile.getAbsolutePath(), 1024);
            assertEquals("FILE_NOT_FOUND", missing.code);
            assertFalse(missing.detail.contains(marker));
            ExecResult outside = system.readFile(outsideFile.getAbsolutePath(), 1024);
            assertEquals("PATH_NOT_ALLOWED", outside.code);
            assertFalse(outside.detail.contains(marker));
            ExecResult directPrivate = system.readFile(privateFile.getAbsolutePath(), 1024);
            assertEquals("FILE_READ", directPrivate.code);
            assertTrue(directPrivate.detail.contains(privateContent));

            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            File auditFile = audit.currentFile();
            long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            executor.attach(new ExtendedCapabilities(null, null, null, null, null, system));
            SkillEngine skills = new SkillEngine(context);
            registerReadSkill(skills, PRIVATE_SKILL, privateFile.getAbsolutePath());
            registerReadSkill(skills, OUTSIDE_SKILL, outsideFile.getAbsolutePath());
            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);

            AgentRuntime.TaskResult allowed = runtime.runTask(task("read-private", PRIVATE_SKILL), false, false);
            assertEquals("SUCCESS", allowed.status);
            assertFalse(allowed.detail.toString().contains(privateContent));
            assertFalse(allowed.detail.toString().contains(marker));

            AgentRuntime.TaskResult blocked = runtime.runTask(task("read-outside", OUTSIDE_SKILL), false, false);
            assertEquals("FAILED", blocked.status);
            assertFalse(blocked.detail.toString().contains(outsideContent));
            assertFalse(blocked.detail.toString().contains(marker));
            JSONObject actionResult = blocked.detail.getJSONArray("steps").getJSONObject(0)
                    .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
            assertEquals("PATH_NOT_ALLOWED", actionResult.getString("code"));
            assertFalse(actionResult.has("detail"));
            String auditText = readFrom(auditFile, auditOffset, auditFile.length());
            assertFalse(auditText.contains(privateContent));
            assertFalse(auditText.contains(outsideContent));
            assertFalse(auditText.contains(marker));
        } finally {
            Files.deleteIfExists(privateFile.toPath());
            Files.deleteIfExists(outsideFile.toPath());
            Files.deleteIfExists(missingFile.toPath());
        }
    }

    private static void registerReadSkill(SkillEngine skills, String skillId, String path) throws Exception {
        ActionSpec readFile = new ActionSpec(ActionSpec.ActionType.READ_FILE,
                new JSONObject().put("path", path).put("max_bytes", 1024),
                3000, 0, ActionSpec.RiskLevel.LOW, false, null);
        JSONObject manifestJson = new JSONObject().put("skill_id", skillId)
                .put("steps", new JSONArray().put(readFile.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(skillId, manifest);
    }

    private static TaskSpec task(String taskId, String skillId) throws Exception {
        return new TaskSpec(taskId, "1.0", "verify app-private file read policy",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(skillId, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 10));
    }

    private static String readFrom(File file, long start, long end) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.seek(start);
            byte[] buffer = new byte[1024];
            long remaining = end - start;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toString("UTF-8");
        }
    }
}
