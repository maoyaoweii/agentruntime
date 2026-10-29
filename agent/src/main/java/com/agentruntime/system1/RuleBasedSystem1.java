package com.agentruntime.system1;

import com.agentruntime.state.WorldState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则基线 System-1：按文本/资源语义匹配给候选打分，取最高分并给出置信度。
 * 低置信度（< 阈值）交由上层升级处理。决策模型可替换。
 */
public final class RuleBasedSystem1 implements System1 {

    public static final double LOW_CONFIDENCE = 0.60;

    private final List<JSONObject> replay = new ArrayList<>();
    private final int replayCap = 200;

    @Override
    public Decision decide(String subGoal, WorldState state, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Decision(null, 0.0, "no_candidates");
        }
        String goal = subGoal == null ? "" : subGoal.toLowerCase(java.util.Locale.ROOT);
        double best = -1;
        double second = -1;
        Candidate bestC = null;
        for (Candidate c : candidates) {
            double s = score(goal, c);
            if (s > best) {
                second = best;
                best = s;
                bestC = c;
            } else if (s > second) {
                second = s;
            }
        }
        double margin = best - Math.max(second, 0);
        double confidence = clamp(best * 0.75 + margin * 0.25);
        String reason = confidence >= LOW_CONFIDENCE ? "semantic_match" : "low_confidence";
        Decision d = new Decision(bestC != null ? bestC.id : null, confidence, reason);
        log(subGoal, candidates, d);
        return d;
    }

    @Override
    public JSONObject replayLog(String subGoal, List<Candidate> candidates, Decision decision) {
        JSONObject o = new JSONObject();
        try {
            o.put("sub_goal", subGoal);
            JSONArray arr = new JSONArray();
            if (candidates != null) {
                for (Candidate c : candidates) arr.put(c.id + ":" + c.label);
            }
            o.put("candidates", arr);
            o.put("choice_id", decision != null ? decision.choiceId : null);
            o.put("confidence", decision != null ? decision.confidence : 0);
            o.put("reason", decision != null ? decision.reason : null);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void log(String subGoal, List<Candidate> candidates, Decision d) {
        synchronized (replay) {
            replay.add(replayLog(subGoal, candidates, d));
            if (replay.size() > replayCap) replay.remove(0);
        }
    }

    public List<JSONObject> replayTrail() {
        synchronized (replay) {
            return new ArrayList<>(replay);
        }
    }

    private static double score(String goal, Candidate c) {
        String label = (c.label == null ? "" : c.label).trim().toLowerCase(java.util.Locale.ROOT);
        String target = (goal == null ? "" : goal).trim().toLowerCase(java.util.Locale.ROOT);
        if (label.isEmpty()) return 0.05;
        if (label.equals(target)) return 1.0;
        if (!target.isEmpty() && label.contains(target)) {
            return 0.75 + 0.25 * ((double) target.length() / label.length());
        }
        if (!target.isEmpty() && target.contains(label)) {
            return 0.70 + 0.25 * ((double) label.length() / target.length());
        }
        double s = 0.1;
        for (String token : target.split("[\\s,，。、:：/{}=]+")) {
            if (token.length() >= 2 && label.contains(token)) s += 0.15;
        }
        return Math.min(0.65, s);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
