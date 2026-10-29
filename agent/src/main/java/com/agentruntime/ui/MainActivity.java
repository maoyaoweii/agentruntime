package com.agentruntime.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.BuildConfig;
import com.agentruntime.agent.R;
import com.agentruntime.device.RomInfo;
import com.agentruntime.ime.AgentImeService;
import com.agentruntime.overlay.OverlayService;
import com.agentruntime.runtime.TaskBus;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.vision.ScreenCapturer;

import org.json.JSONObject;

import java.util.List;

/**
 * 主页 = 一份会话流水（Codex 式）。
 *
 * <p>为什么改成这样：V1.2 的界面是"卡片面板"——状态卡、任务卡、最近卡各占一块，
 * 用户读到的是一堆结论，看不到事情是<b>怎么一步步发生</b>的。
 * 而这个 App 的本质是一条执行流水：说了什么 → 调了哪个技能 → 成功还是失败。
 * 按时间顺序摊开，比四块摘要卡更接近它实际在干的事。
 *
 * <p>和 V1.2 的区别只在"展示方式"，能力一点没减：无障碍/截图/悬浮窗/模型/输入法
 * 五个状态仍在，只是从彩色徽章压成了顶栏下面一行等宽小字。
 */
public final class MainActivity extends Activity implements TaskBus.Listener {

    private LinearLayout statusRow;
    private LinearLayout transcript;
    private ScrollView scroll;
    private TextView tvSession;
    private TextView tvRunning;
    private TextView tvVersion;
    private TextView btnRun;
    private EditText etGoal;
    private CheckBox cbAutoConfirm;

    /** 权限申请的 requestCode。分开放，才能在上层区分"运行时权限结果"和"从设置页回来"。 */
    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_RUNTIME = 1004;

    private CodexTranscript tx;
    private boolean busy;
    private RomInfo rom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        UiKit.applyStatusBarPadding(findViewById(R.id.top_bar));

        statusRow = findViewById(R.id.status_row);
        transcript = findViewById(R.id.transcript);
        scroll = findViewById(R.id.scroll);
        tvSession = findViewById(R.id.tv_session);
        tvRunning = findViewById(R.id.tv_running);
        tvVersion = findViewById(R.id.tv_version);
        btnRun = findViewById(R.id.btn_run);
        etGoal = findViewById(R.id.et_goal);
        cbAutoConfirm = findViewById(R.id.cb_auto_confirm);
        cbAutoConfirm.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        tx = new CodexTranscript(scroll, transcript);

        rom = RomInfo.detect(this);

        btnRun.setOnClickListener(v -> {
            if (busy || TaskRunner.isBusy()) TaskRunner.cancel();
            else startFromInput();
        });
        findViewById(R.id.btn_console).setOnClickListener(v -> openConsole());
        findViewById(R.id.btn_skills).setOnClickListener(v -> open(SkillsActivity.class));
        findViewById(R.id.btn_settings_top).setOnClickListener(v -> open(SettingsActivity.class));

        findViewById(R.id.chip_observe).setOnClickListener(v -> runQuick("观察当前设备环境"));
        findViewById(R.id.chip_screenshot).setOnClickListener(v -> runQuick("截图留证"));
        findViewById(R.id.chip_smoke).setOnClickListener(v -> runQuick(
                "运行工具冒烟，依次输入清空文本、断言、长按、返回、切换应用、回到桌面"));
        findViewById(R.id.chip_skills).setOnClickListener(v -> open(SkillsActivity.class));
        findViewById(R.id.chip_overlay).setOnClickListener(v -> toggleOverlay());
        findViewById(R.id.chip_perms).setOnClickListener(v -> requestAllPermissions());

        tvVersion.setText("v" + BuildConfig.VERSION_NAME + " · "
                + (AgentApp.get() == null ? 0 : AgentApp.get().skillEngine().skillIds().size()) + " 项技能");

        if (savedInstanceState != null) {
            String goal = savedInstanceState.getString("goal");
            if (goal != null) etGoal.setText(goal);
            cbAutoConfirm.setChecked(savedInstanceState.getBoolean("auto_confirm", false));
            // 旋转会重建 Activity：把这次会话已经发生的事件重放一遍，
            // 而不是留一块空白——流水本身就是状态，不该因为转屏丢掉。
            replayHistory(savedInstanceState.getString("task_goal", ""));
        } else {
            replayHistory("");
        }

