package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.state.StateEngine;

import org.json.JSONException;
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
public class AgentRuntimeArgsRedactionTest {

    private static final String SYNTHETIC_SECRET = "synthetic-password-argument-marker";

    @Test
    public void stepArgumentsAndResolutionExceptionAreNotPersisted() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SkillEngine skills = new SkillEngine(context);
        StateEngine state = new StateEngine();
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditLengthBefore = auditFile.exists() ? auditFile.length() : 0L;
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, null, router, audit);

        JSONObject args = new JSONObject().put("password", SYNTHETIC_SECRET);
        TaskSpec.TaskStep step = new TaskSpec.TaskStep("missing.skill.redaction_fixture", args, null);
        TaskSpec task = new TaskSpec("args-redaction-test", "1.0", "synthetic redaction probe",
                Collections.emptyList(), Collections.emptyList(), Collections.singletonList(step),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        AgentRuntime.TaskResult result = runtime.runTask(task, false, false);

        assertEquals("FAILED", result.status);
        String taskResult = result.detail.toString();
        assertFalse(taskResult.contains(SYNTHETIC_SECRET));
        JSONObject stepLog = result.detail.getJSONArray("steps").getJSONObject(0);
        assertEquals(1, stepLog.getInt("arg_count"));
        assertFalse(stepLog.has("args"));
        assertEquals("skill_resolution_rejected", stepLog.getString("error"));
        String appendedAudit = readFrom(auditFile, auditLengthBefore);
        assertFalse(appendedAudit.contains(SYNTHETIC_SECRET));
    }

    private static String readFrom(File file, long offset) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.seek(offset);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
