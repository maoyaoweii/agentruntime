package com.agentruntime.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.agent.R;
import com.agentruntime.notify.AgentNotifier;
import com.agentruntime.runtime.TaskBus;
import com.agentruntime.service.AgentAccessibilityService;
import com.agentruntime.ui.TaskRunner;
import com.agentruntime.ui.UiKit;

/**
 * 悬浮球 + 全局快捷面板。
 *
 * <p>存在的理由很实际：Agent 要操作的 App 在前台，用户不可能先切回 Agent 再下达任务——
 * 那样任务一开始，Agent 自己就被切走了。悬浮球让你在任何界面直接下达指令。
 *
 * <p>技术约束（都是真机上会踩的）：
 * <ul>
 *   <li>必须前台服务，否则系统几秒内就回收；</li>
 *   <li>Android 14 要求声明 foregroundServiceType，这里用 specialUse；</li>
 *   <li>球形窗口加 FLAG_NOT_FOCUSABLE（不抢输入焦点），面板窗口去掉该标志（要能打字）。</li>
 * </ul>
 *
 * <p><b>任务执行浮窗</b>：任务一开跑就叠一块「AI 在想什么、在做什么」的窗口在被测应用之上，
 * 顶部标题栏是拖动把手，可自由挪动。之所以不用 Activity 承载：Agent 自己占前台时，
 * 无障碍 {@code getRootInActiveWindow()} 只能看到 Agent 自己的窗口，所有定位都会超时。
 * 浮窗用 FLAG_NOT_FOCUSABLE，既不抢被测应用焦点，也不影响它接键盘输入。
 */
public final class OverlayService extends Service implements TaskBus.Listener {

    public static final String ACTION_START = "com.agentruntime.overlay.START";
    public static final String ACTION_STOP = "com.agentruntime.overlay.STOP";

    private static volatile boolean running;

    private WindowManager windowManager;
    private View bubbleView;
    private View panelView;
    private View taskView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams taskParams;
    private TextView bubbleBadge;
    private TextView panelState;
    private EditText panelInput;
    private TextView taskTitle;
    private TextView taskProgress;
    private ScrollView taskScroll;
    private LinearLayout taskLog;
    private boolean taskPanelShown;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTaskPanel = this::removeTaskPanel;

    private int screenWidth;
    private int bubbleSize;
    private boolean panelShown;

