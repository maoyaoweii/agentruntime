package com.agentruntime.vision;

import com.agentruntime.local.LocalModelProvider;
import com.agentruntime.local.LocalModelReply;
import com.agentruntime.local.LocalModelRequest;
import com.agentruntime.prompt.PromptBuilder;
import com.agentruntime.prompt.PromptGuard;
import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 混合视觉定位器：Computer Use 的"看一眼再点"能力。
 *
 * <p>三级降级链，从强到弱：
 * <ol>
 *   <li><b>本地 VLM</b>：截图 + 目标描述 → 模型给出坐标。能处理自绘控件、
 *       Canvas 页面、WebView 里节点树缺失的元素。</li>
 *   <li><b>无障碍节点兜底</b>：用目标文本在节点树里找可点击节点，取边界框中心。
 *       精度高、零延迟，但只对结构清晰的控件有效。</li>
 *   <li><b>失败</b>：返回明确原因，绝不猜坐标。</li>
 * </ol>
 *
 * <p>这个顺序是有意为之：视觉模型慢且不可靠，但它覆盖节点树覆盖不到的地方。
 * 反过来（先视觉再节点）会让每个普通点击都多花一次模型推理。
 */
public final class VisualGrounder {

    /** 定位结果。 */
    public static final class GroundResult {
        public final boolean ok;
        public final int x;
        public final int y;
        public final String code;
        public final String detail;
        public final float confidence;
        public final String label;

        private GroundResult(boolean ok, int x, int y, String code, String detail,
                             float confidence, String label) {
            this.ok = ok;
            this.x = x;
            this.y = y;
            this.code = code;
            this.detail = detail;
            this.confidence = confidence;
            this.label = label;
        }

        static GroundResult ok(int x, int y, String code, String detail, float conf, String label) {
            return new GroundResult(true, x, y, code, detail, conf, label);
        }

        static GroundResult fail(String code, String detail) {
            return new GroundResult(false, -1, -1, code, detail, 0f, null);
        }

        public String describe() {
            return code + " " + detail + (ok ? " @(" + x + "," + y + ")" : "");
        }
    }

    /** 模型置信度低于此值即认为不可靠，转节点兜底。 */
    public static final float MIN_CONFIDENCE = 0.5f;

    private final ScreenCapturer capturer;
    private final java.util.function.Supplier<LocalModelProvider> visionSupplier;

    /**
     * @param capturer       截图通道
     * @param visionSupplier 视觉模型供应器（每次调用时取最新实例，配置变更后自动生效）
     */
    public VisualGrounder(ScreenCapturer capturer,
                          java.util.function.Supplier<LocalModelProvider> visionSupplier) {
        this.capturer = capturer;
        this.visionSupplier = visionSupplier;
    }

    private LocalModelProvider provider() {
        return visionSupplier == null ? null : visionSupplier.get();
    }

    /** 视觉通道是否可用（有截图能力且有视觉模型）。 */
    public boolean visionAvailable() {
        LocalModelProvider p = provider();
        return capturer != null && ScreenCapturer.supported()
                && p != null && p.available() && p.supportsVision();
    }

    /**
     * 定位目标。
     *
     * @param target     目标描述，例如"发送按钮"
     * @param state      当前界面状态（用于节点兜底与树摘要）
     * @param allowVision 是否允许走视觉（调用方可关闭以省时）
     */
    public GroundResult ground(String target, WorldState state, boolean allowVision) {
        if (target == null || target.trim().isEmpty()) {
            return GroundResult.fail("TARGET_EMPTY", "target description is empty");
        }

        // 1) 本地 VLM
        if (allowVision && visionAvailable()) {
            GroundResult v = groundByVision(target, state);
            if (v.ok && v.confidence >= MIN_CONFIDENCE) {
                return v;
            }
            // 置信度不足时继续走节点兜底，但保留视觉失败原因
            GroundResult n = groundByNodes(target, state);
            if (n.ok) return n;
            return GroundResult.fail(v.code,
                    "vision low confidence " + v.confidence + "; node fallback: " + n.detail);
        }

        // 2) 节点兜底
        GroundResult n = groundByNodes(target, state);
        if (n.ok) return n;
        return GroundResult.fail(visionAvailable() ? n.code : "VISION_UNAVAILABLE",
                "no visual model and " + n.detail);
    }

    private GroundResult groundByVision(String target, WorldState state) {
        LocalModelProvider p = provider();
        if (p == null) {
            return GroundResult.fail("VISION_UNAVAILABLE", "no vision provider");
        }
        ScreenFrame frame = capturer.capture();
        if (frame == null || !frame.valid()) {
            return GroundResult.fail("CAPTURE_FAILED", "screenshot unavailable");
        }
        String digest = digestTree(state);
        PromptBuilder.Bundle prompt = PromptBuilder.buildGrounding(null, target, digest);

        LocalModelRequest req = LocalModelRequest.builder(p.id())
                .system(prompt.systemPrompt)
                .user(prompt.userPrompt)
                .image(frame.imageBytes, frame.mime)
                .maxTokens(128)
                .temperature(0.0)
                .timeout(12000)
                .build();

        LocalModelReply reply = p.complete(req);
        if (!reply.usable()) {
            return GroundResult.fail("VISION_FAILED", reply.code);
        }
        return parseGrounding(reply.text, frame, target);
    }

