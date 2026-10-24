package com.agentruntime.ui;

import android.app.Application;
import android.widget.Button;
import android.widget.EditText;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.local.LocalModelConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AgentApp.class)
public class SettingsActivityPortValidationTest {

    @Test
    public void hostSyntaxAcceptsDnsIpv4AndIpv6ButRejectsUrlsAndInvalidAddresses() {
        assertTrue(LocalModelConfig.isValidHost("localhost"));
        assertTrue(LocalModelConfig.isValidHost("models.internal.example"));
        assertTrue(LocalModelConfig.isValidHost("192.168.1.25"));
        assertTrue(LocalModelConfig.isValidHost("::1"));
        assertTrue(LocalModelConfig.isValidHost("[2001:db8::5]"));
        assertTrue(LocalModelConfig.isValidHost("2001:db8:0:1:2:3:4:5"));
        assertTrue(LocalModelConfig.isValidHost("::ffff:192.0.2.1"));
        assertTrue(LocalModelConfig.isValidHost(""));

        assertFalse(LocalModelConfig.isValidHost("999.1.1.1"));
        assertFalse(LocalModelConfig.isValidHost("https://models.example/v1"));
        assertFalse(LocalModelConfig.isValidHost("models..example"));
        assertFalse(LocalModelConfig.isValidHost("[::1"));
        assertFalse(LocalModelConfig.isValidHost("1:::2"));
        assertFalse(LocalModelConfig.isValidHost("2001:db8::g"));
        assertFalse(LocalModelConfig.isValidHost("1:2:3:4:5:6:7"));
    }

    @Test
    public void invalidHttpPortShowsErrorAndPreservesSavedConfiguration() {
        AgentApp app = app();
        app.localModels().configure("HTTP", "127.0.0.1", 8091,
                "vision-test", "decider-test", null, null);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity = controller.get();
            EditText port = activity.findViewById(R.id.et_local_port);
            port.setText("65536");
            ((Button) activity.findViewById(R.id.btn_local_save)).performClick();

            assertEquals(8091, app.localModels().config().port);
            assertNotNull("无效端口应显示内联错误", port.getError());
            assertEquals("请输入 1–65535 的有效端口", port.getError().toString());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void validHttpPortClearsOldErrorAndSaves() {
        AgentApp app = app();
        app.localModels().configure("HTTP", "127.0.0.1", 8091,
                "vision-test", "decider-test", null, null);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity = controller.get();
            EditText port = activity.findViewById(R.id.et_local_port);
            port.setError("旧错误");
            port.setText("9090");
            ((Button) activity.findViewById(R.id.btn_local_save)).performClick();

            assertEquals(9090, app.localModels().config().port);
            assertNull("修正为合法端口后应清除旧错误", port.getError());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void invalidHttpHostShowsErrorAndPreservesSavedConfiguration() {
        AgentApp app = app();
        app.localModels().configure("HTTP", "127.0.0.1", 8091,
                "vision-test", "decider-test", null, null);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity = controller.get();
            EditText host = activity.findViewById(R.id.et_local_host);
            host.setText("https://models.example/v1");
            ((Button) activity.findViewById(R.id.btn_local_save)).performClick();

            assertEquals("127.0.0.1", app.localModels().config().host);
            assertNotNull("无效主机地址应显示内联错误", host.getError());
            assertEquals("请输入主机名、IPv4 或 IPv6 地址，不要填写协议或路径",
                    host.getError().toString());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void validHttpHostClearsOldErrorAndSaves() {
        AgentApp app = app();
        app.localModels().configure("HTTP", "127.0.0.1", 8091,
                "vision-test", "decider-test", null, null);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity = controller.get();
            EditText host = activity.findViewById(R.id.et_local_host);
            host.setError("旧错误");
            host.setText("localhost");
            ((Button) activity.findViewById(R.id.btn_local_save)).performClick();

            assertEquals("localhost", app.localModels().config().host);
            assertNull("修正为合法主机名后应清除旧错误", host.getError());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private AgentApp app() {
        Application application = RuntimeEnvironment.getApplication();
        return (AgentApp) application;
    }
}
