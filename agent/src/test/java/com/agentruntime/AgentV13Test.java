package com.agentruntime;

import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.OutputFormat;
import com.agentruntime.intent.IntentModel;
import com.agentruntime.intent.IntentPlanner;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * V1.3 单元测试：内置意图模型、输出格式规范、系统工具与输入法动作。
 *
 * <p>内置模型这一组是真的在跑：直接读 assets 里的权重文件做推理，不是 mock。
 * 这样"模型能用"这件事有证据，而不是"代码写好了"。
 */
public class AgentV13Test {

    private static IntentModel model;
    private static IntentPlanner planner;

    @BeforeClass
    public static void loadModel() throws Exception {
        File f = locateModelFile();
        assertNotNull("找不到模型权重文件：" + f.getAbsolutePath(), f);
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        model = IntentModel.fromJson(json);
        planner = new IntentPlanner(model);
    }

    private static File locateModelFile() {
        String[] candidates = {
                "src/main/assets/models/intent_nb.json",
                "agent/src/main/assets/models/intent_nb.json",
                "../agent/src/main/assets/models/intent_nb.json",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return new File(candidates[0]);
    }

    // --------------------------------------------------------- 内置模型

    @Test
    public void modelLoadsWithExpectedShape() {
        assertEquals(12, model.classCount());
        assertTrue("词表应有几百个 token，实际 " + model.vocabSize(), model.vocabSize() > 200);
        assertTrue(model.trainedDocs() > 200);
    }

    @Test
    public void modelPredictsScreenshotIntent() {
        IntentModel.Prediction p = model.predict("帮我截个屏");
        assertEquals("screenshot", p.intent);
        assertEquals("sys.screenshot", p.skill);
        assertTrue("置信度应可接受，实际 " + p.confidence, p.usable);
    }

    @Test
    public void modelPredictsObserveIntent() {
        IntentModel.Prediction p = model.predict("看看当前设备环境怎么样");
        assertEquals("observe", p.intent);
        assertTrue(p.usable);
    }

    @Test
    public void modelPredictsClockIntent() {
        IntentModel.Prediction p = model.predict("打开闹钟");
        assertEquals("open_clock", p.intent);
        assertTrue(p.usable);
    }

    @Test
    public void modelPredictsNoteIntent() {
        IntentModel.Prediction p = model.predict("在笔记里新建一条记录");
        assertEquals("create_note", p.intent);
    }

    @Test
    public void modelPredictsBrowseIntent() {
        IntentModel.Prediction p = model.predict("访问 https://example.com 看看内容");
        assertEquals("browse", p.intent);
    }

    @Test
    public void modelRefusesOnGibberish() {
        IntentModel.Prediction p = model.predict("zzz qqq xxx 1234");
        assertFalse("乱码不该给出可用结论，实际 " + p.describe(), p.usable);
    }

    @Test
    public void modelHandlesEmptyInput() {
        IntentModel.Prediction p = model.predict("");
        assertEquals("unknown", p.intent);
        assertFalse(p.usable);
    }

    @Test
    public void tokenizeProducesUnigramAndBigram() {
        // "截图" -> 截, 图, 截图 = 3 个特征
        assertEquals(3, IntentModel.tokenize("截图").size());
        // "a b" 去空白后 "ab" -> a, b, ab = 3
        assertEquals(3, IntentModel.tokenize("a b").size());
    }

    // ------------------------------------------------------ 意图到任务

    @Test
    public void plannerBuildsTaskForParamFreeSkill() throws Exception {
        com.agentruntime.dsl.TaskSpec t = planner.plan("截个图");
        assertNotNull("无参技能应能直接构造任务", t);
        assertEquals("intent_screenshot", t.taskId);
        assertEquals(1, t.steps.size());
        assertEquals("sys.screenshot", t.steps.get(0).skillId);
    }

    @Test
    public void clockIntentDeclaresItsTargetPackageAsAllowed() throws Exception {
        com.agentruntime.dsl.TaskSpec t = planner.plan("打开闹钟");
        assertNotNull(t);
        assertEquals("sys.open_clock", t.steps.get(0).skillId);
        assertEquals(1, t.allowedApps.size());
        assertEquals("com.android.deskclock", t.allowedApps.get(0));
    }

    @Test
    public void smokeShortcutBuildsTaskWithBothLaunchTargets() throws Exception {
        assertEquals("smoke", model.predict("工具冒烟").intent);
        com.agentruntime.dsl.TaskSpec t = planner.plan("工具冒烟");
        assertNotNull(t);
        assertEquals("intent_smoke", t.taskId);
        assertEquals("sys.tool_smoke", t.steps.get(0).skillId);
        assertEquals(2, t.allowedApps.size());
        assertEquals("org.fossify.notes", t.allowedApps.get(0));
        assertEquals("com.android.deskclock", t.allowedApps.get(1));
    }

    @Test
    public void plannerBuildsBrowseTaskWhenUrlPresent() throws Exception {
        com.agentruntime.dsl.TaskSpec t = planner.plan("打开 https://example.com/a 看看");
        assertNotNull(t);
        assertEquals("web.open_and_read", t.steps.get(0).skillId);
        assertEquals("https://example.com/a", t.steps.get(0).args.getString("url"));
    }

    @Test
    public void plannerDeclinesWhenArgsCannotBeExtracted() throws Exception {
        // 需要标题/正文参数，当前实现不猜
        assertNull(planner.plan("在笔记里新建一条记录"));
    }

    @Test
    public void plannerDeclinesOnGibberish() throws Exception {
        assertNull(planner.plan("zzz qqq xxx"));
    }

    // ------------------------------------------------------- 输出格式

    @Test
    public void extractsJsonFromMarkdownFence() throws Exception {
        String raw = "好的，结果如下：\n```json\n{\"found\":true,\"x\":1,\"y\":2}\n```\n以上。";
        JSONObject o = OutputFormat.parseObject(raw);
        assertEquals(1, o.getInt("x"));
    }

    @Test
    public void extractsFirstObjectWhenModelEmitsTwo() throws Exception {
        String raw = "{\"a\":1} 然后是 {\"b\":2}";
        JSONObject o = OutputFormat.parseObject(raw);
        assertEquals(1, o.optInt("a", -1));
        assertFalse(o.has("b"));
    }

    @Test
    public void ignoresBracesInsideStrings() throws Exception {
        String raw = "{\"text\":\"这里有 } 和一个 { 符号\",\"ok\":true}";
        JSONObject o = OutputFormat.parseObject(raw);
        assertEquals("这里有 } 和一个 { 符号", o.getString("text"));
        assertTrue(o.getBoolean("ok"));
    }

    @Test
    public void repairsTrailingComma() throws Exception {
        JSONObject o = OutputFormat.parseObject("{\"a\":1,\"b\":[1,2,],}");
        assertEquals(1, o.getInt("a"));
    }

    @Test
    public void repairsCurlyQuotes() throws Exception {
        JSONObject o = OutputFormat.parseObject("{\u201ckey\u201d:\u201cvalue\u201d}");
        assertEquals("value", o.getString("key"));
    }

    @Test
    public void failsWhenNoJsonPresent() {
        try {
            OutputFormat.parseObject("模型拒绝了这次请求");
            fail("should fail");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("no JSON"));
        }
    }

