package com.agentruntime.device;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 厂商 ROM 识别与"该去哪一页授权"的导航。
 *
 * <p>为什么需要这一层：Android 标准 API 只覆盖到"申请权限"，而国内 ROM 上还叠了
 * 三件额外的事——<b>自启动白名单</b>、<b>后台弹出界面</b>、<b>电池优化白名单</b>。
 * 这三样没有统一 API，只能跳到各家的设置页让用户手动点。
 * 跳不对页面，用户就会以为"这个 App 不好使"。
 *
 * <p>刻意不做的事：不尝试用反射去 <i>修改</i> 系统设置。那些私有 API 在多数 ROM 上
 * 要么不存在、要么需要系统签名，硬试的结果是随机崩溃。这里只负责"把用户送到门口"。
 *
 * <p>页面存在性无法在编译期验证（各家 ROM 版本之间类名会变），所以每个入口都给了
 * 候选列表，逐个试，全失败就退回"应用详情页"——那一步在任何 ROM 上都能到。
 */
public final class RomInfo {

    /** 一个授权入口：标题 + 说明 + 候选 Intent。 */
    public static final class Guide {
        public final String title;
        public final String detail;
        private final List<Intent> candidates;

        Guide(String title, String detail, List<Intent> candidates) {
            this.title = title;
            this.detail = detail;
            this.candidates = candidates;
        }

        /**
         * 尝试打开。返回 true 表示打开了某个页面（不一定是对的页面，但至少是 ROM 的设置页）。
         *
         * <p>先用 {@link Context#startActivity} 试候选，全部失败退回应用详情页。
         */
        public boolean open(Context ctx) {
            for (Intent i : candidates) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                    return true;
                } catch (Throwable ignored) {
                    // 该 ROM 没有这个页面，换下一个
                }
            }
            return openAppDetail(ctx);
        }
    }

    public final String rom;
    public final String brand;
    public final String model;
    public final int sdk;
    /** 已知会额外限制后台的 ROM。 */
    public final boolean aggressiveBackgroundPolicy;

    private RomInfo(String rom, String brand, String model, int sdk, boolean aggressive) {
        this.rom = rom;
        this.brand = brand;
        this.model = model;
        this.sdk = sdk;
        this.aggressiveBackgroundPolicy = aggressive;
    }

    // ------------------------------------------------------------ 识别

    /** 读系统属性。不存在或无权限时返回空串，不抛异常。 */
    public static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : ((String) v).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static RomInfo detect(Context ctx) {
        String manu = safe(Build.MANUFACTURER);
        String brand = safe(Build.BRAND);
        String model = safe(Build.MODEL);
        String rom;
        boolean aggressive = false;

        String miui = prop("ro.miui.ui.version.name");
        String hyper = prop("ro.mi.os.version.name");
        String emui = prop("ro.build.version.emui");
        String harmony = prop("hw_sc.build.platform.version");
        String magic = prop("ro.build.version.magic");
        String coloros = prop("ro.build.version.opporom");
        String originos = prop("ro.vivo.os.version");
        String flyme = prop("ro.build.display.id");
        String zui = prop("ro.com.zui.version");
        String oneui = prop("ro.build.version.oneui");

        if (!hyper.isEmpty()) {
            rom = "HyperOS " + hyper;
            aggressive = true;
        } else if (!miui.isEmpty()) {
            rom = "MIUI " + miui;
            aggressive = true;
        } else if (!harmony.isEmpty()) {
            rom = "HarmonyOS " + harmony;
            aggressive = true;
        } else if (!emui.isEmpty()) {
            rom = "EMUI " + emui;
            aggressive = true;
        } else if (!magic.isEmpty()) {
            rom = "MagicOS " + magic;
            aggressive = true;
        } else if (!coloros.isEmpty()) {
            rom = "ColorOS " + coloros;
            aggressive = true;
        } else if (!originos.isEmpty()) {
            rom = "OriginOS " + originos;
            aggressive = true;
        } else if (!oneui.isEmpty()) {
            rom = "One UI " + oneui;
        } else if (flyme != null && flyme.toLowerCase().contains("flyme")) {
            rom = "Flyme";
            aggressive = true;
        } else if (!zui.isEmpty()) {
            rom = "ZUI " + zui;
            aggressive = true;
        } else {
            rom = manu.isEmpty() ? "AOSP" : manu;
        }

        return new RomInfo(rom, brand, model, Build.VERSION.SDK_INT, aggressive);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 一行摘要，给状态条与自检信息用。 */
    public String summary() {
        return rom + " · " + brand + " " + model + " · API " + sdk;
    }

    /** 是否国产 ROM（这些 ROM 才有"自启动管理"这类页面）。 */
    public boolean isDomesticRom() {
        String m = (brand + " " + rom).toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("miui")
                || m.contains("huawei") || m.contains("honor") || m.contains("harmony")
                || m.contains("oppo") || m.contains("oneplus") || m.contains("realme")
                || m.contains("coloros") || m.contains("vivo") || m.contains("iqoo")
                || m.contains("meizu") || m.contains("flyme") || m.contains("lenovo")
                || m.contains("zuk") || m.contains("magic");
    }

    // ------------------------------------------------------------ 授权引导

    /**
     * 按当前 ROM 生成该看的授权清单。
     *
     * <p>顺序是有意排的：先给"不打开就完全不能用"的（无障碍），再给"影响稳定性"的
     * （自启动 / 电池），最后给"可选"的（后台弹出、精确闹钟）。
     */
    public List<Guide> guides(Context ctx) {
        List<Guide> out = new ArrayList<>();

        out.add(new Guide("无障碍服务", "不开启就无法读取界面、无法点击其他应用",
                singleton(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        out.add(new Guide("悬浮窗权限", "悬浮球与快捷面板需要它",
                singleton(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))));

        if (isDomesticRom()) {
            List<Intent> auto = new ArrayList<>();
            Intent miuiAuto = component("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity");
            Intent emuiAuto = component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
            Intent colorAuto = component("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity");
            Intent vivoAuto = component("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
            Intent flymeAuto = component("com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity");
            Intent magicAuto = component("com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
            Intent oneuiAuto = component("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity");
            for (Intent i : new Intent[]{miuiAuto, emuiAuto, colorAuto, vivoAuto,
                    flymeAuto, magicAuto, oneuiAuto}) {
                if (i != null) auto.add(i);
            }
            out.add(new Guide("自启动 / 后台启动",
                    "不开的话，进程被杀后任务不会再被唤起（这是最主要的功能缺失来源）", auto));

            List<Intent> popup = new ArrayList<>();
            Intent miuiPopup = component("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity");
            Intent vivoPopup = component("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity");
            if (miuiPopup != null) popup.add(miuiPopup);
            if (vivoPopup != null) popup.add(vivoPopup);
            out.add(new Guide("后台弹出界面", "MIUI / OriginOS 上不授权，悬浮窗会在后台被拦",
                    popup));
        }

        Intent battery = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        battery.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
        out.add(new Guide("电池优化白名单", "避免系统在息屏后冻结后台任务",
                singleton(battery)));

        return out;
    }

    // ------------------------------------------------------------ 工具

    private static Intent component(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Intent> singleton(Intent i) {
        List<Intent> l = new ArrayList<>(1);
        l.add(i);
        return l;
    }

    private static boolean openAppDetail(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
