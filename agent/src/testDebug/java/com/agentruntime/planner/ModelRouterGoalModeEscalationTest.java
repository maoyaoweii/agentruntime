package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.intent.IntentModel;
import com.agentruntime.intent.IntentPlanner;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.skill.SkillEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ModelRouterGoalModeEscalationTest {

    @Test(timeout = 25000)
    public void lowConfidenceIntentFallsThroughToConfiguredLocalPlanner() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(LocalModelRegistry.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicBoolean requestReceived = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10000);
            JSONObject responseTask = new JSONObject()
                    .put("task_version", "1.0")
                    .put("task_id", "local_after_low_confidence")
                    .put("goal", "synthesize route fallback fixture")
                    .put("allowed_apps", new JSONArray())
                    .put("preconditions", new JSONArray())
                    .put("steps", new JSONArray().put(new JSONObject()
                            .put("skill", "sys.observe")
                            .put("args", new JSONObject())))
                    .put("postconditions", new JSONArray())
                    .put("limits", new JSONObject()
                            .put("max_steps", 5)
                            .put("max_replans", 0)
                            .put("timeout_seconds", 20));
            String responseBody = openAiContent(responseTask.toString());
            Thread responder = new Thread(() -> respondOnce(server,
                    responseBody, requestReceived, serverFailure),
                    "low-confidence-local-planner-loopback");
            responder.setDaemon(true);
            responder.start();

            LocalModelRegistry localModels = new LocalModelRegistry(context);
            localModels.configure("HTTP", "127.0.0.1", server.getLocalPort(),
                    "fixture-vision", "fixture-decider", 3000, 256);
            IntentPlanner ambiguousIntentPlanner = ambiguousIntentPlanner();
            ModelRouter router = new ModelRouter(context, new SkillEngine(context),
                    new AuditLogger(context), localModels, null, ambiguousIntentPlanner);

            TaskSpec task = router.plan("synthesize route fallback fixture", Collections.emptyList());
            responder.join(10000);

            assertFalse("loopback responder should have completed", responder.isAlive());
            assertNull(serverFailure.get());
            assertTrue("local planner should receive the escalated request", requestReceived.get());
            assertEquals("local_after_low_confidence", task.taskId);
            assertEquals(1, router.localCallCount());
            assertEquals(0, router.cloudCallCount());
            assertEquals(0, router.stats().optInt("intent_hits", -1));
        } finally {
            context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            context.getSharedPreferences(LocalModelRegistry.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static IntentPlanner ambiguousIntentPlanner() throws JSONException {
        JSONObject weights = new JSONObject()
                .put("classes", new JSONArray().put("open_clock").put("smoke"))
                .put("skills", new JSONArray().put("sys.open_clock").put("sys.tool_smoke"))
                .put("prior", new JSONArray().put(0).put(0))
                .put("default_logp", new JSONArray().put(0).put(0))
                .put("tokens", new JSONArray())
                .put("weights", new JSONObject());
        return new IntentPlanner(IntentModel.fromJson(weights.toString()));
    }

    private static String openAiContent(String content) throws JSONException {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("message", new JSONObject().put("content", content)))).toString();
    }

    private static void respondOnce(ServerSocket server, String body, AtomicBoolean received,
                                    AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            BufferedReader request = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) {
                // Consume request headers only; never retain or print request contents.
            }
            received.set(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
        } catch (Throwable t) {
            failure.set(t);
        }
    }
}