    @Test
    public void validatesGroundingShape() throws Exception {
        JSONObject ok = new JSONObject("{\"found\":true,\"x\":10,\"y\":20,\"confidence\":0.8}");
        assertTrue(OutputFormat.validateGrounding(ok).ok);

        JSONObject missing = new JSONObject("{\"found\":true}");
        assertFalse(OutputFormat.validateGrounding(missing).ok);

        JSONObject badType = new JSONObject("{\"found\":\"yes\"}");
        assertFalse(OutputFormat.validateGrounding(badType).ok);

        JSONObject notFound = new JSONObject("{\"found\":false}");
        assertTrue(OutputFormat.validateGrounding(notFound).ok);
    }

    @Test
    public void validatesActionShape() throws Exception {
        assertTrue(OutputFormat.validateAction(
                new JSONObject("{\"action\":\"tap_xy\",\"params\":{}}")).ok);
        assertFalse(OutputFormat.validateAction(
                new JSONObject("{\"params\":{}}")).ok);
        assertFalse(OutputFormat.validateAction(
                new JSONObject("{\"action\":\"tap_xy\"}")).ok);
    }

    // ------------------------------------------------------- 新动作校验

    @Test
    public void actionCountMatchesDocumentation() {
        // 41 = V1.3 的 40 个原语 + V1.8 新增 append_file；delete_file 紧随其后；
        // V1.9 新增 tap_text（按文本找+滚动+点）。
        // 数字变更是刻意行为，改这里必须同时确认没有把原语静默删掉。
        assertEquals("动作原语总数应为 45", 45, ActionSpec.ActionType.values().length);
    }

