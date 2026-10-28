package com.agentruntime.skill;

import android.app.Application;

import com.agentruntime.dsl.ActionSpec;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 回归：模板不能在 manifest 加载期被最终 DSL 校验误伤，但 resolve 必须严格校验最终值。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SkillLoadingRegressionTest {
    private SkillEngine engine() {
        Application app = RuntimeEnvironment.getApplication();
        return new SkillEngine(app);
    }

    @Test
    public void allBuiltinSkillsLoadAndNoSilentLoadErrors() throws Exception {
        SkillEngine e = engine();
        // 数量必须与 assets/skills 下的 json 个数一致：少了说明有技能静默加载失败。
        // 用资产清单做基准而不是写死数字，避免每加一个技能都要改一次断言。
        int assetCount = RuntimeEnvironment.getApplication().getAssets().list("skills").length;
        assertEquals("assets/skills 有 " + assetCount + " 个文件，实际加载 " + e.skillIds().size(),
                assetCount, e.skillIds().size());
        assertTrue("load errors: " + e.loadErrors(), e.loadErrors().isEmpty());
    }

    @Test
    public void httpsVariableResolvesAfterManifestLoad() throws Exception {
        List<ActionSpec> actions = engine().resolve("web.open_and_read",
                new JSONObject().put("url", "https://example.com/page").put("max_chars", 1200));
        assertEquals(ActionSpec.ActionType.NAVIGATE, actions.get(0).type);
        assertEquals("https://example.com/page", actions.get(0).params.getString("url"));
    }

    @Test
    public void manifestRiskPropagatesWithoutDowngradingExplicitStepRisk() throws Exception {
        List<ActionSpec> sendActions = engine().resolve("agent_test.send_text",
                new JSONObject().put("text", "synthetic confirmation fixture"));
        assertFalse(sendActions.isEmpty());
        for (ActionSpec action : sendActions) {
            assertEquals(ActionSpec.RiskLevel.HIGH, action.risk);
            assertTrue(action.requiresConfirmation);
        }

        SkillManifest explicitHigh = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.explicit_high_step")
                .put("risk", "LOW")
                .put("steps", new org.json.JSONArray().put(new JSONObject()
                        .put("action", "wait_ms")
                        .put("params", new JSONObject().put("ms", 0))
                        .put("risk", "HIGH"))));
        List<ActionSpec> explicitActions = engine().resolveManifest(explicitHigh, new JSONObject());
        assertEquals(ActionSpec.RiskLevel.HIGH, explicitActions.get(0).risk);
    }

    @Test
    public void substitutedDangerousSchemesRemainRejected() throws Exception {
        for (String value : new String[]{"javascript:alert(1)", "file:///etc/passwd"}) {
            try {
                engine().resolve("web.open_and_read", new JSONObject().put("url", value).put("max_chars", 1200));
                fail("dangerous URL must be rejected: " + value);
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("http/https"));
            }
        }
    }

    @Test
    public void tapTextSkillResolvesToSingleGestureAction() throws Exception {
        List<ActionSpec> actions = engine().resolve("sys.tap_text", new JSONObject().put("text", "Settings"));
        assertEquals(1, actions.size());
        assertEquals(ActionSpec.ActionType.TAP_TEXT, actions.get(0).type);
        assertEquals("Settings", actions.get(0).params.getString("text"));
        assertTrue(actions.get(0).params.optBoolean("scroll", false));
    }

    @Test
    public void tapTextRejectsEmptyTextAndOutOfRangeScroll() {
        try {
            com.agentruntime.dsl.DslParser.parseAction(new JSONObject()
                    .put("action", "tap_text").put("params", new JSONObject()));
            fail("tap_text without params.text must be rejected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("tap_text requires"));
        }
        try {
            com.agentruntime.dsl.DslParser.parseAction(new JSONObject()
                    .put("action", "tap_text")
                    .put("params", new JSONObject().put("text", "Settings").put("max_scrolls", 9)));
            fail("tap_text max_scrolls out of range must be rejected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("max_scrolls"));
        }
    }

    @Test
    public void manifestTemplateIsNotPrevalidatedAsFinalUrl() throws Exception {
        SkillManifest m = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.template")
                .put("args", new org.json.JSONArray().put("url"))
                .put("steps", new org.json.JSONArray().put(new JSONObject()
                        .put("action", "navigate").put("params", new JSONObject().put("url", "${url}")))));
        assertEquals(1, m.stepTemplates().size());
        assertFalse(m.stepTemplates().isEmpty());
    }

    @Test
    public void malformedAndUndeclaredTemplatesFailClearly() throws Exception {
        SkillManifest malformed = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.bad-template").put("args", new org.json.JSONArray().put("url"))
                .put("steps", new org.json.JSONArray().put(new JSONObject().put("action", "navigate")
                        .put("params", new JSONObject().put("url", "https://${url")))));
        try {
            engine().resolveManifest(malformed, new JSONObject().put("url", "example.com"));
            fail("malformed template must fail");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("malformed"));
        }

        SkillManifest undeclared = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.undeclared").put("args", new org.json.JSONArray().put("url"))
                .put("steps", new org.json.JSONArray().put(new JSONObject().put("action", "navigate")
                        .put("params", new JSONObject().put("url", "https://${host}")))));
        try {
            engine().resolveManifest(undeclared, new JSONObject().put("url", "example.com").put("host", "example.com"));
            fail("undeclared variable must fail");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("undeclared"));
        }
    }
}
