package com.agenttestapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

/**
 * 故障注入页：弹窗、加载、页面升级模拟、测试通知（T09/T10/T12）。
 */
public final class FaultActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fault);

        Button injectBtn = findViewById(R.id.btn_inject_dialog);
        updateInjectLabel(injectBtn);
        injectBtn.setOnClickListener(v -> {
            TestApp.setFlag(this, TestApp.KEY_INJECT_DIALOG, !TestApp.injectDialogOnChatOpen);
            updateInjectLabel(injectBtn);
        });

        findViewById(R.id.btn_show_dialog).setOnClickListener(v -> showPermissionDialog());

        final ProgressBar loading = findViewById(R.id.loading_bar);
        loading.setVisibility(View.GONE);
        findViewById(R.id.btn_loading).setOnClickListener(v -> {
            loading.setVisibility(View.VISIBLE);
            new Handler(Looper.getMainLooper()).postDelayed(() -> loading.setVisibility(View.GONE), 3000);
        });

        findViewById(R.id.btn_page_upgrade).setOnClickListener(v -> {
            TestApp.setFlag(this, TestApp.KEY_PAGE_UPGRADE, !TestApp.pageUpgradeMode);
            Toast.makeText(this, "页面升级模拟：" + (TestApp.pageUpgradeMode ? "开" : "关"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_notify).setOnClickListener(v -> sendTestNotification());

        findViewById(R.id.btn_back_main).setOnClickListener(v -> finish());
    }

    private void updateInjectLabel(Button b) {
        b.setText("弹窗注入：" + (TestApp.injectDialogOnChatOpen ? "开" : "关"));
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限提示")
                .setMessage("测试应用请求访问设备文件")
                .setCancelable(false)
                .setPositiveButton("允许", (dialog, which) -> {
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                })
                .show();
    }

    private void sendTestNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    "test_channel", "测试通知", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification.Builder builder = new Notification.Builder(this, "test_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TestUser")
                .setContentText("测试通知消息")
                .setAutoCancel(true);
        nm.notify(9001, builder.build());
    }
}
