package com.agenttestapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 测试聊天页：消息流、附件菜单（图片/文件/返回）、文件发送、文本发送。
 * pageUpgradeMode 模拟应用改版：关键控件换 ID 与文案（T12）。
 * injectDialogOnChatOpen 模拟权限弹窗打断（T10）。
 */
public final class ChatActivity extends Activity {

    public static final int REQ_PICK_FILE = 1001;

    private String alias;
    private String userId;
    private LinearLayout messagesContainer;
    private View attachMenu;
    private TextView pendingFile;
    private Button btnAttach;
    private Button btnSend;
    private Button btnSendFile;
    private EditText textInput;
    private String pendingAlias;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        alias = getIntent().getStringExtra("alias");
        userId = getIntent().getStringExtra("user_id");
        if (alias == null) alias = "TestUser";
        if (userId == null) userId = "u_test_001";

        TextView title = findViewById(R.id.chat_title);
        title.setText(alias);

        messagesContainer = findViewById(R.id.messages_container);
        attachMenu = findViewById(R.id.attach_menu);
        pendingFile = findViewById(R.id.pending_file);
        btnAttach = findViewById(R.id.btn_attach);
        btnSend = findViewById(R.id.btn_send);
        btnSendFile = findViewById(R.id.btn_send_file);
        textInput = findViewById(R.id.text_input);

        attachMenu.setVisibility(View.GONE);
        pendingFile.setVisibility(View.GONE);
        btnSendFile.setVisibility(View.GONE);

        if (TestApp.pageUpgradeMode) {
            btnAttach.setId(R.id.btn_attach_alt);
            btnAttach.setText("添加附件");
            btnSend.setId(R.id.btn_send_alt);
            btnSend.setText("确认发送");
            btnSendFile.setId(R.id.btn_send_file_alt);
            btnSendFile.setText("确认发送文件");
        }

        btnAttach.setOnClickListener(v -> toggleAttachMenu());
        findViewById(R.id.btn_attach_image).setOnClickListener(v -> {
            appendMessage("[图片] photo.png", "image");
            attachMenu.setVisibility(View.GONE);
        });
        findViewById(R.id.btn_attach_file).setOnClickListener(v -> {
            attachMenu.setVisibility(View.GONE);
            startActivityForResult(new Intent(this, FilePickActivity.class), REQ_PICK_FILE);
        });
        findViewById(R.id.btn_attach_back).setOnClickListener(v -> attachMenu.setVisibility(View.GONE));

        btnSendFile.setOnClickListener(v -> {
            if (pendingAlias == null) return;
            appendMessage("[文件] " + pendingAlias, "file");
            pendingAlias = null;
            pendingFile.setVisibility(View.GONE);
            btnSendFile.setVisibility(View.GONE);
        });

        btnSend.setOnClickListener(v -> {
            String text = textInput.getText().toString();
            if (text.isEmpty()) return;
            appendMessage("[文本] " + text, "text");
            textInput.setText("");
        });

        if (TestApp.injectDialogOnChatOpen) {
            showPermissionDialog();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null) {
            pendingAlias = data.getStringExtra("file_alias");
            if (pendingAlias != null) {
                pendingFile.setText("待发送文件：" + pendingAlias);
                pendingFile.setVisibility(View.VISIBLE);
                btnSendFile.setVisibility(View.VISIBLE);
            }
        }
    }

    private void toggleAttachMenu() {
        attachMenu.setVisibility(attachMenu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限提示")
                .setMessage("测试应用请求访问设备文件")
                .setCancelable(false)
                .setPositiveButton("允许", (dialog, which) ->
                        TestApp.setFlag(this, TestApp.KEY_INJECT_DIALOG, false))
                .setNegativeButton("拒绝", (dialog, which) ->
                        TestApp.setFlag(this, TestApp.KEY_INJECT_DIALOG, false))
                .show();
    }

    private void appendMessage(String rendered, String type) {
        TextView tv = new TextView(this);
        tv.setText(rendered);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setPadding(8, 8, 8, 8);
        messagesContainer.addView(tv);
        TestApp.store().append(new TestApp.MessageRecord(userId, alias, type, rendered, System.currentTimeMillis()));
    }
}