    @Test
    public void fileMutationActionsRoundTrip() throws Exception {
        String[] names = {"append_file", "delete_file"};
        for (String n : names) {
            assertEquals(n, ActionSpec.ActionType.fromString(n).wireName());
        }
    }

    @Test
    public void newSystemActionsRoundTrip() throws Exception {
        String[] names = {"set_volume", "get_volume", "set_brightness", "clipboard_set",
                "clipboard_get", "media_key", "vibrate", "open_settings_page",
                "list_apps", "read_file", "write_file",
                "ime_commit", "ime_read", "ime_key"};
        for (String n : names) {
            assertEquals(n, ActionSpec.ActionType.fromString(n).wireName());
        }
    }

    @Test
    public void setVolumeRequiresLevelInRange() throws Exception {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"set_volume\",\"params\":{}}"));
            fail("should reject missing level");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("level"));
        }
        try {
            DslParser.parseAction(new JSONObject(
                    "{\"action\":\"set_volume\",\"params\":{\"level\":150}}"));
            fail("should reject level 150");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("level"));
        }
        DslParser.parseAction(new JSONObject(
                "{\"action\":\"set_volume\",\"params\":{\"level\":50,\"stream\":\"music\"}}"));
    }

    @Test
    public void vibrateRangeIsEnforced() throws Exception {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"vibrate\",\"params\":{\"ms\":9999}}"));
            fail("should reject 9999ms");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("ms"));
        }
    }

    @Test
    public void imeKeyWhitelistIsEnforced() throws Exception {
        try {
            DslParser.parseAction(new JSONObject(
                    "{\"action\":\"ime_key\",\"params\":{\"key\":\"ctrl_alt_del\"}}"));
            fail("should reject unknown key");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("ime_key"));
        }
        DslParser.parseAction(new JSONObject("{\"action\":\"ime_key\",\"params\":{\"key\":\"enter\"}}"));
    }

    @Test
    public void writeFileRequiresPathAndContent() throws Exception {
        try {
            DslParser.parseAction(new JSONObject(
                    "{\"action\":\"write_file\",\"params\":{\"path\":\"a.txt\"}}"));
            fail("should reject missing content");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("content"));
        }
    }

    @Test
    public void listAppsLimitIsBounded() throws Exception {
        try {
            DslParser.parseAction(new JSONObject(
                    "{\"action\":\"list_apps\",\"params\":{\"limit\":9999}}"));
            fail("should reject limit");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("limit"));
        }
    }

    @Test
    public void brightnessRangeIsEnforced() throws Exception {
        try {
            DslParser.parseAction(new JSONObject(
                    "{\"action\":\"set_brightness\",\"params\":{\"percent\":0}}"));
            fail("should reject 0");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("percent"));
        }
    }

    @Test
    public void settingsPageRequiresPageName() throws Exception {
        try {
            DslParser.parseAction(new JSONObject("{\"action\":\"open_settings_page\",\"params\":{}}"));
            fail("should reject missing page");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("page"));
        }
    }
}
