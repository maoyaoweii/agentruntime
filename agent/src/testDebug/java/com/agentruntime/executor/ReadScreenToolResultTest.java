package com.agentruntime.executor;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真机反馈的回归：模型能点、能截屏，却读不到任何界面文字。
 *
 * <p>根因有两层：一是根本没有"读屏"技能（sys.observe 只报电量网络），
 * 二是就算动作拿到了 detail，step log 走的 toLogJson() 也会把它丢掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ReadScreenToolResultTest {

    @Test
    public void readScreenReturnsVisibleTextAndCoordinates() throws Exception {
        StateEngine state = new StateEngine();
        state.rebuildSnapshot("tv.danmaku.bili", "B站", tree(), "n2", "test_fixture");

        ExecResult result = executor(state).execute("read-screen", "s1", "read screen",
                readScreenSpec(), Collections.emptyList());

        assertEquals("SCREEN_TEXT", result.code);
        assertTrue(result.detail.contains("package=tv.danmaku.bili"));
        assertTrue(result.detail.contains("热门"));
        assertTrue(result.detail.contains("可点"));
        assertTrue(result.detail.contains("@10,20-110,70"));
        assertTrue(result.detail.contains("输入框"));
        assertTrue(result.detail.contains("已聚焦"));
    }

    @Test
    public void readScreenFailsLoudlyWhenAccessibilityCannotSee() throws Exception {
        StateEngine state = new StateEngine();
        state.rebuildSnapshot(null, null, null, null, "test_fixture");

        ExecResult result = executor(state).execute("read-screen", "s2", "read screen",
                readScreenSpec(), Collections.emptyList());

        assertEquals("A11Y_ROOT_UNAVAILABLE", result.code);
        assertFalse(result.ok());
        assertTrue(result.detail.contains("无障碍"));
    }

    @Test
    public void toolResultKeepsDetailButLogsDropIt() throws Exception {
        String screen = repeat("界面文字", 60);
        ExecResult result = ExecResult.ok("SCREEN_TEXT", screen, 7, 12);

        JSONObject tool = result.toToolJson(6000);
        assertEquals(screen, tool.getString("detail"));
        assertEquals("SCREEN_TEXT", tool.getString("code"));
        assertFalse(tool.has("detail_truncated"));

        // 审计/落库路径必须仍然没有正文
        assertFalse(result.toLogJson().has("detail"));
    }

    @Test
    public void oversizedToolDetailIsTruncatedNotDropped() throws Exception {
        ExecResult result = ExecResult.ok("PAGE_TEXT", repeat("x", 500), 1, 1);

        JSONObject tool = result.toToolJson(100);

        assertEquals(100, tool.getString("detail").length());
        assertTrue(tool.getBoolean("detail_truncated"));
        assertEquals(500, tool.getInt("detail_length"));
    }

    @Test
    public void typeTextWithoutInputFieldSaysSo() throws Exception {
        StateEngine state = new StateEngine();
        state.rebuildSnapshot("com.tencent.mm", "微信", tree(), null, "test_fixture");

        // tree() 里有一个可输入框，会被当作兜底目标；服务未连接所以落到 INPUT_FAILED，
        // 关键是"找到了输入框"，而不是报 NO_INPUT_FIELD。
        ExecResult result = executor(state).execute("type", "s3", "type",
                new ActionSpec(ActionSpec.ActionType.INPUT_FOCUSED,
                        new JSONObject().put("text", "你好"), 5000, 0,
                        ActionSpec.RiskLevel.MEDIUM, false, null),
                Collections.emptyList());

        assertEquals("INPUT_FAILED", result.code);
    }

    @Test
    public void readScreenRefusesToReadOurOwnChatWindow() throws Exception {
        StateEngine state = new StateEngine();
        state.rebuildSnapshot(RuntimeEnvironment.getApplication().getPackageName(),
                "DSH", tree(), "n2", "test_fixture");

        ExecResult result = executor(state).execute("read-screen", "s4", "read screen",
                readScreenSpec(), Collections.emptyList());

        assertEquals("SELF_WINDOW_FOCUSED", result.code);
        assertFalse(result.ok());
    }

    @Test
    public void swipeIsRefusedWhenOurOwnWindowIsInFront() throws Exception {
        StateEngine state = new StateEngine();
        state.rebuildSnapshot(RuntimeEnvironment.getApplication().getPackageName(),
                "DSH", tree(), "n2", "test_fixture");

        ExecResult result = executor(state).execute("swipe", "s5", "swipe",
                new ActionSpec(ActionSpec.ActionType.SWIPE,
                        new JSONObject().put("direction", "up"), 3000, 0,
                        ActionSpec.RiskLevel.LOW, false, null),
                Collections.emptyList());

        // 真机上的"滑动已经生效"其实滑的是自己的聊天记录，这里必须拦住。
        assertEquals("SELF_WINDOW_FOCUSED", result.code);
    }

    @Test
    public void readScreenSkillIsRegistered() throws Exception {
        SkillEngine skills = new SkillEngine(RuntimeEnvironment.getApplication());

        assertTrue(skills.hasSkill("sys.read_screen"));
        assertTrue(skills.hasSkill("sys.type_text"));
        assertEquals(Collections.emptyList(), skills.manifest("sys.read_screen").requiredArgs);
        assertEquals(Collections.singletonList("text"), skills.manifest("sys.type_text").requiredArgs);
    }

    private static ActionSpec readScreenSpec() throws Exception {
        return new ActionSpec(ActionSpec.ActionType.READ_SCREEN,
                new JSONObject().put("max_chars", 4000), 3000, 0, ActionSpec.RiskLevel.LOW, false, null);
    }

    private static ActionExecutor executor(StateEngine state) {
        Application context = RuntimeEnvironment.getApplication();
        return new ActionExecutor(context, state, new AccessibilityBridge(state),
                new IntentBridge(context), new RuleBasedSystem1(), new RiskGate(),
                new AuditLogger(context));
    }

    private static UiNode tree() {
        UiNode hot = new UiNode("n2", "android.widget.Button", "热门", null, null,
                true, true, true, false, false, false, false, 10, 20, 110, 70,
                Collections.emptyList());
        UiNode search = new UiNode("n3", "android.widget.EditText", null, "搜索视频", null,
                true, true, true, true, true, false, true, 10, 80, 110, 130,
                Collections.emptyList());
        UiNode list = new UiNode("n4", "androidx.recyclerview.widget.RecyclerView", null, null, null,
                false, true, true, false, false, true, false, 0, 140, 1080, 2000,
                Collections.emptyList());
        return new UiNode("n1", "android.widget.FrameLayout", null, null, null,
                false, true, true, false, false, false, false, 0, 0, 1080, 2400,
                Arrays.asList(hot, search, list));
    }

    private static String repeat(String value, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }
}
