package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class CloudPlannerPlanConnectionCleanupTest {

    @Test(timeout = 15000)
    public void malformedCloudResponseIsRejectedOnServedRequest() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences(
                CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE);
        boolean hadUrl = prefs.contains(CloudPlannerAdapter.KEY_BASE_URL);
        String oldUrl = prefs.getString(CloudPlannerAdapter.KEY_BASE_URL, null);
        boolean hadKey = prefs.contains(CloudPlannerAdapter.KEY_API_KEY);
        String oldKey = prefs.getString(CloudPlannerAdapter.KEY_API_KEY, null);
        boolean hadModel = prefs.contains(CloudPlannerAdapter.KEY_MODEL);
        String oldModel = prefs.getString(CloudPlannerAdapter.KEY_MODEL, null);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicBoolean responseSent = new AtomicBoolean();
        Thread responder = null;
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(6000);
            responder = new Thread(() -> respondOnce(server, responseSent, serverFailure),
                    "cloud-planner-cleanup-fixture");
            responder.setDaemon(true);
            responder.start();

            CloudPlannerAdapter adapter = new CloudPlannerAdapter(context);
            adapter.configure("http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions",
                    "synthetic-test-key", "fixture-model");
            try {
                adapter.plan("synthetic-goal", Collections.emptyList(), Collections.emptyList());
                fail("malformed response should be rejected");
            } catch (JSONException expected) {
                // The invalid choices array is intentional; the request has no valid plan.
            }

            responder.join(7000);
            assertFalse("loopback responder should complete", responder.isAlive());
            assertNull("loopback fixture must not fail", serverFailure.get());
            assertTrue("server should receive and answer the planner request", responseSent.get());
        } finally {
            if (responder != null && responder.isAlive()) responder.interrupt();
            SharedPreferences.Editor editor = prefs.edit();
            restore(editor, CloudPlannerAdapter.KEY_BASE_URL, hadUrl, oldUrl);
            restore(editor, CloudPlannerAdapter.KEY_API_KEY, hadKey, oldKey);
            restore(editor, CloudPlannerAdapter.KEY_MODEL, hadModel, oldModel);
            editor.commit();
        }
    }

    private static void respondOnce(ServerSocket server, AtomicBoolean responseSent,
                                   AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            InputStream input = socket.getInputStream();
            int contentLength = readContentLength(input);
            discardExactly(input, contentLength);

            byte[] payload = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
            responseSent.set(true);
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private static int readContentLength(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        int[] terminator = {'\r', '\n', '\r', '\n'};
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) throw new IOException("incomplete request headers");
            bytes.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == terminator.length) break;
        }
        if (matched != terminator.length) throw new IOException("request headers too large");
        String headers = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        throw new IOException("missing content length");
    }

    private static void discardExactly(InputStream input, int remaining) throws IOException {
        byte[] buffer = new byte[1024];
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("incomplete request body");
            remaining -= count;
        }
    }

    private static void restore(SharedPreferences.Editor editor, String key,
                                boolean previouslyPresent, String previousValue) {
        if (previouslyPresent) editor.putString(key, previousValue);
        else editor.remove(key);
    }
}
