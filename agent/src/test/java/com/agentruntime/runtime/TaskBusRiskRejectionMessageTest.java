package com.agentruntime.runtime;

import com.agentruntime.dsl.TaskSpec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TaskBusRiskRejectionMessageTest {

    @Test
    public void runtimeListenerExplainsRejectedConfirmationInPlainChinese() {
        TaskBus bus = TaskBus.get();
        bus.clearLog();
        try {
            AgentRuntime.Listener listener = bus.runtimeListener();
            listener.onStepEnd(1, (TaskSpec.TaskStep) null, false, "RISK_REJECTED", 0);
            listener.onStepEnd(2, (TaskSpec.TaskStep) null, false, "WAIT_TIMEOUT", 17);

            List<TaskBus.Event> events = bus.recent();
            assertEquals(2, events.size());
            assertTrue(events.get(0).message.contains("确认被拒，操作未执行"));
            assertTrue(events.get(0).message.contains("RISK_REJECTED"));
            assertTrue(events.get(1).message.contains("WAIT_TIMEOUT"));
            assertEquals(TaskBus.Level.ERROR, events.get(0).level);
        } finally {
            bus.clearLog();
        }
    }
}