    private float downRawX;
    private float downRawY;
    private int bubbleStartX;
    private int bubbleStartY;
    private float taskDownRawX;
    private float taskDownRawY;
    private int taskStartX;
    private int taskStartY;
    private boolean dragged;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        bubbleSize = UiKit.dp(this, 60);
        AgentNotifier.ensureChannels(this);
        startForeground(AgentNotifier.ID_ONGOING,
                AgentNotifier.ongoing(this, "悬浮球已开启"));
        TaskBus.get().subscribe(this);
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && bubbleView == null) {
            showBubble();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        TaskBus.get().unsubscribe(this);
        removeTaskPanel();
        removePanel();
        removeBubble();
        running = false;
        AgentNotifier.cancelOngoing(this);
        super.onDestroy();
    }

    /**
     * 旋转/分屏/字体缩放都会改变屏幕尺寸，悬浮窗必须重新计算位置。
     *
     * <p>这是悬浮类窗口最容易漏的一处：View 本身不会跟着转，坐标还停在旧值，
     * 横屏后球就跑到屏幕外面去了。面板宽度也要按新宽度重建。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int newWidth = getResources().getDisplayMetrics().widthPixels;
        int newHeight = getResources().getDisplayMetrics().heightPixels;
        boolean widthChanged = newWidth != screenWidth;
        screenWidth = newWidth;

        if (panelShown) {
            removePanel();
            showPanel();
        }
        // 任务浮窗宽度依赖屏宽：重建一次比改 LayoutParams 更省事，也不会残留旧坐标。
        if (taskPanelShown) {
            removeTaskPanel();
            if (TaskBus.get().running()) showTaskPanel();
        }
        if (bubbleView != null && bubbleParams != null) {
            if (bubbleParams.x > screenWidth - bubbleSize) {
                bubbleParams.x = screenWidth - bubbleSize - UiKit.dp(this, 4);
            }
            if (bubbleParams.y > newHeight - bubbleSize - UiKit.dp(this, 24)) {
                bubbleParams.y = newHeight - bubbleSize - UiKit.dp(this, 24);
            }
            if (widthChanged) {
                snapToEdge();
            } else {
                try {
                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                } catch (Throwable ignored) {
                }
            }
        }
        if (bubbleBadge != null) {
            bubbleBadge.setVisibility(TaskBus.get().running() ? View.VISIBLE : View.GONE);
        }
    }

    private void shutdown() {
        removeTaskPanel();
        removePanel();
        removeBubble();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------ 悬浮球

    private void showBubble() {
        if (bubbleView != null || windowManager == null) return;
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleBadge = bubbleView.findViewById(R.id.bubble_badge);
        bubbleView.findViewById(R.id.bubble_badge).setVisibility(View.GONE);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = screenWidth - bubbleSize - UiKit.dp(this, 8);
        bubbleParams.y = getResources().getDisplayMetrics().heightPixels / 3;

        bubbleView.setOnTouchListener(this::onBubbleTouch);
        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Throwable t) {
            UiKit.toast(this, "悬浮球创建失败，检查悬浮窗权限");
            shutdown();
        }
    }

    private void removeBubble() {
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Throwable ignored) {
            }
            bubbleView = null;
        }
    }

    private boolean onBubbleTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                bubbleStartX = bubbleParams.x;
                bubbleStartY = bubbleParams.y;
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (e.getRawX() - downRawX);
                int dy = (int) (e.getRawY() - downRawY);
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                if (dragged) {
                    bubbleParams.x = bubbleStartX + dx;
                    bubbleParams.y = bubbleStartY + dy;
                    try {
                        windowManager.updateViewLayout(bubbleView, bubbleParams);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragged) {
                    snapToEdge();
                } else {
                    togglePanel();
                }
                return true;
            default:
                return false;
        }
    }

    /** 松手后贴到最近的一侧，避免悬浮球挡住内容。 */
    private void snapToEdge() {
        if (bubbleView == null) return;
        int margin = UiKit.dp(this, 4);
        int half = bubbleParams.x + bubbleSize / 2;
        bubbleParams.x = half < screenWidth / 2 ? margin : screenWidth - bubbleSize - margin;
        int maxY = getResources().getDisplayMetrics().heightPixels - bubbleSize - UiKit.dp(this, 48);
        if (bubbleParams.y < UiKit.dp(this, 24)) bubbleParams.y = UiKit.dp(this, 24);
        if (bubbleParams.y > maxY) bubbleParams.y = maxY;
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 面板

    private void togglePanel() {
        if (panelShown) {
            removePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelView != null || windowManager == null) return;
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        panelState = panelView.findViewById(R.id.panel_state);
        panelInput = panelView.findViewById(R.id.panel_input);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 面板宽度取「屏宽减边距」与 420dp 的较小值：横屏下不铺满整个屏幕
        int panelWidth = Math.min(screenWidth - UiKit.dp(this, 32), UiKit.dp(this, 420));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                panelWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;

        panelView.findViewById(R.id.panel_close).setOnClickListener(v -> removePanel());
        panelView.findViewById(R.id.panel_run).setOnClickListener(v -> runFromPanel());
        panelView.findViewById(R.id.panel_chip_observe).setOnClickListener(v -> {
            panelInput.setText("观察当前设备环境");
            runFromPanel();
        });
        panelView.findViewById(R.id.panel_chip_shot).setOnClickListener(v -> {
            panelInput.setText("截图留证");
            runFromPanel();
        });

        try {
            windowManager.addView(panelView, params);
            panelShown = true;
            panelState.setText(TaskBus.get().progressText());
            panelInput.postDelayed(() -> {
                panelInput.requestFocus();
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(panelInput, InputMethodManager.SHOW_IMPLICIT);
            }, 200);
        } catch (Throwable t) {
            UiKit.toast(this, "面板创建失败：" + t.getMessage());
            panelView = null;
            panelShown = false;
        }
    }

    private void removePanel() {
        if (panelView != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
        }
        panelShown = false;
    }

    // ------------------------------------------------------------ 任务执行浮窗

    /**
     * 任务执行浮窗：默认落在屏幕右上方，不挡目标应用的主要操作区。
     *
     * <p>不加 FLAG_NOT_FOCUSABLE 以外的输入标志：窗口本身不该抢焦点，
     * 但必须能接触摸（拖动 + 关闭），所以用 NOT_TOUCH_MODAL 让窗口区域外的触摸透传。
     */
    private void showTaskPanel() {
        if (taskView != null || windowManager == null) return;
        hideHandler.removeCallbacks(hideTaskPanel);
        try {
            taskView = LayoutInflater.from(this).inflate(R.layout.overlay_task, null);
            taskTitle = taskView.findViewById(R.id.task_title);
            taskProgress = taskView.findViewById(R.id.task_progress);
            taskScroll = taskView.findViewById(R.id.task_scroll);
            taskLog = taskView.findViewById(R.id.task_log);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int width = Math.min(screenWidth - UiKit.dp(this, 48), UiKit.dp(this, 360));
            taskParams = new WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            taskParams.gravity = Gravity.TOP | Gravity.START;
            taskParams.x = screenWidth - width - UiKit.dp(this, 8);
            taskParams.y = UiKit.dp(this, 96);

            // 标题栏是拖动把手：整块窗口跟着手指走，松手不吸附，用户可以任意摆放。
            taskView.findViewById(R.id.task_header).setOnTouchListener(this::onTaskTouch);
            taskView.findViewById(R.id.task_close).setOnClickListener(v -> removeTaskPanel());
            windowManager.addView(taskView, taskParams);
            taskPanelShown = true;
            taskProgress.setText(TaskBus.get().progressText());
        } catch (Throwable t) {
            taskView = null;
            taskPanelShown = false;
            taskParams = null;
        }
    }

    private void removeTaskPanel() {
        hideHandler.removeCallbacks(hideTaskPanel);
        if (taskView != null) {
            try {
                windowManager.removeView(taskView);
            } catch (Throwable ignored) {
            }
        }
        taskView = null;
        taskParams = null;
        taskTitle = null;
        taskProgress = null;
        taskScroll = null;
        taskLog = null;
        taskPanelShown = false;
    }

    private boolean onTaskTouch(View v, MotionEvent e) {
        if (taskParams == null || taskView == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                taskDownRawX = e.getRawX();
                taskDownRawY = e.getRawY();
                taskStartX = taskParams.x;
                taskStartY = taskParams.y;
                return true;
            case MotionEvent.ACTION_MOVE: {
                // 允许在屏幕内自由拖动，但不允许拖出屏幕：整窗留在屏内才能保证关闭按钮抓得到。
                int maxX = Math.max(0, screenWidth - taskParams.width);
                int maxY = getResources().getDisplayMetrics().heightPixels - UiKit.dp(this, 64);
                taskParams.x = clamp(taskStartX + (int) (e.getRawX() - taskDownRawX), 0, maxX);
                taskParams.y = clamp(taskStartY + (int) (e.getRawY() - taskDownRawY), 0, maxY);
                try {
                    windowManager.updateViewLayout(taskView, taskParams);
                } catch (Throwable ignored) {
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                return true;
            default:
                return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /** 把一条任务事件追加到浮窗：plan=思考，step=动作，其余按级别着色。 */
    private void appendTaskLog(TaskBus.Event event) {
        if (taskLog == null) return;
        TextView line = new TextView(this);
        line.setTextSize(11f);
        line.setTypeface(Typeface.MONOSPACE);
        line.setTextColor(levelColor(event.level));
        line.setPadding(0, UiKit.dp(this, 1), 0, UiKit.dp(this, 1));
        line.setText(labelOf(event.tag) + event.message);
        taskLog.addView(line);
        while (taskLog.getChildCount() > 8) taskLog.removeViewAt(0);
        if (taskScroll != null) taskScroll.post(() -> taskScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String labelOf(String tag) {
        if ("plan".equals(tag)) return "思考  ";
        if ("step".equals(tag)) return "动作  ";
        return "任务  ";
    }

    private int levelColor(TaskBus.Level level) {
        switch (level) {
            case OK:
                return getColor(R.color.ok);
            case WARN:
                return getColor(R.color.warn);
            case ERROR:
                return getColor(R.color.danger);
            default:
                return getColor(R.color.text_secondary);
        }
    }

    private void runFromPanel() {
        String goal = panelInput == null ? "" : panelInput.getText().toString().trim();
        if (goal.isEmpty()) {
            UiKit.toast(this, "说一句要做什么");
            return;
        }
        if (!AgentAccessibilityService.isRunning()) {
            UiKit.toast(this, "无障碍服务未开启");
            return;
        }
        removePanel();
        showTaskPanel();
        if (taskTitle != null) taskTitle.setText(goal.length() > 18 ? goal.substring(0, 18) + "…" : goal);
        if (taskLog != null) taskLog.removeAllViews();
        new Thread(() -> {
            try {
                AgentRuntimeHolder.run(goal);
            } catch (Throwable t) {
                TaskBus.get().error("overlay", "执行异常：" + t.getMessage());
            }
        }, "overlay-task").start();
    }

    @Override
    public void onEvent(TaskBus.Event event) {
        TaskBus bus = TaskBus.get();
        if (panelState != null && panelShown) {
            panelState.setText(bus.progressText());
        }
        if (bubbleBadge != null) {
            if (bus.running()) {
                bubbleBadge.setVisibility(View.VISIBLE);
                bubbleBadge.setText(bus.stepIndex() + "");
            } else {
                bubbleBadge.setVisibility(View.GONE);
            }
        }
        // 不管任务从哪发起（主界面 / 浮窗面板 / adb），只要事件流在跑就叠出任务浮窗，
        // 用户始终能看到规划与步骤，而不是盯着一块不动的界面猜 Agent 在干什么。
        if (!taskPanelShown && ("plan".equals(event.tag) || "task".equals(event.tag) || bus.running())) {
            showTaskPanel();
        }
        if (taskPanelShown) {
            if (taskProgress != null) {
                // 规划阶段总线状态还是 IDLE，直接显示「空闲」会误导成没在干活。
                taskProgress.setText(bus.running() || !"plan".equals(event.tag)
                        ? bus.progressText() : "思考中…");
            }
            appendTaskLog(event);
            if (!bus.running() && "task".equals(event.tag)) {
                hideHandler.removeCallbacks(hideTaskPanel);
                hideHandler.postDelayed(hideTaskPanel, 5000);
            }
        }
        if (event.level == TaskBus.Level.ERROR) {
            AgentNotifier.updateOngoing(this, event.message);
        }
    }

    /** 隔离对 TaskRunner 的静态调用，避免服务类直接依赖 UI 层过多实现。 */
    private static final class AgentRuntimeHolder {
        static void run(String goal) {
            try {
                TaskRunner.runGoal(goal, false, true);
            } catch (Exception e) {
                TaskBus.get().error("overlay", "执行失败：" + e.getMessage());
            }
        }
    }
}
