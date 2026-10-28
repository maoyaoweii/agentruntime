package com.agentruntime.device;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.KeyEvent;

import com.agentruntime.executor.ExecResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 系统底层调用桥：音量、亮度、剪贴板、媒体键、振动、设置页、应用列表、受限文件读写。
 *
 * <p>统一在这里做的原因和 RiskGat 同理——<b>设备能力必须有唯一入口</b>，
 * 否则能力会从各处悄悄长出来，最后没人说得清这个 App 到底能改什么。
 *
 * <p>权限边界（都是刻意选的，不是偷懒）：
 * <ul>
 *   <li>音量 / 媒体键 / 剪贴板：公开 API，无需授权</li>
 *   <li>亮度：需要 {@code WRITE_SETTINGS}，未授权时明确返回 NEED_PERMISSION</li>
 *   <li>振动：VIBRATE 是普通权限，装上就有</li>
 *   <li>文件：三级根目录——app 私有目录、app 专属外部目录（均无需权限）、共享存储
 *       {@code /sdcard}（需 READ/WRITE_EXTERNAL_STORAGE）。第三级在分区存储机型上会
 *       如实返回 NEED_PERMISSION / SCOPED_STORAGE，而不是假装写成功又悄悄丢数据；
 *       也刻意不申请 MANAGE_EXTERNAL_STORAGE（一个不该给的权限）</li>
 * </ul>
 */
public final class SystemBridge {

    /** 支持的音量流。 */
    private static final String[] STREAMS = {
            "music", "ring", "alarm", "notification", "call", "system"
    };

