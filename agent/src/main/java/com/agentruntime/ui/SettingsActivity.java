package com.agentruntime.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.RomInfo;
import com.agentruntime.env.EnvironmentSnapshot;
import com.agentruntime.local.LocalModelConfig;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.planner.CloudPlannerAdapter;
import com.agentruntime.service.AgentAccessibilityService;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 设置与自检：权限、模型、环境、数据。
 *
 * <p>设计原则是"每一项都能当场验证"——配了模型就能点自检，
 * 而不是配完之后靠跑一个任务来试错。
 */
public final class SettingsActivity extends Activity {

    private TextView permA11y;
    private TextView permOverlay;
    private TextView permNotify;
    private TextView permIme;
    private TextView permWriteSettings;
    private TextView permStorage;
    private TextView permRuntime;

    private EditText etCloudUrl;
    private EditText etCloudKey;
    private EditText etCloudModel;
    private TextView tvCloudState;

    private RadioGroup rgBackend;
    private EditText etLocalHost;
    private EditText etLocalPort;
    private EditText etLocalVision;
    private EditText etLocalDecider;
    private TextView tvLocalState;

    private TextView tvEnv;
    private RomInfo rom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        UiKit.applyStatusBarPadding(findViewById(R.id.top_bar));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        permA11y = findViewById(R.id.perm_a11y);
        permOverlay = findViewById(R.id.perm_overlay);
        permNotify = findViewById(R.id.perm_notify);
        permIme = findViewById(R.id.perm_ime);
        permWriteSettings = findViewById(R.id.perm_write_settings);
        permStorage = findViewById(R.id.perm_storage);
        permRuntime = findViewById(R.id.perm_runtime);

        etCloudUrl = findViewById(R.id.et_cloud_url);
        etCloudKey = findViewById(R.id.et_cloud_key);
        // Password EditText can still save its text in the view hierarchy state.
        // Do not put an unsaved API key in the Activity instance-state bundle.
        etCloudKey.setSaveEnabled(false);
        etCloudModel = findViewById(R.id.et_cloud_model);
        tvCloudState = findViewById(R.id.tv_cloud_state);

        rgBackend = findViewById(R.id.rg_backend);
        etLocalHost = findViewById(R.id.et_local_host);
        etLocalPort = findViewById(R.id.et_local_port);
        etLocalVision = findViewById(R.id.et_local_vision);
        etLocalDecider = findViewById(R.id.et_local_decider);
        tvLocalState = findViewById(R.id.tv_local_state);

        tvEnv = findViewById(R.id.tv_env);

        bindRom();

