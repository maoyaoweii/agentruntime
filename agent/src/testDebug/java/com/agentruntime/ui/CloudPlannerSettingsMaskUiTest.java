package com.agentruntime.ui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.EditText;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.planner.CloudPlannerAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AgentApp.class)
public class CloudPlannerSettingsMaskUiTest {

    @Test
    public void savingShortKeyShowsMaskedHintAndClearsEntryField() {
        AgentApp app = (AgentApp) RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences(
                CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE);
        boolean hadBaseUrl = prefs.contains(CloudPlannerAdapter.KEY_BASE_URL);
        String oldBaseUrl = prefs.getString(CloudPlannerAdapter.KEY_BASE_URL, null);
        boolean hadApiKey = prefs.contains(CloudPlannerAdapter.KEY_API_KEY);
        String oldApiKey = prefs.getString(CloudPlannerAdapter.KEY_API_KEY, null);
        boolean hadModel = prefs.contains(CloudPlannerAdapter.KEY_MODEL);
        String oldModel = prefs.getString(CloudPlannerAdapter.KEY_MODEL, null);
        ActivityController<SettingsActivity> controller = null;
        String syntheticKey = "dev-key";
        try {
            controller = Robolectric.buildActivity(SettingsActivity.class).setup();
            SettingsActivity activity = controller.get();
            EditText url = activity.findViewById(R.id.et_cloud_url);
            EditText key = activity.findViewById(R.id.et_cloud_key);
            EditText model = activity.findViewById(R.id.et_cloud_model);
            url.setText("https://cloud.example.invalid/v1");
            key.setText(syntheticKey);
            model.setText("fixture-model");
            ((Button) activity.findViewById(R.id.btn_cloud_save)).performClick();

            assertEquals("", key.getText().toString());
            assertEquals("已保存（****），留空表示不修改", key.getHint().toString());
            assertFalse(key.getHint().toString().contains(syntheticKey));
        } finally {
            if (controller != null) controller.pause().stop().destroy();
            SharedPreferences.Editor editor = prefs.edit();
            restore(editor, CloudPlannerAdapter.KEY_BASE_URL, hadBaseUrl, oldBaseUrl);
            restore(editor, CloudPlannerAdapter.KEY_API_KEY, hadApiKey, oldApiKey);
            restore(editor, CloudPlannerAdapter.KEY_MODEL, hadModel, oldModel);
            editor.commit();
        }
    }

    @Test
    public void unsavedKeyIsNotRestoredAfterActivityRecreation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            EditText key = controller.get().findViewById(R.id.et_cloud_key);
            key.setText("synthetic-unsaved-value");

            controller.recreate();

            EditText restoredKey = controller.get().findViewById(R.id.et_cloud_key);
            assertTrue("unsaved password field should be empty after recreation",
                    restoredKey.getText().length() == 0);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void restore(SharedPreferences.Editor editor, String key,
                                boolean previouslyPresent, String previousValue) {
        if (previouslyPresent) editor.putString(key, previousValue);
        else editor.remove(key);
    }
}
