package com.agentruntime.runtime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 观察内容"给模型"和"落库"必须走两条路。
 *
 * <p>{@code includeObservations=true} 是 DSH 的 run_skill 链路：模型必须看到屏文字才能决策；
 * 默认的 false 是任务/审计链路：结果里不能出现屏内容。这两条都不能被后来的改动悄悄合并。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ObservationVisibilityTest {

    private static final String SKILL_ID = "test.read_screen_private";
    private static final String SCREEN_TEXT = "热门-合成标记-不可外泄";

    @Test
    public void toolCallPathCarriesScreenText() throws Exception {
        AgentRuntime.TaskResult result = run(true);

        assertEquals("SUCCESS", result.status);
        String serialized = result.detail.toString();
        assertTrue(serialized.contains(SCREEN_TEXT));
        assertTrue(serialized.contains("SCREEN_TEXT"));
    }

    @Test
    public void taskAndAuditPathStillHidesScreenText() throws Exception {
        AgentRuntime.TaskResult result = run(false);

        assertEquals("SUCCESS", result.status);
        assertFalse(result.detail.toString().contains(SCREEN_TEXT));
    }

    private static AgentRuntime.TaskResult run(boolean includeObservations) throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        state.rebuildSnapshot("tv.danmaku.bili", "B站", tree(), "n2", "test_fixture");

        AuditLogger audit = new AuditLogger(context);
        SkillEngine skills = new SkillEngine(context);
        ActionExecutor executor = new ActionExecutor(context, state, new AccessibilityBridge(state),
                new IntentBridge(context), new RuleBasedSystem1(), new RiskGate(), audit);

        ActionSpec readScreen = new ActionSpec(ActionSpec.ActionType.READ_SCREEN,
                new JSONObject().put("max_chars", 4000), 3000, 0,
                ActionSpec.RiskLevel.LOW, false, null);
        JSONObject manifestJson = new JSONObject().put("skill_id", SKILL_ID)
                .put("steps", new JSONArray().put(readScreen.toJson()));
        register(skills, SKILL_ID, SkillManifest.fromJson(manifestJson));

        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);
        TaskSpec task = new TaskSpec("observe-visibility", "1.0", "read the screen",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL_ID, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        return runtime.runTask(task, false, false, null, new AtomicBoolean(false), includeObservations);
    }

    @SuppressWarnings("unchecked")
    private static void register(SkillEngine skills, String id, SkillManifest manifest) throws Exception {
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        ((Map<String, SkillManifest>) registryField.get(skills)).put(id, manifest);
    }

    private static UiNode tree() {
        UiNode hot = new UiNode("n2", "android.widget.Button", SCREEN_TEXT, null, null,
                true, true, true, false, false, false, false, 10, 20, 110, 70,
                Collections.emptyList());
        return new UiNode("n1", "android.widget.FrameLayout", null, null, null,
                false, true, true, false, false, false, false, 0, 0, 1080, 2400,
                Collections.singletonList(hot));
    }
}
