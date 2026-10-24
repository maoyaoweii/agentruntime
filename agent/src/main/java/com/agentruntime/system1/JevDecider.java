package com.agentruntime.system1;

import android.content.Context;

import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jev-like 决策器的模型化实现：受限候选集合上的线性打分选择。
 *
 * <p>对应立项方案术语表：<i>System-1 / Jev-like = 受限候选集合上的快速决策接口范式，
 * 不特指可本地部署的具体模型产品</i>。
 *
 * <p>它替代的是 {@link RuleBasedSystem1} 的<b>可替换实现</b>，不是替换整个 System1 接口——
 * 方案对 System1 的验收要求是「候选动作输入 / 选择输出可回放；第一版本地规则可用，
 * 决策模型可替换」，所以两者并存、按可用性选择。
 *
 * <p>为什么用线性模型而不是神经网络：候选打分只有 10 个特征、决策预算在毫秒级，
 * 线性模型的推理是几十次乘加，可解释、可回放、可热替换权重。用一个小 MLP 不会更准，
 * 但会让"为什么选了这个"变成黑箱——而方案明确要求可回放。
 *
 * <p>权重来自 tools/train_jev_decider.py（合成场景训练，见 {@link #describe()}）。
 * 真实界面表现需要用真机候选集重训，不要拿训练集准确率当线上准确率。
 */
public final class JevDecider implements System1 {

    private final float[] weights;
    private final String version;
    private final JSONObject meta;

    /**
     * 最近一次 {@link #decide} 的逐候选特征与得分。
     *
     * <p>为什么缓存在实例里而不是让 replayLog 重算：{@code replayLog} 的签名只拿到
     * {@code Candidate}（id/nodeId/label），拿不到 {@code UiNode}，重算不出 clickable/坐标/面积。
     * 所以选择在决策时顺手把输入快照留下来。
     *
     * <p>代价是「同一线程内先 decide 再 replayLog」这一隐含约定。执行器正是这么用的；
     * 跨线程拿到的会是别人的 trace，此时 {@code trace_available} 会因为候选数不匹配而为 false，
     * 不会静默给出错误的回放数据。
     */
    private volatile JSONArray lastTrace;

    private JevDecider(float[] weights, String version, JSONObject meta) {
        this.weights = weights;
        this.version = version;
        this.meta = meta;
    }

    // ------------------------------------------------------------ 加载

    public static JevDecider fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("weights");
        float[] w = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            w[i] = (float) arr.getDouble(i);
        }
        if (w.length != JevFeatures.DIM) {
            throw new JSONException("weight dim mismatch: " + w.length + " vs " + JevFeatures.DIM);
        }
        return new JevDecider(w, root.optString("version", "1.0"), root);
    }

    /** 从 assets 加载；失败返回 null，由调用方回退到规则实现。 */
    public static JevDecider fromAssets(Context context) {
        if (context == null) return null;
        try (InputStream in = context.getAssets().open("models/jev_decider.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return fromJson(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    public String version() {
        return version;
    }

    public float[] weights() {
        return weights.clone();
    }

    public JSONObject describe() {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", meta.optString("kind", "jev_like_linear_decider"));
            o.put("version", version);
            o.put("features", meta.optJSONArray("features"));
            o.put("weights", meta.optJSONArray("weights"));
            o.put("train_scenarios", meta.optInt("train_scenarios", 0));
            o.put("train_accuracy", meta.optDouble("train_accuracy", 0));
            o.put("rule_baseline_accuracy", meta.optDouble("rule_baseline_accuracy", 0));
            o.put("note", meta.optString("note", ""));
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------ 决策

    @Override
    public Decision decide(String subGoal, WorldState state, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            lastTrace = null;
            return new Decision(null, 0, "no_candidates");
        }
        int screenW = screenWidth(state);
        int screenH = screenHeight(state);

        int n = candidates.size();
        float[] scores = new float[n];
        JSONArray trace = new JSONArray();

        for (int i = 0; i < n; i++) {
            Candidate c = candidates.get(i);
            UiNode node = findByNodeId(state, c.nodeId);
            boolean resolved = node != null;
            float[] x = resolved
                    ? JevFeatures.extract(subGoal, node, screenW, screenH)
                    : fallbackFeatures(subGoal, c.label);
            scores[i] = dot(x);
            try {
                JSONObject item = new JSONObject();
                item.put("id", c.id);
                item.put("label", c.label);
                // node_resolved=false 表示这行是退化特征（属性位全 0），复算时要按同样规则理解
                item.put("node_resolved", resolved);
                item.put("features", new JSONArray(toList(x)));
                item.put("score", scores[i]);
                trace.put(item);
            } catch (JSONException ignored) {
            }
        }

        int best = 0;
        for (int i = 1; i < n; i++) {
            if (scores[i] > scores[best]) best = i;
        }

        // softmax 求置信度：所有候选分数接近时置信度自然低，这正是上层该升级的信号
        float max = scores[best];
        double sum = 0;
        for (float s : scores) sum += Math.exp(s - max);
        double conf = 1.0 / Math.max(sum, 1e-9);

        String reason = String.format(Locale.US,
                "jev model v%s, best=%s score=%.2f conf=%.2f candidates=%d",
                version, candidates.get(best).label, scores[best], conf, n);
        lastTrace = trace;
        return new Decision(candidates.get(best).id, conf, reason);
    }

    /**
     * 可回放日志：带上每个候选的特征向量与得分，离线可完整复现这次选择。
     *
     * <p>复算方法：对每行 {@code features} 与顶层 {@code weights} 做点积得到分数，
     * 取最大者，应等于 {@code choice}。因此这份日志是自包含的——
     * 不依赖原始界面树，也不需要重跑设备。
     */
    @Override
    public JSONObject replayLog(String subGoal, List<Candidate> candidates, Decision decision) {
        JSONObject o = new JSONObject();
        try {
            o.put("sub_goal", subGoal);
            o.put("model_version", version);
            o.put("features", new JSONArray(java.util.Arrays.asList(JevFeatures.NAMES)));
            o.put("weights", new JSONArray(toList(weights)));
            if (decision != null) {
                o.put("choice", decision.choiceId);
                o.put("confidence", decision.confidence);
                o.put("reason", decision.reason);
            }
            JSONArray cands = new JSONArray();
            if (candidates != null) {
                for (Candidate c : candidates) {
                    JSONObject item = new JSONObject();
                    item.put("id", c.id);
                    item.put("label", c.label);
                    cands.put(item);
                }
            }
            o.put("candidates", cands);

            // 逐候选特征快照：没有它，这份日志只能"看"不能"复算"
            JSONArray trace = lastTrace;
            boolean usable = trace != null
                    && candidates != null
                    && trace.length() == candidates.size();
            o.put("trace_available", usable);
            if (usable) {
                o.put("trace", trace);
                o.put("replay_rule", "argmax(dot(weights, features)) 应等于 choice");
            } else {
                o.put("trace_missing_reason", trace == null
                        ? "decide() 尚未在本实例上调用"
                        : "候选数量与最近一次 decide 不一致（可能跨线程/跨任务取用）");
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------ 内部

    private float dot(float[] x) {
        float s = 0;
        for (int i = 0; i < weights.length && i < x.length; i++) {
            s += weights[i] * x[i];
        }
        return s;
    }

    /** 找不到节点时的退化特征：只保留标签相关项，属性项给 0（保守，不假装可点）。 */
    private static float[] fallbackFeatures(String goal, String label) {
        float[] x = new float[JevFeatures.DIM];
        String g = goal == null ? "" : goal.trim();
        String l = label == null ? "" : label.trim();
        x[0] = l.isEmpty() ? 0f : (l.equals(g) ? 1f : 0f);
        x[1] = (!g.isEmpty() && !l.isEmpty() && g.contains(l)) ? 1f : 0f;
        x[2] = (!g.isEmpty() && !l.isEmpty() && l.contains(g)) ? 1f : 0f;
        x[3] = Math.min(1f, (float) JevFeatures.lcs(l, g) / Math.max(g.length(), 1));
        x[9] = Math.min(1f, l.length() / 20f);
        return x;
    }

    private static UiNode findByNodeId(WorldState state, String nodeId) {
        if (state == null || nodeId == null) return null;
        List<UiNode> hits = state.findNodes(n -> nodeId.equals(n.nodeId));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static int screenWidth(WorldState state) {
        int max = 0;
        if (state != null && state.root != null) {
            for (UiNode n : state.root.flat()) max = Math.max(max, n.right);
        }
        return max > 0 ? max : 1080;
    }

    private static int screenHeight(WorldState state) {
        int max = 0;
        if (state != null && state.root != null) {
            for (UiNode n : state.root.flat()) max = Math.max(max, n.bottom);
        }
        return max > 0 ? max : 1920;
    }

    private static List<Float> toList(float[] arr) {
        List<Float> out = new ArrayList<>(arr.length);
        for (float v : arr) out.add(v);
        return out;
    }
}
