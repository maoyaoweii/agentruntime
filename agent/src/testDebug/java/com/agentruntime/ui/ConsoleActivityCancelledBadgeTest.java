package com.agentruntime.ui;

import android.app.Application;
import android.widget.TextView;

import com.agentruntime.agent.R;
import com.agentruntime.runtime.TaskBus;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ConsoleActivityCancelledBadgeTest {

    @Test
    public void cancelledStateRendersCancelledBadge() throws Exception {
        TaskBus bus = TaskBus.get();
        Field stateField = TaskBus.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(bus);

        try {
            stateField.set(bus, TaskBus.State.CANCELLED);
            ActivityController<ConsoleActivity> controller =
                    Robolectric.buildActivity(ConsoleActivity.class).setup();
            try {
                TextView badge = controller.get().findViewById(R.id.tv_console_state);
                assertEquals("已取消", badge.getText().toString());
            } finally {
                controller.pause().stop().destroy();
            }
        } finally {
            stateField.set(bus, previousState);
        }
    }
}
