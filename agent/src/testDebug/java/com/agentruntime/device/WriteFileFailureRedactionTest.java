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
public class WriteFileFailureRedactionTest {

    private static final String SKILL_ID = "test.write_file_failure_redaction";

    @Test
    public void writeFailureDoesNotEchoPathOrContentIntoResultsOrAudit() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String marker = "synthetic-file-write-error-" + UUID.randomUUID();
        File directoryTarget = new File(context.getFilesDir(), marker);
        Files.createDirectory(directoryTarget.toPath());
        String content = "synthetic-content-" + marker;
        try {
            assertTrue(directoryTarget.isDirectory());
            SystemBridge system = new SystemBridge(context);
            ExecResult direct = system.writeFile(directoryTarget.getAbsolutePath(), content);
            assertEquals("FILE_WRITE_FAILED", direct.code);
            assertTrue(direct.detail.contains("FileNotFoundException"));
            assertFalse("direct error detail must not echo the path", direct.detail.contains(marker));
            assertFalse("direct error detail must not echo the content", direct.detail.contains(content));

            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            File auditFile = audit.currentFile();
            long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            executor.attach(new ExtendedCapabilities(null, null, null, null, null, system));

            ActionSpec writeFile = new ActionSpec(ActionSpec.ActionType.WRITE_FILE,
                    new JSONObject().put("path", directoryTarget.getAbsolutePath()).put("content", content),
                    3000, 0, ActionSpec.RiskLevel.LOW, false, null);
            JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                    .put("steps", new JSONArray().put(writeFile.toJson()));
            SkillManifest manifest = SkillManifest.fromJson(manifestJson);
            SkillEngine skills = new SkillEngine(context);
            Field registryField = SkillEngine.class.getDeclaredField("registry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
            registry.put(SKILL_ID, manifest);

            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
            TaskSpec task = new TaskSpec("write-file-failure", "1.0", "write synthetic file fixture",
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                    Collections.emptyList(), new TaskSpec.Limits(5, 0, 10));
            AgentRuntime.TaskResult result = runtime.runTask(task, false, false);
            assertEquals("FAILED", result.status);
            assertFalse(result.detail.toString().contains(marker));
            assertFalse(result.detail.toString().contains(content));
            JSONObject actionResult = result.detail.getJSONArray("steps").getJSONObject(0)
                    .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
            assertEquals("FILE_WRITE_FAILED", actionResult.getString("code"));
            assertFalse(actionResult.has("detail"));
            String persistedAudit = readFrom(auditFile, auditOffset, auditFile.length());
            assertFalse(persistedAudit.contains(marker));
            assertFalse(persistedAudit.contains(content));
        } finally {
            Files.deleteIfExists(directoryTarget.toPath());
        }
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
