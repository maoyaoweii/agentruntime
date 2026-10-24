package com.agentruntime.intent;

import android.content.Context;

import com.agentruntime.dsl.DslParser;
import com.agentruntime.dsl.TaskSpec;
import com.agentruntime.planner.LocalRulePlanner;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 内置意图规划器：把一句话落到内置技能上，不联网、不走模型推理服务。
 *
 * <p>它只处理「一句话 = 一个技能调用」这类目标。需要多步组合、需要复杂参数提取的目标
 * 一律返回 null，交给上层更强的通道——<strong>宁可交给能做的，也不要用错误的参数硬跑</strong>。
 *
 * <p>之所以值得单独一层：这类目标占日常使用的绝大多数（"截个图"、"看看环境"、"打开时钟"），
 * 用规则命中不了换说法，用大模型又太慢太贵，本地小模型刚好。
 */
public final class IntentPlanner {

    /** 意图 → 无参技能。这些可以直接执行。 */
    private static final Map<String, String> PARAM_FREE = new HashMap<>();

    static {
        PARAM_FREE.put("observe", "sys.observe");
        PARAM_FREE.put("screenshot", "sys.screenshot");
        PARAM_FREE.put("open_clock", "sys.open_clock");
        PARAM_FREE.put("smoke", "sys.tool_smoke");
        PARAM_FREE.put("coord_smoke", "sys.coordinate_smoke");
    }

    private final IntentModel model;

    public IntentPlanner(IntentModel model) {
        this.model = model;
    }

    /** 从 assets 加载内置模型；失败返回 null（功能降级，不影响其他通道）。 */
    public static IntentPlanner create(Context context) {
        if (context == null) return null;
        try (InputStream in = context.getAssets().open("models/intent_nb.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return new IntentPlanner(IntentModel.fromJson(json));
        } catch (Throwable t) {
            return null;
        }
    }

    public IntentModel model() {
        return model;
    }

    /**
     * 尝试把目标直接变成任务。
     *
     * @return 命中且参数齐备时返回任务；否则 null（调用方继续往上升级）
     */
    public TaskSpec plan(String goal) throws JSONException {
        if (model == null || goal == null || goal.trim().isEmpty()) return null;
        IntentModel.Prediction p = model.predict(goal);
        if (!p.usable) return null;

        String skill = PARAM_FREE.get(p.intent);
        if (skill != null) {
            return buildTask(goal, p.intent, skill, new JSONObject());
        }

        // 浏览类需要 URL，能从原句里抽出来才做
        if ("browse".equals(p.intent)) {
            String url = LocalRulePlanner.extractUrl(goal);
            if (url != null) {
                JSONObject args = new JSONObject();
                args.put("url", url);
                args.put("max_chars", "2000");
                return buildTask(goal, p.intent, "web.open_and_read", args);
            }
        }

        // 其余意图需要参数提取（标题、对象、消息文本），当前实现不猜
        return null;
    }

    /** 预测但不论是否可执行，用于日志与诊断。 */
    public IntentModel.Prediction classify(String goal) {
        return model == null ? null : model.predict(goal);
    }

    private TaskSpec buildTask(String goal, String intent, String skill, JSONObject args)
            throws JSONException {
        JSONObject task = new JSONObject();
        task.put("task_version", "1.0");
        task.put("task_id", "intent_" + intent);
        task.put("goal", goal);
        org.json.JSONArray allowedApps = new org.json.JSONArray();
        if ("sys.open_clock".equals(skill)) allowedApps.put("com.android.deskclock");
        else if ("sys.tool_smoke".equals(skill)) {
            allowedApps.put("org.fossify.notes");
            allowedApps.put("com.android.deskclock");
        }
        task.put("allowed_apps", allowedApps);
        task.put("preconditions", new org.json.JSONArray());

        org.json.JSONArray steps = new org.json.JSONArray();
        JSONObject step = new JSONObject();
        step.put("skill", skill);
        step.put("args", args);
        steps.put(step);
        task.put("steps", steps);

        task.put("postconditions", new org.json.JSONArray());

        JSONObject limits = new JSONObject();
        limits.put("max_steps", 16);
        limits.put("max_replans", 1);
        limits.put("timeout_seconds", 240);
        task.put("limits", limits);

        return DslParser.taskFromJson(task);
    }
}
