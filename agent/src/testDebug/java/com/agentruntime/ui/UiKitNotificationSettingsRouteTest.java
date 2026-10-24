package com.agentruntime.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class UiKitNotificationSettingsRouteTest {

    @Test
    public void api30OpensAppNotificationSettingsInsteadOfRequestingRuntimePermission() {
        ActivityController<RecordingActivity> controller =
                Robolectric.buildActivity(RecordingActivity.class).setup();
        RecordingActivity activity = controller.get();
        try {
            UiKit.requestNotificationPermission(activity, 71);

            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    activity.startedIntent.getAction());
            assertEquals(activity.getPackageName(),
                    activity.startedIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE));
            assertNull(Shadow.<ShadowActivity>extract(activity).getLastRequestedPermission());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    @Config(sdk = 33)
    public void api33RequestsPostNotificationsRuntimePermission() {
        ActivityController<RecordingActivity> controller =
                Robolectric.buildActivity(RecordingActivity.class).setup();
        RecordingActivity activity = controller.get();
        try {
            UiKit.requestNotificationPermission(activity, 72);

            ShadowActivity.PermissionsRequest request =
                    Shadow.<ShadowActivity>extract(activity).getLastRequestedPermission();
            assertEquals(72, request.requestCode);
            assertEquals(1, request.requestedPermissions.length);
            assertEquals("android.permission.POST_NOTIFICATIONS", request.requestedPermissions[0]);
            assertNull(activity.startedIntent);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    public static class RecordingActivity extends Activity {
        Intent startedIntent;

        @Override
        public void startActivity(Intent intent) {
            startedIntent = intent;
        }
    }
}
