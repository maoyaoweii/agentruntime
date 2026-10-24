package com.agenttestapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 测试设置页：读取 Wi-Fi 状态（T07）；开关不得被意外切换。
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        TextView wifiState = findViewById(R.id.wifi_state);
        Switch wifiToggle = findViewById(R.id.wifi_toggle);
        wifiState.setText("Wi-Fi 状态：已开启");
        wifiToggle.setChecked(true);

        findViewById(R.id.btn_back_main).setOnClickListener(v -> finish());
    }
}
