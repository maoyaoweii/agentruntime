package com.agentruntime;

import android.app.Application;

import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.env.EnvironmentProbe;
import com.agentruntime.executor.ActionExecutor;
import com.agentruntime.executor.ExtendedCapabilities;
import com.agentruntime.executor.RiskGate;
import com.agentruntime.local.LocalModelRegistry;
import com.agentruntime.planner.ModelRouter;
import com.agentruntime.runtime.AgentRuntime;
import com.agentruntime.skill.SkillEngine;
import com.agentruntime.state.StateEngine;
import com.agentruntime.system1.RuleBasedSystem1;
import com.agentruntime.system1.System1;
import com.agentruntime.vision.GestureBridge;
import com.agentruntime.vision.ScreenCapturer;
import com.agentruntime.vision.VisualGrounder;
import com.agentruntime.web.BrowserController;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 进程级组件装配。单实例持有核心模块，避免重复创建占用内存。
 */
public final class AgentApp extends Application {

    private static AgentApp instance;

    private StateEngine stateEngine;
    private AuditLogger auditLogger;
    private System1 system1;
    private SkillEngine skillEngine;
    private ModelRouter modelRouter;
    private RiskGate riskGate;
    private AccessibilityBridge accessibilityBridge;
    private IntentBridge intentBridge;
    private ActionExecutor actionExecutor;
    private AgentRuntime agentRuntime;

    /** System-1 当前是否跑在模型化决策器上（否则为规则基线）。 */
    private boolean system1IsModel;

    // --- V1.1 新增组件 ---
    private EnvironmentProbe envProbe;
    private LocalModelRegistry localModels;
    private ScreenCapturer screenCapturer;
    private GestureBridge gestureBridge;
    private VisualGrounder visualGrounder;
    private BrowserController browserController;

    // --- V1.3 新增组件 ---
    private com.agentruntime.device.SystemBridge systemBridge;
    private com.agentruntime.intent.IntentPlanner intentPlanner;

    /** 测试模式：高风险动作自动确认（仅测试环境使用，审计中显式记录）。 */
    private volatile boolean testModeAutoConfirm = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        stateEngine = new StateEngine();
        auditLogger = new AuditLogger(this);
        // System-1 / Jev-like：优先用模型化决策器，加载失败回退规则实现
        // （方案验收要求：候选输入/选择输出可回放；第一版规则可用，决策模型可替换）
        com.agentruntime.system1.JevDecider jev =
                com.agentruntime.system1.JevDecider.fromAssets(this);
        system1 = jev != null ? jev : new RuleBasedSystem1();
        system1IsModel = jev != null;
        skillEngine = new SkillEngine(this);
        riskGate = new RiskGate();
        accessibilityBridge = new AccessibilityBridge(stateEngine);
        intentBridge = new IntentBridge(this);
        actionExecutor = new ActionExecutor(this, stateEngine, accessibilityBridge, intentBridge, system1, riskGate, auditLogger);

        // V1.1：环境感知与本地模型要先于路由器装配（路由器依赖它们做分层规划）
        envProbe = new EnvironmentProbe(this);
        localModels = new LocalModelRegistry(this);
        // V1.3：内置意图模型随包安装，加载失败不影响其他通道
        intentPlanner = com.agentruntime.intent.IntentPlanner.create(this);

        modelRouter = new ModelRouter(this, skillEngine, auditLogger, localModels, envProbe, intentPlanner);
        agentRuntime = new AgentRuntime(stateEngine, skillEngine, actionExecutor, modelRouter, auditLogger);

        // V1.1：视觉通道 / 坐标手势 / 浏览器控制；V1.3：系统底层工具
        screenCapturer = new ScreenCapturer(null);
        gestureBridge = new GestureBridge(null);
        visualGrounder = new VisualGrounder(screenCapturer, () -> localModels.provider());
        browserController = new BrowserController(this);
        systemBridge = new com.agentruntime.device.SystemBridge(this);
        actionExecutor.attach(new ExtendedCapabilities(
                gestureBridge, screenCapturer, visualGrounder, browserController, envProbe, systemBridge));
    }

    public static AgentApp get() {
        return instance;
    }

    public StateEngine stateEngine() {
        return stateEngine;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public System1 system1() {
        return system1;
    }

    public SkillEngine skillEngine() {
        return skillEngine;
    }

    public ModelRouter modelRouter() {
        return modelRouter;
    }

    public RiskGate riskGate() {
        return riskGate;
    }

    public AccessibilityBridge accessibilityBridge() {
        return accessibilityBridge;
    }

    public IntentBridge intentBridge() {
        return intentBridge;
    }

    public ActionExecutor actionExecutor() {
        return actionExecutor;
    }

    public AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    public boolean isTestModeAutoConfirm() {
        return testModeAutoConfirm;
    }

    public void setTestModeAutoConfirm(boolean v) {
        testModeAutoConfirm = v;
    }

    public EnvironmentProbe envProbe() {
        return envProbe;
    }

    public LocalModelRegistry localModels() {
        return localModels;
    }

    public ScreenCapturer screenCapturer() {
        return screenCapturer;
    }

    public GestureBridge gestureBridge() {
        return gestureBridge;
    }

    public VisualGrounder visualGrounder() {
        return visualGrounder;
    }

    public BrowserController browserController() {
        return browserController;
    }

    public com.agentruntime.device.SystemBridge systemBridge() {
        return systemBridge;
    }

    public com.agentruntime.intent.IntentPlanner intentPlanner() {
        return intentPlanner;
    }

    /** System-1 是否使用模型化决策器（Jev-like）。 */
    public boolean system1IsModel() {
        return system1IsModel;
    }

    public JSONObject system1Info() {
        JSONObject o = new JSONObject();
        try {
            o.put("impl", system1IsModel ? "jev_like_model" : "rule_baseline");
            if (system1 instanceof com.agentruntime.system1.JevDecider) {
                o.put("model", ((com.agentruntime.system1.JevDecider) system1).describe());
            }
        } catch (JSONException ignored) {
        }
        return o;
    }
}
