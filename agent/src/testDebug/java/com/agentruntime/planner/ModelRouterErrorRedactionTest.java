package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.skill.SkillEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ModelRouterErrorRedactionTest {

    private static final String SYNTHETIC_KEY = "synthetic-model-key-redaction-fixture";

    @Test(timeout = 15000)
    public void malformedProviderOutputDoesNotPersistOrReturnEchoedKey() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicBoolean requestReceived = new AtomicBoolean();

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(8000);
            String malformedContent = "{\"api_key\":\"" + SYNTHETIC_KEY + "\",\"broken\":}";
            String body = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("message", new JSONObject().put("content", malformedContent)))).toString();
            Thread responder = new Thread(() -> respondOnce(server, body, requestReceived, serverFailure),
                    "model-router-redaction-fixture");
            responder.setDaemon(true);
            responder.start();

            SkillEngine skills = new SkillEngine(context);
            AuditLogger audit = new AuditLogger(context);
            File auditFile = audit.currentFile();
            long auditLengthBefore = auditFile.exists() ? auditFile.length() : 0L;
            ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
            String endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
            router.cloud().configure(endpoint, SYNTHETIC_KEY, "fixture-redaction-model");

            JSONException planFailure = null;
            try {
                router.plan("opaque planner redaction probe", Collections.emptyList());
            } catch (JSONException expected) {
                planFailure = expected;
            }
            responder.join(10000);

            assertTrue("fixture should receive a planner request", requestReceived.get());
            assertNull(serverFailure.get());
            assertNotNull("malformed model output should be rejected", planFailure);
            assertFalse(planFailure.getMessage().contains(SYNTHETIC_KEY));
            String appendedAudit = readFrom(auditFile, auditLengthBefore);
            assertFalse(appendedAudit.contains(SYNTHETIC_KEY));
        } finally {
            context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static void respondOnce(ServerSocket server, String body,
                                    AtomicBoolean received, AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(8000);
            BufferedReader request = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) {
                // Consume request headers without retaining or printing Authorization.
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
