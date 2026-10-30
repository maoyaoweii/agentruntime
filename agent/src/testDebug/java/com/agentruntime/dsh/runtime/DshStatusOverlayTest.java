package com.agentruntime.dsh.runtime;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;

/**
 * 悬浮状态卡是"锦上添花"的通道：没有权限时必须安静地什么都不做，
 * 绝不能因为加不上窗口把正在跑的任务带崩。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DshStatusOverlayTest {

    @Test
    public void withoutOverlayPermissionShowAndHideAreNoOps() {
        Application context = RuntimeEnvironment.getApplication();
        DshStatusOverlay overlay = new DshStatusOverlay(context);

        assertFalse(DshStatusOverlay.permitted(context));

        overlay.show("session");
        overlay.hide();
    }
}
