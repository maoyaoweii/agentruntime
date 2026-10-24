package com.agentruntime.runtime;

import android.app.Application;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExecResult;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.ime.AgentImeService;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ImeReadLogRedactionTest {

    private static final String SYNTHETIC_IME_TEXT = "synthetic-ime-account-secret-marker";
    private static final String SKILL_ID = "test.ime_read_private";

    @Test
    public void imeReadContentsRemainInMemoryButAreOmittedFromLogsAndTaskResult() throws Exception {
        Field instanceField = AgentImeService.class.getDeclaredField("instance");
        Field connectionField = AgentImeService.class.getDeclaredField("connection");
        instanceField.setAccessible(true);
        connectionField.setAccessible(true);
        Object previousInstance = instanceField.get(null);
        Object previousConnection = connectionField.get(null);
        ServiceController<AgentImeService> controller = Robolectric.buildService(AgentImeService.class).create();
        try {
            InputConnection syntheticConnection = syntheticInputConnection();
            connectionField.set(null, syntheticConnection);
            verifyImeReadRedaction();
        } finally {
            try {
                controller.destroy();
            } finally {
                instanceField.set(null, previousInstance);
                connectionField.set(null, previousConnection);
            }
        }
    }

    private static void verifyImeReadRedaction() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        SkillEngine skills = new SkillEngine(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        ActionSpec imeRead = new ActionSpec(ActionSpec.ActionType.IME_READ,
                new JSONObject().put("max_chars", 128), 3000, 0,
                ActionSpec.RiskLevel.LOW, false, null);

        ExecResult directResult = executor.execute("ime-read-redaction", "direct", "read synthetic input",
                imeRead, Collections.emptyList());
        assertEquals("IME_TEXT", directResult.code);
        assertTrue(directResult.detail.contains(SYNTHETIC_IME_TEXT));
        long afterDirect = auditFile.length();
        assertFalse(readFrom(auditFile, auditOffset, afterDirect).contains(SYNTHETIC_IME_TEXT));

        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(imeRead.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("ime-read-redaction", "1.0", "read a synthetic input fixture",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        AgentRuntime.TaskResult taskResult = runtime.runTask(task, false, false);
        assertEquals("SUCCESS", taskResult.status);
        String persistedTaskResult = taskResult.detail.toString();
        assertFalse(persistedTaskResult.contains(SYNTHETIC_IME_TEXT));
        JSONObject loggedAction = taskResult.detail.getJSONArray("steps").getJSONObject(0)
                .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
        assertEquals("IME_TEXT", loggedAction.getString("code"));
        assertFalse(loggedAction.has("detail"));
        assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(SYNTHETIC_IME_TEXT));
        assertTrue(directResult.detail.contains("ime-account"));
    }

    private static InputConnection syntheticInputConnection() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getExtractedText".equals(method.getName())) {
                    ExtractedText result = new ExtractedText();
                    result.text = SYNTHETIC_IME_TEXT;
                    return result;
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                if (type == float.class) return 0f;
                if (type == double.class) return 0d;
                if (type == char.class) return '\0';
                if (type == byte.class) return (byte) 0;
                if (type == short.class) return (short) 0;
                return null;
            }
        };
        return (InputConnection) Proxy.newProxyInstance(AgentImeService.class.getClassLoader(),
                new Class<?>[]{InputConnection.class}, handler);
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
