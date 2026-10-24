package com.agentruntime.local;

/**
 * 本地模型调用结果。
 *
 * <p>失败不抛异常而是带 code，让上层能做降级决策（重试 / 换后端 / 回退规则），
 * 而不是在半路炸掉整个任务。
 */
public final class LocalModelReply {

    public final boolean ok;
    public final String text;
    public final String code;
    public final String provider;
    public final long latencyMs;
    public final String model;

    private LocalModelReply(boolean ok, String text, String code, String provider,
                            long latencyMs, String model) {
        this.ok = ok;
        this.text = text;
        this.code = code;
        this.provider = provider;
        this.latencyMs = latencyMs;
        this.model = model;
    }

    public static LocalModelReply ok(String text, String provider, long latency, String model) {
        return new LocalModelReply(true, text == null ? "" : text, "OK", provider, latency, model);
    }

    public static LocalModelReply fail(String code, String provider, long latency, String model) {
        return new LocalModelReply(false, "", code, provider, latency, model);
    }

    /** 结果是否非空可用（模型返回空串等同于失败）。 */
    public boolean usable() {
        return ok && text != null && !text.trim().isEmpty();
    }
}
