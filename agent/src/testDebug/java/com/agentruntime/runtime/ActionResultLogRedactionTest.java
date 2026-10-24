package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
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
public class ActionResultLogRedactionTest {

    private static final String SYNTHETIC_SCREEN_TEXT = "synthetic-account-secret-marker";
    private static final String SKILL_ID = "test.read_private_state";

    @Test
    public void readStateDetailRemainsInMemoryButIsOmittedFromLogsAndTaskResult() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        UiNode root = new UiNode("node-private", "android.widget.TextView", SYNTHETIC_SCREEN_TEXT,
                null, "sample:id/private_value", false, true, true,
                false, false, false, false, 0, 0, 100, 40, Collections.emptyList());
        state.rebuildSnapshot("sample.app", "sample window", root, "node-private", "test_fixture");
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        SkillEngine skills = new SkillEngine(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ActionSpec readState = new ActionSpec(ActionSpec.ActionType.READ_STATE,
                new JSONObject().put("find", new JSONObject().put("text", SYNTHETIC_SCREEN_TEXT)),
                3000, 0, ActionSpec.RiskLevel.LOW, false, null);

        ExecResult directResult = executor.execute("read-state-redaction", "direct", "read private field",
                readState, Collections.emptyList());
        assertEquals("STATE_READ", directResult.code);
        assertTrue(directResult.detail.contains(SYNTHETIC_SCREEN_TEXT));
        long afterDirect = auditFile.length();
        assertFalse(readFrom(auditFile, auditOffset, afterDirect).contains(SYNTHETIC_SCREEN_TEXT));

        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(readState.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("read-state-redaction", "1.0", "read a synthetic private state",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        AgentRuntime.TaskResult taskResult = runtime.runTask(task, false, false);
        assertEquals("SUCCESS", taskResult.status);
        String persistedTaskResult = taskResult.detail.toString();
        assertFalse(persistedTaskResult.contains(SYNTHETIC_SCREEN_TEXT));
        JSONObject loggedAction = taskResult.detail.getJSONArray("steps").getJSONObject(0)
                .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
        assertEquals("STATE_READ", loggedAction.getString("code"));
        assertFalse(loggedAction.has("detail"));
        assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(SYNTHETIC_SCREEN_TEXT));
        assertTrue(directResult.detail.contains("synthetic-account"));
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
