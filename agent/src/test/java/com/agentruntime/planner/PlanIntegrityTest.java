package com.agentruntime.planner;

import android.app.Application;
import com.agentruntime.audit.AuditLogger;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.skill.SkillEngine;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class PlanIntegrityTest {
    @Test public void preservesCreateThenSaveInTheSameApplication() throws Exception {
        TaskSpec result = sanitize(task(2));
        assertEquals(2, result.steps.size());
        assertEquals("新建", result.steps.get(0).args.getString("text"));
        assertEquals("保存", result.steps.get(1).args.getString("text"));
    }

    @Test public void nineStepsRemainWithinTheValidatedActionBudget() throws Exception {
        TaskSpec result = sanitize(task(9));
        assertEquals(9, result.steps.size());
        assertEquals(64, result.limits.maxSteps);
        assertTrue(DslParser.validateTaskJson(result.toJson()).ok);
    }

    private TaskSpec sanitize(TaskSpec spec) throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ModelRouter router = new ModelRouter(app, new SkillEngine(app), new AuditLogger(app), null, null, null);
        Method sanitize = ModelRouter.class.getDeclaredMethod("sanitize", TaskSpec.class);
        sanitize.setAccessible(true);
        return (TaskSpec) sanitize.invoke(router, spec);
    }

    private TaskSpec task(int count) throws Exception {
        JSONArray steps = new JSONArray();
        for (int i = 0; i < count; i++) {
            steps.put(new JSONObject().put("skill", "sys.ui_launch_tap")
                    .put("args", new JSONObject().put("app_package", "org.fixture.notes")
                            .put("text", i == 0 ? "新建" : "保存")));
        }
        return DslParser.parseTask(new JSONObject().put("task_version", "1.0").put("task_id", "integrity")
                .put("goal", "synthetic fixture").put("allowed_apps", new JSONArray().put("org.fixture.notes"))
                .put("preconditions", new JSONArray()).put("postconditions", new JSONArray()).put("steps", steps)
                .put("limits", new JSONObject().put("max_steps", 24).put("max_replans", 0)
                        .put("timeout_seconds", 180)).toString());
    }
}
