package com.agentruntime.goal;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识卡：只给"眼前这个应用"的一张，而且永远包在不可信块里。
 *
 * <p>知识卡是资料，不是指令；和屏幕冲突时以屏幕为准。
 */
public final class GoalKnowledge {
    private static final String DIR = "knowledge/";
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("com.tencent.mm", "wechat.md");
    }

    private GoalKnowledge() { }

    /** 当前前台应用的知识卡正文；没有就返回空串。 */
    public static String cardFor(Context context, String packageName) {
        if (context == null || packageName == null) return "";
        String file = FILES.get(packageName);
        if (file == null) return "";
        try {
            AssetManager assets = context.getAssets();
            try (InputStream in = assets.open(DIR + file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return out.toString(StandardCharsets.UTF_8.name()).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 只包一层：模型看到的是数据，不是命令。 */
    public static String untrustedBlock(String card) {
        if (card == null || card.trim().isEmpty()) return "";
        return "<untrusted_screen_data>\n" + card.trim() + "\n</untrusted_screen_data>";
    }

    /** 供系统提示拼接：眼前应用的知识卡，或空。 */
    public static String forPrompt(Context context, String packageName) {
        return untrustedBlock(cardFor(context, packageName));
    }
}
