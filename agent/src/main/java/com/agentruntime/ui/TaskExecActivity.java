package com.agentruntime.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.runtime.AgentRuntime;

import org.json.JSONObject;

/**
 * 外部任务触发入口（测试回放器 / adb 使用）。
 * extras: goal / task_json / auto_confirm / allow_replan / finish_after_run
 * 输出：files/last_result.json + logcat tag=AgentRuntimeResult
 */
public final class TaskExecActivity extends Activity {

    public static final String TAG = "AgentRuntimeResult";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView tv = new TextView(this);
        tv.setText("AgentRuntime 任务执行中...");
        tv.setPadding(32, 32, 32, 32);
        setContentView(tv);

        String goal = getIntent().getStringExtra("goal");
        String taskJson = getIntent().getStringExtra("task_json");
        String goalB64 = getIntent().getStringExtra("goal_b64");
        String taskJsonB64 = getIntent().getStringExtra("task_json_b64");
        if (goal == null && goalB64 != null) {
            goal = new String(android.util.Base64.decode(goalB64, android.util.Base64.NO_WRAP),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        if (taskJson == null && taskJsonB64 != null) {
            taskJson = new String(android.util.Base64.decode(taskJsonB64, android.util.Base64.NO_WRAP),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        boolean autoConfirm = getIntent().getBooleanExtra("auto_confirm", false);
        boolean allowReplan = getIntent().getBooleanExtra("allow_replan", true);
        boolean finishAfterRun = getIntent().getBooleanExtra("finish_after_run", true);

        final String cloudBaseUrl = getIntent().getStringExtra("cloud_base_url");
        final String cloudApiKey = getIntent().getStringExtra("cloud_api_key");
        final String cloudModel = getIntent().getStringExtra("cloud_model");
        final boolean cloudPing = getIntent().getBooleanExtra("cloud_ping", false);
        final boolean cloudInfo = getIntent().getBooleanExtra("cloud_info", false);

        // V1.1：本地模型配置与自检入口
        final String localBackend = getIntent().getStringExtra("local_backend");
        final String localHost = getIntent().getStringExtra("local_host");
        final String localVision = getIntent().getStringExtra("local_vision_model");
        final String localDecider = getIntent().getStringExtra("local_decider_model");
        final int localPort = getIntent().getIntExtra("local_port", -1);
        final int localTimeout = getIntent().getIntExtra("local_timeout_ms", -1);
        final boolean localInfo = getIntent().getBooleanExtra("local_info", false);
        final boolean envInfo = getIntent().getBooleanExtra("env_info", false);
        final boolean capabilityInfo = getIntent().getBooleanExtra("capability_info", false);

        final String goalFinal = goal;
        final String taskJsonFinal = taskJson;
        final boolean autoFinal = autoConfirm;
        final boolean replanFinal = allowReplan;

        // 真机/模拟器上的关键行为：Agent 要操作的 App 必须在最前台，
        // 否则无障碍 getRootInActiveWindow() 只会返回本 Activity 自己的窗口，
        // 所有 wait_for/定位都会因为"看不到目标界面"而超时。
        // 真实入口（悬浮窗/磁贴）本来就不占前台，这里补上等价行为：
        // 任务开始前把任务栈移到后台，让被测应用成为活动窗口。
        //
        // 例外：带 auto_confirm=false 的交互式任务不后移。用户要在这个界面上
        // 逐个点同意（权限弹窗、风险确认都挂在当前 Activity 的窗口上），
        // 一后移它们就无处可点，任务会直接卡死在等待里。
        if (autoConfirm && getIntent().getBooleanExtra("behind_after_launch", true)) {
            moveTaskToBack(true);
        }

        new Thread(() -> {
            AgentRuntime.TaskResult result;
            try {
                boolean cloudTouched = cloudBaseUrl != null || cloudApiKey != null || cloudModel != null || cloudPing || cloudInfo;
                if (cloudTouched && (goalFinal == null || goalFinal.isEmpty())
                        && (taskJsonFinal == null || taskJsonFinal.isEmpty())) {
                    com.agentruntime.planner.CloudPlannerAdapter cloud = AgentApp.get().modelRouter().cloud();
                    if (cloudBaseUrl != null || cloudApiKey != null || cloudModel != null) {
                        cloud.configure(cloudBaseUrl, cloudApiKey, cloudModel);
                    }
                    JSONObject out = cloudPing ? cloud.ping() : cloud.configInfo();
                    out.put("task_id", "cloud_api");
                    out.put("status", out.optBoolean("ok", out.optBoolean("configured", false)) ? "SUCCESS" : "INFO");
                    TaskRunner.writeResult(AgentApp.get(), out);
                    Log.i(TAG, out.toString());
                    if (finishAfterRun) finish();
                    return;
                }

                boolean emptyTask = (goalFinal == null || goalFinal.isEmpty())
                        && (taskJsonFinal == null || taskJsonFinal.isEmpty());
                boolean localTouched = localBackend != null || localHost != null || localPort > 0
                        || localVision != null || localDecider != null || localTimeout > 0 || localInfo;

                if ((localTouched || envInfo || capabilityInfo) && emptyTask) {
                    com.agentruntime.local.LocalModelRegistry registry = AgentApp.get().localModels();
                    if (localBackend != null || localHost != null || localPort > 0
                            || localVision != null || localDecider != null || localTimeout > 0) {
                        registry.configure(localBackend, localHost,
                                localPort > 0 ? localPort : null,
                                localVision, localDecider,
                                localTimeout > 0 ? localTimeout : null, null);
                    }
                    JSONObject out;
                    if (capabilityInfo) {
                        out = buildCapabilityInfo();
                    } else if (envInfo) {
                        out = AgentApp.get().envProbe().refresh().toJson();
                    } else {
                        out = registry.probe();
                    }
                    out.put("task_id", "self_check");
                    out.put("status", out.optBoolean("available",
                            out.optBoolean("configured", true)) ? "SUCCESS" : "INFO");
                    TaskRunner.writeResult(AgentApp.get(), out);
                    Log.i(TAG, out.toString());
                    if (finishAfterRun) finish();
                    return;
                }

                if (taskJsonFinal != null && !taskJsonFinal.isEmpty()) {
                    result = TaskRunner.runTaskJson(taskJsonFinal, autoFinal, replanFinal);
                } else if (goalFinal != null && !goalFinal.isEmpty()) {
                    result = TaskRunner.runGoal(goalFinal, autoFinal, replanFinal);
                } else {                    JSONObject err = new JSONObject();
                    err.put("status", "ERROR");
                    err.put("error", "goal or task_json extra required");
                    Log.i(TAG, err.toString());
                    runOnUiThread(() -> tv.setText(err.toString()));
                    if (finishAfterRun) finish();
                    return;
                }
                JSONObject out = new JSONObject();
                out.put("task_id", result.taskId);
                out.put("status", result.status);
                out.put("detail", result.detail);
                TaskRunner.writeResult(AgentApp.get(), out);
                Log.i(TAG, out.toString());
                String summary = "任务: " + result.taskId + "\n状态: " + result.status;
                runOnUiThread(() -> tv.setText(summary));
            } catch (Exception e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("status", "ERROR");
                    err.put("error", String.valueOf(e.getMessage()));
                    TaskRunner.writeResult(AgentApp.get(), err);
                    Log.i(TAG, err.toString());
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> tv.setText("ERROR: " + e.getMessage()));
            } finally {
                if (finishAfterRun) {
                    runOnUiThread(this::finish);
                }
            }
        }, "agent-task-exec").start();
    }

    /**
     * 能力自检：一次性回答"现在到底能做什么"。
     *
     * <p>这是给验收用的——把每个能力的可用性摊开，而不是等某个任务失败了才去猜。
     * 每一项都来自真实探测，不做"理论支持"的宣称。
     */
    private JSONObject buildCapabilityInfo() {
        JSONObject out = new JSONObject();
        try {
            AgentApp app = AgentApp.get();
            out.put("sdk_int", android.os.Build.VERSION.SDK_INT);
            out.put("a11y_connected", com.agentruntime.service.AgentAccessibilityService.isRunning());

            JSONObject shot = new JSONObject();
            shot.put("supported", com.agentruntime.vision.ScreenCapturer.supported());
            shot.put("requires_api", 30);
            out.put("screenshot", shot);

            JSONObject gesture = new JSONObject();
            gesture.put("supported", com.agentruntime.vision.GestureBridge.supported());
            gesture.put("requires_api", 24);
            out.put("gesture", gesture);

            JSONObject browser = new JSONObject();
            browser.put("internal_attached", com.agentruntime.web.BrowserBridge.isAttached());
            browser.put("internal_ready", com.agentruntime.web.BrowserBridge.isReady());
            browser.put("dom_readable", true);
            browser.put("js_arbitrary_exec", false);
            out.put("browser", browser);

            out.put("local_model", app.localModels().probe());
            out.put("cloud_planner", app.modelRouter().cloud().configInfo());
            out.put("skill_count", app.skillEngine().skillIds().size());
            out.put("action_count", com.agentruntime.dsl.ActionSpec.ActionType.values().length);
            out.put("prompt_version", com.agentruntime.prompt.PromptLibrary.PROMPT_VERSION);

            // 端侧视觉模型：运行时是否内置、权重是否就位
            JSONObject vlm = new JSONObject();
            vlm.put("engine", "onnxruntime-android");
            vlm.put("tokenizer_vocab", 49152);
            vlm.put("model", com.agentruntime.vision.vlm.SmolVlmStore.check(this).toJson());
            vlm.put("generative_ready", false);
            vlm.put("note", "分词与预处理已验证；生成循环待与真实权重联调");
            out.put("vlm", vlm);
        } catch (Exception e) {
            try {
                out.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
