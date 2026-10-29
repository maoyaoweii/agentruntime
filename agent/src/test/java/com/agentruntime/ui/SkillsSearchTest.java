package com.agentruntime.ui;

import android.widget.*;
import com.agentruntime.agent.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SkillsSearchTest {
    @Test public void searchSupportsRiskLabelsAndRecoversFromNoResults() {
        SkillsActivity activity = Robolectric.buildActivity(SkillsActivity.class).setup().get();
        EditText search = activity.findViewById(R.id.et_skill_search);
        LinearLayout container = activity.findViewById(R.id.skill_container);
        int total = container.getChildCount();
        assertTrue(total > 0);
        search.setText("高风险");
        assertTrue(container.getChildCount() > 0);
        for (int i = 0; i < container.getChildCount(); i++) {
            TextView badge = container.getChildAt(i).findViewById(R.id.item_skill_risk);
            assertNotNull(badge);
            assertEquals("高风险", badge.getText().toString());
        }
        search.setText("no-such-fixture-skill");
        assertEquals(1, container.getChildCount());
        assertTrue(((TextView) container.getChildAt(0)).getText().toString().contains("没有找到"));
        search.setText("");
        assertEquals(total, container.getChildCount());
    }
}
