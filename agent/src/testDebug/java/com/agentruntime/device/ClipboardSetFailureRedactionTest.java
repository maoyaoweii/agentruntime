package com.agentruntime.device;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, shadows = ClipboardSetFailureRedactionTest.ThrowingClipboardShadow.class)
public class ClipboardSetFailureRedactionTest {

    private static final String SKILL_ID = "test.clipboard_set_failure_redaction";

    @Test
    public void clipboardFailureMessageCannotEchoInputIntoResultsOrLogs() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String sensitiveText = "synthetic-clipboard-failure-secret-" + UUID.randomUUID();
        SystemBridge system = new SystemBridge(context);
        ExecResult direct = system.setClipboard(sensitiveText);
        assertEquals("CLIPBOARD_FAILED", direct.code);
        assertTrue(direct.detail.contains("IllegalStateException"));
        assertFalse("in-memory clipboard error detail must not echo the payload",
                direct.detail.contains(sensitiveText));

        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        executor.attach(new ExtendedCapabilities(null, null, null, null, null, system));

        ActionSpec clipboardSet = new ActionSpec(ActionSpec.ActionType.CLIPBOARD_SET,
                new JSONObject().put("text", sensitiveText), 3000, 0,
                ActionSpec.RiskLevel.LOW, false, null);
        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(clipboardSet.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        SkillEngine skills = new SkillEngine(context);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);

        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("clipboard-set-failure", "1.0", "write synthetic clipboard fixture",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 10));
        AgentRuntime.TaskResult result = runtime.runTask(task, false, false);
        assertEquals("FAILED", result.status);
        assertFalse(result.detail.toString().contains(sensitiveText));
        JSONObject actionResult = result.detail.getJSONArray("steps").getJSONObject(0)
                .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
        assertEquals("CLIPBOARD_FAILED", actionResult.getString("code"));
        assertFalse(actionResult.has("detail"));
        assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(sensitiveText));
    }

    @Implements(ClipboardManager.class)
    public static class ThrowingClipboardShadow {
        @Implementation
        protected void setPrimaryClip(ClipData clip) {
            String text = clip == null || clip.getItemCount() == 0 ? ""
                    : clip.getItemAt(0).coerceToText(RuntimeEnvironment.getApplication()).toString();
            throw new IllegalStateException(text);
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
