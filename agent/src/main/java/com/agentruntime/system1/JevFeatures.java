package com.agentruntime.system1;

import com.agentruntime.state.UiNode;

/**
 * Jev-like 决策器的特征提取。
 *
 * <p><b>这里的顺序与计算方式必须与 tools/train_jev_decider.py 的 FEATURES 逐项一致。</b>
 * 任何一处漂移都会让训练好的权重失效，而症状是"准确率莫名其妙掉下来"，很难定位。
 * 改这里必须同时改训练脚本并重训。
 */
public final class JevFeatures {

    public static final int DIM = 10;

    public static final String[] NAMES = {
            "exact_match",
            "goal_contains",
            "label_contains",
            "overlap",
            "clickable",
            "visible",
            "editable",
            "area_ratio",
            "center_bias",
            "label_len",
    };

    private JevFeatures() {
    }

    /**
     * 提取一个候选的特征向量。
     *
     * @param goal     语义子目标（例如"发送"）
     * @param node     候选节点
     * @param screenW  屏幕宽（用于面积与位置归一化）
     * @param screenH  屏幕高
     */
    public static float[] extract(String goal, UiNode node, int screenW, int screenH) {
        float[] x = new float[DIM];
        if (node == null) return x;

        String g = goal == null ? "" : goal.trim();
        String label = node.text != null ? node.text
                : (node.contentDescription != null ? node.contentDescription : "");
        label = label.trim();

        x[0] = label.isEmpty() ? 0f : (label.equals(g) ? 1f : 0f);
        x[1] = (!g.isEmpty() && !label.isEmpty() && g.contains(label)) ? 1f : 0f;
        x[2] = (!g.isEmpty() && !label.isEmpty() && label.contains(g)) ? 1f : 0f;
        x[3] = Math.min(1f, (float) lcs(label, g) / Math.max(g.length(), 1));
        x[4] = node.clickable ? 1f : 0f;
        x[5] = node.visible ? 1f : 0f;
        x[6] = node.editable ? 1f : 0f;

        float w = Math.max(1, screenW);
        float h = Math.max(1, screenH);
        float area = ((node.right - node.left) * (node.bottom - node.top)) / (w * h);
        x[7] = Math.min(1f, Math.max(0f, area * 4f));

        // 越靠屏幕中心越优先：中心=1，四角=0
        float cx = (node.left + node.right) / 2f;
        float cy = (node.top + node.bottom) / 2f;
        float dx = (cx - w / 2f) / (w / 2f);
        float dy = (cy - h / 2f) / (h / 2f);
        float dist = (float) Math.sqrt(dx * dx + dy * dy) / (float) Math.sqrt(2);
        x[8] = Math.max(0f, Math.min(1f, 1f - dist));

        x[9] = Math.min(1f, label.length() / 20f);
        return x;
    }

    /** 最长公共子序列长度（用于字面重合率）。 */
    public static int lcs(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 0; i < a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                } else {
                    cur[j] = Math.max(prev[j], cur[j - 1]);
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[b.length()];
    }
}
