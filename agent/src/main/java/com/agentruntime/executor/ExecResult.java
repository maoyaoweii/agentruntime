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

    /**
     * 回给模型的观察结果：必须带上 detail，否则模型只知道"成功了"，不知道"看到了什么"。
     *
     * <p>之前 run_skill 把 toLogJson() 的结果当成模型输出，detail 被整体丢掉，于是
     * 读屏/读网页/截图全部退化成只有一个状态码（OBSERVE_OK、PAGE_TEXT、SHOT_OK），
     * 模型再努力也拿不到任何界面文字。这里给 detail 留一个有上限的通道，
     * 审计日志仍用 toLogJson()，不落屏内容。
     */
    public JSONObject toToolJson(int maxDetailChars) {
        JSONObject o = toLogJson();
        try {
            if (detail != null && !detail.isEmpty()) {
                if (detail.length() <= maxDetailChars) {
                    o.put("detail", detail);
                } else {
                    o.put("detail", detail.substring(0, Math.max(0, maxDetailChars)));
                    o.put("detail_truncated", true);
                    o.put("detail_length", detail.length());
                }
            }
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
