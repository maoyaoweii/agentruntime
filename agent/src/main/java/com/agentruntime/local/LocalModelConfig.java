package com.agentruntime.local;

import java.net.IDN;

/**
 * 本地小模型配置。
 *
 * <p>纯数据对象，不依赖 Android，便于单测直接构造。持久化由 {@link LocalModelRegistry} 负责。
 *
 * <p>为什么要有 backend 概念：本地推理在 Android 上有三种现实路径——
 * <ol>
 *   <li><b>http</b>：设备上另起一个推理进程（llama.cpp / Ollama / 自建服务），Agent 通过
 *       127.0.0.1 调用。零依赖、易替换，是当前默认推荐路径。</li>
 *   <li><b>onnx</b>：用 ONNX Runtime Mobile 或 MediaPipe 在进程内跑量化模型。
 *       需要在 APK 里打进 runtime 与权重，体积增加几十 MB。</li>
 *   <li><b>none</b>：不启用本地模型，全部走云或纯规则。</li>
 * </ol>
 */
public final class LocalModelConfig {

    public enum Backend { NONE, HTTP, ONNX }

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_TIMEOUT_MS = 15000;

    public final Backend backend;
    public final String host;
    public final int port;

    /** 视觉快速模型名（屏幕元素定位用，要求延迟低，不要求推理深度）。 */
    public final String visionModel;

    /** 决策模型名（下一步动作选择用，要求指令遵循能力）。 */
    public final String deciderModel;

    public final int timeoutMs;
    public final int maxTokens;

    public LocalModelConfig(Backend backend, String host, int port, String visionModel,
                            String deciderModel, int timeoutMs, int maxTokens) {
        this.backend = backend == null ? Backend.NONE : backend;
        this.host = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host.trim();
        this.port = isValidTcpPort(port) ? port : DEFAULT_HTTP_PORT;
        this.visionModel = (visionModel == null || visionModel.trim().isEmpty())
                ? "qwen2-vl-2b-instruct" : visionModel.trim();
        this.deciderModel = (deciderModel == null || deciderModel.trim().isEmpty())
                ? "qwen2.5-1.5b-instruct" : deciderModel.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.maxTokens = maxTokens > 0 ? maxTokens : 512;
    }

    public static boolean isValidTcpPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * Checks host syntax without resolving DNS. Empty input is valid because the config
     * constructor intentionally defaults it to loopback.
     */
    public static boolean isValidHost(String host) {
        if (host == null || host.trim().isEmpty()) return true;
        String value = host.trim();
        boolean hasOpeningBracket = value.startsWith("[");
        boolean hasClosingBracket = value.endsWith("]");
        if (hasOpeningBracket != hasClosingBracket) return false;
        if (hasOpeningBracket) value = value.substring(1, value.length() - 1);
        if (value.isEmpty()) return false;

        if (value.indexOf(':') >= 0) {
            return isValidIpv6Literal(value);
        }
        if (hasOpeningBracket) return false;

        if (value.matches("[0-9.]+")) {
            return isValidIpv4Literal(value);
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        String domain = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        if (domain.isEmpty() || domain.length() > 253) return false;
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")) return false;
        }
        return true;
    }

    private static boolean isValidIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            try {
                if (Integer.parseInt(octet) > 255) return false;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIpv6Literal(String value) {
        if (value.indexOf('%') >= 0) return false;
        int compression = value.indexOf("::");
        boolean compressed = compression >= 0;
        if (compressed && compression != value.lastIndexOf("::")) return false;
        if (!compressed && (value.startsWith(":") || value.endsWith(":"))) return false;

        String[] groups = value.split(":", -1);
        int groupCount = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) {
                if (!compressed) return false;
                continue;
            }
            if (group.indexOf('.') >= 0) {
                if (i != groups.length - 1 || !isValidIpv4Literal(group)) return false;
                groupCount += 2;
            } else {
                if (group.length() > 4) return false;
                for (int j = 0; j < group.length(); j++) {
                    char c = group.charAt(j);
                    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                            || (c >= 'A' && c <= 'F'))) return false;
                }
                groupCount++;
            }
        }
        return compressed ? groupCount < 8 : groupCount == 8;
    }

    public static LocalModelConfig disabled() {
        return new LocalModelConfig(Backend.NONE, null, 0, null, null, 0, 0);
    }

    public static LocalModelConfig defaultHttp() {
        return new LocalModelConfig(Backend.HTTP, "127.0.0.1", DEFAULT_HTTP_PORT,
                null, null, DEFAULT_TIMEOUT_MS, 512);
    }

    public boolean enabled() {
        return backend != Backend.NONE;
    }

    /** OpenAI 兼容的 chat completions 端点。 */
    public String chatEndpoint() {
        String authorityHost = host;
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            authorityHost = "[" + host + "]";
        }
        return "http://" + authorityHost + ":" + port + "/v1/chat/completions";
    }

    public String fingerprint() {
        return backend + "|" + host + ":" + port + "|" + visionModel + "|" + deciderModel;
    }
}
