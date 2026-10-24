package com.agentruntime.system1;

import com.agentruntime.state.WorldState;

import org.json.JSONObject;

import java.util.List;

/**
 * System-1（Jev-like）：受限候选集上的快速选择器接口范式。
 * 只从 Executor 提供的合法候选中作出选择，不直接调用设备 API。
 */
public interface System1 {

    final class Candidate {
        public final String id;
        public final String nodeId;
        public final String label;

        public Candidate(String id, String nodeId, String label) {
            this.id = id;
            this.nodeId = nodeId;
            this.label = label;
        }
    }

    final class Decision {
        public final String choiceId;
        public final double confidence;
        public final String reason;

        public Decision(String choiceId, double confidence, String reason) {
            this.choiceId = choiceId;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    Decision decide(String subGoal, WorldState state, List<Candidate> candidates);

    /** 决策接口可回放：输入/输出转 JSON 供回归评估。 */
    JSONObject replayLog(String subGoal, List<Candidate> candidates, Decision decision);
}
