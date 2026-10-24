package com.agentruntime.local;

/**
 * ONNX Runtime Mobile 后端（进程内推理）。
 *
 * <p><b>V1.4 现状：runtime 已真正打包进 APK（onnxruntime-android 1.18.0，arm64），
 * 但推理图尚未与真实权重联调。</b>
 *
 * <p>为什么还没联调：SmolVLM-256M 的生成循环依赖三个 ONNX 的具体输入输出命名与
 * past_key_values 布局，这些只有加载真实权重才能确定。硬编码一套猜测的形状，
 * 只会得到"看起来能跑、实际全是乱码"的实现。
 *
 * <p>能确定的部分已经做完并测过：分词器（真实词表 49152 词、BPE 可逆）、
 * 图像预处理（Idefics3 网格规划、SigLIP 归一化），见 {@code vision/vlm/}。
 *
 * <p>本类负责探测 runtime 是否存在，并在推理未就绪时返回明确原因，
 * 避免上层误以为视觉通道已可用。
 */
public final class OnnxLocalModelProvider implements LocalModelProvider {

    public static final String ID = "local-onnx";

    private static final String ORT_ENV_CLASS = "ai.onnxruntime.OrtEnvironment";
    private static final String ORT_SESSION_CLASS = "ai.onnxruntime.OrtSession";

    private final LocalModelConfig config;
    private final boolean runtimePresent;
    private final String runtimeDetail;

    public OnnxLocalModelProvider(LocalModelConfig config) {
        this.config = config;
        Probe p = probeRuntime();
        this.runtimePresent = p.present;
        this.runtimeDetail = p.detail;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return config != null
                && config.backend == LocalModelConfig.Backend.ONNX
                && runtimePresent;
    }

    @Override
    public boolean supportsVision() {
        // Native runtime presence is not a usable VLM: model weights and the
        // generation loop are not wired yet, so callers must use non-vision fallbacks.
        return false;
    }

    @Override
    public LocalModelReply complete(LocalModelRequest request) {
        if (!available()) {
            return LocalModelReply.fail(
                    runtimePresent ? "PROVIDER_DISABLED" : "ORT_RUNTIME_MISSING",
                    ID, 0, request == null ? null : request.model);
        }
        // 权重加载与推理图尚未接入：宁可明确失败，也不返回伪造结果。
        return LocalModelReply.fail("INFERENCE_NOT_IMPLEMENTED", ID, 0,
                request == null ? null : request.model);
    }

    /** runtime 探测结果，供自检接口展示。 */
    public String runtimeDetail() {
        return runtimeDetail;
    }

    private static final class Probe {
        final boolean present;
        final String detail;

        Probe(boolean present, String detail) {
            this.present = present;
            this.detail = detail;
        }
    }

    private static Probe probeRuntime() {
        try {
            // 必须用 initialize=false：OrtEnvironment 的静态块会加载 native 库，
            // 一旦在这里触发初始化，JVM 单测环境就会因为找不到 .so 而误判为"未打包"。
            // 这里只回答"类在不在"，真正的 native 初始化留到创建 session 时。
            ClassLoader cl = OnnxLocalModelProvider.class.getClassLoader();
            Class.forName(ORT_ENV_CLASS, false, cl);
            Class.forName(ORT_SESSION_CLASS, false, cl);
            return new Probe(true, "onnxruntime classes found");
        } catch (ClassNotFoundException e) {
            return new Probe(false, "onnxruntime-android not bundled");
        } catch (Throwable t) {
            return new Probe(false, "probe failed: " + t.getClass().getSimpleName());
        }
    }
}
