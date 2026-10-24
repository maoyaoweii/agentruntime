package com.agenttestapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

/**
 * 测试主页：搜索测试用户并进入会话；入口跳转表单/设置/故障注入页。
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText searchInput = findViewById(R.id.search_input);
        Button btnSearch = findViewById(R.id.btn_search);
        final Button[] rows = {
                findViewById(R.id.btn_user_testuser),
                findViewById(R.id.btn_user_testuser2),
                findViewById(R.id.btn_user_admin)
        };
        final String[] userIds = {"u_test_001", "u_test_002", "u_admin_001"};

        btnSearch.setOnClickListener(v -> applyFilter(searchInput.getText().toString(), rows));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s.toString(), rows);
            }
        });

        for (int i = 0; i < rows.length; i++) {
            final int idx = i;
            rows[i].setOnClickListener(v -> {
                Intent intent = new Intent(this, ChatActivity.class);
                intent.putExtra("alias", rows[idx].getText().toString());
                intent.putExtra("user_id", userIds[idx]);
                startActivity(intent);
            });
        }

        findViewById(R.id.btn_form).setOnClickListener(v -> startActivity(new Intent(this, FormActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_fault).setOnClickListener(v -> startActivity(new Intent(this, FaultActivity.class)));
    }

    private void applyFilter(String query, Button[] rows) {
        String q = query == null ? "" : query.trim();
        for (Button row : rows) {
            boolean match = q.isEmpty() || row.getText().toString().contains(q);
            row.setVisibility(match ? View.VISIBLE : View.GONE);
        }
    }
}