    /** 解析模型返回的坐标 JSON 并映射到真实屏幕。 */
    public static GroundResult parseGrounding(String raw, ScreenFrame frame, String target) {
        if (raw == null) return GroundResult.fail("VISION_EMPTY", "empty reply");
        try {
            JSONObject o = com.agentruntime.dsl.OutputFormat.parseObject(raw);
            com.agentruntime.dsl.OutputFormat.Validation v =
                    com.agentruntime.dsl.OutputFormat.validateGrounding(o);
            if (!v.ok) {
                return GroundResult.fail("VISION_BAD_FORMAT", v.error);
            }
            if (!o.optBoolean("found", false)) {
                return GroundResult.fail("VISION_NOT_FOUND", "model reported found=false: " + target);
            }
            int mx = o.optInt("x", -1);
            int my = o.optInt("y", -1);
            if (mx < 0 || my < 0) {
                return GroundResult.fail("VISION_BAD_COORD", "x/y missing or negative");
            }
            float conf = (float) o.optDouble("confidence", 0.0);
            CoordinateMapper.Point p = CoordinateMapper.mapToScreen(
                    mx, my, frame.imageWidth, frame.imageHeight,
                    frame.screenWidth, frame.screenHeight);
            if (!p.inBounds) {
                return GroundResult.fail("VISION_OUT_OF_BOUNDS",
                        "model coord " + mx + "," + my + " maps outside screen");
            }
            String label = PromptGuard.stripControl(o.optString("label", ""));
            return GroundResult.ok(p.x, p.y, "VISION_OK",
                    "img=" + frame.imageWidth + "x" + frame.imageHeight
                            + " scale=" + String.format(java.util.Locale.US, "%.3f", frame.scale),
                    conf, label);
        } catch (JSONException e) {
            return GroundResult.fail("VISION_BAD_JSON", "cannot parse: " + truncate(raw, 80));
        }
    }

    /** 节点兜底：按文本/描述找可点击节点，取中心点。 */
    private GroundResult groundByNodes(String target, WorldState state) {
        if (state == null) return GroundResult.fail("NO_STATE", "world state unavailable");
        String needle = target.trim();
        List<UiNode> hits = state.findNodes(n -> n.visible && n.clickable
                && (n.containsText(needle) || needle.equalsIgnoreCase(safe(n.text))
                || needle.equalsIgnoreCase(safe(n.contentDescription))));
        if (hits.isEmpty()) {
            // 放宽到任意可见节点（有些控件 clickable 标记不可靠）
            hits = state.findNodes(n -> n.visible
                    && (n.containsText(needle) || needle.equalsIgnoreCase(safe(n.text))));
        }
        if (hits.isEmpty()) {
            return GroundResult.fail("NODE_NOT_FOUND", "no node matches: " + needle);
        }
        UiNode n = hits.get(0);
        CoordinateMapper.Point p = CoordinateMapper.centerOf(n.left, n.top, n.right, n.bottom,
                screenWidth(state), screenHeight(state));
        if (!p.inBounds) {
            return GroundResult.fail("NODE_OUT_OF_BOUNDS", "node bounds outside screen");
        }
        return GroundResult.ok(p.x, p.y, "NODE_CENTER_OK",
                "node=" + safe(n.resourceId) + " bounds=" + n.left + "," + n.top + "," + n.right + "," + n.bottom,
                1.0f, safe(n.text));
    }

    /** 节点树摘要：给模型一点上下文，但必须限长（预算有限）。 */
    public static String digestTree(WorldState state) {
        if (state == null || state.root == null) return "";
        StringBuilder sb = new StringBuilder();
        List<UiNode> flat = state.root.flat();
        int count = 0;
        for (UiNode n : flat) {
            if (!n.visible) continue;
            String label = n.text != null ? n.text : n.contentDescription;
            if (label == null || label.trim().isEmpty()) continue;
            if (sb.length() > 900) break;
            sb.append(label.length() > 20 ? label.substring(0, 20) : label)
                    .append(n.clickable ? "[c]" : "")
                    .append(' ');
            count++;
            if (count >= 40) break;
        }
        return sb.toString().trim();
    }

    private static int screenWidth(WorldState state) {
        int max = 0;
        if (state != null && state.root != null) {
            for (UiNode n : state.root.flat()) max = Math.max(max, n.right);
        }
        return max > 0 ? max : 1080;
    }

    private static int screenHeight(WorldState state) {
        int max = 0;
        if (state != null && state.root != null) {
            for (UiNode n : state.root.flat()) max = Math.max(max, n.bottom);
        }
        return max > 0 ? max : 1920;
    }

    static String extractJson(String content) {
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) return content.trim();
        return content.substring(s, e + 1);
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
