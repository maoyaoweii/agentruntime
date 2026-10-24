package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.skill.SkillEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
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

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ModelRouterFallbackTest {

    private static final String SYNTHETIC_KEY = "loopback-routing-key-only";

    @Test(timeout = 25000)
    public void fallsBackToCloudWhenLocalModelOutputIsNotTaskDsl() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(LocalModelRegistry.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        AtomicReference<Throwable> localFailure = new AtomicReference<>();
        AtomicReference<Throwable> cloudFailure = new AtomicReference<>();
        AtomicBoolean localRequestReceived = new AtomicBoolean();
        AtomicBoolean cloudRequestReceived = new AtomicBoolean();

        try (ServerSocket localServer = new ServerSocket(0);
             ServerSocket cloudServer = new ServerSocket(0)) {
            localServer.setSoTimeout(10000);
            cloudServer.setSoTimeout(10000);

            JSONObject taskJson = new JSONObject()
                    .put("task_version", "1.0")
                    .put("task_id", "cloud_fallback_result")
                    .put("goal", "synthesize route fallback fixture")
                    .put("allowed_apps", new JSONArray())
                    .put("preconditions", new JSONArray())
                    .put("steps", new JSONArray().put(new JSONObject()
                            .put("skill", "sys.open_clock")
                            .put("args", new JSONObject())))
                    .put("postconditions", new JSONArray())
                    .put("limits", new JSONObject()
                            .put("max_steps", 5)
                            .put("max_replans", 0)
                    .put("timeout_seconds", 20));

            String localResponse = openAiContent("this is not valid task JSON");
            String cloudResponse = openAiContent(taskJson.toString());
            Thread localResponder = new Thread(() -> respondOnce(localServer,
                    localResponse, localRequestReceived, localFailure),
                    "local-planner-loopback");
            Thread cloudResponder = new Thread(() -> respondOnce(cloudServer,
                    cloudResponse, cloudRequestReceived, cloudFailure),
                    "cloud-planner-loopback");
            localResponder.setDaemon(true);
            cloudResponder.setDaemon(true);
            localResponder.start();
            cloudResponder.start();

            LocalModelRegistry localModels = new LocalModelRegistry(context);
            localModels.configure("HTTP", "127.0.0.1", localServer.getLocalPort(),
                    "fixture-vision", "fixture-decider", 3000, 256);
            SkillEngine skills = new SkillEngine(context);
            AuditLogger audit = new AuditLogger(context);
            ModelRouter router = new ModelRouter(context, skills, audit,
                    localModels, null, null);
            String cloudEndpoint = "http://127.0.0.1:" + cloudServer.getLocalPort()
                    + "/v1/chat/completions";
            router.cloud().configure(cloudEndpoint, SYNTHETIC_KEY, "fixture-cloud-model");

            TaskSpec planned = router.plan("synthesize route fallback fixture", Collections.emptyList());

            localResponder.join(10000);
            cloudResponder.join(10000);
            assertFalse("local fixture server should finish", localResponder.isAlive());
            assertFalse("cloud fixture server should finish", cloudResponder.isAlive());
            assertNull(localFailure.get());
            assertNull(cloudFailure.get());
            assertTrue("local model must be attempted first", localRequestReceived.get());
            assertTrue("cloud planner must be called after local output is rejected", cloudRequestReceived.get());
            assertEquals("cloud_fallback_result", planned.taskId);
            assertEquals(0, router.rulePlanCount());
            assertEquals(0, router.localCallCount());
            assertEquals(1, router.cloudCallCount());
        } finally {
            context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            context.getSharedPreferences(LocalModelRegistry.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static String openAiContent(String content) throws JSONException {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("message", new JSONObject().put("content", content)))).toString();
    }

    private static void respondOnce(ServerSocket server, String body,
                                    AtomicBoolean received, AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            BufferedReader request = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) {
                // Consume headers without retaining or printing the synthetic Authorization value.
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
