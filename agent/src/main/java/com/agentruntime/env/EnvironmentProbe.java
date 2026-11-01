package com.agentruntime.env;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.agentruntime.service.AgentAccessibilityService;

/**
 * 环境采集器：把设备当前状态压成一份 {@link EnvironmentSnapshot}。
 *
 * <p>只读取公开 API，不申请额外权限（电池/网络状态用粘性广播与 ConnectivityManager，
 * 都不需要运行时授权）。采集失败一律降级为 known-unknown，不抛异常——
 * 环境信息缺失不应让任务直接失败。
 */
public final class EnvironmentProbe {

    /** 屏幕尺寸缓存的过期时间：旋转、分屏、字体缩放都可能改变结果。 */
    private static final long CACHE_TTL_MS = 3000;

    private final Context context;
    private EnvironmentSnapshot cached;
    private long cachedAt;

    public EnvironmentProbe(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 采集当前环境；3 秒内重复调用直接返回缓存（提示词构建会频繁取用）。 */
    public EnvironmentSnapshot snapshot() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_TTL_MS) {
            return cached;
        }
        EnvironmentSnapshot s = probe();
        cached = s;
        cachedAt = now;
        return s;
    }

    /** 强制重新采集，忽略缓存。 */
    public EnvironmentSnapshot refresh() {
        cached = null;
        return snapshot();
    }

    private EnvironmentSnapshot probe() {
        EnvironmentSnapshot.Builder b = EnvironmentSnapshot.builder()
                .sdkInt(Build.VERSION.SDK_INT)
                .release(Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE)
                .manufacturer(safe(Build.MANUFACTURER))
                .model(safe(Build.MODEL))
                .timestamp(System.currentTimeMillis());

        fillScreen(b);
        fillLocale(b);
        fillBattery(b);
        fillNetwork(b);
        fillBrightness(b);

        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        // "服务活着" 和 "读得到界面" 是两件事：进程被系统绑定后仍可能 getRootInActiveWindow() 恒为 null，
        // 只报 a11y_connected=true 会让模型以为能读屏，实际一条文字都拿不到。
        b.a11y(svc != null, svc != null && Build.VERSION.SDK_INT >= 30);
        // 第三层：系统设置里到底勾没勾。用户说"我开了"和系统记录不一致时，这一项能立刻分辨谁在说谎。
        boolean enabledInSettings;
        try {
            enabledInSettings = com.agentruntime.device.A11yStatus.enabledInSettings(context);
        } catch (Throwable t) {
            enabledInSettings = false;
        }
        b.a11yEnabled(enabledInSettings);
        b.a11yRoot(svc != null && svc.canReadRootNow());

        return b.build();
    }

    private void fillScreen(EnvironmentSnapshot.Builder b) {
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int w = 0;
            int h = 0;
            int rotation = 0;
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    Point p = new Point();
                    display.getRealSize(p);
                    w = p.x;
                    h = p.y;
                    rotation = display.getRotation() * 90;
                }
            }
            if (w <= 0 || h <= 0) {
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            b.screen(w, h)
                    .densityDpi(dm.densityDpi)
                    .fontScale(round2(context.getResources().getConfiguration().fontScale))
                    .rotation(rotation);
        } catch (Throwable ignored) {
            // 屏幕信息缺失时保持默认值，由 hasValidScreen() 兜底判断
        }
    }

    private void fillLocale(EnvironmentSnapshot.Builder b) {
        try {
            Configuration cfg = context.getResources().getConfiguration();
            String lang = cfg.locale != null ? cfg.locale.getLanguage() : "unknown";
            String ctry = cfg.locale != null ? cfg.locale.getCountry() : "unknown";
            boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            b.locale(lang, ctry).nightMode(night);
        } catch (Throwable ignored) {
            b.locale("unknown", "unknown").nightMode(false);
        }
    }

    private void fillBattery(EnvironmentSnapshot.Builder b) {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent intent = context.registerReceiver(null, filter);
            int level = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
            int status = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            int percent = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            b.battery(percent, charging);
        } catch (Throwable ignored) {
            b.battery(-1, false);
        }
    }

    private void fillNetwork(EnvironmentSnapshot.Builder b) {
        String type = "offline";
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    switch (info.getType()) {
                        case ConnectivityManager.TYPE_WIFI:
                            type = "wifi";
                            break;
                        case ConnectivityManager.TYPE_MOBILE:
                            type = "cellular";
                            break;
                        case ConnectivityManager.TYPE_ETHERNET:
                            type = "ethernet";
                            break;
                        default:
                            type = "other";
                            break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无 ACCESS_NETWORK_STATE 权限时保持 offline
        }
        b.network(type);
    }

    private void fillBrightness(EnvironmentSnapshot.Builder b) {
        int value = -1;
        try {
            value = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
        } catch (Throwable ignored) {
        }
        b.brightness(value);
    }

    /**
     * 读取上一次记录的环境指纹，用于判断环境是否发生跃变（换机/旋转/深浅色切换）。
     * 返回 null 表示首次运行。
     */
    public String previousFingerprint() {
        SharedPreferences sp = context.getSharedPreferences("env_probe", Context.MODE_PRIVATE);
        String v = sp.getString("fingerprint", null);
        return v == null || v.isEmpty() ? null : v;
    }

    /** 记录本次环境指纹。 */
    public void rememberFingerprint(String fingerprint) {
        if (fingerprint == null) return;
        context.getSharedPreferences("env_probe", Context.MODE_PRIVATE)
                .edit().putString("fingerprint", fingerprint).apply();
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "unknown" : s;
    }
}
