package com.agentruntime.executor;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;

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
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ActionExecutorLocateTextRedactionTest {

    private static final String SCREEN_SECRET = "synthetic-screen-secret-marker";
    private static final String QUERY_SECRET = "synthetic-query-secret-marker";

    @Test
    public void locatorFailureAndSuccessDoNotPersistScreenOrQueryText() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        UiNode root = new UiNode("node-1", "android.widget.EditText", SCREEN_SECRET,
                null, "sample:id/secret_field", true, true, true,
                true, true, false, false, 0, 0, 100, 40, Collections.emptyList());
        state.rebuildSnapshot("sample.app", "sample window", root, "node-1", "test_fixture");
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditOffset = auditFile.exists() ? auditFile.length() : 0L;
        ActionExecutor executor = new ActionExecutor(context, state,
                new AccessibilityBridge(state), new IntentBridge(context),
                new RuleBasedSystem1(), new RiskGate(), audit);

        ActionSpec missing = findByText(QUERY_SECRET);
        ExecResult missResult = executor.execute("locate-redaction", "miss", "synthetic miss",
                missing, Collections.emptyList());
        assertEquals("NODE_NOT_FOUND", missResult.code);
        String missJson = missResult.toJson().toString();
        assertFalse(missJson.contains(QUERY_SECRET));
        assertFalse(missJson.contains(SCREEN_SECRET));
        long afterMiss = auditFile.length();
        String missAudit = readFrom(auditFile, auditOffset, afterMiss);
        assertFalse(missAudit.contains(QUERY_SECRET));
        assertFalse(missAudit.contains(SCREEN_SECRET));

        ActionSpec found = findByText(SCREEN_SECRET);
        ExecResult foundResult = executor.execute("locate-redaction", "found", "synthetic found",
                found, Collections.emptyList());
        assertEquals("NODE_FOUND", foundResult.code);
        assertFalse(foundResult.toJson().toString().contains(SCREEN_SECRET));
        String foundAudit = readFrom(auditFile, afterMiss, auditFile.length());
        assertFalse(foundAudit.contains(SCREEN_SECRET));
    }

    private static ActionSpec findByText(String text) throws Exception {
        JSONObject find = new JSONObject().put("text", text);
        JSONObject params = new JSONObject().put("find", find);
        return DslParser.parseAction(new JSONObject().put("action", "find_node").put("params", params));
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
