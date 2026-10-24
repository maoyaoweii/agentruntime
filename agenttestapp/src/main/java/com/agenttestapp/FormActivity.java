package com.agenttestapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 测试表单页：填写、滚动、本地回显提交（T08 的本地化子集，无外部请求）。
 */
public final class FormActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_form);

        EditText name = findViewById(R.id.form_name);
        EditText email = findViewById(R.id.form_email);
        EditText note = findViewById(R.id.form_note);
        TextView echo = findViewById(R.id.form_echo);

        findViewById(R.id.btn_submit).setOnClickListener(v ->
                echo.setText("已提交：" + name.getText() + " / " + email.getText() + " / " + note.getText()));
    }
}
