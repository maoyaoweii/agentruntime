package com.agentruntime.dsh.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 默认值里不能出现私有代理的名字；DeepSeek 协议必须保留 reasoning_content。
 *
 * <p>模型地址与模型名只能由用户在设置里填写后生效。把私货写进 DEFAULT_*
 * 会让所有没配置过的用户直接把请求打到别人的代理上。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProviderDefaultsTest {

    @Test
    public void defaultModelIsNotAPrivateProxyName() {
        assertNotEquals("deepseekv4.1flash1017", ProviderConfig.DEFAULT_MODEL);
        assertNotEquals("deepseek-ai/DeepSeek-V4.1-Flash-1017", ProviderConfig.DEFAULT_MODEL);
        assertFalse(ProviderConfig.DEFAULT_MODEL.contains("river.ai"));
        assertFalse(ProviderConfig.DEFAULT_URL.contains("river.ai"));
    }

    @Test
    public void deepSeekProtocolKeepsReasoningContentAcrossToolTurns() throws Exception {
        ProviderConfig.Snapshot deepSeek =
                new ProviderConfig.Snapshot("https://api.river.ai/v1", "deepseek-ai/DeepSeek-V4.1-Flash-1017",
                        "synthetic-key", false, 1);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "assistant")
                .put("content", "").put("reasoning_content", "opaque thoughts"));
        JSONObject body = ChatProtocol.request(deepSeek, messages, new JSONArray());

        assertEquals("opaque thoughts",
                body.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertTrue(body.getBoolean("stream"));
    }

    @Test
    public void openAiProtocolDropsReasoningContent() throws Exception {
        ProviderConfig.Snapshot openAi =
                new ProviderConfig.Snapshot("https://api.example.com/v1", "fixture", "synthetic-key", false, 2);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "assistant")
                .put("content", "").put("reasoning_content", "opaque thoughts"));

        JSONObject body = ChatProtocol.request(openAi, messages, new JSONArray());

        assertFalse(body.getJSONArray("messages").getJSONObject(0).has("reasoning_content"));
    }

    @Test
    public void providerFinishReasonIsCarriedInTheErrorCode() {
        try {
            ChatProtocol.validate(new JSONObject().put("role", "assistant").put("content", "partial"),
                    "content_filter");
            throw new AssertionError("应当拒绝非 stop/tool_calls 的结束原因");
        } catch (Exception e) {
            assertEquals("MODEL_OUTPUT_INCOMPLETE:content_filter", e.getMessage());
        }
    }
}
