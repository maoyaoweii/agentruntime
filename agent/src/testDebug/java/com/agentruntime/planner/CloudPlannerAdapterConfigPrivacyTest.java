package com.agentruntime.planner;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class CloudPlannerAdapterConfigPrivacyTest {

    @Test
    public void shortSavedKeyIsMaskedAndNullPartialUpdatePreservesIt() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences(
                CloudPlannerAdapter.PREFS, Context.MODE_PRIVATE);
        boolean hadBaseUrl = prefs.contains(CloudPlannerAdapter.KEY_BASE_URL);
        String oldBaseUrl = prefs.getString(CloudPlannerAdapter.KEY_BASE_URL, null);
        boolean hadApiKey = prefs.contains(CloudPlannerAdapter.KEY_API_KEY);
        String oldApiKey = prefs.getString(CloudPlannerAdapter.KEY_API_KEY, null);
        boolean hadModel = prefs.contains(CloudPlannerAdapter.KEY_MODEL);
        String oldModel = prefs.getString(CloudPlannerAdapter.KEY_MODEL, null);
        String syntheticKey = "  dev-key  ";
        String baseUrl = "https://cloud.example.invalid/v1";
        CloudPlannerAdapter adapter = new CloudPlannerAdapter(context);
        try {
            adapter.configure(baseUrl, syntheticKey, "fixture-model");

            JSONObject initial = adapter.configInfo();
            assertTrue(initial.getBoolean("api_key_set"));
            assertEquals("****", initial.getString("api_key_masked"));
            assertFalse(initial.toString().contains(syntheticKey.trim()));

            adapter.configure(null, null, "fixture-model-updated");
            JSONObject updated = adapter.configInfo();
            assertEquals(baseUrl, updated.getString("base_url"));
            assertEquals("fixture-model-updated", updated.getString("model"));
            assertTrue(updated.getBoolean("api_key_set"));
            assertEquals("****", updated.getString("api_key_masked"));
            assertTrue(updated.getBoolean("configured"));
            assertFalse(updated.toString().contains(syntheticKey.trim()));
        } catch (Exception e) {
            throw new AssertionError("cloud config fixture failed", e);
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            restore(editor, CloudPlannerAdapter.KEY_BASE_URL, hadBaseUrl, oldBaseUrl);
            restore(editor, CloudPlannerAdapter.KEY_API_KEY, hadApiKey, oldApiKey);
            restore(editor, CloudPlannerAdapter.KEY_MODEL, hadModel, oldModel);
            editor.commit();
        }
    }

    private static void restore(SharedPreferences.Editor editor, String key,
                                boolean previouslyPresent, String previousValue) {
        if (previouslyPresent) editor.putString(key, previousValue);
        else editor.remove(key);
    }
}
