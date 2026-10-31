package com.agentruntime.prompt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 规划提示的硬约束必须还在这一份里。
 *
 * <p>静态约束只能有一处：以前规划器在 CloudPlannerAdapter 里另写了一套长 system，
 * 两边约束一旦漂移，就会出现"同一句话在两个入口要求不一样"。
 */
public class PlannerPromptTest {

    @Test
    public void promptVersionAdvancesWithThisChange() {
        assertEquals("1.2.0", PromptLibrary.PROMPT_VERSION);
    }

    @Test
    public void plannerRejectsWebStandInsAndEmptyPreconditions() {
        String prompt = PromptLibrary.PLANNER_SYSTEM;

        assertTrue(prompt.contains("不能用 web.* 代替原生应用"));
        assertTrue(prompt.contains("preconditions 必须是 []"));
        assertTrue(prompt.contains("sys.open_settings_page"));
        assertTrue(prompt.contains("ui_contains:"));
        assertTrue(prompt.contains("text_message_visible:"));
        assertTrue(prompt.contains("package_is:"));
        assertTrue(prompt.contains("3 到 5 个中英文候选"));
        assertTrue(prompt.contains("confirmation 只允许"));
        assertTrue(prompt.contains("max_steps 至少 24"));
        assertTrue(prompt.contains("{\"error\":\"原因\"}"));
        assertTrue(prompt.contains("task_version 必须是 1.0"));
    }

    @Test
    public void deciderRefusesToRepeatTheSameAction() {
        assertTrue(PromptLibrary.DECIDER_SYSTEM.contains("give_up"));
        assertTrue(PromptLibrary.DECIDER_SYSTEM.contains("禁止重复上一动作的相同参数"));
        assertTrue(PromptLibrary.DECIDER_SYSTEM.contains("一次只决策一步"));
    }

    @Test
    public void groundingNeverGuessesCoordinates() {
        assertTrue(PromptLibrary.GROUNDING_SYSTEM.contains("{\"found\":false}"));
        assertTrue(PromptLibrary.GROUNDING_SYSTEM.contains("严禁猜测"));
        assertFalse(PromptLibrary.GROUNDING_SYSTEM.contains("大概位置\"}"));
    }
}
