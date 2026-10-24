package com.agentruntime.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.runtime.TaskBus;

import java.util.List;

/**
 * 任务控制台：把执行过程逐行摊开。
 *
 * <p>这是"烧心"问题的正面回答——用户能看到每一步在做什么、花了多久、成功还是失败，
 * 而不是盯着一个静态界面猜它有没有在跑。
 */
public final class ConsoleActivity extends Activity implements TaskBus.Listener {

    public static final String EXTRA_GOAL = "goal";
    public static final String EXTRA_SKILL = "skill";

    private TextView tvTask;
    private TextView tvState;
    private TextView tvStep;
    private TextView tvLog;
    private EditText etGoal;
    private ProgressBar progress;
    private ScrollView logScroll;
    private Button btnStop;
    private Button btnReplay;

    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        UiKit.applyStatusBarPadding(findViewById(R.id.top_bar));

        tvTask = findViewById(R.id.tv_console_task);
        tvState = findViewById(R.id.tv_console_state);
        tvStep = findViewById(R.id.tv_console_step);
        tvLog = findViewById(R.id.tv_console_log);
        etGoal = findViewById(R.id.et_console_goal);
        progress = findViewById(R.id.progress);
        logScroll = findViewById(R.id.log_scroll);
        btnStop = findViewById(R.id.btn_stop);
        btnReplay = findViewById(R.id.btn_replay);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            logBuffer.clear();
            tvLog.setText("");
        });
        btnStop.setOnClickListener(v -> {
            AgentApp app = AgentApp.get();
            if (app != null) app.agentRuntime().cancel();
            TaskBus.get().warn("ui", "已请求停止");
        });
        btnReplay.setOnClickListener(v -> {
            String goal = etGoal.getText().toString().trim();
            if (goal.isEmpty()) {
                UiKit.toast(this, "输入一个目标");
                return;
            }
            run(goal);
        });

        String goal = getIntent().getStringExtra(EXTRA_GOAL);
        String skill = getIntent().getStringExtra(EXTRA_SKILL);
        if (savedInstanceState != null) {
            // 横竖屏旋转导致的重建：任务可能还在跑，绝不能重复下发
            String savedText = savedInstanceState.getString("goal_text");
            if (savedText != null) etGoal.setText(savedText);
            restoreRecentLog();
            syncBusyFromBus();
        } else if (skill != null && !skill.isEmpty()) {
            etGoal.setText("技能：" + skill);
            runSkill(skill);
        } else if (goal != null && !goal.isEmpty()) {
            etGoal.setText(goal);
            run(goal);
        } else {
            restoreRecentLog();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("goal_text", etGoal.getText().toString());
    }

    /** 旋转后按钮状态从总线恢复，不依赖内存里的局部变量。 */
    private void syncBusyFromBus() {
        busy = TaskBus.get().running();
        btnReplay.setEnabled(!busy);
        updateHeader();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TaskBus.get().subscribe(this);
    }

    @Override
    protected void onStop() {
        TaskBus.get().unsubscribe(this);
        super.onStop();
    }

    /** 从后台进入时把已有日志补上，避免看起来像空页面。 */
    private void restoreRecentLog() {
        List<TaskBus.Event> events = TaskBus.get().recent();
        for (TaskBus.Event e : events) appendLog(e);
        updateHeader();
    }

    private void run(String goal) {
        if (busy) {
            UiKit.toast(this, "任务正在执行");
            return;
        }
        busy = true;
        btnReplay.setEnabled(false);
        btnStop.setEnabled(true);
        logBuffer.clear();
        tvLog.setText("");
        TaskBus.get().clearLog();

        new Thread(() -> {
            try {
                TaskRunner.runGoal(goal, true, true);
            } catch (Throwable t) {
                TaskBus.get().error("ui", "执行异常：" + t.getMessage());
            } finally {
                runOnUiThread(() -> {
                    busy = false;
                    btnReplay.setEnabled(true);
                    updateHeader();
                });
            }
        }, "console-task").start();
    }

    /** 直接执行一个无参技能（技能库点"立即执行"走这里）。 */
    private void runSkill(String skillId) {
        if (busy) {
            UiKit.toast(this, "任务正在执行");
            return;
        }
        busy = true;
        btnReplay.setEnabled(false);
        btnStop.setEnabled(true);
        logBuffer.clear();
        tvLog.setText("");
        TaskBus.get().clearLog();

        new Thread(() -> {
            try {
                TaskRunner.runSkill(skillId, new org.json.JSONObject(), true);
            } catch (Throwable t) {
                TaskBus.get().error("ui", "执行异常：" + t.getMessage());
            } finally {
                runOnUiThread(() -> {
                    busy = false;
                    btnReplay.setEnabled(true);
                    updateHeader();
                });
            }
        }, "console-skill").start();
    }

    @Override
    public void onEvent(TaskBus.Event event) {
        appendLog(event);
        updateHeader();
    }

    private void appendLog(TaskBus.Event e) {
        int start = logBuffer.length();
        logBuffer.append(e.toLine()).append('\n');
        int color;
        switch (e.level) {
            case OK:
                color = getColor(R.color.console_ok);
                break;
            case WARN:
                color = getColor(R.color.console_warn);
                break;
            case ERROR:
                color = getColor(R.color.console_err);
                break;
            default:
                color = getColor(R.color.console_text);
                break;
        }
        logBuffer.setSpan(new ForegroundColorSpan(color), start, logBuffer.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvLog.setText(logBuffer);

        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateHeader() {
        TaskBus bus = TaskBus.get();
        tvTask.setText(bus.currentTaskId().isEmpty() ? "等待任务" : bus.currentTaskId());

        switch (bus.state()) {
            case RUNNING:
                UiKit.setBadge(tvState, "执行中", UiKit.Badge.WARN);
                break;
            case SUCCESS:
                UiKit.setBadge(tvState, "成功", UiKit.Badge.OK);
                break;
            case FAILED:
                UiKit.setBadge(tvState, "失败", UiKit.Badge.DANGER);
                break;
            case CANCELLED:
                UiKit.setBadge(tvState, "已取消", UiKit.Badge.WARN);
                break;
            default:
                UiKit.setBadge(tvState, "空闲", UiKit.Badge.NEUTRAL);
                break;
        }

        int total = Math.max(1, bus.stepTotal());
        int done = bus.stepIndex();
        progress.setProgress(Math.min(100, done * 100 / total));
        tvStep.setText(bus.running()
                ? "第 " + done + "/" + total + " 步 · " + bus.currentStep()
                : (bus.state() == TaskBus.State.IDLE ? "—" : "已结束 · 共 " + bus.stepTotal() + " 步"));
    }
}