    /** 支持的设置页。 */
    private static final String[][] SETTINGS_PAGES = {
            {"wifi", Settings.ACTION_WIFI_SETTINGS},
            // "网络和互联网"是一个聚合页，和 Wi-Fi 页不是同一个入口；
            // 模型说"打开网络设置"时真正想看的是这一页。
            {"network", Settings.ACTION_WIRELESS_SETTINGS},
            {"bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS},
            {"display", Settings.ACTION_DISPLAY_SETTINGS},
            {"sound", Settings.ACTION_SOUND_SETTINGS},
            {"apps", Settings.ACTION_APPLICATION_SETTINGS},
            {"privacy", Settings.ACTION_PRIVACY_SETTINGS},
            {"location", Settings.ACTION_LOCATION_SOURCE_SETTINGS},
            {"battery", Intent.ACTION_POWER_USAGE_SUMMARY},
            {"accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"developer", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS},
    };

    private final Context context;

    public SystemBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------- 音量

    public ExecResult setVolume(String streamName, int level) {
        long start = System.currentTimeMillis();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return ExecResult.fail("NO_AUDIO_SERVICE", "AudioManager unavailable", 0, elapsed(start));
        Integer stream = streamOf(streamName);
        if (stream == null) {
            return ExecResult.reject("BAD_STREAM", "unknown stream: " + streamName, 0);
        }
        int max = am.getStreamMaxVolume(stream);
        int target = Math.max(0, Math.min(max, level));
        try {
            am.setStreamVolume(stream, target, 0);
            return ExecResult.ok("VOLUME_SET", streamName + "=" + target + "/" + max, 0, elapsed(start));
        } catch (SecurityException e) {
            return ExecResult.reject("NEED_PERMISSION",
                    "volume change blocked (需要勿扰模式例外权限)", 0);
        } catch (Throwable t) {
            return ExecResult.fail("VOLUME_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    public ExecResult getVolume(String streamName) {
        long start = System.currentTimeMillis();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return ExecResult.fail("NO_AUDIO_SERVICE", "AudioManager unavailable", 0, elapsed(start));
        Integer stream = streamOf(streamName);
        if (stream == null) {
            return ExecResult.reject("BAD_STREAM", "unknown stream: " + streamName, 0);
        }
        int cur = am.getStreamVolume(stream);
        int max = am.getStreamMaxVolume(stream);
        return ExecResult.ok("VOLUME_READ", streamName + "=" + cur + "/" + max, 0, elapsed(start));
    }

    private static Integer streamOf(String name) {
        if (name == null) return AudioManager.STREAM_MUSIC;
        switch (name.trim().toLowerCase()) {
            case "music":
                return AudioManager.STREAM_MUSIC;
            case "ring":
                return AudioManager.STREAM_RING;
            case "alarm":
                return AudioManager.STREAM_ALARM;
            case "notification":
                return AudioManager.STREAM_NOTIFICATION;
            case "call":
                return AudioManager.STREAM_VOICE_CALL;
            case "system":
                return AudioManager.STREAM_SYSTEM;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------- 亮度

    public boolean canWriteSettings() {
        return Settings.System.canWrite(context);
    }

    public ExecResult setBrightness(int percent) {
        long start = System.currentTimeMillis();
        if (!canWriteSettings()) {
            return ExecResult.reject("NEED_PERMISSION",
                    "WRITE_SETTINGS not granted (去设置里允许修改系统设置)", 0);
        }
        int clamped = Math.max(1, Math.min(100, percent));
        int value = Math.round(clamped * 255f / 100f);
        try {
            ContentResolver cr = context.getContentResolver();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            boolean ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value);
            return ok
                    ? ExecResult.ok("BRIGHTNESS_SET", clamped + "% (" + value + "/255)", 0, elapsed(start))
                    : ExecResult.fail("BRIGHTNESS_FAILED", "putInt returned false", 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("BRIGHTNESS_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    // ----------------------------------------------------------- 剪贴板

    public ExecResult setClipboard(String text) {
        long start = System.currentTimeMillis();
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return ExecResult.fail("NO_CLIPBOARD", "service unavailable", 0, elapsed(start));
            cm.setPrimaryClip(ClipData.newPlainText("agent", text == null ? "" : text));
            return ExecResult.ok("CLIPBOARD_SET",
                    (text == null ? 0 : text.length()) + " chars", 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("CLIPBOARD_FAILED",
                    "clipboard write failed (" + t.getClass().getSimpleName() + ")", 0, elapsed(start));
        }
    }

    public ExecResult getClipboard() {
        long start = System.currentTimeMillis();
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return ExecResult.fail("CLIPBOARD_EMPTY", "no primary clip", 0, elapsed(start));
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return ExecResult.fail("CLIPBOARD_EMPTY", "clip has no items", 0, elapsed(start));
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return ExecResult.ok("CLIPBOARD_READ",
                    text == null ? "" : text.toString(), 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("CLIPBOARD_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    // ----------------------------------------------------------- 媒体键

    public ExecResult mediaKey(String key) {
        long start = System.currentTimeMillis();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return ExecResult.fail("NO_AUDIO_SERVICE", "AudioManager unavailable", 0, elapsed(start));
        int code = mediaCodeOf(key);
        if (code == 0) return ExecResult.reject("BAD_MEDIA_KEY", "unknown media key: " + key, 0);
        try {
            long now = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
            return ExecResult.ok("MEDIA_KEY_OK", key, 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("MEDIA_KEY_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    private static int mediaCodeOf(String key) {
        if (key == null) return 0;
        switch (key.trim().toLowerCase()) {
            case "play":
                return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "pause":
                return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "play_pause":
                return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "next":
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "previous":
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "stop":
                return KeyEvent.KEYCODE_MEDIA_STOP;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------- 振动

    public ExecResult vibrate(long ms) {
        long start = System.currentTimeMillis();
        long duration = Math.max(20, Math.min(2000, ms));
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager)
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) {
                return ExecResult.fail("NO_VIBRATOR", "device has no vibrator", 0, elapsed(start));
            }
            v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            return ExecResult.ok("VIBRATE_OK", duration + "ms", 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("VIBRATE_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    // --------------------------------------------------------- 设置页

    /** 自然语言/中文别名 → 规范页名。模型写的是意图，不是枚举值。 */
    private static final String[][] PAGE_ALIASES = {
            {"网络", "network"}, {"网络和互联网", "network"}, {"互联网", "network"},
            {"internet", "network"}, {"networks", "network"},
            // 模型常把枚举写成 snake_case（实测 page=network_and_internet）
            {"network and internet", "network"}, {"network internet", "network"},
            {"wireless", "network"},
            {"无线", "wifi"}, {"无线网络", "wifi"}, {"wlan", "wifi"},
            {"蓝牙", "bluetooth"}, {"显示", "display"}, {"屏幕", "display"},
            {"声音", "sound"}, {"音量", "sound"}, {"应用", "apps"}, {"应用管理", "apps"},
            {"隐私", "privacy"}, {"位置", "location"}, {"定位", "location"},
            {"电池", "battery"}, {"电量", "battery"},
            {"无障碍", "accessibility"}, {"辅助功能", "accessibility"},
            {"开发者", "developer"}, {"开发者选项", "developer"},
    };

    /** 把用户/模型给的页名收敛到规范别名；认不出来返回 null。 */
    private static String canonicalPage(String page) {
        if (page == null) return null;
        // 下划线/连字符统一成空格，再压掉重复空格：network_and_internet → network and internet
        String p = page.trim().toLowerCase().replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ").trim();
        if (p.isEmpty()) return null;
        // 规范名本身也按下划线写在 SETTINGS_PAGES 里（如 accessibility），先做精确匹配
        for (String[] pair : SETTINGS_PAGES) {
            if (pair[0].equalsIgnoreCase(p)) return pair[0];
        }
        for (String[] alias : PAGE_ALIASES) {
            if (alias[0].equalsIgnoreCase(p)) return alias[1];
        }
        return null;
    }

    public ExecResult openSettingsPage(String page) {
        long start = System.currentTimeMillis();
        String canonical = canonicalPage(page);
        String action = null;
        if (canonical != null) {
            for (String[] pair : SETTINGS_PAGES) {
                if (pair[0].equals(canonical)) {
                    action = pair[1];
                    break;
                }
            }
        }
        if (action == null) {
            return ExecResult.reject("BAD_SETTINGS_PAGE",
                    "unknown page: " + page + ", allowed=" + allowedPages(), 0);
        }
        try {
            Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return ExecResult.ok("SETTINGS_OPENED", canonical, 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("SETTINGS_OPEN_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    public static String allowedPages() {
        StringBuilder sb = new StringBuilder();
        for (String[] p : SETTINGS_PAGES) {
            if (sb.length() > 0) sb.append('|');
            sb.append(p[0]);
        }
        return sb.toString();
    }

    // --------------------------------------------------------- 应用列表

    public ExecResult listApps(String filter, int limit) {
        long start = System.currentTimeMillis();
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            JSONArray arr = new JSONArray();
            int max = Math.max(1, Math.min(200, limit));
            String f = filter == null ? "" : filter.trim().toLowerCase();
            for (ApplicationInfo ai : apps) {
                if (arr.length() >= max) break;
                String label = String.valueOf(pm.getApplicationLabel(ai));
                if (!f.isEmpty()
                        && !label.toLowerCase().contains(f)
                        && !ai.packageName.toLowerCase().contains(f)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("package", ai.packageName);
                o.put("label", label.length() > 40 ? label.substring(0, 40) : label);
                o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                arr.put(o);
            }
            JSONObject out = new JSONObject();
            out.put("total_installed", apps.size());
            out.put("matched", arr.length());
            out.put("apps", arr);
            return ExecResult.ok("APPS_LISTED", out.toString(), 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("APPS_FAILED", String.valueOf(t.getMessage()), 0, elapsed(start));
        }
    }

    // ------------------------------------------------------------- 文件

    /**
     * 允许读写的根目录。
     *
     * <p>前两个是 app 自己的目录，永远可用、不需要任何权限。第三个是共享存储
     * （{@code /sdcard}），它才是"文件写入写出内存读取"要覆盖的目标：
     * 没有它，Agent 就只能在自己的沙箱里自娱自乐，导出不了任何东西给用户。
     *
     * <p>共享存储是否真的可达，交给 {@link #sharedStorageIssue} 在每次调用时判断，
     * 而不是在这里静态决定——权限是运行时可变的，缓存一次结论就会出现
     * "刚授权完仍然报 PATH_NOT_ALLOWED"的假故障。
     */
    private File[] allowedRoots() {
        return new File[]{context.getFilesDir(), context.getExternalFilesDir(null),
                Environment.getExternalStorageDirectory()};
    }

    /**
     * 共享存储当前不可用的原因，null 表示可用。
     *
     * <p>分区存储之后"有没有权限"和"能不能读写"不再是一回事：
     * API 29 靠 {@code requestLegacyExternalStorage} 退回旧模型，30 起该标志被忽略、
     * 共享目录只能靠 MANAGE_EXTERNAL_STORAGE（本工程刻意不申请）。所以这里分版本如实回答，
     * 不把一个被系统忽略的权限当成"已授权"。
     *
     * <p><b>关键顺序：先判 app 自己的目录。</b>{@code getExternalFilesDir()} 的物理路径
     * 就落在 {@code /sdcard/Android/data/<pkg>/files} 下面，用"是否在 /sdcard 之下"去判
     * 会把 app 自己的目录也判成受限共享目录——那是个假故障，app 专属目录在分区存储下
     * 一直可读写且不需要任何权限。
     */
    private String sharedStorageIssue(File target) {
        if (target == null) return null;
        // app 私有目录与 app 专属外部目录：永远可用，不受分区存储影响。
        if (isUnder(target, context.getFilesDir())) return null;
        if (isUnder(target, context.getExternalFilesDir(null))) return null;
        if (!isUnderSharedStorage(target)) return null;

        if (Build.VERSION.SDK_INT >= 30) {
            return "Android 11+ 分区存储下共享目录需 MANAGE_EXTERNAL_STORAGE（未申请）；"
                    + "可用 app 专属目录：" + context.getExternalFilesDir(null);
        }
        if (Build.VERSION.SDK_INT >= 29 && !Environment.isExternalStorageLegacy()) {
            return "本机未启用 legacy 外部存储；可用 app 专属目录："
                    + context.getExternalFilesDir(null);
        }
        int read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        if (read != PackageManager.PERMISSION_GRANTED) {
            return "NEED_PERMISSION:READ_EXTERNAL_STORAGE";
        }
        int write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (write != PackageManager.PERMISSION_GRANTED) {
            return "NEED_PERMISSION:WRITE_EXTERNAL_STORAGE";
        }
        return null;
    }

    private boolean isUnder(File f, File root) {
        if (f == null || root == null) return false;
        try {
            String rc = root.getCanonicalPath();
            String fc = f.getCanonicalPath();
            return fc.equals(rc) || fc.startsWith(rc + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isUnderSharedStorage(File f) {
        return isUnder(f, Environment.getExternalStorageDirectory());
    }

    /**
     * 路径安全校验：解析成 canonical path 后必须落在允许的根目录内。
     *
     * <p>必须做这一步——{@code ../../} 这类序列能绕过朴素的 startsWith 字符串检查。
     */
    public File resolveSafe(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            File candidate = new File(path.trim());
            if (!candidate.isAbsolute()) {
                candidate = new File(context.getFilesDir(), path.trim());
            }
            String canonical = candidate.getCanonicalPath();
            for (File root : allowedRoots()) {
                if (root == null) continue;
                String rc = root.getCanonicalPath();
                if (canonical.equals(rc) || canonical.startsWith(rc + File.separator)) {
                    return new File(canonical);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把"这条路径当前不可用"翻成结构化拒绝；可用时返回 null。
     *
     * <p>抽出来是因为读写/追加/删除四个入口都要做同一套判断——复制四遍之后，
     * 改一处边界条件必然漏掉三处。
     */
    private ExecResult storageBlocked(File f) {
        String issue = sharedStorageIssue(f);
        if (issue == null) return null;
        // 前缀 NEED_PERMISSION: 让上层转成"去申请权限"的引导，而不是笼统失败。
        return ExecResult.reject(issue.startsWith("NEED_PERMISSION") ? "NEED_PERMISSION" : "SCOPED_STORAGE",
                issue, 0);
    }

    public ExecResult readFile(String path, int maxBytes) {
        long start = System.currentTimeMillis();
        File f = resolveSafe(path);
        if (f == null) {
            return ExecResult.reject("PATH_NOT_ALLOWED",
                    "allowed roots: app private dir, app external dir, shared storage", 0);
        }
        ExecResult blocked = storageBlocked(f);
        if (blocked != null) return blocked;
        if (!f.exists() || !f.isFile()) {
            return ExecResult.fail("FILE_NOT_FOUND", "file does not exist in the allowed area",
                    0, elapsed(start));
        }
        if (!f.canRead()) {
            return ExecResult.fail("FILE_READ_FAILED", "file read failed (unreadable)", 0, elapsed(start));
        }
        int cap = Math.max(1, Math.min(256 * 1024, maxBytes));
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), cap)];
            int read;
            try {
                read = in.read(buf);
            } finally {
                in.close();
            }
            if (read <= 0) return ExecResult.ok("FILE_READ", "", 0, elapsed(start));
            String text = new String(buf, 0, read, StandardCharsets.UTF_8);
            return ExecResult.ok("FILE_READ", text, 0, elapsed(start));
        } catch (Throwable t) {
            // 可读性校验与真实读取之间仍有竞态（如文件刚好此时被改变权限或删除），
            // 不再用 exists/canRead 兜底判断，直接把失败如实上报为脱敏的 IO 失败。
            return ExecResult.fail("FILE_READ_FAILED",
                    "file read failed (" + t.getClass().getSimpleName() + ")", 0, elapsed(start));
        }
    }

    public ExecResult writeFile(String path, String content) {
        long start = System.currentTimeMillis();
        File f = resolveSafe(path);
        if (f == null) {
            return ExecResult.reject("PATH_NOT_ALLOWED",
                    "allowed roots: app private dir, app external dir, shared storage", 0);
        }
        ExecResult blocked = storageBlocked(f);
        if (blocked != null) return blocked;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return ExecResult.fail("MKDIR_FAILED", "parent directory could not be created",
                        0, elapsed(start));
            }
            byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(data);
            }
            return ExecResult.ok("FILE_WRITTEN", data.length + " bytes -> " + f.getName(), 0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("FILE_WRITE_FAILED",
                    "file write failed (" + t.getClass().getSimpleName() + ")", 0, elapsed(start));
        }
    }

    /**
     * 追加写入：在已有文件末尾追加内容，文件不存在时创建。
     *
     * <p>这是"修改文件"的语义——覆盖写会丢掉原有内容，而用户要的往往是把新的一行
     * 记到已有文件后面。两者分成两个动作，而不是给 write_file 加一个布尔开关：
     * 布尔值默认为 false 时，模型很容易漏传就变成整文件覆盖。
     */
    public ExecResult appendFile(String path, String content) {
        long start = System.currentTimeMillis();
        File f = resolveSafe(path);
        if (f == null) {
            return ExecResult.reject("PATH_NOT_ALLOWED",
                    "allowed roots: app private dir, app external dir, shared storage", 0);
        }
        ExecResult blocked = storageBlocked(f);
        if (blocked != null) return blocked;
        if (f.exists() && !f.isFile()) {
            return ExecResult.reject("NOT_A_FILE", "append target is not a regular file", 0);
        }
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return ExecResult.fail("MKDIR_FAILED", "parent directory could not be created",
                        0, elapsed(start));
            }
            byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            boolean existed = f.exists();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(data);
            }
            return ExecResult.ok(existed ? "FILE_APPENDED" : "FILE_CREATED",
                    data.length + " bytes -> " + f.getName() + " (" + f.length() + "B total)",
                    0, elapsed(start));
        } catch (Throwable t) {
            return ExecResult.fail("FILE_APPEND_FAILED",
                    "file append failed (" + t.getClass().getSimpleName() + ")", 0, elapsed(start));
        }
    }

    /**
     * 删除文件。
     *
     * <p>刻意只删"单个普通文件"：不做递归删目录、不允许删任何允许根目录本身。
     * 一个 DELETE 动作如果能把 /sdcard 或 app 私有目录整个抹掉，那就不是工具而是事故；
     * 需要批量清理时应该由上层显式列文件再逐个删，每一步都可审计。
     */
    public ExecResult deleteFile(String path) {
        long start = System.currentTimeMillis();
        File f = resolveSafe(path);
        if (f == null) {
            return ExecResult.reject("PATH_NOT_ALLOWED",
                    "allowed roots: app private dir, app external dir, shared storage", 0);
        }
        ExecResult blocked = storageBlocked(f);
        if (blocked != null) return blocked;
        if (isRoot(f)) {
            return ExecResult.reject("REFUSE_ROOT_DELETE", "refusing to delete an allowed root", 0);
        }
        if (f.isDirectory()) {
            return ExecResult.reject("REFUSE_DIRECTORY_DELETE",
                    "delete_file only removes a single file; recursive directory delete is not allowed", 0);
        }
        if (!f.exists()) {
            return ExecResult.fail("FILE_NOT_FOUND", "file does not exist in the allowed area",
                    0, elapsed(start));
        }
        long size = f.length();
        try {
            if (!f.delete()) {
                return ExecResult.fail("FILE_DELETE_FAILED", "delete returned false (file still exists)",
                        0, elapsed(start));
            }
        } catch (Throwable t) {
            return ExecResult.fail("FILE_DELETE_FAILED",
                    "file delete failed (" + t.getClass().getSimpleName() + ")", 0, elapsed(start));
        }
        if (f.exists()) {
            return ExecResult.fail("FILE_DELETE_FAILED", "file still exists after delete",
                    0, elapsed(start));
        }
        return ExecResult.ok("FILE_DELETED", size + " bytes removed -> " + f.getName(), 0, elapsed(start));
    }

    /** 是否是某个允许根目录本身（删除这类路径一律拒绝）。 */
    private boolean isRoot(File f) {
        for (File root : allowedRoots()) {
            if (root == null) continue;
            try {
                if (root.getCanonicalPath().equals(f.getCanonicalPath())) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------- 其它

    /** 打开系统的"修改系统设置"授权页（亮度需要）。 */
    public void requestWriteSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 当前前台组件（用于判断是不是自己的界面）。 */
    public static ComponentName topComponent(Context c) {
        return null;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
