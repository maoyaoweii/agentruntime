package com.agentruntime.dsh;

import android.app.Application;

import com.agentruntime.dsh.model.Cancellation;
import com.agentruntime.dsh.model.ModelTransport;
import com.agentruntime.dsh.model.ProviderConfig;
import com.agentruntime.dsh.plugin.MobilePlugin;
import com.agentruntime.dsh.plugin.PluginRegistry;
import com.agentruntime.dsh.runtime.DshRuntime;
import com.agentruntime.dsh.store.SessionStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 目标未核验不得完成；同一条回复里的屏幕动作只能执行第一个。
 *
 * <p>真机上的两种典型失败：模型只写一段"我做不到"就结束，系统当成成功；
 * 或者一次回复里连发读屏和点击，点在还没看见的界面上。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DshGoalVerificationTest {

    private static final DshRuntime.Listener QUIET = new DshRuntime.Listener() {
        public void status(String s) { }
        public void delta(String s) { }
        public void settled() { }
    };
    private final ProviderConfig.Snapshot provider =
            new ProviderConfig.Snapshot("https://api.deepseek.com", "fixture", "synthetic-key", true);

    private static JSONObject reply() throws Exception {
        return new JSONObject().put("role", "assistant").put("content", "已完成");
    }

    private static JSONObject runSkillCall(String id, String skill, String argsJson) throws Exception {
        JSONObject args = new JSONObject().put("skill_id", skill).put("args", new JSONObject(argsJson));
        return new JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                        .put("function", new JSONObject().put("name", "run_skill").put("arguments", args.toString()))));
    }

    private PluginRegistry registry(AtomicInteger reads, AtomicInteger taps) throws Exception {
        PluginRegistry registry = new PluginRegistry(
                RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), 0),
                (s, t) -> true);
        registry.register(new MobilePlugin() {
            public String id() { return "phone-skills"; }
            public String title() { return "phone"; }
            public String description() { return "phone"; }
            public List<Tool> tools() {
                try {
                    return Collections.singletonList(new Tool("run_skill", "phone",
                            PluginRegistry.schema(new JSONObject()
                                    .put("skill_id", PluginRegistry.field("string", "skill"))
                                    .put("args", PluginRegistry.field("object", "args")), "skill_id", "args"),
                            false, true, (a, t) -> {
                                if ("sys.read_screen".equals(a.getString("skill_id"))) {
                                    reads.incrementAndGet();
                                    return new JSONObject().put("ok", true).put("code", "SCREEN_TEXT")
                                            .put("output", "screen-1");
                                }
                                taps.incrementAndGet();
                                return new JSONObject().put("ok", true).put("code", "TAP_OK").put("output", "tapped");
                            }));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        });
        return registry;
    }

    @Test
    public void textOnlyReplyWithoutEvidenceIsNotReportedAsSuccess() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        PluginRegistry registry = registry(new AtomicInteger(), new AtomicInteger());
        ModelTransport transport = (p, m, t, c, d) -> {
            requests.incrementAndGet();
            return reply();
        };
        try (SessionStore store = new SessionStore(RuntimeEnvironment.getApplication())) {
            String session = store.createSession().id;
            String turn = new DshRuntime(store, transport).run(session, "打开设置", true, provider,
                    registry.snapshot(true), new Cancellation(5000), QUIET);

            assertEquals("failed", store.turnState(turn));
            assertEquals("GOAL_UNVERIFIED", store.turnCode(turn));
            // 第一次纯文字被驳回，内部续跑一次；第二次仍无工具才结束。
            assertEquals(2, requests.get());
            assertTrue(store.events(session).stream().anyMatch(e -> e.type.equals("runtime_nudge")));
        }
    }

    @Test
    public void onlyTheFirstScreenToolInOneReplyRuns() throws Exception {
        AtomicInteger reads = new AtomicInteger(), taps = new AtomicInteger(), requests = new AtomicInteger();
        PluginRegistry registry = registry(reads, taps);
        ModelTransport transport = (p, m, t, c, d) -> {
            int n = requests.getAndIncrement();
            if (n > 0) return reply();
            JSONObject answer = runSkillCall("call_0", "sys.read_screen", "{}");
            answer.getJSONArray("tool_calls").put(runSkillCall("call_1", "sys.tap_text", "{\"text\":\"设置\"}")
                    .getJSONArray("tool_calls").getJSONObject(0));
            return answer;
        };
        try (SessionStore store = new SessionStore(RuntimeEnvironment.getApplication())) {
            String session = store.createSession().id;
            new DshRuntime(store, transport).run(session, "点设置", true, provider,
                    registry.snapshot(true), new Cancellation(5000), QUIET);

            assertEquals(1, reads.get());
            assertEquals(0, taps.get());
            assertTrue(store.events(session).stream()
                    .filter(e -> e.type.equals("tool_result"))
                    .anyMatch(e -> e.payload.optString("content").contains("SKIPPED_DEPENDS_ON_UNSEEN_RESULT")));
        }
    }

    @Test
    public void chatModeMayEndWithTextOnly() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        PluginRegistry registry = registry(new AtomicInteger(), new AtomicInteger());
        ModelTransport transport = (p, m, t, c, d) -> {
            requests.incrementAndGet();
            return reply();
        };
        try (SessionStore store = new SessionStore(RuntimeEnvironment.getApplication())) {
            String session = store.createSession().id;
            String turn = new DshRuntime(store, transport).run(session, "你好", false, provider,
                    registry.snapshot(false), new Cancellation(5000), QUIET);

            assertEquals("completed", store.turnState(turn));
            assertEquals(1, requests.get());
            assertFalse(store.turnCode(turn).equals("GOAL_UNVERIFIED"));
        }
    }
}
