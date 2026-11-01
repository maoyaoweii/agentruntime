package com.agentruntime.dsh.plugin;

import android.app.Application;

import com.agentruntime.dsh.model.Cancellation;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 超长结果必须"截断"，不能"整段丢掉"。
 *
 * <p>网页正文和读屏结果经常超过 32KB。旧实现直接返回 data_omitted，
 * 模型看到的是一句"工具已返回，详细结果超过上限"——等于什么都没拿到。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class PluginRegistryClampTest {

    private static PluginRegistry registryWith(MobilePlugin.Handler handler) throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        PluginRegistry registry = new PluginRegistry(
                context.getSharedPreferences("clamp-test", 0), (summary, token) -> true);
        registry.register(new MobilePlugin() {
            @Override public String id() { return "clamp"; }
            @Override public String title() { return "clamp"; }
            @Override public String description() { return "clamp"; }
            @Override public java.util.List<Tool> tools() {
                return Collections.singletonList(new Tool("big", "big", schema(), false, false, handler));
            }
        });
        return registry;
    }

    private static JSONObject schema() {
        try {
            return PluginRegistry.schema(new JSONObject());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void oversizedResultKeepsHeadAndTailInsteadOfDroppingIt() throws Exception {
        StringBuilder body = new StringBuilder();
        while (body.length() < 40000) body.append("第").append(body.length()).append("行内容；");
        String huge = body.toString();

        PluginRegistry registry = registryWith((args, token) ->
                new JSONObject().put("ok", true).put("code", "SCREEN_TEXT").put("output", huge));

        JSONObject result = registry.snapshot(true).invoke("big", new JSONObject(), new Cancellation(5000));

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("truncated"));
        assertFalse(result.has("data_omitted"));
        String output = result.getString("output");
        assertTrue(output.startsWith(huge.substring(0, 40)));
        assertTrue(output.endsWith(huge.substring(huge.length() - 40)));
        assertTrue(output.length() < 25000);
        assertTrue(output.contains("中间已省略"));
    }

    @Test
    public void smallResultPassesThroughUntouched() throws Exception {
        PluginRegistry registry = registryWith((args, token) ->
                new JSONObject().put("ok", true).put("code", "SCREEN_TEXT")
                        .put("output", "「热门」 可点 @120,340-480,400"));

        JSONObject result = registry.snapshot(true).invoke("big", new JSONObject(), new Cancellation(5000));

        assertEquals("「热门」 可点 @120,340-480,400", result.getString("output"));
        assertFalse(result.has("truncated"));
    }
}
