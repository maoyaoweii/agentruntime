package com.agentruntime;

import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;
import com.agentruntime.system1.JevDecider;
import com.agentruntime.system1.JevFeatures;
import com.agentruntime.system1.RuleBasedSystem1;
import com.agentruntime.system1.System1;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * V1.5 System-1 / Jev-like 决策器测试。
 *
 * <p>依据立项方案：Jev-like 是「受限候选集合上的快速决策接口范式」，
 * 验收要求是「候选动作输入 / 选择输出可回放；第一版本地规则可用，决策模型可替换」。
 * 所以这里重点验三件事：<b>特征算得对</b>、<b>选得对</b>、<b>回放记录完整</b>。
 */
public class AgentV15JevTest {

    private static JevDecider decider;

    @BeforeClass
    public static void loadModel() throws Exception {
        File f = locate();
        assertNotNull("找不到 jev_decider.json：" + f.getAbsolutePath(), f);
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        decider = JevDecider.fromJson(json);
    }

    private static File locate() {
        String[] paths = {
                "src/main/assets/models/jev_decider.json",
                "agent/src/main/assets/models/jev_decider.json",
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return new File(paths[0]);
    }

    /**
     * 造一个新实例。
     *
     * <p>回放 trace 是实例状态（{@code lastTrace}），而 {@link #decider} 是 {@code @BeforeClass}
     * 共享的——同一个实例上别的用例可能已经调用过 decide，直接断言"还没调用过"会随执行顺序飘。
     * 所以这类用例各自造新实例，保证起点干净。
     */
    private static JevDecider fresh() throws Exception {
        File f = locate();
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return JevDecider.fromJson(json);
    }

    // ------------------------------------------------------------ 特征

    @Test
    public void featureDimensionMatchesTraining() {
        assertEquals(10, JevFeatures.DIM);
        assertEquals(JevFeatures.DIM, JevFeatures.NAMES.length);
        assertEquals(10, decider.weights().length);
    }

    @Test
    public void exactMatchFeatureFires() {
        UiNode n = node("n1", "发送", true, true, false, 100, 100, 200, 160);
        float[] x = JevFeatures.extract("发送", n, 1080, 2340);
        assertEquals(1f, x[0], 1e-6);   // exact_match
        assertEquals(1f, x[1], 1e-6);   // goal_contains（"发送"包含"发送"）
        assertEquals(1f, x[2], 1e-6);   // label_contains
        assertEquals(1f, x[4], 1e-6);   // clickable
        assertEquals(1f, x[5], 1e-6);   // visible
    }

    @Test
    public void nonMatchingLabelScoresZeroOnTextFeatures() {
        UiNode n = node("n1", "取消", true, true, false, 100, 100, 200, 160);
        float[] x = JevFeatures.extract("发送", n, 1080, 2340);
        assertEquals(0f, x[0], 1e-6);
        assertEquals(0f, x[1], 1e-6);
        assertEquals(0f, x[2], 1e-6);
    }

    @Test
    public void clickableAndVisibleAreEncoded() {
        UiNode dead = node("n1", "发送", false, true, false, 0, 0, 10, 10);
        UiNode hidden = node("n2", "发送", true, false, false, 0, 0, 10, 10);
        assertEquals(0f, JevFeatures.extract("发送", dead, 1080, 2340)[4], 1e-6);
        assertEquals(0f, JevFeatures.extract("发送", hidden, 1080, 2340)[5], 1e-6);
    }

    @Test
    public void centerBiasPrefersMiddleOfScreen() {
        UiNode center = node("n1", "确定", true, true, false, 440, 1070, 640, 1270);
        UiNode corner = node("n2", "确定", true, true, false, 0, 0, 100, 100);
        float[] xc = JevFeatures.extract("确定", center, 1080, 2340);
        float[] xk = JevFeatures.extract("确定", corner, 1080, 2340);
        assertTrue("center should beat corner: " + xc[8] + " vs " + xk[8], xc[8] > xk[8]);
    }

    @Test
    public void areaRatioIsCappedForHugeContainers() {
        UiNode full = node("n1", "列表", false, true, false, 0, 0, 1080, 2340);
        float[] x = JevFeatures.extract("列表", full, 1080, 2340);
        assertEquals(1f, x[7], 1e-6);   // 全屏面积 *4 后被截到 1
    }

    @Test
    public void lcsHandlesCommonSubsequence() {
        assertEquals(2, JevFeatures.lcs("发送", "发送消息"));
        assertEquals(0, JevFeatures.lcs("取消", "发送"));
        assertEquals(3, JevFeatures.lcs("abc", "axbxc"));
    }

    // ------------------------------------------------------------ 决策

    @Test
    public void picksExactMatchOverNoise() {
        WorldState st = world(
                node("n1", "取消", true, true, false, 600, 1800, 900, 1900),
                node("n2", "发送", true, true, false, 200, 1800, 500, 1900),
                node("n3", "输入消息", true, true, true, 100, 1600, 900, 1750));
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "取消"),
                new System1.Candidate("c1", "n2", "发送"),
                new System1.Candidate("c2", "n3", "输入消息"));
        System1.Decision d = decider.decide("发送", st, cands);
        assertEquals("c1", d.choiceId);
        assertTrue("conf should be high: " + d.confidence, d.confidence > 0.5);
    }

    @Test
    public void prefersClickableOverNonClickableDuplicate() {
        WorldState st = world(
                node("n1", "保存成功", false, true, false, 100, 100, 400, 160),
                node("n2", "保存", true, true, false, 100, 300, 400, 360));
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "保存成功"),
                new System1.Candidate("c1", "n2", "保存"));
        assertEquals("c1", decider.decide("保存", st, cands).choiceId);
    }

    @Test
    public void handlesEmptyCandidateList() {
        System1.Decision d = decider.decide("发送", world(), new ArrayList<>());
        assertNull(d.choiceId);
        assertEquals(0.0, d.confidence, 1e-9);
    }

    @Test
    public void confidenceDropsWhenCandidatesAreSimilar() {
        // 两个完全同名同属性的候选：模型无法区分，置信度应接近 0.5
        WorldState st = world(
                node("n1", "确定", true, true, false, 100, 100, 300, 160),
                node("n2", "确定", true, true, false, 700, 100, 900, 160));
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "确定"),
                new System1.Candidate("c1", "n2", "确定"));
        System1.Decision d = decider.decide("确定", st, cands);
        assertTrue("ambiguous should be near 0.5: " + d.confidence,
                d.confidence > 0.3 && d.confidence < 0.75);
    }

    // ------------------------------------------------------------ 可回放

    @Test
    public void replayLogRecordsFeaturesAndWeights() throws Exception {
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "取消"),
                new System1.Candidate("c1", "n2", "发送"));
        System1.Decision d = new System1.Decision("c1", 0.9, "test");
        JSONObject log = decider.replayLog("发送", cands, d);
        assertEquals("发送", log.getString("sub_goal"));
        assertEquals("c1", log.getString("choice"));
        assertEquals(10, log.getJSONArray("features").length());
        assertEquals(10, log.getJSONArray("weights").length());
        assertEquals(2, log.getJSONArray("candidates").length());
    }

    /**
     * 这条是「可回放」的真正验收：拿日志里的 weights 与每行 features 重算一遍，
     * 得到的 argmax 必须等于日志里记的 choice。没有原始界面树也必须算得出来。
     */
    @Test
    public void replayTraceRecomputesChoiceOffline() throws Exception {
        JevDecider fresh = fresh();
        WorldState st = world(
                node("n1", "取消", true, true, false, 600, 1800, 900, 1900),
                node("n2", "发送", true, true, false, 200, 1800, 500, 1900),
                node("n3", "输入消息", true, true, true, 100, 1600, 900, 1750));
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "取消"),
                new System1.Candidate("c1", "n2", "发送"),
                new System1.Candidate("c2", "n3", "输入消息"));

        System1.Decision d = fresh.decide("发送", st, cands);
        JSONObject log = fresh.replayLog("发送", cands, d);

        assertTrue(log.getBoolean("trace_available"));
        assertTrue(log.has("replay_rule"));

        org.json.JSONArray w = log.getJSONArray("weights");
        org.json.JSONArray trace = log.getJSONArray("trace");
        assertEquals(3, trace.length());

        String recomputed = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < trace.length(); i++) {
            JSONObject row = trace.getJSONObject(i);
            assertTrue(row.getBoolean("node_resolved"));
            org.json.JSONArray x = row.getJSONArray("features");
            assertEquals(10, x.length());
            double s = 0;
            for (int k = 0; k < 10; k++) s += w.getDouble(k) * x.getDouble(k);
            // 日志里的 score 必须与用日志里的 features×weights 复算一致
            assertEquals("row " + i, s, row.getDouble("score"), 1e-3);
            if (s > bestScore) {
                bestScore = s;
                recomputed = row.getString("id");
            }
        }
        assertEquals("离线复算应复现同一个选择", d.choiceId, recomputed);
    }

    /** 候选数与最近一次 decide 不一致时，必须明说「不可用」，不能给出错位的回放数据。 */
    @Test
    public void replayTraceRefusesMismatchedCandidates() throws Exception {
        JevDecider fresh = fresh();
        // 还没调用过 decide
        JSONObject before = fresh.replayLog("发送",
                Arrays.asList(new System1.Candidate("c0", "n1", "发送")),
                new System1.Decision("c0", 0.5, "t"));
        assertTrue(!before.getBoolean("trace_available"));
        assertTrue(before.has("trace_missing_reason"));
        assertTrue(!before.has("trace"));

        // 调过 decide，但回放时换了候选集
        WorldState st = world(
                node("n1", "发送", true, true, false, 200, 1800, 500, 1900),
                node("n2", "取消", true, true, false, 600, 1800, 900, 1900));
        List<System1.Candidate> two = Arrays.asList(
                new System1.Candidate("c0", "n1", "发送"),
                new System1.Candidate("c1", "n2", "取消"));
        fresh.decide("发送", st, two);

        List<System1.Candidate> three = new ArrayList<>(two);
        three.add(new System1.Candidate("c2", "n9", "别的"));
        JSONObject after = fresh.replayLog("发送", three, new System1.Decision("c0", 0.5, "t"));
        assertTrue(!after.getBoolean("trace_available"));
        assertTrue(!after.has("trace"));
    }

    // ------------------------------------------------------ 可替换性

    @Test
    public void bothImplementationsShareTheSameInterface() {
        System1 rule = new RuleBasedSystem1();
        WorldState st = world(
                node("n1", "取消", true, true, false, 600, 1800, 900, 1900),
                node("n2", "发送", true, true, false, 200, 1800, 500, 1900));
        List<System1.Candidate> cands = Arrays.asList(
                new System1.Candidate("c0", "n1", "取消"),
                new System1.Candidate("c1", "n2", "发送"));

        System1.Decision ruleD = rule.decide("发送", st, cands);
        System1.Decision modelD = decider.decide("发送", st, cands);
        // 两种实现都必须给出合法选择，且都落在候选集内——这是"可替换"的最低保证
        assertTrue(cands.stream().anyMatch(c -> c.id.equals(ruleD.choiceId)));
        assertTrue(cands.stream().anyMatch(c -> c.id.equals(modelD.choiceId)));
        assertEquals("c1", ruleD.choiceId);
        assertEquals("c1", modelD.choiceId);
    }

    @Test
    public void modelMetadataIsExposed() throws Exception {
        JSONObject info = decider.describe();
        assertEquals("jev_like_linear_decider", info.getString("kind"));
        assertTrue(info.getInt("train_scenarios") > 0);
        assertTrue(info.getDouble("train_accuracy") > 0.8);
    }

    // ------------------------------------------------------------ 工具

    private static UiNode node(String id, String text, boolean clickable, boolean visible,
                               boolean editable, int l, int t, int r, int b) {
        return new UiNode(id, "android.widget.Button", text, null, "pkg:id/" + id,
                clickable, true, visible, false, editable, false, false,
                l, t, r, b, new ArrayList<>());
    }

    private static WorldState world(UiNode... nodes) {
        List<UiNode> children = new ArrayList<>(Arrays.asList(nodes));
        UiNode root = new UiNode("root", "android.widget.FrameLayout", null, null, null,
                false, true, true, false, false, false, false,
                0, 0, 1080, 2340, children);
        return new WorldState(1, 1, System.currentTimeMillis(), "com.example", "t",
                "test", root, null, 0);
    }
}
