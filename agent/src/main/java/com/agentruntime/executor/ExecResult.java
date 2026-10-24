package com.agentruntime.executor;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 动作执行回执：区分已执行、结果未知、明确失败与拒绝执行。
 */
public final class ExecResult {

    public enum Status { OK, FAILED, UNKNOWN, REJECTED, TIMEOUT, NEED_CONFIRM, CANCELLED }

    public final Status status;
    public final String code;
    public final String detail;
    public final long stateEpoch;
    public final long durationMs;

    public ExecResult(Status status, String code, String detail, long stateEpoch, long durationMs) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.stateEpoch = stateEpoch;
        this.durationMs = durationMs;
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public static ExecResult ok(String code, String detail, long epoch, long durationMs) {
        return new ExecResult(Status.OK, code, detail, epoch, durationMs);
    }

    public static ExecResult fail(String code, String detail, long epoch, long durationMs) {
        return new ExecResult(Status.FAILED, code, detail, epoch, durationMs);
    }

    public static ExecResult reject(String code, String detail, long epoch) {
        return new ExecResult(Status.REJECTED, code, detail, epoch, 0);
    }

    public static ExecResult timeout(String code, String detail, long epoch, long durationMs) {
        return new ExecResult(Status.TIMEOUT, code, detail, epoch, durationMs);
    }

    public static ExecResult unknown(String code, String detail, long epoch, long durationMs) {
        return new ExecResult(Status.UNKNOWN, code, detail, epoch, durationMs);
    }

    public static ExecResult cancelled(String code, String detail, long epoch) {
        return new ExecResult(Status.CANCELLED, code, detail, epoch, 0);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status.name());
            o.put("code", code);
            if (detail != null) o.put("detail", detail);
            o.put("state_epoch", stateEpoch);
            o.put("duration_ms", durationMs);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Safe summary for persistent task logs and audit records; keeps detail for in-memory callers only. */
    public JSONObject toLogJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status.name());
            o.put("code", code);
            o.put("state_epoch", stateEpoch);
            o.put("duration_ms", durationMs);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
