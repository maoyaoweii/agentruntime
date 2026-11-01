package com.agentruntime.vision;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 画面文字识别默认开启。
 *
 * <p>真机上微信只返回 2 个节点、0 个文字，如果还要用户先去设置里打开，
 * 等于把"读消息"这件事退回给用户。关掉仍然有效。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ScreenOcrDefaultsTest {

    @Test public void enabledByDefaultAndCanBeTurnedOff() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("dsh_screen_ocr", Context.MODE_PRIVATE).edit().clear().commit();

        assertTrue("默认必须是开启的", ScreenOcrSettings.enabled(context));

        assertTrue(ScreenOcrSettings.setEnabled(context, false));
        assertFalse(ScreenOcrSettings.enabled(context));

        long before = ScreenOcrSettings.revision(context);
        assertTrue(ScreenOcrSettings.setEnabled(context, true));
        assertTrue("开关变化要更新版本号", ScreenOcrSettings.revision(context) > before);
        assertTrue(ScreenOcrSettings.enabled(context));
    }
}
