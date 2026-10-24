package com.agenttestapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

/**
 * 测试文件选择页：虚构测试文件（report.pdf / photo.png / notes.txt）。
 */
public final class FilePickActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_pick);

        bind(R.id.btn_file_report, "report.pdf");
        bind(R.id.btn_file_photo, "photo.png");
        bind(R.id.btn_file_notes, "notes.txt");
    }

    private void bind(int id, final String alias) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra("file_alias", alias);
            setResult(RESULT_OK, data);
            finish();
        });
    }
}
