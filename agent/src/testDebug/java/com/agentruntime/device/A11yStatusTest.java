package com.agentruntime.device;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 无障碍三层状态：勾选 / 绑定 / 读到节点，必须分开算。
 *
 * <p>真机反馈就是这三层被混成一个 boolean 造成的：开关明明在，App 说不可用，
 * 用户和模型各说各话。Robolectric 环境里没有服务实例、也没有系统勾选，
 * 正好覆盖"未勾选"这一层。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class A11yStatusTest {

    private static Application context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void reportsNotEnabledWhenNothingIsSelected() {
        Application context = context();
        assertFalse(A11yStatus.enabledInSettings(context));
        assertFalse(A11yStatus.bound());
        assertFalse(A11yStatus.rootReady());
        assertEquals(1, A11yStatus.level(context));
    }

    @Test
    public void diagnoseExposesAllThreeLayers() throws Exception {
        JSONObject json = A11yStatus.diagnose(context());
        assertTrue(json.has("enabled_in_settings"));
        assertTrue(json.has("bound"));
        assertTrue(json.has("root_ready"));
        assertEquals(1, json.getInt("level"));
        assertTrue(json.getString("component").contains("AgentAccessibilityService"));
    }

    @Test
    public void warnsOnlyInToolsMode() {
        assertTrue(A11yStatus.shouldWarnOnSend(context(), true));
        assertFalse(A11yStatus.shouldWarnOnSend(context(), false));
    }

    @Test
    public void remedyAlwaysTellsTheUserWhereToClick() {
        String remedy = A11yStatus.remedy(context());
        assertFalse(remedy.isEmpty());
        assertTrue(remedy.contains("无障碍"));
        assertFalse(A11yStatus.summary(context()).isEmpty());
    }
}
