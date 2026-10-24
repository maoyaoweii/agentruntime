package com.agentruntime.runtime;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.inputmethod.InputConnection;

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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SensitiveInputWriteLogRedactionTest {

    private static final String SKILL_ID = "test.sensitive_input_write";

    @Test
    public void clipboardAndImeInputsStayOutOfPersistentLogsOnSuccessAndFailure() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData previousClip = clipboard != null && clipboard.hasPrimaryClip()
                ? clipboard.getPrimaryClip() : null;
        Field instanceField = AgentImeService.class.getDeclaredField("instance");
        Field connectionField = AgentImeService.class.getDeclaredField("connection");
        instanceField.setAccessible(true);
        connectionField.setAccessible(true);
        Object previousInstance = instanceField.get(null);
        InputConnection previousConnection = (InputConnection) connectionField.get(null);
        ServiceController<AgentImeService> imeController = null;
        try {
            assertTrue("Robolectric clipboard service required", clipboard != null);
            imeController = Robolectric.buildService(AgentImeService.class).create();

            String nonce = UUID.randomUUID().toString();
            String clipboardSecret = "synthetic-clipboard-secret-" + nonce;
            String imeSecret = "synthetic-ime-secret-" + nonce;
            AtomicReference<String> committed = new AtomicReference<>();
            AtomicBoolean throwWithSensitiveMessage = new AtomicBoolean(false);
            InputConnection fakeConnection = (InputConnection) Proxy.newProxyInstance(
                    InputConnection.class.getClassLoader(), new Class<?>[]{InputConnection.class},
                    (proxy, method, args) -> {
                        if ("commitText".equals(method.getName())) {
                            committed.set(args == null || args.length == 0 ? null : String.valueOf(args[0]));
                            if (throwWithSensitiveMessage.get()) {
                                throw new IllegalStateException(imeSecret);
                            }
                            return true;
                        }
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType == int.class) return 0;
                        if (returnType == long.class) return 0L;
                        if (returnType == float.class) return 0f;
                        if (returnType == double.class) return 0d;
                        if (returnType == char.class) return '\0';
                        return null;
                    });
            connectionField.set(null, fakeConnection);

            StateEngine state = new StateEngine();
            AuditLogger audit = new AuditLogger(context);
            File auditFile = audit.currentFile();
            long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
            SystemBridge system = new SystemBridge(context);
            ActionExecutor executor = new ActionExecutor(context, state,
                    new AccessibilityBridge(state), new IntentBridge(context),
                    new RuleBasedSystem1(), new RiskGate(), audit);
            executor.attach(new ExtendedCapabilities(null, null, null, null, null, system));

            ActionSpec clipboardSet = action(ActionSpec.ActionType.CLIPBOARD_SET, "text", clipboardSecret);
            ActionSpec imeCommit = action(ActionSpec.ActionType.IME_COMMIT, "text", imeSecret);
            ExecResult directClipboard = executor.execute("sensitive-write", "direct-clipboard",
                    "write synthetic clipboard fixture", clipboardSet, Collections.emptyList());
            assertEquals("CLIPBOARD_SET", directClipboard.code);
            assertFalse(directClipboard.detail.contains(clipboardSecret));
            assertEquals(clipboardSecret, clipboard.getPrimaryClip().getItemAt(0).coerceToText(context).toString());

            ExecResult directIme = executor.execute("sensitive-write", "direct-ime",
                    "commit synthetic IME fixture", imeCommit, Collections.emptyList());
            assertEquals("IME_COMMIT_OK", directIme.code);
            assertEquals(imeSecret, committed.get());
            assertFalse(directIme.detail.contains(imeSecret));
            assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(clipboardSecret));
            assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(imeSecret));

            throwWithSensitiveMessage.set(true);
            SkillEngine skills = new SkillEngine(context);
            JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                    .put("steps", new JSONArray().put(clipboardSet.toJson()).put(imeCommit.toJson()));
            SkillManifest manifest = SkillManifest.fromJson(manifestJson);
            Field registryField = SkillEngine.class.getDeclaredField("registry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
            registry.put(SKILL_ID, manifest);

            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
            TaskSpec task = new TaskSpec("sensitive-write-task", "1.0", "write synthetic fixtures",
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                    Collections.emptyList(), new TaskSpec.Limits(5, 0, 20));
            AgentRuntime.TaskResult taskResult = runtime.runTask(task, false, false);
            assertEquals("FAILED", taskResult.status);
            assertEquals(2, taskResult.detail.getInt("executed_actions"));
            String persistedResult = taskResult.detail.toString();
            assertFalse(persistedResult.contains(clipboardSecret));
            assertFalse(persistedResult.contains(imeSecret));
            String persistedAudit = readFrom(auditFile, auditOffset, auditFile.length());
            assertFalse(persistedAudit.contains(clipboardSecret));
            assertFalse(persistedAudit.contains(imeSecret));

            JSONArray actions = taskResult.detail.getJSONArray("steps").getJSONObject(0)
                    .getJSONArray("actions");
            assertEquals("CLIPBOARD_SET", actions.getJSONObject(0).getJSONObject("result").getString("code"));
            assertFalse(actions.getJSONObject(0).getJSONObject("result").has("detail"));
            assertFalse(actions.getJSONObject(1).getJSONObject("result").has("detail"));
            assertTrue("fake IME must receive the payload before raising a sensitive exception", imeSecret.equals(committed.get()));
        } finally {
            if (imeController != null) imeController.destroy();
            instanceField.set(null, previousInstance);
            connectionField.set(null, previousConnection);
            if (clipboard != null) {
                if (previousClip != null) clipboard.setPrimaryClip(previousClip);
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
            }
        }
    }

    private static ActionSpec action(ActionSpec.ActionType type, String key, String value) throws Exception {
        return new ActionSpec(type, new JSONObject().put(key, value), 3000, 0,
                ActionSpec.RiskLevel.LOW, false, null);
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