        findViewById(R.id.row_a11y).setOnClickListener(v -> UiKit.openAccessibilitySettings(this));
        findViewById(R.id.row_overlay_perm).setOnClickListener(v -> {
            if (!UiKit.hasOverlayPermission(this)) {
                UiKit.openOverlayPermission(this, 1002);
            } else {
                UiKit.toast(this, "已授权，去主页开启悬浮球");
            }
        });
        findViewById(R.id.row_notify_perm).setOnClickListener(v ->
                UiKit.requestNotificationPermission(this, 1003));
        findViewById(R.id.row_runtime_perm).setOnClickListener(v -> {
            String[] missing = UiKit.ungrantedRuntimePermissions(this);
            if (missing.length == 0) {
                UiKit.toast(this, "运行时权限都已授予");
                return;
            }
            StringBuilder sb = new StringBuilder("系统会依次弹出以下 ").append(missing.length)
                    .append(" 项，每一项都需要你点「允许」：\n");
            for (String p : missing) sb.append("  · ").append(UiKit.permissionLabel(p)).append('\n');
            new AlertDialog.Builder(this)
                    .setTitle("运行时权限")
                    .setMessage(sb.toString())
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始申请", (d, w) ->
                            UiKit.requestRuntimePermissions(this, 1005))
                    .show();
        });
        findViewById(R.id.row_storage_perm).setOnClickListener(v -> explainStorage());
        findViewById(R.id.row_ime).setOnClickListener(v -> {
            if (com.agentruntime.ime.AgentImeService.isSelected(this)) {
                UiKit.toast(this, "Agent 输入法已启用");
            } else {
                UiKit.toast(this, "在输入法列表里勾选 AgentRuntime 输入法后切过来");
                com.agentruntime.ime.AgentImeService.showPicker(this);
            }
        });
        findViewById(R.id.row_write_settings).setOnClickListener(v -> {
            AgentApp app = AgentApp.get();
            if (app != null && app.systemBridge() != null && app.systemBridge().canWriteSettings()) {
                UiKit.toast(this, "已授权");
            } else if (app != null && app.systemBridge() != null) {
                app.systemBridge().requestWriteSettings();
            }
        });

        findViewById(R.id.btn_cloud_save).setOnClickListener(v -> saveCloud());
        findViewById(R.id.btn_cloud_ping).setOnClickListener(v -> pingCloud());
        findViewById(R.id.btn_local_save).setOnClickListener(v -> saveLocal());
        findViewById(R.id.btn_local_probe).setOnClickListener(v -> probeLocal());
        findViewById(R.id.btn_env_refresh).setOnClickListener(v -> renderEnv());

        findViewById(R.id.row_audit).setOnClickListener(v -> showAudit());
        findViewById(R.id.row_clear).setOnClickListener(v -> confirmClear());

        loadCloudForm();
        loadLocalForm();
        renderEnv();

        if (savedInstanceState != null) {
            etCloudUrl.setText(savedInstanceState.getString("cloud_url", ""));
            etCloudModel.setText(savedInstanceState.getString("cloud_model", ""));
            etLocalHost.setText(savedInstanceState.getString("local_host", ""));
            etLocalPort.setText(savedInstanceState.getString("local_port", ""));
            etLocalVision.setText(savedInstanceState.getString("local_vision", ""));
            etLocalDecider.setText(savedInstanceState.getString("local_decider", ""));
            int backend = savedInstanceState.getInt("backend", -1);
            if (backend != -1) rgBackend.check(backend);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("cloud_url", etCloudUrl.getText().toString());
        outState.putString("cloud_model", etCloudModel.getText().toString());
        outState.putString("local_host", etLocalHost.getText().toString());
        outState.putString("local_port", etLocalPort.getText().toString());
        outState.putString("local_vision", etLocalVision.getText().toString());
        outState.putString("local_decider", etLocalDecider.getText().toString());
        outState.putInt("backend", rgBackend.getCheckedRadioButtonId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderPermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderPermissions();
        if (requestCode == 1005 && grantResults.length > 0) {
            int granted = 0;
            for (int r : grantResults) {
                if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) granted++;
            }
            String[] still = UiKit.ungrantedRuntimePermissions(this);
            UiKit.toast(this, still.length == 0
                    ? "权限已全部授予（本次同意 " + granted + " 项）"
                    : "已同意 " + granted + " 项，仍有 " + still.length + " 项被拒绝");
        }
    }

    // ------------------------------------------------------------ 机型适配

    /**
     * 机型适配入口。
     *
     * <p>为什么放在设置页而不是首页：它是一次性动作（装机时点一遍就够），
     * 天天摆在首页只会占地方。但必须能被找到，所以给一行常驻入口 + 当前 ROM 名称。
     */
    private void bindRom() {
        rom = RomInfo.detect(this);
        TextView name = findViewById(R.id.tv_rom_name);
        TextView badge = findViewById(R.id.tv_rom_badge);
        if (name != null) name.setText(rom.rom);
        if (badge != null) {
            badge.setText(rom.isDomesticRom() ? "需额外授权" : "标准 AOSP");
            badge.setTextColor(getColor(rom.isDomesticRom() ? R.color.warn : R.color.text_tertiary));
        }
        View row = findViewById(R.id.row_rom);
        if (row != null) row.setOnClickListener(v -> showRomGuides());
    }

    /**
     * 弹出本机该开的授权清单。
     *
     * <p>用自绘列表而不是 AlertDialog.setItems：每一条都要带一句"为什么需要"，
     * 单行 item 塞不下，会截断成看不出所以然的两三个字。
     */
    private void showRomGuides() {
        java.util.List<RomInfo.Guide> guides = rom.guides(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 16);
        box.setPadding(pad, UiKit.dp(this, 6), pad, UiKit.dp(this, 6));

        for (RomInfo.Guide g : guides) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
            item.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView title = new TextView(this);
            title.setText("\u203A " + g.title);
            title.setTextSize(14f);
            title.setTextColor(getColor(R.color.text_primary));
            title.setTypeface(android.graphics.Typeface.MONOSPACE);
            item.addView(title);

            TextView detail = new TextView(this);
            detail.setText(g.detail);
            detail.setTextSize(11.5f);
            detail.setTextColor(getColor(R.color.text_secondary));
            detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
            item.addView(detail);

            item.setOnClickListener(v -> {
                if (!g.open(this)) UiKit.toast(this, "打不开该页面，请手动到系统设置里找");
            });
            box.addView(item);
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(rom.summary())
                .setView(sc)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------ 权限

    private void renderPermissions() {
        boolean a11y = AgentAccessibilityService.isRunning();
        UiKit.setBadge(permA11y, a11y ? "已启用" : "去开启",
                a11y ? UiKit.Badge.OK : UiKit.Badge.DANGER);

        boolean overlay = UiKit.hasOverlayPermission(this);
        UiKit.setBadge(permOverlay, overlay ? "已授权" : "去授权",
                overlay ? UiKit.Badge.OK : UiKit.Badge.WARN);

        boolean notify = UiKit.hasNotificationPermission(this);
        UiKit.setBadge(permNotify, notify ? "已允许" : "去允许",
                notify ? UiKit.Badge.OK : UiKit.Badge.WARN);

        boolean ime = com.agentruntime.ime.AgentImeService.isSelected(this);
        UiKit.setBadge(permIme, ime ? "已启用" : "去启用",
                ime ? UiKit.Badge.OK : UiKit.Badge.NEUTRAL);

        AgentApp app = AgentApp.get();
        boolean ws = app != null && app.systemBridge() != null && app.systemBridge().canWriteSettings();
        UiKit.setBadge(permWriteSettings, ws ? "已授权" : "去授权",
                ws ? UiKit.Badge.OK : UiKit.Badge.NEUTRAL);

        // 存储：把"能不能读写共享目录"和"为什么不能"一起显示。
        // 分区存储之后这两件事不再等价，只显示一个权限状态会误导。
        boolean readStorage = UiKit.hasReadStoragePermission(this);
        boolean writeStorage = UiKit.hasWriteStoragePermission(this);
        if (permStorage != null) {
            String text;
            UiKit.Badge badge;
            if (readStorage && writeStorage) {
                text = "可读写";
                badge = UiKit.Badge.OK;
            } else if (readStorage) {
                text = "只读";
                badge = UiKit.Badge.WARN;
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                text = "受分区存储限制";
                badge = UiKit.Badge.WARN;
            } else {
                text = "未授权";
                badge = UiKit.Badge.DANGER;
            }
            UiKit.setBadge(permStorage, text, badge);
        }

        String[] total = UiKit.runtimePermissions(this);
        String[] ok = UiKit.grantedRuntimePermissions(this);
        if (permRuntime != null) {
            UiKit.setBadge(permRuntime, ok.length + "/" + total.length,
                    ok.length == total.length ? UiKit.Badge.OK : UiKit.Badge.WARN);
        }
    }

    /** 存储权限说明：先说清本机到底受哪条规则约束，再给出可用的替代路径。 */
    private void explainStorage() {
        StringBuilder sb = new StringBuilder();
        sb.append("本机 Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");
        sb.append("共享目录 /sdcard：")
                .append(UiKit.hasReadStoragePermission(this) ? "可读" : "不可读")
                .append(" / ")
                .append(UiKit.hasWriteStoragePermission(this) ? "可写" : "不可写")
                .append('\n');
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            sb.append("Android 11 起共享目录受分区存储限制，"
                    + "读写他应用文件需要 MANAGE_EXTERNAL_STORAGE，本 App 刻意不申请"
                    + "（那等于交出整个存储空间）。\n");
        }
        sb.append("\napp 专属目录（始终可用，无需权限）：\n  ");
        java.io.File ext = getExternalFilesDir(null);
        sb.append(ext == null ? "(不可用)" : ext.getAbsolutePath());
        sb.append("\n\nAgent 的 read_file / write_file 就落在这里。");

        new AlertDialog.Builder(this)
                .setTitle("存储读写")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .setPositiveButton(android.os.Build.VERSION.SDK_INT >= 30 ? "去应用详情页" : "申请存储权限",
                        (d, w) -> {
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                try {
                                    startActivity(new android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.parse("package:" + getPackageName())));
                                } catch (Exception ignored) {
                                }
                            } else {
                                UiKit.requestRuntimePermissions(this, 1005);
                            }
                        })
                .show();
    }

    // ------------------------------------------------------------ 云端

    private void loadCloudForm() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        CloudPlannerAdapter cloud = app.modelRouter().cloud();
        JSONObject info = cloud.configInfo();
        etCloudUrl.setText(info.optString("base_url", ""));
        etCloudModel.setText(info.optString("model", ""));
        etCloudKey.setText("");
        etCloudKey.setHint(info.optBoolean("api_key_set", false)
                ? "已保存（" + info.optString("api_key_masked", "") + "），留空表示不修改"
                : "API Key");
        tvCloudState.setText(info.optBoolean("configured", false)
                ? "已配置：" + info.optString("model", "")
                : "未配置——未配置时全部走本地规则与本地模型");
    }

    private void saveCloud() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        String url = etCloudUrl.getText().toString().trim();
        String key = etCloudKey.getText().toString().trim();
        String model = etCloudModel.getText().toString().trim();
        app.modelRouter().cloud().configure(
                url.isEmpty() ? null : url,
                key.isEmpty() ? null : key,
                model.isEmpty() ? null : model);
        UiKit.toast(this, "已保存");
        loadCloudForm();
    }

    private void pingCloud() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        tvCloudState.setText("正在自检…");
        new Thread(() -> {
            JSONObject r = app.modelRouter().cloud().ping();
            runOnUiThread(() -> {
                boolean ok = r.optBoolean("ok", false);
                UiKit.setBadge(tvCloudState, ok ? "连通正常" : "自检失败",
                        ok ? UiKit.Badge.OK : UiKit.Badge.DANGER);
                tvCloudState.setText(ok
                        ? "连通正常 · HTTP " + r.optInt("http_code", 0)
                        + " · " + r.optLong("latency_ms", 0) + "ms"
                        : "自检失败：" + r.optString("error", "未知错误"));
            });
        }, "cloud-ping").start();
    }

    // ------------------------------------------------------------ 本地

    private void loadLocalForm() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        LocalModelConfig cfg = app.localModels().config();
        switch (cfg.backend) {
            case HTTP:
                rgBackend.check(R.id.rb_http);
                break;
            case ONNX:
                rgBackend.check(R.id.rb_onnx);
                break;
            default:
                rgBackend.check(R.id.rb_none);
                break;
        }
        etLocalHost.setText(cfg.host);
        etLocalPort.setText(String.valueOf(cfg.port));
        etLocalVision.setText(cfg.visionModel);
        etLocalDecider.setText(cfg.deciderModel);
        renderLocalState();
    }

    private void renderLocalState() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        JSONObject probe = app.localModels().probe();
        boolean ok = probe.optBoolean("available", false);
        String backend = probe.optString("backend", "none");
        if ("none".equals(backend)) {
            UiKit.setBadge(tvLocalState, "未启用", UiKit.Badge.NEUTRAL);
            tvLocalState.setText("未启用——规划全部走本地规则，必要时走云端");
        } else if (ok) {
            UiKit.setBadge(tvLocalState, "可用", UiKit.Badge.OK);
            tvLocalState.setText(backend.toUpperCase(java.util.Locale.ROOT) + " · " + probe.optString("runtime", ""));
        } else {
            UiKit.setBadge(tvLocalState, "不可用", UiKit.Badge.DANGER);
            tvLocalState.setText(backend.toUpperCase(java.util.Locale.ROOT) + " 不可用 · "
                    + probe.optString("runtime", "原因未知"));
        }
    }

    private void saveLocal() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        String backend;
        int checked = rgBackend.getCheckedRadioButtonId();
        if (checked == R.id.rb_http) {
            backend = "HTTP";
        } else if (checked == R.id.rb_onnx) {
            backend = "ONNX";
        } else {
            backend = "NONE";
        }
        String host = etLocalHost.getText().toString().trim();
        String portText = etLocalPort.getText().toString().trim();
        Integer port = null;
        try {
            if (!portText.isEmpty()) port = Integer.parseInt(portText);
        } catch (NumberFormatException ignored) {
        }
        if ("HTTP".equals(backend) && (port == null || !LocalModelConfig.isValidTcpPort(port))) {
            etLocalPort.setError("请输入 1–65535 的有效端口");
            etLocalPort.requestFocus();
            return;
        }
        etLocalPort.setError(null);
        if ("HTTP".equals(backend) && !LocalModelConfig.isValidHost(host)) {
            etLocalHost.setError("请输入主机名、IPv4 或 IPv6 地址，不要填写协议或路径");
            etLocalHost.requestFocus();
            return;
        }
        etLocalHost.setError(null);
        String vision = etLocalVision.getText().toString().trim();
        String decider = etLocalDecider.getText().toString().trim();

        LocalModelRegistry reg = app.localModels();
        reg.configure(backend,
                host.isEmpty() ? null : host,
                port,
                vision.isEmpty() ? null : vision,
                decider.isEmpty() ? null : decider,
                null, null);
        UiKit.toast(this, "已保存");
        loadLocalForm();
    }

    private void probeLocal() {
        renderLocalState();
        UiKit.toast(this, "已刷新");
    }

    // ------------------------------------------------------------ 环境

    private void renderEnv() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        EnvironmentSnapshot s = app.envProbe().refresh();
        StringBuilder sb = new StringBuilder();
        sb.append("设备      ").append(s.manufacturer).append(' ').append(s.model).append('\n');
        sb.append("系统      Android ").append(s.release).append(" (API ").append(s.sdkInt).append(")\n");
        sb.append("屏幕      ").append(s.screenWidth).append('x').append(s.screenHeight)
                .append("  ").append(s.densityDpi).append("dpi")
                .append("  字体x").append(s.fontScale).append('\n');
        sb.append("旋转      ").append(s.rotation).append("°\n");
        sb.append("语言      ").append(s.language).append('-').append(s.country)
                .append("  深色 ").append(s.nightMode ? "开" : "关").append('\n');
        sb.append("电量      ").append(s.batteryPercent).append('%')
                .append(s.charging ? " 充电中" : "").append('\n');
        sb.append("网络      ").append(s.network).append('\n');
        sb.append("无障碍    ").append(com.agentruntime.device.A11yStatus.summary(this)).append('\n');
        sb.append("          系统勾选 ").append(s.a11yEnabledInSettings ? "是" : "否")
                .append("  已绑定 ").append(s.a11yConnected ? "是" : "否")
                .append("  可读屏 ").append(s.a11yRootReady ? "是" : "否")
                .append("  截图 ").append(s.canCapture ? "可" : "不可").append('\n');
        sb.append("指纹      ").append(s.fingerprint());
        tvEnv.setText(sb.toString());
    }

    // ------------------------------------------------------------ 数据

    private void showAudit() {
        AgentApp app = AgentApp.get();
        if (app == null) return;
        AuditLogger logger = app.auditLogger();
        File f = logger.currentFile();
        String content = tailFile(f, 60);
        new AlertDialog.Builder(this)
                .setTitle("审计记录 · " + f.getName())
                .setMessage(content)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清理本地任务数据")
                .setMessage("会删除截图、结果文件与临时数据，审计记录保留。确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    int n = clearLocalData();
                    UiKit.toast(this, "已清理 " + n + " 项");
                })
                .show();
    }

    private int clearLocalData() {
        int count = 0;
        File[] items = getFilesDir().listFiles();
        if (items == null) return 0;
        for (File f : items) {
            if ("audit".equals(f.getName())) continue;
            if (deleteRecursive(f)) count++;
        }
        return count;
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) {
                for (File c : cs) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    private static String tailFile(File f, int maxLines) {
        if (f == null || !f.exists()) return "(空)";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int cap = (int) Math.min(len, 24 * 1024);
            raf.seek(len - cap);
            byte[] buf = new byte[cap];
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }
}
