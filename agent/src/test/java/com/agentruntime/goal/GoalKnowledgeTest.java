package com.agentruntime.goal;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 知识卡只给眼前这一张，且永远包在不可信块里。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class GoalKnowledgeTest {

    @Test public void wechatCardCoversTheTrapsWeActuallyHit() {
        Application context = RuntimeEnvironment.getApplication();
        String card = GoalKnowledge.cardFor(context, "com.tencent.mm");

        assertTrue(card.contains("com.tencent.mm"));
        assertTrue("要写清楚搜索会同时出联系人/群/记录", card.contains("同时"));
        assertTrue("草稿不等于已发送", card.contains("草稿"));
        assertTrue("空树要改看图", card.contains("空树"));
        assertTrue(card.contains("底部 tab"));
    }

    @Test public void unknownAppHasNoCardAndCardsAreWrappedAsUntrusted() {
        Application context = RuntimeEnvironment.getApplication();

        assertTrue(GoalKnowledge.cardFor(context, "com.example.none").isEmpty());

        String wrapped = GoalKnowledge.forPrompt(context, "com.tencent.mm");
        assertTrue(wrapped.startsWith("<untrusted_screen_data>"));
        assertTrue(wrapped.endsWith("</untrusted_screen_data>"));
        assertFalse(GoalKnowledge.forPrompt(context, null).contains("微信"));
    }
}
