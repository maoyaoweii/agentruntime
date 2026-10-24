package com.agentruntime.runtime;

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
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;
import com.agentruntime.web.BrowserBridge;
import com.agentruntime.web.BrowserController;

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
public class ReadPageLogRedactionTest {

    private static final String SYNTHETIC_PAGE_TEXT = "synthetic-web-account-secret-marker";
    private static final String SKILL_ID = "test.read_page_private";

    @Test
    public void readPageTextRemainsInMemoryButIsOmittedFromLogsAndTaskResult() throws Exception {
        Field webViewField = BrowserBridge.class.getDeclaredField("webView");
        Field readyField = BrowserBridge.class.getDeclaredField("pageReady");
        webViewField.setAccessible(true);
        readyField.setAccessible(true);
        Object previousWebView = webViewField.get(null);
        Object previousReady = readyField.get(null);
        try {
            webViewField.set(null, null);
            readyField.set(null, false);
            verifyReadPageRedaction();
        } finally {
            webViewField.set(null, previousWebView);
            readyField.set(null, previousReady);
        }
    }

    private static void verifyReadPageRedaction() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        UiNode root = new UiNode("page-node", "android.widget.TextView", SYNTHETIC_PAGE_TEXT,
                null, "browser:id/page_text", false, true, true,
                false, false, false, false, 0, 0, 200, 50, Collections.emptyList());
        state.rebuildSnapshot("browser.app", "browser window", root, "page-node", "test_fixture");
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        SkillEngine skills = new SkillEngine(context);
        BrowserController browser = new BrowserController(context);
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);
        executor.attach(new ExtendedCapabilities(null, null, null, browser, null));
        ActionSpec readPage = new ActionSpec(ActionSpec.ActionType.READ_PAGE,
                new JSONObject().put("max_chars", 100), 3000, 0,
                ActionSpec.RiskLevel.LOW, false, null);

        ExecResult directResult = executor.execute("read-page-redaction", "direct", "read synthetic page",
                readPage, Collections.emptyList());
        assertEquals("PAGE_TEXT_A11Y", directResult.code);
        assertTrue(directResult.detail.contains(SYNTHETIC_PAGE_TEXT));
        long afterDirect = auditFile.length();
        assertFalse(readFrom(auditFile, auditOffset, afterDirect).contains(SYNTHETIC_PAGE_TEXT));

        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(readPage.toJson()));
        SkillManifest manifest = SkillManifest.fromJson(manifestJson);
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SkillManifest> registry = (Map<String, SkillManifest>) registryField.get(skills);
        registry.put(SKILL_ID, manifest);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("read-page-redaction", "1.0", "read a synthetic browser page",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        AgentRuntime.TaskResult taskResult = runtime.runTask(task, false, false);
        assertEquals("SUCCESS", taskResult.status);
        String persistedTaskResult = taskResult.detail.toString();
        assertFalse(persistedTaskResult.contains(SYNTHETIC_PAGE_TEXT));
        JSONObject loggedAction = taskResult.detail.getJSONArray("steps").getJSONObject(0)
                .getJSONArray("actions").getJSONObject(0).getJSONObject("result");
        assertEquals("PAGE_TEXT_A11Y", loggedAction.getString("code"));
        assertFalse(loggedAction.has("detail"));
        assertFalse(readFrom(auditFile, auditOffset, auditFile.length()).contains(SYNTHETIC_PAGE_TEXT));
        assertTrue(directResult.detail.contains("web-account"));
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
