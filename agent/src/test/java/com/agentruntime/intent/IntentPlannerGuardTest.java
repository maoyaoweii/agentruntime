package com.agentruntime.intent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 意图模型「实体盲区」护栏：open_clock 只能被真正的时钟类目标触发。
 *
 * <p>背景（真机实测，见 verification/market_apps/B03、B04）：
 * 「打开 Aurora 商店应用」「打开 F-Droid 应用商店」被内置模型判成 open_clock，
 * 实际启动的是时钟，任务还报 SUCCESS。护栏把这类误判挡在通道入口。
 */
public class IntentPlannerGuardTest {

    @Test
    public void clockWordsAreRecognized() {
        assertTrue(IntentPlanner.mentionsClock("打开时钟"));
        assertTrue(IntentPlanner.mentionsClock("帮我打开闹钟应用"));
        assertTrue(IntentPlanner.mentionsClock("open the clock app"));
        assertTrue(IntentPlanner.mentionsClock("计时器"));
    }

    @Test
    public void otherAppsAreNotClockGoals() {
        assertFalse(IntentPlanner.mentionsClock("打开 Aurora 商店应用"));
        assertFalse(IntentPlanner.mentionsClock("打开 F-Droid 应用商店"));
        assertFalse(IntentPlanner.mentionsClock("打开 NewPipe 应用"));
        assertFalse(IntentPlanner.mentionsClock(null));
    }
}
