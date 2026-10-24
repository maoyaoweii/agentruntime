package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.skill.SkillEngine;

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
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ModelRouterGoalPrivacyTest {

    private static final String SYNTHETIC_SECRET = "synthetic-private-goal-marker-not-a-credential";

    @Test
    public void auditAndUnavailablePlannerErrorDoNotPersistRawGoal() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        String goal = "opaque fixture " + SYNTHETIC_SECRET;
        AuditLogger audit = new AuditLogger(context);
        File auditFile = audit.currentFile();
        long auditLengthBefore = auditFile.exists() ? auditFile.length() : 0L;
        ModelRouter router = new ModelRouter(context, new SkillEngine(context), audit,
                null, null, null);

        JSONException planningFailure = null;
        try {
            router.plan(goal, Collections.emptyList());
        } catch (JSONException expected) {
            planningFailure = expected;
        }

        assertNotNull("unconfigured planners should fail", planningFailure);
        assertFalse(planningFailure.getMessage().contains(SYNTHETIC_SECRET));
        String appendedAudit = readFrom(auditFile, auditLengthBefore).trim();
        assertFalse(appendedAudit.contains(SYNTHETIC_SECRET));
        JSONObject record = new JSONObject(appendedAudit);
        JSONObject detail = record.getJSONObject("detail");
        assertFalse(detail.has("goal"));
        assertEquals(goal.length(), detail.getInt("goal_chars"));
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
