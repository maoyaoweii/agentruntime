package com.agentruntime;

import android.app.NotificationManager;
import android.content.Context;

import com.agentruntime.ui.UiKit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowNotificationManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NotificationPermissionTest {

    @Test
    @Config(sdk = 33)
    public void android13StatusTracksRuntimePermissionGrant() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowContextWrapper shadow = Shadow.extract(context);
        shadow.denyPermissions("android.permission.POST_NOTIFICATIONS");
        assertFalse(UiKit.hasNotificationPermission(context));

        shadow.grantPermissions("android.permission.POST_NOTIFICATIONS");
        assertTrue(UiKit.hasNotificationPermission(context));
    }

    @Test
    public void preAndroid13StatusTracksSystemNotificationToggle() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        assertNotNull(manager);
        ShadowNotificationManager shadow = Shadow.extract(manager);

        shadow.setNotificationsEnabled(false);
        assertFalse(UiKit.hasNotificationPermission(context));

        shadow.setNotificationsEnabled(true);
        assertTrue(UiKit.hasNotificationPermission(context));
    }
}
