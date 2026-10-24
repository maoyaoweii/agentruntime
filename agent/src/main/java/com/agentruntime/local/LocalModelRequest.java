package com.agentruntime.local;

/**
 * 一次本地模型调用的请求。
 *
 * <p>刻意保持扁平：本地小模型上下文有限，不值得为它设计复杂的多轮结构。
 */
public final class LocalModelRequest {

    public final String model;
    public final String systemPrompt;
    public final String userPrompt;

    /** 可选的图片（已编码的 JPEG/PNG 字节），视觉模型才会用到。 */
    public final byte[] imageBytes;
    public final String imageMime;

    public final int maxTokens;
    public final double temperature;
    public final int timeoutMs;

    private LocalModelRequest(Builder b) {
        this.model = b.model;
        this.systemPrompt = b.systemPrompt;
        this.userPrompt = b.userPrompt;
        this.imageBytes = b.imageBytes;
        this.imageMime = b.imageMime == null ? "image/jpeg" : b.imageMime;
        this.maxTokens = b.maxTokens > 0 ? b.maxTokens : 512;
        this.temperature = b.temperature;
        this.timeoutMs = b.timeoutMs > 0 ? b.timeoutMs : LocalModelConfig.DEFAULT_TIMEOUT_MS;
    }

    public boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0;
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    public static final class Builder {
        private final String model;
        private String systemPrompt;
        private String userPrompt;
        private byte[] imageBytes;
        private String imageMime;
        private int maxTokens = 512;
        private double temperature = 0.1;
        private int timeoutMs;

        Builder(String model) {
            this.model = model;
        }

        public Builder system(String v) { this.systemPrompt = v; return this; }
        public Builder user(String v) { this.userPrompt = v; return this; }
        public Builder image(byte[] bytes, String mime) {
            this.imageBytes = bytes;
            this.imageMime = mime;
            return this;
        }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder timeout(int ms) { this.timeoutMs = ms; return this; }

        public LocalModelRequest build() {
            return new LocalModelRequest(this);
        }
    }
}
