package com.agentruntime.runtime;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.device.SystemBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExtendedCapabilities;
import com.agentruntime.executor.ExecResult;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ClipboardGetLogRedactionTest {

    private static final String SYNTHETIC_CLIP_TEXT = "synthetic-clipboard-account-secret-marker";
    private static final String SKILL_ID = "test.clipboard_get_private";

    @Test
    public void clipboardContentsRemainInMemoryButAreOmittedFromLogsAndTaskResult() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData previous = clipboard != null && clipboard.hasPrimaryClip() ? clipboard.getPrimaryClip() : null;
        try {
            assertTrue("Robolectric clipboard service required", clipboard != null);
            clipboard.setPrimaryClip(ClipData.newPlainText("synthetic test fixture", SYNTHETIC_CLIP_TEXT));
            verifyClipboardRedaction(context);
        } finally {
            if (clipboard != null) {
                if (previous != null) clipboard.setPrimaryClip(previous);
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
            }
        }
    }

    private static void verifyClipboardRedaction(Application context) throws Exception {
        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        SkillEngine skills = new SkillEngine(context);
        SystemBridge system = new SystemBridge(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        executor.attach(new ExtendedCapabilities(null, null, null, null, null, system));
        ActionSpec clipboardGet = new ActionSpec(ActionSpec.ActionType.CLIPBOARD_GET,
                new JSONObject(), 3000, 0, ActionSpec.RiskLevel.LOW, false, null);

        ExecResult directResult = executor.execute("clipboard-redaction", "direct", "read synthetic clipboard",
                clipboardGet, Collections.emptyList());
        assertEquals("CLIPBOARD_READ", directResult.code);
        assertTrue(directResult.detail.contains(SYNTHETIC_CLIP_TEXT));
        long afterDirect = auditFile.length();
        assertFalse(readFrom(auditFile, auditOffset, afterDirect).contains(SYNTHETIC_CLIP_TEXT));

        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(clipboardGet.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("clipboard-redaction", "1.0", "read a synthetic clipboard fixture",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        AgentRuntime.TaskResult taskResult = runtime.runTask(task, false, false);
        assertEquals("SUCCESS", taskResult.status);
        String persistedTaskResult = taskResult.detail.toString();
        assertFalse(persistedTaskResult.contains(SYNTHETIC_CLIP_TEXT));
        JSONObject loggedAction = taskResult.detail.getJSONArray("steps").getJSONObject(0)
                .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
        assertEquals("CLIPBOARD_READ", loggedAction.getString("code"));
        assertFalse(loggedAction.has("detail"));
        assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(SYNTHETIC_CLIP_TEXT));
        assertTrue(directResult.detail.contains("clipboard-account"));
    }

    private static String readFrom(File file, long start, long end) throws IOException {
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
