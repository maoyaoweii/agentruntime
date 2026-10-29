package com.agentruntime.local;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 本地 HTTP 推理后端（OpenAI 兼容 /v1/chat/completions）。
 *
 * <p>适用场景：设备上另起一个推理进程（llama.cpp server、Ollama、自建服务），
 * Agent 通过 127.0.0.1 调用。这样做的好处是 APK 不用背 NDK 与权重文件，
 * 模型可以随时换、单独升级。
 *
 * <p>本类不依赖任何 Android API，因此可以在 JVM 单测里用真实 HTTP 服务验证协议层。
 */
public final class HttpLocalModelProvider implements LocalModelProvider {

    public static final String ID = "local-http";

    private final LocalModelConfig config;

    public HttpLocalModelProvider(LocalModelConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return config != null && config.backend == LocalModelConfig.Backend.HTTP;
    }

    @Override
    public boolean supportsVision() {
        return available();
    }

    @Override
    public LocalModelReply complete(LocalModelRequest req) {
        long start = System.currentTimeMillis();
        String model = req.model == null ? config.deciderModel : req.model;
        if (!available()) {
            return LocalModelReply.fail("PROVIDER_DISABLED", ID, 0, model);
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = buildBody(req);
            conn = (HttpURLConnection) new URL(config.chatEndpoint()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.min(3000, req.timeoutMs));
            conn.setReadTimeout(req.timeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = readAll(in);
            long elapsed = System.currentTimeMillis() - start;

            if (code < 200 || code >= 300) {
                return LocalModelReply.fail("HTTP_" + code, ID, elapsed, model);
            }
            String content = extractContent(raw);
            if (content == null || content.trim().isEmpty()) {
                return LocalModelReply.fail("EMPTY_CONTENT", ID, elapsed, model);
            }
            return LocalModelReply.ok(content, ID, elapsed, model);
        } catch (java.net.SocketTimeoutException e) {
            return LocalModelReply.fail("TIMEOUT", ID, System.currentTimeMillis() - start, model);
        } catch (IOException e) {
            return LocalModelReply.fail("IO_ERROR", ID, System.currentTimeMillis() - start, model);
        } catch (JSONException e) {
            return LocalModelReply.fail("BAD_JSON", ID, System.currentTimeMillis() - start, model);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 构造 OpenAI 兼容请求体；有图片时切换为多模态 content 数组。 */
    public JSONObject buildBody(LocalModelRequest req) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", req.model == null ? config.deciderModel : req.model);
        body.put("max_tokens", req.maxTokens);
        body.put("temperature", req.temperature);
        body.put("stream", false);

        JSONArray messages = new JSONArray();
        if (req.systemPrompt != null && !req.systemPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", req.systemPrompt));
        }
        JSONObject userMsg = new JSONObject().put("role", "user");
        if (req.hasImage()) {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", req.userPrompt == null ? "" : req.userPrompt));
            String b64 = Base64.getEncoder().encodeToString(req.imageBytes);
            String dataUrl = "data:" + req.imageMime + ";base64," + b64;
            parts.put(new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", dataUrl)));
            userMsg.put("content", parts);
        } else {
            userMsg.put("content", req.userPrompt == null ? "" : req.userPrompt);
        }
        messages.put(userMsg);
        body.put("messages", messages);
        return body;
    }

    /** 从响应中取出 choices[0].message.content；兼容 content 为数组的服务端实现。 */
    public static String extractContent(String raw) throws JSONException {
        if (raw == null || raw.trim().isEmpty()) return null;
        JSONObject root = new JSONObject(raw);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) return null;
        Object content = msg.opt("content");
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part != null && part.has("text")) sb.append(part.optString("text"));
            }
            return sb.toString();
        }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        return com.agentruntime.network.ResponseBody.read(in);
    }
}
