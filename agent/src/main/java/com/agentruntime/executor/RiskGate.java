package com.agentruntime.executor;

import com.agentruntime.dsl.ActionSpec;

/**
 * Risk Gate：高风险动作（外部发送、付款、删除、权限变更、外部分享）必须确认。
 * 未配置确认通道时，高风险动作默认拒绝执行（fail-safe）。
 */
public final class RiskGate {

    public interface ConfirmationChannel {
        /** 返回 true 表示用户同意执行。 */
        boolean requestConfirmation(String actionId, String summary);
    }

    public static final class Decision {
        public final boolean allow;
        public final boolean autoConfirmed;
        public final String reason;

        Decision(boolean allow, boolean autoConfirmed, String reason) {
            this.allow = allow;
            this.autoConfirmed = autoConfirmed;
            this.reason = reason;
        }
    }

    private volatile ConfirmationChannel channel;
    private volatile boolean testModeAutoConfirm;

    public void setConfirmationChannel(ConfirmationChannel channel) {
        this.channel = channel;
    }

    public void setTestModeAutoConfirm(boolean v) {
        this.testModeAutoConfirm = v;
    }

    public Decision check(String actionId, ActionSpec spec, String summary) {
        boolean risky = spec.risk == ActionSpec.RiskLevel.HIGH || spec.requiresConfirmation;
        if (!risky) return new Decision(true, false, "low_risk");
        if (testModeAutoConfirm) {
            return new Decision(true, true, "test_mode_auto_confirm");
        }
        ConfirmationChannel c = channel;
        if (c == null) return new Decision(false, false, "no_confirmation_channel");
        boolean ok = c.requestConfirmation(actionId, summary);
        return new Decision(ok, false, ok ? "user_confirmed" : "user_rejected");
    }
}
