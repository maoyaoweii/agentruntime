package com.agentruntime.dsh.runtime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.dsh.ui.DshActivity;
import com.agentruntime.dsh.ui.UiKit;

/**
 * 工作时的小状态卡。
 *
 * <p>两难：助手要操作微信/B站，聊天界面就不能占着前台（否则无障碍只能看到自己）；
 * 可一旦退到后台，用户又完全看不见它在干什么，弹窗也容易被系统挡掉。
 *
 * <p>所以把"进度 + 停止 + 回对话"搬到 {@code TYPE_APPLICATION_OVERLAY} 窗口里：
 * 它不抢焦点（不影响目标应用接收输入，也不会成为无障碍的活动窗口），
 * 却始终浮在目标应用上方，用户随时能看到当前在调什么、跑了多久、要不要停。
 */
public final class DshStatusOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View card;
    private TextView status, meta, live, dot;
    private WindowManager.LayoutParams params;
    private String session;
    private long startedAt;
    private boolean showing;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!showing) return;
            render();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable listener = this::render;

    DshStatusOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean permitted(Context context) {
        return Settings.canDrawOverlays(context);
    }

    void show(String session) {
        this.session = session;
        this.startedAt = System.currentTimeMillis();
        if (!permitted(context) || windowManager == null) return;
        if (showing) { render(); return; }
        try {
            card = build();
            params = layoutParams();
            windowManager.addView(card, params);
            showing = true;
            AgentApp.get().dsh().controller.observe(listener);
            main.post(tick);
        } catch (RuntimeException e) {
            // 悬浮窗被系统拒绝（部分 ROM 需要每次重新授权）时静默降级，进度仍可在通知里看。
            card = null;
            showing = false;
        }
    }

    void hide() {
        if (!showing) return;
        showing = false;
        main.removeCallbacks(tick);
        try { AgentApp.get().dsh().controller.remove(listener); } catch (RuntimeException ignored) { }
        try { if (card != null) windowManager.removeView(card); } catch (RuntimeException ignored) { }
        card = null;
    }

    private WindowManager.LayoutParams layoutParams() {
        int width = context.getResources().getDisplayMetrics().widthPixels - UiKit.dp(context, 28);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = UiKit.dp(context, 44);
        p.setTitle("DSH 工作状态");
        return p;
    }

    private View build() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 12),
                UiKit.dp(context, 14), UiKit.dp(context, 12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xf7ffffff);
        background.setCornerRadius(UiKit.dp(context, 18));
        background.setStroke(UiKit.dp(context, 1), 0xffdfe4f2);
        root.setBackground(background);
        if (Build.VERSION.SDK_INT >= 21) root.setElevation(UiKit.dp(context, 10));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(12);
        dot.setTextColor(UiKit.LINK);
        head.addView(dot);
        TextView title = new TextView(context);
        title.setText("  DSH 正在工作");
        title.setTextSize(13);
        title.setTextColor(UiKit.INK);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button stop = new Button(context);
        stop.setText("停止");
        stop.setTextSize(12);
        stop.setAllCaps(false);
        stop.setMinWidth(0);
        stop.setMinimumWidth(0);
        stop.setMinHeight(0);
        stop.setMinimumHeight(UiKit.dp(context, 32));
        stop.setTextColor(0xffb3261e);
        stop.setPadding(UiKit.dp(context, 12), 0, UiKit.dp(context, 12), 0);
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(0xfffdecea);
        stopBg.setCornerRadius(UiKit.dp(context, 12));
        stop.setBackground(stopBg);
        stop.setOnClickListener(v -> {
            DshController.View active = AgentApp.get().dsh().controller.active();
            if (active != null) AgentApp.get().dsh().controller.cancel(active.id, "USER_CANCELLED");
        });
        head.addView(stop);
        root.addView(head);

        status = new TextView(context);
        status.setTextSize(13);
        status.setTextColor(UiKit.INK);
        status.setMaxLines(2);
        status.setPadding(0, UiKit.dp(context, 6), 0, 0);
        root.addView(status);

        meta = new TextView(context);
        meta.setTextSize(11);
        meta.setTextColor(UiKit.MUTED);
        meta.setPadding(0, UiKit.dp(context, 3), 0, 0);
        root.addView(meta);

        live = new TextView(context);
        live.setTextSize(11);
        live.setTextColor(0xff8a93a6);
        live.setMaxLines(2);
        live.setEllipsize(android.text.TextUtils.TruncateAt.END);
        live.setPadding(0, UiKit.dp(context, 5), 0, 0);
        live.setVisibility(View.GONE);
        root.addView(live);

        root.setOnClickListener(v -> openChat());
        return root;
    }

    private void openChat() {
        try {
            android.content.Intent intent = new android.content.Intent(context, DshActivity.class)
                    .putExtra("session", session)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (RuntimeException ignored) { }
    }

    private void render() {
        if (!showing) return;
        DshController.View active;
        try {
            active = AgentApp.get().dsh().controller.active();
        } catch (RuntimeException e) {
            // 进程刚起或本机数据不可用时不要让悬浮卡把主线程带崩。
            hide();
            return;
        }
        if (active == null) {
            status.setText("已完成，点击回到对话");
            meta.setText("");
            live.setVisibility(View.GONE);
            dot.setTextColor(0xff1a7858);
            return;
        }
        dot.setTextColor(UiKit.LINK);
        String state = active.status == null || active.status.isEmpty() ? "正在工作…" : active.status;
        status.setText(state);
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
        meta.setText("已运行 " + seconds + " 秒 · 期间可随时点「停止」");
        String text = active.live == null ? "" : active.live.trim();
        if (text.isEmpty()) {
            live.setVisibility(View.GONE);
        } else {
            live.setVisibility(View.VISIBLE);
            live.setText(text.length() > 120 ? text.substring(text.length() - 120) : text);
        }
    }
}