        syncBusyFromBus();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("goal", etGoal.getText().toString());
        outState.putBoolean("auto_confirm", cbAutoConfirm.isChecked());
        outState.putString("task_goal", lastTaskGoal);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!TaskBus.get().recent().isEmpty()) {
            tx.clear();
            replayHistory(lastTaskGoal);
        }
        TaskBus.get().subscribe(this);
    }

    @Override
    protected void onStop() {
        TaskBus.get().unsubscribe(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncBusyFromBus();
        renderStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        renderStatus();
    }

    // ------------------------------------------------------------ 状态条

    /**
     * 状态条：`键 值` 形式的等宽小字。
     *
     * <p>用短键（a11y / cap / ovl / mdl / ime）而不是中文全称，是为了在一屏宽度内塞得下
     * 六项；值用颜色区分——绿=可用，黄=有但没开，红=缺失且必需，灰=可选未配置。
     */
    private void renderStatus() {
        AgentApp app = AgentApp.get();
        if (app == null || statusRow == null) return;
        statusRow.removeAllViews();

        boolean a11y = AgentAccessibilityService.isRunning();
        addChip("无障碍", a11y ? "已连接" : "待开启", a11y ? Kind.OK : Kind.WARN);

        boolean cap = ScreenCapturer.supported();
        addChip("截图", cap && a11y ? "可用" : "待就绪", cap && a11y ? Kind.OK : Kind.WARN);

        boolean ovlPerm = UiKit.hasOverlayPermission(this);
        boolean ovlOn = OverlayService.isRunning();
        addChip("悬浮助手", ovlOn ? "已开启" : (ovlPerm ? "可开启" : "待授权"),
                ovlOn ? Kind.OK : (ovlPerm ? Kind.WARN : Kind.DIM));

        JSONObject probe = app.localModels().probe();
        boolean localOk = probe.optBoolean("available", false);
        String backend = probe.optString("backend", "");
        addChip("本地模型", localOk ? "已就绪" : "未配置",
                localOk ? Kind.OK : Kind.DIM);

        boolean ime = AgentImeService.isSelected(this);
        addChip("输入法", ime ? "已启用" : "可选", ime ? Kind.OK : Kind.DIM);

        boolean cloud = app.modelRouter().cloud().isConfigured();
        addChip("云端规划", cloud ? "已配置" : "未配置", cloud ? Kind.OK : Kind.DIM);

        int skills = app.skillEngine().skillIds().size();
        addChip("技能", skills + " 项", Kind.DIM);

        // 权限单独一项：被拒之后所有"看起来莫名其妙失败"的操作，根因通常在这里，
        // 必须能在首页一眼看到，而不是去设置页里数。
        int total = UiKit.runtimePermissions(this).length;
        int okPerms = UiKit.grantedRuntimePermissions(this).length;
        addChip("权限", okPerms + "/" + total,
                okPerms == total ? Kind.OK : Kind.WARN);

        // ROM 单独一项：多厂商适配的结论必须能在界面上看见，否则用户无从判断
        // "我这台机器为什么和教程不一样"。
        addChip("系统", rom.rom.split(" ")[0], rom.isDomesticRom() ? Kind.WARN : Kind.DIM);
    }

    private enum Kind { OK, WARN, BAD, DIM }

    private void addChip(String key, String value, Kind kind) {
        TextView tv = new TextView(this);
        tv.setText(key + " · " + value);
        tv.setTextSize(12f);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setMinHeight(UiKit.dp(this, 40));
        tv.setBackgroundResource(R.drawable.bg_chip);
        int color;
        switch (kind) {
            case OK:
                color = R.color.ok;
                break;
            case WARN:
                color = R.color.warn;
                break;
            case BAD:
                color = R.color.danger;
                break;
            default:
                color = R.color.text_tertiary;
                break;
        }
        tv.setTextColor(getColor(color));
        tv.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                statusRow.getOrientation() == LinearLayout.VERTICAL ? -1 : -2, -2);
        params.setMarginEnd(UiKit.dp(this, 8));
        params.bottomMargin = statusRow.getOrientation() == LinearLayout.VERTICAL ? UiKit.dp(this, 8) : 0;
        tv.setFocusable(true);
        tv.setOnClickListener(v -> open(SettingsActivity.class));
        tv.setContentDescription(key + "，" + value + "，点击打开设置");
        statusRow.addView(tv, params);
    }

    // ------------------------------------------------------------ 空态

    private void showWelcome() {
        View welcome = getLayoutInflater().inflate(R.layout.view_welcome, transcript, false);
        TextView ready = welcome.findViewById(R.id.welcome_status);
        ready.setText(AgentAccessibilityService.isRunning()
                ? "✓  设备已连接 · 可以开始" : "连接设备能力，开始第一次任务  →");
        ready.setOnClickListener(v -> open(SettingsActivity.class));
        welcome.findViewById(R.id.welcome_observe).setOnClickListener(v -> runQuick("观察当前设备环境"));
        welcome.findViewById(R.id.welcome_screenshot).setOnClickListener(v -> runQuick("截图留证"));
        welcome.findViewById(R.id.welcome_skills).setOnClickListener(v -> open(SkillsActivity.class));
        transcript.addView(welcome);
    }

    /** 旋转后重放本次会话。事件流本身就是唯一事实来源，界面只是它的投影。 */
    private void replayHistory(String goal) {
        List<TaskBus.Event> events = TaskBus.get().recent();
        if ((goal == null || goal.isEmpty()) && events.isEmpty()) {
            showWelcome();
            return;
        }
        if (goal != null && !goal.isEmpty()) {
            tx.addUser(goal);
            tx.addSection("运行");
        }
        for (TaskBus.Event e : events) {
            appendEvent(e, false);
        }
        if (TaskBus.get().running()) {
            tvRunning.setText(TaskBus.get().progressText());
        }
    }

    // ------------------------------------------------------------ 执行

    private String lastTaskGoal = "";

    private void startFromInput() {
        String goal = etGoal.getText().toString().trim();
        if (goal.isEmpty()) {
            UiKit.toast(this, "先说一句要做什么");
            return;
        }
        runGoal(goal);
    }

    private void runQuick(String goal) {
        etGoal.setText(goal);
        runGoal(goal);
    }

    private void runGoal(String goal) {
        if (busy || TaskRunner.isBusy()) {
            UiKit.toast(this, "上一个任务还在执行");
            return;
        }
        if (!AgentAccessibilityService.isRunning()) {
            // 这里以前是直接 startActivity(SettingsActivity)：用户按了"运行"，
            // 界面却自己跳走了，看起来像被踢出去，而且任务已经写进流水变成一条假记录。
            // 改成先问一句，跳转由用户决定。
            showNeedsAccessibility(goal);
            return;
        }

        lastTaskGoal = goal;
        busy = true;
        setRunningUi(true);
        tvSession.setText("  ·  " + shortId());

        tx.clear();
        tx.addUser(goal);
        tx.addSection("运行");

        TaskBus.get().clearLog();
        TaskBus.get().info("ui", "已下发任务");

        // 悬浮球在跑就主动让出前台：Agent 自己占着前台时无障碍只能看到自己的窗口，
        // 目标应用的定位会全部超时；任务进度交给浮窗展示，用户仍能全程看到。
        if (OverlayService.isRunning()) moveTaskToBack(true);

        boolean auto = BuildConfig.DEBUG && cbAutoConfirm.isChecked();
        new Thread(() -> {
            String status = "ERROR";
            try {
                com.agentruntime.runtime.AgentRuntime.TaskResult r =
                        TaskRunner.runGoal(goal, auto, true);
                status = r.status;
            } catch (Throwable t) {
                TaskBus.get().error("ui", "执行异常：" + t.getMessage());
            } finally {
                String finalStatus = status;
                runOnUiThread(() -> {
                    busy = false;
                    setRunningUi(false);
                    if (!"SUCCESS".equals(finalStatus)) {
                        tx.addError("任务未成功：" + finalStatus + "（控制台可看完整步骤）");
                    }
                    renderStatus();
                });
            }
        }, "ui-task").start();
    }

    private void setRunningUi(boolean running) {
        btnRun.setEnabled(true);
        btnRun.setText(running ? "停止任务" : "开始任务");
        btnRun.setBackgroundResource(running ? R.drawable.bg_btn_danger : R.drawable.bg_btn_primary);
        btnRun.setTextColor(getColor(running ? R.color.danger : R.color.text_on_brand));
        etGoal.setEnabled(!running);
        if (!running) {
            tvRunning.setText("按你的指令，逐步执行");
        }
    }

    // ------------------------------------------------------------ 权限

    /**
     * 未开无障碍时的引导。
     *
     * <p>不使用 toast 打发：无障碍是这个 App 的硬前提，说不清就没法用。
     * 对话框里保留用户刚输入的目标，回来之后不用重打一遍。
     */
    private void showNeedsAccessibility(String goal) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("需要先开启无障碍服务")
                .setMessage("Agent 靠无障碍读取界面结构、执行点击与输入，不开就无法操作其他应用。\n\n"
                        + "开启后回到这里，刚才输入的目标会保留。")
                .setNegativeButton("稍后", (d, w) -> tx.addWarn("无障碍未开启，任务没有下发"))
                .setPositiveButton("去开启", (d, w) -> {
                    etGoal.setText(goal);
                    tx.addWarn("无障碍未开启，已跳到系统设置");
                    UiKit.openAccessibilitySettings(this);
                })
                .show();
    }

    /**
     * 运行时权限统一申请入口。
     *
     * <p>先弹一个说明页再交给系统：系统弹窗只说"允许 App 访问你的照片吗"，
     * 用户不知道这个 Agent 为什么要照片。说明页把每条权限对应到具体功能，
     * 之后的逐个同意才是知情的。
     */
    private void requestAllPermissions() {
        String[] missing = UiKit.ungrantedRuntimePermissions(this);
        StringBuilder sb = new StringBuilder();
        if (missing.length == 0) {
            sb.append("运行时权限都已授予。\n\n");
        } else {
            sb.append("将依次向系统申请以下 ").append(missing.length).append(" 项权限，")
                    .append("每一项都需要你本人点「允许」：\n");
            for (String p : missing) {
                sb.append("  · ").append(UiKit.permissionLabel(p)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("还需要两个特殊权限（系统设置页里手动开，无法弹窗）：\n");
        sb.append("  · 悬浮窗：").append(UiKit.hasOverlayPermission(this) ? "已授权" : "未授权")
                .append('\n');
        boolean a11y = AgentAccessibilityService.isRunning();
        sb.append("  · 无障碍：").append(a11y ? "已开启" : "未开启").append('\n');
        sb.append('\n');
        sb.append("存储说明：Android 11 起共享目录被分区存储限制，"
                + "本 App 不申请 MANAGE_EXTERNAL_STORAGE，"
                + "文件读写默认走 app 专属目录（/sdcard/Android/data/com.agentruntime.agent/files）。");

        new android.app.AlertDialog.Builder(this)
                .setTitle("权限申请")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .setNeutralButton("去设置页授权", (d, w) -> open(SettingsActivity.class))
                .setPositiveButton("开始申请", (d, w) -> {
                    if (!UiKit.hasOverlayPermission(this)) {
                        // 悬浮窗必须先给，否则后面的浮窗进度没地方显示。
                        UiKit.openOverlayPermission(this, REQ_OVERLAY);
                    }
                    UiKit.requestRuntimePermissions(this, REQ_RUNTIME);
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderStatus();
        if (requestCode != REQ_RUNTIME) return;
        int granted = 0;
        int denied = 0;
        for (int r : grantResults) {
            if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) granted++;
            else denied++;
        }
        String[] still = UiKit.ungrantedRuntimePermissions(this);
        if (still.length == 0) {
            tx.addNote("perm", "运行时权限已全部授予（本次同意 " + granted + " 项）");
            return;
        }
        // 拒绝之后不能沉默：调用点会在真正需要时返回 NEED_PERMISSION，
        // 与其那时候让人一头雾水，不如现在就说清楚还差哪几项。
        StringBuilder sb = new StringBuilder("仍被拒绝 ").append(still.length).append(" 项：\n");
        for (String p : still) sb.append("  · ").append(UiKit.permissionLabel(p)).append('\n');
        tx.addWarn("权限未齐（已同意 " + granted + "，拒绝 " + denied + "）");
        new android.app.AlertDialog.Builder(this)
                .setTitle("还有权限未授予")
                .setMessage(sb.toString())
                .setNegativeButton("知道了", null)
                .setPositiveButton("去应用详情页", (d, w) -> openAppDetail())
                .show();
    }

    private void openAppDetail() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            UiKit.toast(this, "打不开应用详情页");
        }
    }

    private void syncBusyFromBus() {
        busy = TaskBus.get().running() || TaskRunner.isBusy();
        setRunningUi(busy);
        if (busy) {
            tvRunning.setText(TaskBus.get().progressText());
        }
    }

    private static String shortId() {
        String id = TaskBus.get().currentTaskId();
        if (id == null || id.isEmpty()) return "session";
        return id.length() <= 14 ? id : id.substring(0, 14);
    }

    // ------------------------------------------------------ TaskBus 回调

    @Override
    public void onEvent(TaskBus.Event event) {
        if (event == null) return;
        appendEvent(event, true);
        syncBusyFromBus();
        if (TaskBus.get().running()) {
            tvRunning.setText(TaskBus.get().progressText());
        }
    }

    /**
     * 把一条运行事件翻成流水条目。
     *
     * <p>解析 `step` 的两行消息（`[1/3] skill.id` 与 `     ✓ CODE  123ms`）确实是脏活，
     * 但改 TaskBus 的协议影响面更大——它还有悬浮窗、控制台、通知三个消费者。
     * 所以把解析收在这里，格式变了只改一处。
     */
    private void appendEvent(TaskBus.Event e, boolean animate) {
        String tag = e.tag == null ? "" : e.tag;
        String msg = e.message == null ? "" : e.message;

        if ("step".equals(tag)) {
            String t = msg.trim();
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                String idx = close > 0 ? t.substring(1, close) : "?";
                String skill = close > 0 ? t.substring(close + 1).trim() : t;
                tx.addToolCall(skill, "step " + idx);
            } else {
                boolean ok = !t.startsWith("\u2717");
                String rest = t.length() > 1 ? t.substring(1).trim() : t;
                String code = rest;
                long ms = 0;
                String[] parts = rest.split("\\s{2,}");
                if (parts.length > 1) {
                    code = parts[0];
                    String d = parts[parts.length - 1].replace("ms", "").trim();
                    try {
                        ms = Long.parseLong(d);
                    } catch (NumberFormatException ignored) {
                    }
                }
                tx.addToolResult(ok, code, ms);
            }
            return;
        }

        if ("plan".equals(tag)) {
            tx.addNote("plan", msg);
            return;
        }

        if ("task".equals(tag)) {
            if (msg.startsWith("开始任务")) {
                tx.addSection("任务");
                tx.addNote("task", msg);
            } else if (msg.startsWith("任务完成") || msg.startsWith("任务结束")) {
                tx.addSummary(e.level == TaskBus.Level.OK, msg);
            } else {
                tx.addNote("task", msg);
            }
            return;
        }

        if (e.level == TaskBus.Level.ERROR) {
            tx.addError(msg);
        } else if (e.level == TaskBus.Level.WARN) {
            tx.addWarn(msg);
        } else {
            tx.addNote(tag, msg);
        }
    }

    // ------------------------------------------------------------ 导航

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    private void openConsole() {
        startActivity(new Intent(this, ConsoleActivity.class));
    }

    private void toggleOverlay() {
        if (OverlayService.isRunning()) {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
            tx.addNote("ovl", "悬浮球已关闭");
        } else {
            if (!UiKit.hasOverlayPermission(this)) {
                tx.addWarn("需要先授予悬浮窗权限");
                UiKit.openOverlayPermission(this, 1001);
                return;
            }
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START));
            tx.addNote("ovl", "悬浮球已开启");
        }
        findViewById(R.id.chip_overlay).postDelayed(this::renderStatus, 400);
    }
}
