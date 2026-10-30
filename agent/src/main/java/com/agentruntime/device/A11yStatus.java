package com.agentruntime.device;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.agentruntime.service.AgentAccessibilityService;

import org.json.JSONObject;

/**
 * 无障碍的真实状态诊断。
 *
 * <p>真机反馈里最常见的一句是"我明明开了无障碍，App 却说不可用"。这中间其实有三层，
 * 任何一层断了表现都不一样，只报一个 boolean 永远说不清：
 * <ol>
 *   <li>系统设置里是否勾选了这个服务（{@code ENABLED_ACCESSIBILITY_SERVICES}）；</li>
 *   <li>系统是否真的把服务绑定起来了（{@link AgentAccessibilityService#getInstance()}）；</li>
 *   <li>绑定之后能否读到窗口节点（厂商 ROM 上服务"半死"时这一层会单独失败）。</li>
 * </ol>
 * 侧载安装的 App 在 Android 13+ 还会被"受限设置"拦住：列表里能看见，开关却点不动，
 * 必须先到应用详情页允许受限设置。这里的 {@link #remedy} 就是给这种情况准备的。
 */
public final class A11yStatus {
    public static final String SERVICE_CLASS = "com.agentruntime.service.AgentAccessibilityService";

    private A11yStatus() { }

    public static String component(Context context) {
        return context.getPackageName() + "/" + SERVICE_CLASS;
    }

    /** 系统设置里是否勾选（不代表已绑定）。 */
    public static boolean enabledInSettings(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.isEmpty()) return false;
        String target = component(context);
        String shortTarget = context.getPackageName() + "/." + SERVICE_CLASS.substring(SERVICE_CLASS.lastIndexOf('.') + 1);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(flat);
        while (splitter.hasNext()) {
            String entry = splitter.next();
            if (entry == null) continue;
            if (entry.equalsIgnoreCase(target) || entry.equalsIgnoreCase(shortTarget)) return true;
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (name != null && name.getPackageName().equals(context.getPackageName())
                    && name.getClassName().equals(SERVICE_CLASS)) return true;
        }
        return false;
    }

    public static boolean bound() {
        return AgentAccessibilityService.getInstance() != null;
    }

    public static boolean rootReady() {
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        return service != null && service.canReadRootNow();
    }

    /** 0 正常，1 未勾选，2 勾选了但没绑定，3 绑定了但读不到界面。 */
    public static int level(Context context) {
        if (!enabledInSettings(context)) return 1;
        if (!bound()) return 2;
        return rootReady() ? 0 : 3;
    }

    public static JSONObject diagnose(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("enabled_in_settings", enabledInSettings(context));
            out.put("bound", bound());
            out.put("root_ready", rootReady());
            out.put("level", level(context));
            out.put("component", component(context));
            out.put("sdk_int", Build.VERSION.SDK_INT);
            out.put("restricted_settings_risk", Build.VERSION.SDK_INT >= 33);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 一句话说清现在卡在哪一层。 */
    public static String summary(Context context) {
        switch (level(context)) {
            case 0: return "无障碍可用，能读到界面";
            case 1: return "系统里没有勾选 DSH Mobile 辅助服务";
            case 2: return "系统已勾选，但服务没有绑定（受限设置或被系统回收）";
            default: return "服务已绑定，但读不到界面节点（厂商 ROM 半死状态）";
        }
    }

    /** 针对当前层级给出下一步该点哪里。 */
    public static String remedy(Context context) {
        switch (level(context)) {
            case 0: return "无需处理。";
            case 1: return "打开系统设置 → 无障碍 → 已下载的应用 → DSH Mobile 辅助服务，打开开关。";
            case 2:
                return "这是侧载安装最常见的坑：Android 13 起，非应用商店安装的 App 会被「受限设置」拦住，"
                        + "列表里能看见、开关却点不动或点了又自动关。\n\n"
                        + "处理顺序：\n"
                        + "1. 设置 → 应用 → DSH Mobile → 右上角 ⋮ → 允许受限设置；\n"
                        + "2. 回到 设置 → 无障碍 → DSH Mobile 辅助服务，先关再开；\n"
                        + "3. 小米/红米（HyperOS/MIUI）再到 应用信息 → 省电策略 设为「无限制」，允许自启动。";
            default:
                return "服务在，但窗口树是空的。先切到要操作的 App（例如微信/B站）再试；"
                        + "仍然失败就到 设置 → 应用 → DSH Mobile → 强行停止，然后重新打开无障碍开关。";
        }
    }
}
