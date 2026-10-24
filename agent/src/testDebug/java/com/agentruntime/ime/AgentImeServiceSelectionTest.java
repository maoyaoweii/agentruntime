package com.agentruntime.ime;

import android.app.Application;
import android.content.ComponentName;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class AgentImeServiceSelectionTest {

    @Test
    public void selectedImeMustBelongToExactApplicationPackage() {
        Application context = RuntimeEnvironment.getApplication();
        String setting = Settings.Secure.DEFAULT_INPUT_METHOD;
        String previous = Settings.Secure.getString(context.getContentResolver(), setting);
        try {
            String valid = new ComponentName(context.getPackageName(), AgentImeService.class.getName())
                    .flattenToString();
            Settings.Secure.putString(context.getContentResolver(), setting, valid);
            assertTrue(AgentImeService.isSelected(context));

            Settings.Secure.putString(context.getContentResolver(), setting,
                    "com.example.other/.Keyboard");
            assertFalse(AgentImeService.isSelected(context));

            // A malformed flattened component must not match merely because its class text
            // contains this application's package name.
            Settings.Secure.putString(context.getContentResolver(), setting,
                    "com.example.other/" + context.getPackageName() + "/.ForgedKeyboard");
            assertFalse(AgentImeService.isSelected(context));
        } finally {
            Settings.Secure.putString(context.getContentResolver(), setting, previous);
        }
    }
}
