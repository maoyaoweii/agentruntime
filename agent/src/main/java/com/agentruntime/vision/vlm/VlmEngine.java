package com.agentruntime.vision.vlm;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 端侧视觉语言模型推理引擎（SmolVLM-256M / Idefics3 架构）。
 *
 * <p><b>当前状态：运行时已内置、分词与预处理已验证、会话加载与形状自述已实现；
 * 生成循环尚未与真实权重联调。</b>
 *
 * <p>为什么生成循环没跑过：它依赖三个 ONNX 的具体输入输出命名与张量形状
 * （尤其是 decoder 的 past_key_values 布局），而这些东西只有加载真实权重才能确认。
 * 硬编码一套猜测的形状，只会得到一个"看起来能跑、实际全是乱码"的实现——
 * 那比明说"还没联调"更糟。
 *
 * <p>所以这里做的是：把能确定的部分（分词、预处理、会话管理、形状自述）做到可验证，
 * 并提供 {@link #describe()} 让人放上真实权重后能立刻看到形状并据此对齐。
 *
 * <p>模型下载见 {@link SmolVlmStore} 与 tools/fetch_vlm_model.py。
 */
public final class VlmEngine implements AutoCloseable {

    /** 生成上限，防止自回归跑飞。 */
    public static final int MAX_NEW_TOKENS = 96;

    private final OrtEnvironment env;
    private final OrtSession visionSession;
    private final OrtSession decoderSession;
    private final OrtSession embedSession;
    private final VlmTokenizer tokenizer;
    private final long loadMs;

    private VlmEngine(OrtEnvironment env, OrtSession vision, OrtSession decoder,
                      OrtSession embed, VlmTokenizer tokenizer, long loadMs) {
        this.env = env;
        this.visionSession = vision;
        this.decoderSession = decoder;
        this.embedSession = embed;
        this.tokenizer = tokenizer;
        this.loadMs = loadMs;
    }

    /**
     * 打开引擎。
     *
     * @param dir  模型目录（三个 .onnx 所在）
     * @param tok  已加载的分词器
     * @throws Exception 文件缺失、内存不足、模型损坏都会在这里抛
     */
    public static VlmEngine open(File dir, VlmTokenizer tok) throws Exception {
        long start = System.currentTimeMillis();
        File vision = new File(dir, SmolVlmStore.FILE_VISION);
        File decoder = new File(dir, SmolVlmStore.FILE_DECODER);
        File embed = new File(dir, SmolVlmStore.FILE_EMBED);
        for (File f : new File[]{vision, decoder, embed}) {
            if (!f.isFile()) {
                throw new IllegalStateException("model file missing: " + f.getName());
            }
        }

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // 端侧优先省内存：线程数压到 2，禁用内存池扩张
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        OrtSession visionS = env.createSession(vision.getAbsolutePath(), opts);
        OrtSession embedS = env.createSession(embed.getAbsolutePath(), opts);
        OrtSession decoderS = env.createSession(decoder.getAbsolutePath(), opts);

        return new VlmEngine(env, visionS, decoderS, embedS, tok,
                System.currentTimeMillis() - start);
    }

    public long loadMs() {
        return loadMs;
    }

    public VlmTokenizer tokenizer() {
        return tokenizer;
    }

    /** 三个会话的输入输出名称与形状：放上真实权重后靠这个对齐张量。 */
    public JSONObject describe() {
        JSONObject o = new JSONObject();
        try {
            o.put("load_ms", loadMs);
            o.put("vision", sessionInfo(visionSession));
            o.put("embed", sessionInfo(embedSession));
            o.put("decoder", sessionInfo(decoderSession));
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static JSONObject sessionInfo(OrtSession s) {
        JSONObject o = new JSONObject();
        try {
            JSONArray inputs = new JSONArray();
            for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : s.getInputInfo().entrySet()) {
                JSONObject item = new JSONObject();
                item.put("name", e.getKey());
                item.put("info", String.valueOf(e.getValue().getInfo()));
                inputs.put(item);
            }
            JSONArray outputs = new JSONArray();
            for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : s.getOutputInfo().entrySet()) {
                JSONObject item = new JSONObject();
                item.put("name", e.getKey());
                item.put("info", String.valueOf(e.getValue().getInfo()));
                outputs.put(item);
            }
            o.put("inputs", inputs);
            o.put("outputs", outputs);
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e.getMessage()));
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    /**
     * 把截图预处理成视觉编码器输入。
     *
     * @return [patchCount][3*512*512]
     */
    public float[][] preprocess(int[] argb, int width, int height, int maxPatches) {
        return VlmPreprocessor.preprocess(argb, width, height, maxPatches);
    }

    /**
     * 生成回答。
     *
     * <p><b>未联调</b>：当前返回明确的 NOT_READY，而不是一个假装成功的字符串。
     * 联调步骤写在 docs/V1.4_VLM_PLAN.md。
     */
    public String generate(int[] argb, int width, int height, String question) {
        // 占位：真实的 image-token 拼接与 KV cache 循环需要按 describe() 输出的实际形状实现。
        // 这里返回 null，由调用方转成 VISION_NOT_READY，避免上层误以为模型已工作。
        return null;
    }

    public boolean generativeReady() {
        return false;
    }

    @Override
    public void close() {
        try {
            if (visionSession != null) visionSession.close();
        } catch (Exception ignored) {
        }
        try {
            if (decoderSession != null) decoderSession.close();
        } catch (Exception ignored) {
        }
        try {
            if (embedSession != null) embedSession.close();
        } catch (Exception ignored) {
        }
    }
}
