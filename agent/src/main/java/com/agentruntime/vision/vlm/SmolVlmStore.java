package com.agentruntime.vision.vlm;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 端侧视觉模型的文件管理。
 *
 * <p>模型为什么不打进 APK：SmolVLM-256M 的 ONNX 三件套合计约 573MB
 * （vision_encoder 374MB + decoder_q4 86MB + embed_tokens 113MB），
 * 打进 APK 会让安装包变成 600MB 量级，且 Play 与多数分发渠道都不接受。
 *
 * <p>所以策略是：<b>运行时内置，权重外置</b>。
 * 目录放在应用的外部私有目录（getExternalFilesDir），用户可以用文件管理器或 adb 把
 * 下载好的 .onnx 拷进去，不需要 root。
 *
 * <p>下载脚本见 tools/fetch_vlm_model.py。
 */
public final class SmolVlmStore {

    /** 相对 getExternalFilesDir 的模型目录。 */
    public static final String DIR_NAME = "models/smolvlm";

    /** 必需文件与最小期望体积（用于识别半截下载）。 */
    public static final String FILE_VISION = "vision_encoder.onnx";
    public static final String FILE_DECODER = "decoder_model_merged_q4.onnx";
    public static final String FILE_EMBED = "embed_tokens.onnx";

    private static final long MIN_VISION = 300L * 1024 * 1024;
    private static final long MIN_DECODER = 60L * 1024 * 1024;
    private static final long MIN_EMBED = 90L * 1024 * 1024;

    private SmolVlmStore() {
    }

    /** 模型目录（可能不存在）。 */
    public static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, DIR_NAME);
    }

    /** 状态快照。 */
    public static final class Status {
        public final boolean installed;
        public final String missing;
        public final long totalBytes;
        public final String dir;

        Status(boolean installed, String missing, long totalBytes, String dir) {
            this.installed = installed;
            this.missing = missing;
            this.totalBytes = totalBytes;
            this.dir = dir;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("installed", installed);
                o.put("missing", missing == null ? "" : missing);
                o.put("total_mb", Math.round(totalBytes / 1048576.0));
                o.put("dir", dir);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public String describe() {
            return installed
                    ? "已安装 " + Math.round(totalBytes / 1048576.0) + "MB"
                    : "缺少 " + missing;
        }
    }

    /** 检查模型是否就绪。 */
    public static Status check(Context context) {
        File d = dir(context);
        File vision = new File(d, FILE_VISION);
        File decoder = new File(d, FILE_DECODER);
        File embed = new File(d, FILE_EMBED);

        StringBuilder missing = new StringBuilder();
        long total = 0;

        total += verify(vision, MIN_VISION, FILE_VISION, missing);
        total += verify(decoder, MIN_DECODER, FILE_DECODER, missing);
        total += verify(embed, MIN_EMBED, FILE_EMBED, missing);

        return new Status(missing.length() == 0,
                missing.length() == 0 ? null : missing.toString(),
                total, d.getAbsolutePath());
    }

    private static long verify(File f, long minBytes, String name, StringBuilder missing) {
        if (!f.isFile()) {
            if (missing.length() > 0) missing.append(", ");
            missing.append(name).append("(缺失)");
            return 0;
        }
        long len = f.length();
        if (len < minBytes) {
            if (missing.length() > 0) missing.append(", ");
            missing.append(name).append("(不完整 ").append(len / 1048576).append("MB)");
            return len;
        }
        return len;
    }

    public static File visionFile(Context c) {
        return new File(dir(c), FILE_VISION);
    }

    public static File decoderFile(Context c) {
        return new File(dir(c), FILE_DECODER);
    }

    public static File embedFile(Context c) {
        return new File(dir(c), FILE_EMBED);
    }
}
