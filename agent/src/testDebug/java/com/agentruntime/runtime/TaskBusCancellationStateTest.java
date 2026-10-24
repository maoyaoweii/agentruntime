package com.agentruntime.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TaskBusCancellationStateTest {

    @Test
    public void cancellationIsShownSeparatelyFromFailure() {
        TaskBus bus = TaskBus.get();
        bus.clearLog();
        AgentRuntime.Listener listener = bus.runtimeListener();
        try {
            listener.onFinish(new AgentRuntime.TaskResult("cancelled-test", "CANCELLED", new JSONObject()));

            assertEquals(TaskBus.State.CANCELLED, bus.state());
            assertEquals("已取消", bus.progressText());
            List<TaskBus.Event> cancelledEvents = bus.recent();
            assertEquals(1, cancelledEvents.size());
            assertEquals(TaskBus.Level.WARN, cancelledEvents.get(0).level);
            assertEquals("任务已取消", cancelledEvents.get(0).message);

            bus.clearLog();
            listener.onFinish(new AgentRuntime.TaskResult("timed-out-test", "TIMEOUT", new JSONObject()));
            assertEquals(TaskBus.State.FAILED, bus.state());
            assertEquals(TaskBus.Level.ERROR, bus.recent().get(0).level);
        } finally {
            bus.clearLog();
        }
    }
}
