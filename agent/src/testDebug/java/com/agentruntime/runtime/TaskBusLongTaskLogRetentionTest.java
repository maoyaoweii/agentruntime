package com.agentruntime.runtime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TaskBusLongTaskLogRetentionTest {

    @Test
    public void keepsLatest300EventsInOrderAndReturnsIndependentSnapshot() {
        TaskBus bus = TaskBus.get();
        bus.clearLog();
        try {
            for (int i = 0; i < 350; i++) {
                bus.info("long-task-test", "entry-" + i);
            }

            List<TaskBus.Event> snapshot = bus.recent();
            assertEquals(300, snapshot.size());
            assertEquals("entry-50", snapshot.get(0).message);
            assertEquals("entry-349", snapshot.get(snapshot.size() - 1).message);

            snapshot.clear();
            assertEquals("recent() must return an independent snapshot", 300, bus.recent().size());
        } finally {
            bus.clearLog();
        }
    }
}
