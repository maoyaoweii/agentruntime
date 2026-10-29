package com.agentruntime.device;

import android.app.Application;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class IntentBridgePlatformTest {
    @Test @Config(sdk = 30) public void legacyPlatformCanStillCloseSystemDialogs() {
        Application app = RuntimeEnvironment.getApplication();
        assertEquals("DIALOGS_CLOSED", new IntentBridge(app).closeSystemDialogs().code);
        assertEquals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS,
                Shadows.shadowOf(app).getBroadcastIntents().get(0).getAction());
    }

    @Test @Config(sdk = 33) public void modernPlatformNeverSendsTheProtectedBroadcastWithoutAccessibility() {
        Application app = RuntimeEnvironment.getApplication();
        assertEquals("NEED_ACCESSIBILITY", new IntentBridge(app).closeSystemDialogs().code);
        assertTrue(Shadows.shadowOf(app).getBroadcastIntents().isEmpty());
    }
}
