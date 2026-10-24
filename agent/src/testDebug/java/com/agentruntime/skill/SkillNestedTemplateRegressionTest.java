package com.agentruntime.skill;

import android.app.Application;

import com.agentruntime.dsl.ActionSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SkillNestedTemplateRegressionTest {

    @Test
    public void resolvesPlaceholdersInsideObjectsNestedInArrays() throws Exception {
        JSONObject step = new JSONObject()
                .put("action", "wait_ms")
                .put("params", new JSONObject()
                        .put("ms", 0)
                        .put("metadata", new JSONObject()
                                .put("tags", new JSONArray()
                                        .put("review-${user}")
                                        .put(new JSONObject()
                                                .put("owner", "${user}")
                                                .put("notes", new JSONArray().put("for-${user}"))))));
        SkillManifest manifest = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.nested_template")
                .put("args", new JSONArray().put("user"))
                .put("steps", new JSONArray().put(step)));

        List<ActionSpec> actions = new SkillEngine(RuntimeEnvironment.getApplication())
                .resolveManifest(manifest, new JSONObject().put("user", "Mira"));

        JSONArray tags = actions.get(0).params.getJSONObject("metadata").getJSONArray("tags");
        assertEquals("review-Mira", tags.getString(0));
        JSONObject nested = tags.getJSONObject(1);
        assertEquals("Mira", nested.getString("owner"));
        assertEquals("for-Mira", nested.getJSONArray("notes").getString(0));
    }

    @Test
    public void rejectsUndeclaredPlaceholderInsideNestedArrayObject() throws Exception {
        JSONObject step = new JSONObject()
                .put("action", "wait_ms")
                .put("params", new JSONObject()
                        .put("ms", 0)
                        .put("metadata", new JSONObject()
                                .put("tags", new JSONArray().put(new JSONObject()
                                        .put("owner", "${unlisted}")))));
        SkillManifest manifest = SkillManifest.fromJson(new JSONObject()
                .put("skill_id", "test.nested_undeclared")
                .put("args", new JSONArray().put("user"))
                .put("steps", new JSONArray().put(step)));

        try {
            new SkillEngine(RuntimeEnvironment.getApplication()).resolveManifest(manifest,
                    new JSONObject().put("user", "Mira").put("unlisted", "synthetic value"));
            fail("undeclared nested placeholder must fail");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("undeclared"));
        }
    }
}
