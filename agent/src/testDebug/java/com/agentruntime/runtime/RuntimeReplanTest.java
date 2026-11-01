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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 失败步骤必须"重规划"，不能把同一步原样再跑一遍。
 *
 * <p>旧实现把 allowReplan 关死（DSH 传了 cancellation，于是 owner!=null），
 * 失败后只会重放同样的技能与参数，失败码自然也一样。这里锁住两条：
 * 失败会进入 replan、并且不再出现第二次完全相同的动作。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RuntimeReplanTest {

    private static final String SKILL = "test.missing_entry";

    @Test
    public void failedStepIsReplannedWithEvidenceInsteadOfReplayed() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        state.rebuildSnapshot("com.android.settings", "设置", tree(), "n1", "test_fixture");
        AuditLogger audit = new AuditLogger(context);
        SkillEngine skills = new SkillEngine(context);
        register(skills);
        ActionExecutor executor = new ActionExecutor(context, state, new AccessibilityBridge(state),
                new IntentBridge(context), new RuleBasedSystem1(), new RiskGate(), audit);
        ModelRouter router = new ModelRouter(context, skills, audit, null, null, null);
        AgentRuntime runtime = new AgentRuntime(state, skills, executor, router, audit);

        TaskSpec task = new TaskSpec("replan-fixture", "1.0", "打开一个不存在的设置入口",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep(SKILL, new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(8, 1, 30));

        AgentRuntime.TaskResult result = runtime.runTask(task, false, true, null, new AtomicBoolean(false), false);

        assertEquals("FAILED", result.status);
        JSONObject replan = firstReplan(result.detail);
        assertNotNull("失败步骤没有进入重规划", replan);
        assertEquals("NODE_NOT_FOUND", replan.optString("failure_code"));
        assertEquals("com.android.settings", replan.optString("foreground"));
        assertTrue(replan.has("replan_error"));
        // 同一动作只执行一次：没有再原样重放一遍。
        assertEquals(1, countCode(result.detail, "NODE_NOT_FOUND"));
    }

    private static JSONObject firstReplan(JSONObject detail) {
        JSONArray steps = detail.optJSONArray("steps");
        if (steps == null) return null;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null && step.optBoolean("replan")) return step;
        }
        return null;
    }

    private static int countCode(JSONObject detail, String code) {
        int count = 0;
        JSONArray steps = detail.optJSONArray("steps");
        if (steps == null) return 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            JSONArray actions = step == null ? null : step.optJSONArray("actions");
            if (actions == null) continue;
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                JSONObject value = action == null ? null : action.optJSONObject("result");
                if (value != null && code.equals(value.optString("code"))) count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void register(SkillEngine skills) throws Exception {
        JSONObject manifest = new JSONObject().put("skill_id", SKILL)
                .put("steps", new JSONArray().put(new JSONObject()
                        .put("action", "tap_text")
                        .put("params", new JSONObject().put("text", "不存在的入口")
                                .put("scroll", false).put("max_scrolls", 0))
                        .put("timeout_ms", 3000)));
        Field registryField = SkillEngine.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        ((Map<String, SkillManifest>) registryField.get(skills)).put(SKILL, SkillManifest.fromJson(manifest));
    }

    private static UiNode tree() {
        UiNode wifi = new UiNode("n1", "android.widget.TextView", "WLAN", null, null,
                true, true, true, false, false, false, false, 40, 200, 600, 260, Collections.emptyList());
        return new UiNode("n0", "android.widget.FrameLayout", null, null, null,
                false, true, true, false, false, false, false, 0, 0, 1080, 2400,
                Collections.singletonList(wifi));
    }

    /** 确认门与路径限制不因这次改动被削弱：高风险动作仍然要确认。 */
    @Test
    public void riskyActionStillNeedsConfirmation() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        StateEngine state = new StateEngine();
        state.rebuildSnapshot("com.android.settings", "设置", tree(), "n1", "test_fixture");
        AuditLogger audit = new AuditLogger(context);
        SkillEngine skills = new SkillEngine(context);
        ActionExecutor executor = new ActionExecutor(context, state, new AccessibilityBridge(state),
                new IntentBridge(context), new RuleBasedSystem1(), new RiskGate(), audit);
        ActionSpec risky = new ActionSpec(ActionSpec.ActionType.TAP_TEXT,
                new JSONObject().put("text", "WLAN").put("scroll", false),
                3000, 0, ActionSpec.RiskLevel.HIGH, true, null);

        com.agentruntime.executor.ExecResult result = executor.execute("t", "a", "tap", risky,
                Collections.emptyList());

        assertTrue("未配置确认通道时高风险动作必须被拒绝", !result.ok());
        assertEquals("RISK_REJECTED", result.code);
    }
}
