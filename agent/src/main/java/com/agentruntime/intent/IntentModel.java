package com.agentruntime.intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置意图分类模型（多项式朴素贝叶斯 + 字符 n-gram）。
 *
 * <p>这是真正跑在设备上的模型：权重文件 17KB，随 APK 一起安装，开机即可用，
 * 不需要用户配任何东西。训练脚本见 {@code tools/train_intent_model.py}。
 *
 * <p>它解决的问题很具体：L0 规则靠关键词命中，说法一换就失效（"把它截下来" 命中不了
 * "截图"）。这个模型用字符 n-gram 做浅层泛化，能在毫秒级把自然语言落到 12 个意图之一。
 *
 * <p>能力边界必须说清：它不是大模型，不做推理链，只做<strong>单句意图分类</strong>。
 * 置信度不足时它必须认输（返回低置信度），由上层交给更强但更慢的通道。
 */
public final class IntentModel {

    /** 低于此置信度视为不可用，上层应继续往上升级。 */
    public static final float MIN_CONFIDENCE = 0.65f;

    /** 一次预测的结果。 */
    public static final class Prediction {
        public final String intent;
        public final String skill;
        public final float confidence;
        public final boolean usable;

        Prediction(String intent, String skill, float confidence) {
            this.intent = intent;
            this.skill = skill;
            this.confidence = confidence;
            this.usable = confidence >= MIN_CONFIDENCE;
        }

        public String describe() {
            return intent + " -> " + skill + " (" + String.format(Locale.US, "%.2f", confidence) + ")";
        }
    }

    private final String[] classes;
    private final String[] skills;
    private final float[] prior;
    private final float[] defaultLogp;
    private final Map<String, Integer> vocab;
    private final Map<Integer, Map<Integer, Float>> weights;
    private final int trainedDocs;

    private IntentModel(String[] classes, String[] skills, float[] prior, float[] defaultLogp,
                        Map<String, Integer> vocab, Map<Integer, Map<Integer, Float>> weights,
                        int trainedDocs) {
        this.classes = classes;
        this.skills = skills;
        this.prior = prior;
        this.defaultLogp = defaultLogp;
        this.vocab = vocab;
        this.weights = weights;
        this.trainedDocs = trainedDocs;
    }

    /** 从 JSON 权重构建。测试可直接喂字符串，不必经过 AssetManager。 */
    public static IntentModel fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray clsArr = root.getJSONArray("classes");
        JSONArray skillArr = root.getJSONArray("skills");
        JSONArray priorArr = root.getJSONArray("prior");
        JSONArray defaultArr = root.getJSONArray("default_logp");
        JSONArray tokenArr = root.getJSONArray("tokens");

        int n = clsArr.length();
        String[] classes = new String[n];
        String[] skills = new String[n];
        float[] prior = new float[n];
        float[] def = new float[n];
        for (int i = 0; i < n; i++) {
            classes[i] = clsArr.getString(i);
            skills[i] = skillArr.getString(i);
            prior[i] = (float) priorArr.getDouble(i);
            def[i] = (float) defaultArr.getDouble(i);
        }

        Map<String, Integer> vocab = new HashMap<>();
        for (int i = 0; i < tokenArr.length(); i++) {
            vocab.put(tokenArr.getString(i), i);
        }

        Map<Integer, Map<Integer, Float>> weights = new HashMap<>();
        JSONObject wObj = root.getJSONObject("weights");
        Iterator<String> keys = wObj.keys();
        while (keys.hasNext()) {
            String tk = keys.next();
            JSONObject row = wObj.getJSONObject(tk);
            Map<Integer, Float> m = new HashMap<>();
            Iterator<String> cks = row.keys();
            while (cks.hasNext()) {
                String ck = cks.next();
                m.put(Integer.parseInt(ck), (float) row.getDouble(ck));
            }
            if (!m.isEmpty()) {
                weights.put(Integer.parseInt(tk), m);
            }
        }

        return new IntentModel(classes, skills, prior, def, vocab, weights,
                root.optInt("trained_docs", 0));
    }

    public int classCount() {
        return classes.length;
    }

    public int vocabSize() {
        return vocab.size();
    }

    public int trainedDocs() {
        return trainedDocs;
    }

    public String[] intents() {
        return classes.clone();
    }

    /** 预测一句话的意图。 */
    public Prediction predict(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Prediction("unknown", "", 0f);
        }
        int n = classes.length;
        float[] scores = new float[n];
        System.arraycopy(prior, 0, scores, 0, n);

        for (String tok : tokenize(text)) {
            Integer ti = vocab.get(tok);
            Map<Integer, Float> row = ti == null ? null : weights.get(ti);
            for (int c = 0; c < n; c++) {
                Float lp = row == null ? null : row.get(c);
                scores[c] += (lp != null) ? lp : defaultLogp[c];
            }
        }

        int best = 0;
        for (int i = 1; i < n; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        float max = scores[best];
        double sum = 0;
        for (float s : scores) {
            sum += Math.exp(s - max);
        }
        float conf = (float) (1.0 / Math.max(sum, 1e-9));
        return new Prediction(classes[best], skills[best], conf);
    }

    /**
     * 分词：字符 unigram + bigram，全小写、去空白。
     *
     * <p>必须与 Python 训练端逐字一致，否则特征对不上，模型会退化成随机猜。
     */
    public static List<String> tokenize(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        List<String> out = new ArrayList<>(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            out.add(String.valueOf(s.charAt(i)));
            if (i + 1 < s.length()) {
                out.add(s.substring(i, i + 2));
            }
        }
        return out;
    }
}
