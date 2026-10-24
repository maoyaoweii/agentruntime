package com.agentruntime.ui;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.provider.Settings;
import android.widget.TextView;

import com.agentruntime.agent.R;
import com.agentruntime.service.AgentAccessibilityService;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SettingsActivityPermissionRefreshTest {

    @Test
    public void notificationBadgeRefreshesAfterReturningToSettingsActivity() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Shadows.shadowOf(manager).setNotificationsEnabled(false);

        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        TextView badge = activity.findViewById(R.id.perm_notify);
        assertEquals("去允许", badge.getText().toString());

        Shadows.shadowOf(manager).setNotificationsEnabled(true);
        controller.pause().resume();
        assertEquals("已允许", badge.getText().toString());

        controller.pause().stop().destroy();
    }

    @Test
    public void overlayBadgeRefreshesAfterReturningFromSystemSettings() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        boolean originallyAllowed = Settings.canDrawOverlays(context);
        ShadowSettings.setCanDrawOverlays(false);
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity = controller.get();
            TextView badge = activity.findViewById(R.id.perm_overlay);
            assertEquals("去授权", badge.getText().toString());

            ShadowSettings.setCanDrawOverlays(true);
            controller.pause().resume();
            assertEquals("已授权", badge.getText().toString());
        } finally {
            controller.pause().stop().destroy();
            ShadowSettings.setCanDrawOverlays(originallyAllowed);
        }
    }

    @Test
    public void accessibilityBadgeRefreshesAndRestoresStaticServiceState() throws Exception {
        Field instance = AgentAccessibilityService.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object originalService = instance.get(null);
        ActivityController<SettingsActivity> controller = null;
        try {
            instance.set(null, new AgentAccessibilityService());
            controller = Robolectric.buildActivity(SettingsActivity.class).setup();
            SettingsActivity activity = controller.get();
            TextView badge = activity.findViewById(R.id.perm_a11y);
            assertEquals("已启用", badge.getText().toString());

            instance.set(null, null);
            controller.pause().resume();
            assertEquals("去开启", badge.getText().toString());
        } finally {
            if (controller != null) controller.pause().stop().destroy();
            instance.set(null, originalService);
        }
    }

    @Test
    public void imeBadgeRefreshesAfterReturningFromInputMethodSettings() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        String oldInputMethod = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        ActivityController<SettingsActivity> controller = null;
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, "com.example.other/.InputMethod");
            controller = Robolectric.buildActivity(SettingsActivity.class).setup();
            SettingsActivity activity = controller.get();
            TextView badge = activity.findViewById(R.id.perm_ime);
            assertEquals("去启用", badge.getText().toString());

            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    context.getPackageName() + "/com.agentruntime.ime.AgentImeService");
            controller.pause().resume();
            assertEquals("已启用", badge.getText().toString());
        } finally {
            if (controller != null) controller.pause().stop().destroy();
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, oldInputMethod);
        }
    }
}
