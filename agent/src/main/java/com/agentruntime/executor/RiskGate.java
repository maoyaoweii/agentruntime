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
    private final ThreadLocal<StepScope> stepScope = new ThreadLocal<>();

    /** A decision covers one fixed skill expansion and expires before the next step or retry. */
    public final class StepScope implements AutoCloseable {
        private final String summary;
        private boolean approved;

        private StepScope(String summary) { this.summary = summary; }
        @Override public void close() { stepScope.remove(); }
    }

    public StepScope openStep(String summary) {
        StepScope scope = new StepScope(summary);
        stepScope.set(scope);
        return scope;
    }

    public void setConfirmationChannel(ConfirmationChannel channel) {
        this.channel = channel;
    }

    public void setTestModeAutoConfirm(boolean v) {
        this.testModeAutoConfirm = v;
    }

    public Decision check(String actionId, ActionSpec spec, String summary) {
        boolean risky = spec.risk == ActionSpec.RiskLevel.HIGH || spec.requiresConfirmation;
        if (!risky) return new Decision(true, false, "low_risk");
        return request(actionId,summary,false);
    }

    /** Generic text taps may resolve to a consequential button only after preflight. */
    public Decision confirmSensitiveTap(String actionId,String detail) {
        return request(actionId,detail,true);
    }

    private Decision request(String actionId,String summary,boolean includeDetail) {
        if (testModeAutoConfirm) {
            return new Decision(true, true, "test_mode_auto_confirm");
        }
        StepScope scope = stepScope.get();
        if (!includeDetail && scope != null && scope.approved) return new Decision(true, false, "user_confirmed_step");
        ConfirmationChannel c = channel;
        if (c == null) return new Decision(false, false, "no_confirmation_channel");
        String prompt=scope==null?summary:scope.summary+(includeDetail?"\n\n"+summary:"");
        boolean ok = c.requestConfirmation(actionId,prompt);
        if (ok && scope != null) scope.approved = true;
        return new Decision(ok, false, ok ? "user_confirmed" : "user_rejected");
    }
}
