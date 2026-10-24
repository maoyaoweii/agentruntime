package com.agentruntime.env;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 运行时环境快照：描述"Agent 现在处在什么设备、什么配置、什么状态下"。
 *
 * <p>这是纯数据对象，不依赖任何 Android API，便于在 JVM 单测中直接构造与断言。
 * 采集逻辑见 {@link EnvironmentProbe}。
 *
 * <p>存在的意义：规划器如果不知道屏幕多大、语言是什么、是否深色模式、有没有无障碍服务，
 * 就只能输出"看似合理但落不了地"的动作序列。环境必须进入提示词，而不是留在代码里。
 */
public final class EnvironmentSnapshot {

    /** 快照格式版本，字段变更时递增，便于审计回溯。 */
    public static final String VERSION = "1.1";

    public final int sdkInt;
    public final String release;
    public final String manufacturer;
    public final String model;

    public final int screenWidth;
    public final int screenHeight;
    public final int densityDpi;
    public final float fontScale;
    public final int rotation;

    public final String language;
    public final String country;
    public final boolean nightMode;

    public final int batteryPercent;
    public final boolean charging;
    public final String network;

    public final int brightness;
    public final boolean a11yConnected;
    public final boolean canCapture;
    public final long timestamp;

    private EnvironmentSnapshot(Builder b) {
        this.sdkInt = b.sdkInt;
        this.release = b.release;
        this.manufacturer = b.manufacturer;
        this.model = b.model;
        this.screenWidth = b.screenWidth;
        this.screenHeight = b.screenHeight;
        this.densityDpi = b.densityDpi;
        this.fontScale = b.fontScale;
        this.rotation = b.rotation;
        this.language = b.language;
        this.country = b.country;
        this.nightMode = b.nightMode;
        this.batteryPercent = b.batteryPercent;
        this.charging = b.charging;
        this.network = b.network;
        this.brightness = b.brightness;
        this.a11yConnected = b.a11yConnected;
        this.canCapture = b.canCapture;
        this.timestamp = b.timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 设备是否具备视觉通道（截图能力）。 */
    public boolean visionCapable() {
        return canCapture;
    }

    /** 屏幕是否处于可用尺寸（排除采集失败的 0 值）。 */
    public boolean hasValidScreen() {
        return screenWidth > 0 && screenHeight > 0;
    }

    /** 语言是否中文环境，影响坐标动作的文本输入策略。 */
    public boolean isChineseLocale() {
        return "zh".equalsIgnoreCase(language);
    }

    /** 环境指纹：用于判断环境是否发生变化，避免重复构建提示词。 */
    public String fingerprint() {
        return sdkInt + "|" + screenWidth + "x" + screenHeight + "|" + densityDpi
                + "|" + fontScale + "|" + rotation + "|" + language + "|" + nightMode
                + "|" + a11yConnected + "|" + canCapture;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            o.put("sdk_int", sdkInt);
            o.put("release", nullSafe(release));
            o.put("manufacturer", nullSafe(manufacturer));
            o.put("model", nullSafe(model));
            o.put("screen_width", screenWidth);
            o.put("screen_height", screenHeight);
            o.put("density_dpi", densityDpi);
            o.put("font_scale", fontScale);
            o.put("rotation", rotation);
            o.put("language", nullSafe(language));
            o.put("country", nullSafe(country));
            o.put("night_mode", nightMode);
            o.put("battery_percent", batteryPercent);
            o.put("charging", charging);
            o.put("network", nullSafe(network));
            o.put("brightness", brightness);
            o.put("a11y_connected", a11yConnected);
            o.put("can_capture", canCapture);
            o.put("timestamp", timestamp);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * 生成注入提示词的环境段落。
     *
     * <p>刻意保持短：每多一行都在消耗模型上下文。只放会影响决策的字段，
     * 厂商型号只在需要判断 ROM 差异时才出现。
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("<environment>\n");
        sb.append("android=").append(sdkInt).append(" (").append(nullSafe(release)).append(")\n");
        sb.append("screen=").append(screenWidth).append("x").append(screenHeight)
                .append(" dpi=").append(densityDpi)
                .append(" font_scale=").append(fontScale)
                .append(" rotation=").append(rotation).append("\n");
        sb.append("locale=").append(nullSafe(language)).append("-").append(nullSafe(country))
                .append(" night_mode=").append(nightMode ? "on" : "off").append("\n");
        sb.append("device=").append(nullSafe(manufacturer)).append(" ").append(nullSafe(model)).append("\n");
        sb.append("battery=").append(batteryPercent).append("%")
                .append(charging ? " charging" : "")
                .append(" network=").append(nullSafe(network)).append("\n");
        sb.append("a11y_connected=").append(a11yConnected)
                .append(" screenshot_capable=").append(canCapture).append("\n");
        sb.append("</environment>");
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }

    public static final class Builder {
        private int sdkInt;
        private String release = "unknown";
        private String manufacturer = "unknown";
        private String model = "unknown";
        private int screenWidth;
        private int screenHeight;
        private int densityDpi = 160;
        private float fontScale = 1.0f;
        private int rotation;
        private String language = "unknown";
        private String country = "unknown";
        private boolean nightMode;
        private int batteryPercent = -1;
        private boolean charging;
        private String network = "unknown";
        private int brightness = -1;
        private boolean a11yConnected;
        private boolean canCapture;
        private long timestamp = System.currentTimeMillis();

        public Builder sdkInt(int v) { this.sdkInt = v; return this; }
        public Builder release(String v) { this.release = v; return this; }
        public Builder manufacturer(String v) { this.manufacturer = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder screen(int w, int h) { this.screenWidth = w; this.screenHeight = h; return this; }
        public Builder densityDpi(int v) { this.densityDpi = v; return this; }
        public Builder fontScale(float v) { this.fontScale = v; return this; }
        public Builder rotation(int v) { this.rotation = v; return this; }
        public Builder locale(String lang, String ctry) { this.language = lang; this.country = ctry; return this; }
        public Builder nightMode(boolean v) { this.nightMode = v; return this; }
        public Builder battery(int percent, boolean charging) {
            this.batteryPercent = percent;
            this.charging = charging;
            return this;
        }
        public Builder network(String v) { this.network = v; return this; }
        public Builder brightness(int v) { this.brightness = v; return this; }
        public Builder a11y(boolean connected, boolean canCapture) {
            this.a11yConnected = connected;
            this.canCapture = canCapture;
            return this;
        }
        public Builder timestamp(long v) { this.timestamp = v; return this; }

        public EnvironmentSnapshot build() {
            return new EnvironmentSnapshot(this);
        }
    }
}
