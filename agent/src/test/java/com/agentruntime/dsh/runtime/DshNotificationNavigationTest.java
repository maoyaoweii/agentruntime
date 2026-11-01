package com.agentruntime.dsh.runtime;

import android.content.Intent;
import android.widget.EditText;
import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.dsh.store.SessionStore;
import com.agentruntime.dsh.ui.DshActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=AgentApp.class)
public final class DshNotificationNavigationTest {
    @Test public void notificationReusesChatAndPreservesBothSessionDrafts() {
        AgentApp app=AgentApp.get(); SessionStore store=app.dsh().sessions;
        String first=store.createSession().id,second=store.createSession().id;
        app.getSharedPreferences("com.agentruntime.dsh.ui.DshActivity",0).edit().putString("draft_"+second,"草稿 B").commit();
        Intent initial=new Intent(app,DshActivity.class).putExtra("session",first);
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class,initial).setup()) {
            EditText composer=controller.get().findViewById(R.id.dsh_composer); composer.setText("草稿 A");
            Intent notification=DshTurnService.conversationIntent(app,second);
            assertTrue((notification.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP)!=0);
            assertTrue((notification.getFlags()&Intent.FLAG_ACTIVITY_SINGLE_TOP)!=0);
            controller.newIntent(notification);
            assertEquals("草稿 A",app.getSharedPreferences("com.agentruntime.dsh.ui.DshActivity",0).getString("draft_"+first,""));
            assertEquals("草稿 B",composer.getText().toString());
        }
    }
}
