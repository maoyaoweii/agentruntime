package com.agentruntime.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import com.agentruntime.agent.R;
import com.agentruntime.ui.ConsoleActivity;
import com.agentruntime.ui.MainActivity;

/**
 * 通知统一入口：常驻状态 + 任务结果。
 *
 * <p>常驻通知不是装饰——它是前台服务的存活凭证，也是用户从任何界面回到 Agent 的入口。
 * 结果通知单独一条渠道，方便用户单独静音结果提醒而保留状态。
 */
public final class AgentNotifier {

    public static final String CH_STATUS = "agent_status";
    public static final String CH_RESULT = "agent_result";

    public static final int ID_ONGOING = 1001;
    public static final int ID_RESULT = 1002;

    private AgentNotifier() {
    }

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(
                CH_STATUS, "Agent 运行状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("悬浮球与后台执行状态");
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel result = new NotificationChannel(
                CH_RESULT, "任务结果", NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription("任务完成或失败时提醒");
        nm.createNotificationChannel(result);
    }

    /** 前台服务用的常驻通知。 */
    public static Notification ongoing(Context c, String text) {
        ensureChannels(c);

        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingFlags());

        PendingIntent console = PendingIntent.getActivity(c, 1,
                new Intent(c, ConsoleActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingFlags());

        Notification.Builder b = builder(c, CH_STATUS)
                .setSmallIcon(R.drawable.ic_bot)
                .setContentTitle("Agent 待命中")
                .setContentText(text == null ? "悬浮球已开启，点击进入" : text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_bot), "控制台", console).build());

        return b.build();
    }

    public static void updateOngoing(Context c, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_ONGOING, ongoing(c, text));
    }

    /** 任务结果通知。 */
    public static void result(Context c, boolean success, String taskId, String status) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        PendingIntent console = PendingIntent.getActivity(c, 2,
                new Intent(c, ConsoleActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingFlags());

        Notification n = builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_bot)
                .setContentTitle(success ? "任务完成" : "任务未成功")
                .setContentText((taskId == null ? "" : taskId + " · ") + status)
                .setContentIntent(console)
                .setAutoCancel(true)
                .build();
        nm.notify(ID_RESULT, n);
    }

    public static void cancelOngoing(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_ONGOING);
    }

    private static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(c, channel);
        }
        return new Notification.Builder(c);
    }

    private static int pendingFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
