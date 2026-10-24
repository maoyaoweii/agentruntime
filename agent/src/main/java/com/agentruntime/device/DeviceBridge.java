package com.agentruntime.device;

import com.agentruntime.executor.ExecResult;
import com.agentruntime.state.UiNode;

import org.json.JSONObject;

/**
 * DeviceBridge：对上提供统一动作能力，对下探测当前设备可用接口。
 * 调度依据为动作类别、当前授权、API 能力与风险，不为提高成功率静默提权。
 */
public interface DeviceBridge {

    String name();

    boolean available();

    JSONObject capabilityProbe();

    ExecResult performClick(UiNode target, long timeoutMs);

    ExecResult performInput(UiNode target, String text, long timeoutMs);

    ExecResult performScroll(UiNode target, String direction, long timeoutMs);

    ExecResult performGlobalBack();

    ExecResult performLaunchApp(String packageName, long timeoutMs);
}
