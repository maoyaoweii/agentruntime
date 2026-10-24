package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class CloudPlannerAdapterTest {

    private static final String SYNTHETIC_KEY = "fake-test-key-1234";

    @Test
    public void settingsMaskKeyAndPingDoesNotEchoProviderErrorBody() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        String echoedError = "provider rejected key " + SYNTHETIC_KEY;
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(8000);
            Thread responder = new Thread(
                    () -> respondOnce(server, echoedError, serverFailure),
                    "fake-model-provider");
            responder.setDaemon(true);
            responder.start();

            String endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
            CloudPlannerAdapter adapter = new CloudPlannerAdapter(context);
            adapter.configure(endpoint, SYNTHETIC_KEY, "fixture-model-v1");

            JSONObject initial = adapter.configInfo();
            assertTrue(initial.getBoolean("api_key_set"));
            assertEquals("fake****1234", initial.getString("api_key_masked"));
            assertFalse(initial.toString().contains(SYNTHETIC_KEY));

            // Mirrors SettingsActivity: blank key/base URL are passed as null and preserve prior values.
            adapter.configure(null, null, "fixture-model-v2");
            JSONObject updated = adapter.configInfo();
            assertEquals(endpoint, updated.getString("base_url"));
            assertEquals("fixture-model-v2", updated.getString("model"));
            assertEquals("fake****1234", updated.getString("api_key_masked"));
            assertFalse(updated.toString().contains(SYNTHETIC_KEY));

            FutureTask<JSONObject> request = new FutureTask<>(adapter::ping);
            Thread client = new Thread(request, "model-ping-test-client");
            client.setDaemon(true);
            client.start();
            JSONObject result = request.get(10, TimeUnit.SECONDS);
            responder.join(10000);

            assertFalse("fake provider should finish", responder.isAlive());
            assertNull(serverFailure.get());
            assertEquals(401, result.getInt("http_code"));
            assertEquals("HTTP 401", result.getString("error"));
            assertFalse(result.toString().contains(SYNTHETIC_KEY));
            assertFalse(result.toString().contains(echoedError));
        } finally {
            context.getSharedPreferences(CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static void respondOnce(ServerSocket server, String body,
                                    AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            BufferedReader request = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) {
                // Consume headers only; the test never records or prints the Authorization value.
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 401 Unauthorized\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
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
