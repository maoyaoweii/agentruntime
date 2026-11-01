/*
 * Inspired by Mika (Apache-2.0, https://github.com/wangzan101/mika)
 */
package com.agentruntime.goal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核对成功过的路径，供下次第 3 步重放。
 *
 * <p>只存"招数 + 目标 + 结果码"，不存屏幕文字：屏幕内容每次都要重新取证。
 */
public final class GoalPathStore {
    private static final String PREFS = "dsh_goal_paths";

    private final SharedPreferences preferences;
    private final Map<String, String> memory;

    private GoalPathStore(SharedPreferences preferences, Map<String, String> memory) {
        this.preferences = preferences;
        this.memory = memory;
    }

    public static GoalPathStore inMemory() {
        return new GoalPathStore(null, new ConcurrentHashMap<>());
    }

    public static GoalPathStore preferences(Context context) {
        return new GoalPathStore(context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE), null);
    }

    public List<GoalCard.PathStep> load(String key) {
        String raw = read(key);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            return GoalCard.pathFromJson(new JSONArray(raw));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void save(String key, List<GoalCard.PathStep> steps) {
        JSONArray array = new JSONArray();
        for (GoalCard.PathStep step : steps) {
            try {
                array.put(step.toJson());
            } catch (Exception ignored) {
            }
        }
        write(key, array.toString());
    }

    private String read(String key) {
        return memory != null ? memory.get(key) : preferences.getString(key, "");
    }

    private void write(String key, String value) {
        if (memory != null) memory.put(key, value);
        else preferences.edit().putString(key, value).apply();
    }

    /** 便于诊断：路径里到底记了哪些招。 */
    public static JSONArray describe(List<GoalCard.PathStep> steps) {
        JSONArray array = new JSONArray();
        for (GoalCard.PathStep step : steps) {
            try {
                array.put(new JSONObject().put("tactic", step.tactic).put("target", step.target));
            } catch (Exception ignored) {
            }
        }
        return array;
    }
}
