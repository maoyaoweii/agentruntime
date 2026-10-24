package com.agentruntime;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;

import com.agentruntime.ui.UiKit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class UiKitPermissionFlowTest {

    @Test
    public void overlayPermissionFallbackFailureDoesNotEscapeActivity() {
        ActivityController<FailingSettingsActivity> controller =
                Robolectric.buildActivity(FailingSettingsActivity.class).setup();
        FailingSettingsActivity activity = controller.get();

        UiKit.openOverlayPermission(activity, 1002);

        assertEquals(1, activity.requestedIntentCount);
        assertEquals(1, activity.fallbackIntentCount);
        controller.pause().stop().destroy();
    }

    @Test
    public void accessibilitySettingsFailureDoesNotEscapeActivity() {
        ActivityController<FailingSettingsActivity> controller =
                Robolectric.buildActivity(FailingSettingsActivity.class).setup();
        FailingSettingsActivity activity = controller.get();

        UiKit.openAccessibilitySettings(activity);

        assertEquals(1, activity.fallbackIntentCount);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, activity.lastStartedIntent.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,
                activity.lastStartedIntent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        controller.pause().stop().destroy();
    }

    public static class FailingSettingsActivity extends Activity {
        int requestedIntentCount;
        int fallbackIntentCount;
        Intent lastStartedIntent;

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            requestedIntentCount++;
            throw new ActivityNotFoundException("no package-specific overlay settings handler");
        }

        @Override
        public void startActivity(Intent intent) {
            fallbackIntentCount++;
            lastStartedIntent = intent;
            throw new ActivityNotFoundException("no general overlay settings handler");
        }
    }
}
