package com.agentruntime;

import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.system1.RuleBasedSystem1;
import com.agentruntime.system1.System1;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentCoreTest {

    private static final String TASK_JSON = "{"
            + "\"task_version\":\"1.0\","
            + "\"task_id\":\"send_test_document\","
            + "\"goal\":\"发送 report.pdf\","
            + "\"allowed_apps\":[\"com.agenttestapp\"],"
            + "\"preconditions\":[\"test_account_ready\"],"
            + "\"steps\":["
            + " {\"skill\":\"agent_test.send_file\",\"args\":{\"contact_alias\":\"TestUser\",\"file_alias\":\"report.pdf\"},\"confirmation\":\"before_external_send\"}"
            + "],"
            + "\"postconditions\":[\"file_message_visible\"],"
            + "\"limits\":{\"max_steps\":24,\"max_replans\":2,\"timeout_seconds\":180}"
            + "}";

    @Test
    public void parseValidTask() throws Exception {
        TaskSpec t = DslParser.parseTask(TASK_JSON);
        assertEquals("send_test_document", t.taskId);
        assertEquals(1, t.steps.size());
        assertEquals("agent_test.send_file", t.steps.get(0).skillId);
        assertEquals(24, t.limits.maxSteps);
    }

    @Test
    public void rejectUnknownActionType() {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"exec_shell\",\"params\":{}}"));
            fail("should reject non-whitelisted action");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("whitelist"));
        }
    }

    @Test
    public void rejectTaskWithoutSteps() throws Exception {
        JSONObject bad = new JSONObject(TASK_JSON).put("steps", new org.json.JSONArray());
        assertFalse(DslParser.validateTaskJson(bad).ok);
    }

    @Test
    public void rejectUnknownTaskKey() throws Exception {
        JSONObject bad = new JSONObject(TASK_JSON).put("evil_payload", "x");
        assertFalse(DslParser.validateTaskJson(bad).ok);
    }

    @Test
    public void taskLimitsRejectUnboundedRetriesAndTimeouts() throws Exception {
        for (int invalidReplans : new int[]{-1, 6}) {
            JSONObject task = new JSONObject(TASK_JSON);
            task.getJSONObject("limits").put("max_replans", invalidReplans);
            assertFalse("max_replans=" + invalidReplans,
                    DslParser.validateTaskJson(task).ok);
        }
        for (long invalidTimeout : new long[]{0, 3601, Long.MAX_VALUE}) {
            JSONObject task = new JSONObject(TASK_JSON);
            task.getJSONObject("limits").put("timeout_seconds", invalidTimeout);
            assertFalse("timeout_seconds=" + invalidTimeout,
                    DslParser.validateTaskJson(task).ok);
        }
    }

    @Test
    public void taskLimitsAcceptZeroReplansAndLongestSupportedTimeout() throws Exception {
        JSONObject task = new JSONObject(TASK_JSON);
        task.getJSONObject("limits")
                .put("max_steps", 64)
                .put("max_replans", 0)
                .put("timeout_seconds", 3600);

        assertTrue(DslParser.validateTaskJson(task).ok);
        TaskSpec parsed = DslParser.parseTask(task.toString());
        assertEquals(64, parsed.limits.maxSteps);
        assertEquals(0, parsed.limits.maxReplans);
        assertEquals(3600L, parsed.limits.timeoutSeconds);
    }

    @Test
    public void system1PicksExactLabel() {
        RuleBasedSystem1 s1 = new RuleBasedSystem1();
        List<System1.Candidate> candidates = Arrays.asList(
                new System1.Candidate("c0", "n1", "图片"),
                new System1.Candidate("c1", "n2", "文件"),
                new System1.Candidate("c2", "n3", "返回"));
        System1.Decision d = s1.decide("文件", null, candidates);
        assertEquals("c1", d.choiceId);
        assertTrue(d.confidence >= RuleBasedSystem1.LOW_CONFIDENCE);
    }

    @Test
    public void system1PrefersShorterLabelOnPartialMatch() {
        RuleBasedSystem1 s1 = new RuleBasedSystem1();
        List<System1.Candidate> candidates = Arrays.asList(
                new System1.Candidate("c0", "n1", "确认发送"),
                new System1.Candidate("c1", "n2", "确认发送文件"));
        System1.Decision d = s1.decide("发送", null, candidates);
        assertEquals("c0", d.choiceId);
    }

    @Test
    public void system1PicksExactUser() {
        RuleBasedSystem1 s1 = new RuleBasedSystem1();
        List<System1.Candidate> candidates = Arrays.asList(
                new System1.Candidate("c0", "n1", "TestUser"),
                new System1.Candidate("c1", "n2", "TestUser2"));
        System1.Decision d = s1.decide("TestUser", null, candidates);
        assertEquals("c0", d.choiceId);
    }

    @Test
    public void actionSpecRoundTrip() throws Exception {
        ActionSpec a = DslParser.parseAction(new JSONObject(
                "{\"action\":\"click_node\",\"params\":{\"find\":{\"resource_id\":\"btn\"}},\"timeout_ms\":5000}"));
        assertEquals(ActionSpec.ActionType.CLICK_NODE, a.type);
        assertEquals("click_node", a.toJson().getString("action"));
    }

    @Test
    public void newToolActionsParse() throws Exception {
        assertEquals(ActionSpec.ActionType.PRESS_KEY, DslParser.parseAction(
                new JSONObject("{\"action\":\"press_key\",\"params\":{\"key\":\"home\"}}")).type);
        assertEquals(ActionSpec.ActionType.LONG_CLICK, DslParser.parseAction(
                new JSONObject("{\"action\":\"long_click\",\"params\":{\"find\":{\"resource_id\":\"item\"}}}")).type);
        assertEquals(ActionSpec.ActionType.CLEAR_TEXT, DslParser.parseAction(
                new JSONObject("{\"action\":\"clear_text\",\"params\":{\"find\":{\"editable\":true}}}")).type);
        assertEquals(ActionSpec.ActionType.WAIT_MS, DslParser.parseAction(
                new JSONObject("{\"action\":\"wait_ms\",\"params\":{\"ms\":500}}")).type);
        assertEquals(ActionSpec.ActionType.CLOSE_SYSTEM_DIALOGS, DslParser.parseAction(
                new JSONObject("{\"action\":\"close_system_dialogs\",\"params\":{}}")).type);
    }

    @Test
    public void pressKeyRejectsUnknownKey() {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"press_key\",\"params\":{\"key\":\"format_disk\"}}"));
            fail("should reject non-whitelisted key");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("whitelist"));
        }
    }

    @Test
    public void waitMsRejectsOutOfRange() {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"wait_ms\",\"params\":{\"ms\":999999}}"));
            fail("should reject out-of-range wait_ms");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("range"));
        }
    }

    @Test
    public void longClickRequiresFind() {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"long_click\",\"params\":{}}"));
            fail("should require find spec");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("find"));
        }
    }

    @Test
    public void negativeConditionsEvaluate() {
        com.agentruntime.executor.ConditionChecker cc = new com.agentruntime.executor.ConditionChecker(null);
        com.agentruntime.state.UiNode leaf = new com.agentruntime.state.UiNode(
                "n0", "android.widget.TextView", "hello", null, null,
                false, true, true, false, false, false, false, 0, 0, 10, 10, null);
        com.agentruntime.state.WorldState st = new com.agentruntime.state.WorldState(
                1, 1, System.currentTimeMillis(), "com.app", "Main", "test", leaf, null, 1);
        assertTrue(cc.evaluate("ui_not_contains:missing", st));
        assertFalse(cc.evaluate("ui_not_contains:hello", st));
        assertTrue(cc.evaluate("package_is_not:other.app", st));
        assertFalse(cc.evaluate("package_is_not:com.app", st));
    }

    @Test
    public void resourceIdShortNameMatchesAndroidFormat() {
        com.agentruntime.state.UiNode n = new com.agentruntime.state.UiNode(
                "n0", "android.widget.EditText", null, null, "com.agenttestapp:id/search_input",
                false, true, true, true, true, false, false, 0, 0, 10, 10, null);
        assertTrue(n.matchesResource("search_input"));
        assertTrue(n.matchesResource("com.agenttestapp:id/search_input"));
        assertFalse(n.matchesResource("other_input"));
    }

    @Test
    public void fingerprintNormalizesNullFieldsAndIgnoresFocus() {
        com.agentruntime.state.UiNode a = new com.agentruntime.state.UiNode(
                "n0", "android.widget.Button", null, null, "com.app:id/b",
                true, true, true, true, false, false, false, 0, 0, 10, 10, null);
        com.agentruntime.state.UiNode b = new com.agentruntime.state.UiNode(
                "n1", "android.widget.Button", "", "", "com.app:id/b",
                true, true, true, true, false, false, true, 0, 0, 10, 10, null);
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void identityKeyIgnoresBoundsForLiveMatching() {
        com.agentruntime.state.UiNode beforeLayout = new com.agentruntime.state.UiNode(
                "n0", "android.widget.Button", "发送", null, "com.app:id/btn_send",
                true, true, true, true, false, false, false, 10, 20, 100, 60, null);
        com.agentruntime.state.UiNode afterLayout = new com.agentruntime.state.UiNode(
                "n1", "android.widget.Button", "发送", null, "com.app:id/btn_send",
                true, true, true, true, false, false, true, 10, 80, 100, 120, null);
        assertEquals(beforeLayout.identityKey(), afterLayout.identityKey());
    }

    @Test
    public void textMatchingIsCaseInsensitiveForAllCapsButtons() {
        com.agentruntime.state.UiNode btn = new com.agentruntime.state.UiNode(
                "n0", "android.widget.Button", "TESTUSER", null, "com.app:id/btn_user_testuser",
                true, true, true, true, false, false, false, 0, 0, 10, 10, null);
        assertTrue(btn.containsText("TestUser"));
        assertTrue(btn.matchesText("testuser"));
        assertFalse(btn.matchesText("TestUser2"));
    }
}
