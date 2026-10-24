package com.agentruntime.dsl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Task DSL：表达“要完成什么、可以做什么、如何知道完成”。
 * 设备端在执行前必须完成 Schema / 白名单 / 权限 / 风险校验。
 */
public final class TaskSpec {

    public static final String TASK_VERSION = "1.0";

    public static final class TaskStep {
        public final String skillId;
        public final JSONObject args;
        public final String confirmation;

        public TaskStep(String skillId, JSONObject args, String confirmation) throws JSONException {
            if (skillId == null || skillId.isEmpty()) throw new JSONException("step.skill required");
            this.skillId = skillId;
            this.args = args != null ? args : new JSONObject();
            this.confirmation = confirmation;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("skill", skillId);
                o.put("args", args);
                if (confirmation != null) o.put("confirmation", confirmation);
            } catch (JSONException ignored) {
            }
            return o;
        }
    }

    public static final class Limits {
        public final int maxSteps;
        public final int maxReplans;
        public final long timeoutSeconds;

        public Limits(int maxSteps, int maxReplans, long timeoutSeconds) {
            this.maxSteps = maxSteps > 0 ? maxSteps : 24;
            this.maxReplans = Math.max(0, maxReplans);
            this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 180;
        }

        public static Limits defaults() {
            return new Limits(24, 2, 180);
        }
    }

    public final String taskId;
    public final String version;
    public final String goal;
    public final List<String> allowedApps;
    public final List<String> preconditions;
    public final List<TaskStep> steps;
    public final List<String> postconditions;
    public final Limits limits;

    public TaskSpec(String taskId, String version, String goal,
                    List<String> allowedApps, List<String> preconditions,
                    List<TaskStep> steps, List<String> postconditions, Limits limits) throws JSONException {
        if (taskId == null || taskId.isEmpty()) throw new JSONException("task_id required");
        this.taskId = taskId;
        this.version = version != null ? version : TASK_VERSION;
        this.goal = goal != null ? goal : "";
        this.allowedApps = allowedApps != null ? allowedApps : new ArrayList<>();
        this.preconditions = preconditions != null ? preconditions : new ArrayList<>();
        this.steps = steps != null ? steps : new ArrayList<>();
        this.postconditions = postconditions != null ? postconditions : new ArrayList<>();
        this.limits = limits != null ? limits : Limits.defaults();
    }

    public List<TaskStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("task_version", version);
            o.put("task_id", taskId);
            o.put("goal", goal);
            o.put("allowed_apps", new JSONArray(allowedApps));
            o.put("preconditions", new JSONArray(preconditions));
            JSONArray st = new JSONArray();
            for (TaskStep s : steps) st.put(s.toJson());
            o.put("steps", st);
            o.put("postconditions", new JSONArray(postconditions));
            JSONObject lim = new JSONObject();
            lim.put("max_steps", limits.maxSteps);
            lim.put("max_replans", limits.maxReplans);
            lim.put("timeout_seconds", limits.timeoutSeconds);
            o.put("limits", lim);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
