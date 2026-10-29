package com.agentruntime.local;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 本地模型注册表：负责配置持久化、后端构造与可用性自检。
 *
 * <p>设计上把「配置」「后端实现」「调用」三层分开，换后端时不用改调用方。
 */
public final class LocalModelRegistry {

    public static final String PREFS = "local_model";

    public static final String KEY_BACKEND = "backend";
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_VISION_MODEL = "vision_model";
    public static final String KEY_DECIDER_MODEL = "decider_model";
    public static final String KEY_TIMEOUT_MS = "timeout_ms";
    public static final String KEY_MAX_TOKENS = "max_tokens";

    private final Context context;
    private LocalModelProvider provider;
    private String providerKey;

    public LocalModelRegistry(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------ config

    public LocalModelConfig config() {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String backendName = sp.getString(KEY_BACKEND, LocalModelConfig.Backend.NONE.name());
        LocalModelConfig.Backend backend;
        try {
            backend = LocalModelConfig.Backend.valueOf(backendName);
        } catch (IllegalArgumentException e) {
            backend = LocalModelConfig.Backend.NONE;
        }
        return new LocalModelConfig(
                backend,
                sp.getString(KEY_HOST, "127.0.0.1"),
                sp.getInt(KEY_PORT, LocalModelConfig.DEFAULT_HTTP_PORT),
                sp.getString(KEY_VISION_MODEL, null),
                sp.getString(KEY_DECIDER_MODEL, null),
                sp.getInt(KEY_TIMEOUT_MS, LocalModelConfig.DEFAULT_TIMEOUT_MS),
                sp.getInt(KEY_MAX_TOKENS, 512));
    }

    /** 部分更新：传 null 表示保留原值。 */
    public void configure(String backend, String host, Integer port,
                          String visionModel, String deciderModel,
                          Integer timeoutMs, Integer maxTokens) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (backend != null) e.putString(KEY_BACKEND, backend.trim().toUpperCase(java.util.Locale.ROOT));
        if (host != null) e.putString(KEY_HOST, host.trim());
        if (port != null) e.putInt(KEY_PORT, port);
        if (visionModel != null) e.putString(KEY_VISION_MODEL, visionModel.trim());
        if (deciderModel != null) e.putString(KEY_DECIDER_MODEL, deciderModel.trim());
        if (timeoutMs != null) e.putInt(KEY_TIMEOUT_MS, timeoutMs);
        if (maxTokens != null) e.putInt(KEY_MAX_TOKENS, maxTokens);
        e.apply();
        provider = null;
        providerKey = null;
    }

    // ---------------------------------------------------------- provider

    /** 当前后端实例；配置变化后自动重建。 */
    public synchronized LocalModelProvider provider() {
        LocalModelConfig cfg = config();
        String key = cfg.fingerprint();
        if (provider != null && key.equals(providerKey)) {
            return provider;
        }
        switch (cfg.backend) {
            case HTTP:
                provider = new HttpLocalModelProvider(cfg);
                break;
            case ONNX:
                provider = new OnnxLocalModelProvider(cfg);
                break;
            case NONE:
            default:
                provider = new NoopProvider();
                break;
        }
        providerKey = key;
        return provider;
    }

    public boolean available() {
        return provider().available();
    }

    /**
     * 自检：返回后端类型、可用性、模型名、运行时状态。
     * 不做真实推理调用（避免无谓等待），只回答"能不能用"。
     */
    public JSONObject probe() {
        JSONObject o = new JSONObject();
        LocalModelConfig cfg = config();
        try {
            o.put("backend", cfg.backend.name().toLowerCase(java.util.Locale.ROOT));
            o.put("host", cfg.host);
            o.put("port", cfg.port);
            o.put("vision_model", cfg.visionModel);
            o.put("decider_model", cfg.deciderModel);
            o.put("timeout_ms", cfg.timeoutMs);
            LocalModelProvider p = provider();
            o.put("provider", p.id());
            o.put("available", p.available());
            o.put("supports_vision", p.supportsVision());
            if (p instanceof OnnxLocalModelProvider) {
                o.put("runtime", ((OnnxLocalModelProvider) p).runtimeDetail());
            } else if (p instanceof HttpLocalModelProvider) {
                o.put("runtime", "http endpoint " + cfg.chatEndpoint());
            } else {
                o.put("runtime", "local model disabled");
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 空后端：未启用时占位，保证调用方不需要判空。 */
    private static final class NoopProvider implements LocalModelProvider {
        @Override
        public String id() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean supportsVision() {
            return false;
        }

        @Override
        public LocalModelReply complete(LocalModelRequest request) {
            return LocalModelReply.fail("LOCAL_MODEL_DISABLED", "none", 0,
                    request == null ? null : request.model);
        }
    }
}
