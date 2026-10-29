package com.agentruntime.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import com.agentruntime.agent.BuildConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TaskEntryPermissionTest {
    @Test public void automationEntryRequiresShellPermissionAndIsPrivateInRelease() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ActivityInfo info = context.getPackageManager().getActivityInfo(new ComponentName(context, TaskExecActivity.class), 0);
        assertEquals("android.permission.DUMP", info.permission);
        assertEquals(BuildConfig.DEBUG, info.exported);
    }
}
