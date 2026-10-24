package com.agentruntime.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
 * <p><b>未在设备验证</b>：本环境无真机与模拟器，悬浮窗只做到编译通过与逻辑自洽。
 */
public final class OverlayService extends Service implements TaskBus.Listener {

    public static final String ACTION_START = "com.agentruntime.overlay.START";
    public static final String ACTION_STOP = "com.agentruntime.overlay.STOP";

    private static volatile boolean running;

    private WindowManager windowManager;
    private View bubbleView;
    private View panelView;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubbleBadge;
    private TextView panelState;
    private EditText panelInput;

    private int screenWidth;
    private int bubbleSize;
    private boolean panelShown;

    private float downRawX;
    private float downRawY;
    private int bubbleStartX;
    private int bubbleStartY;
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
        if (panelState != null && panelShown) {
            panelState.setText(TaskBus.get().progressText());
        }
        if (bubbleBadge != null) {
            TaskBus bus = TaskBus.get();
            if (bus.running()) {
                bubbleBadge.setVisibility(View.VISIBLE);
                bubbleBadge.setText(bus.stepIndex() + "");
            } else {
                bubbleBadge.setVisibility(View.GONE);
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
