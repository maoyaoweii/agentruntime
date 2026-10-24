package com.agentruntime.executor;

import com.agentruntime.env.EnvironmentProbe;
import com.agentruntime.vision.GestureBridge;
import com.agentruntime.vision.ScreenCapturer;
import com.agentruntime.vision.VisualGrounder;
import com.agentruntime.web.BrowserController;

/**
 * V1.1 新增能力的容器。
 *
 * <p>单独抽一个容器，而不是把五个参数塞进 ActionExecutor 构造函数：
 * 这些能力都是可选的（缺无障碍服务就没有截图，缺模型就没有视觉），
 * 用可空容器表达"有则用、无则明确拒绝"，比构造重载更清楚。
 */
public final class ExtendedCapabilities {

    /** 坐标手势通道（tap/swipe/long press）。 */
    public final GestureBridge gestures;

    /** 截图通道。 */
    public final ScreenCapturer capturer;

    /** 混合视觉定位。 */
    public final VisualGrounder grounder;

    /** 浏览器控制。 */
    public final BrowserController browser;

    /** 环境采集。 */
    public final EnvironmentProbe envProbe;

    /** 系统底层调用（音量/亮度/剪贴板/媒体键/振动/设置页/应用列表/受限文件）。 */
    public final com.agentruntime.device.SystemBridge system;

    public ExtendedCapabilities(GestureBridge gestures, ScreenCapturer capturer,
                                VisualGrounder grounder, BrowserController browser,
                                EnvironmentProbe envProbe) {
        this(gestures, capturer, grounder, browser, envProbe, null);
    }

    public ExtendedCapabilities(GestureBridge gestures, ScreenCapturer capturer,
                                VisualGrounder grounder, BrowserController browser,
                                EnvironmentProbe envProbe,
                                com.agentruntime.device.SystemBridge system) {
        this.gestures = gestures;
        this.capturer = capturer;
        this.grounder = grounder;
        this.browser = browser;
        this.envProbe = envProbe;
        this.system = system;
    }

    public boolean visionReady() {
        return capturer != null && ScreenCapturer.supported();
    }

    public boolean gesturesReady() {
        return gestures != null && GestureBridge.supported();
    }
}
