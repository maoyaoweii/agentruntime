package com.agentruntime.runtime;

import com.agentruntime.dsl.TaskSpec;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TaskBusGoalPrivacyTest {

    private static final String SYNTHETIC_SECRET = "synthetic-private-task-goal-marker";

    @Test
    public void taskStartEventDoesNotEchoRawGoal() throws Exception {
        Constructor<TaskBus> constructor = TaskBus.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TaskBus bus = constructor.newInstance();
        TaskSpec task = new TaskSpec("goal-privacy-test", "1.0", "opaque fixture " + SYNTHETIC_SECRET,
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new TaskSpec.TaskStep("sys.open_clock", new JSONObject(), null)),
                Collections.emptyList(), new TaskSpec.Limits(5, 0, 5));

        bus.runtimeListener().onTaskStart(task);

        List<TaskBus.Event> events = bus.recent();
        StringBuilder messages = new StringBuilder();
        for (TaskBus.Event event : events) messages.append(event.message).append('\n');
        assertFalse(messages.toString().contains(SYNTHETIC_SECRET));
        assertTrue(messages.toString().contains("目标已载入"));
    }
}
