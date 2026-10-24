# AgentRuntime V1.6：安卓实测接手结果

## 先看结论

已接手源码、建立 Linux 构建环境，并在**实际启动的 Android 11 / API 30 x86_64 虚拟机**上安装和运行修复后的 APK，不是用截图设计稿或 JVM 模拟代替设备实测。

**核心设备执行链路已验证，但项目还不能宣称“完整 AI 智能体全部跑通”。** 截图、滑动、长按、测试 App 中文消息发送可执行；浏览器页面读取仍未通过，视觉模型权重缺失，获准的一次云端健康检查返回 HTTP 404。

- 全量单元测试：**148 项，0 失败、0 错误、0 跳过**。见 [JUnit 原始结果](../test-results/junit/) 和 [汇总](../test-results/build/final-unit-summary.json)。
- 真正设备内 instrumentation：**7 项全部通过**，含 ONNX Runtime 1.18.0 原生初始化。见 [设备回执](../test-results/device/instrumentation.json)。
- Debug、Release 和测试 APK 均完成构建；交付的安装包是最新 Debug 测试版，**不是生产签名发布包**。

## 设备上确实跑了什么

| 项目 | 实测结果与证据 |
|---|---|
| 技能注册 | 从 22 修复到 23 个，40 种动作；[最终自检](../test-results/device/capability_final.json)。 |
| 无障碍截图 | `SHOT_OK`，实际生成 480×960 JPEG；[截图动作回执](../test-results/device/screenshot_final.json)。 |
| 手势 | 实际 `SWIPE_OK`、`LONG_PRESS_OK`，动作前后截图成功；[手势回执](../test-results/device/coordinate_final.json)。 |
| 中文文本发送 | 在独立 AgentTestApp 中打开 TestUser、输入并发送合成中文文本，界面断言通过；[回执](../test-results/device/chat_send_final.json)、[真实截图](../test-results/device/chat_message_final.png)。 |
| 文件选择与发送 | 测试 App 中选择 report.pdf，发送后的 UI 断言通过；[回执](../test-results/device/send_file.json)、[截图](../test-results/device/chat_file.png)。这不是向真实聊天服务上传文件的测试。 |
| 系统工具 | 音量读取、剪贴板写读、振动 API、应用枚举通过；[工具回执](../test-results/device/toolkit.json)。虚拟机不能证明物理振动。 |
| Wi-Fi 状态 | 读取测试 App 中的状态文字并完成断言；[回执](../test-results/device/read_wifi.json)。不是实际无线电开关测试。 |
| 发送确认边界 | `auto_confirm=false` 时返回 `RISK_REJECTED / no_confirmation_channel`，未发送测试标记；[回执](../test-results/device/send_blocked.json)。 |
| 文件安全与系统权限 | 私有文件写读、越界路径拒绝、音量设置后恢复、未授予亮度修改时返回 `NEED_PERMISSION`、剪贴板回环、越界手势拒绝、模型未安装状态判断：设备内 7 项测试全部通过。 |

测试消息只发生在包名 `com.agenttestapp` 的合成测试应用中，没有向真实联系人发消息。

## 这次修复了什么

1. **截图和手势的系统能力声明缺失。** 原版无障碍配置没有 `canTakeScreenshot`、`canPerformGestures`，出现“服务已连接却无法截图”。已补齐声明，并让能力自检读取系统实际授予的 capability，而不只检查服务是否存在。修复前的 [失败回执](../test-results/round1/device/screenshot.json) 已保留。
2. **第 23 个技能在加载时被误拒绝。** `${url}` 模板尚未替换就进入最终 URL 校验，导致 `web.open_and_read` 不在注册表。已改为替换后再执行原有 DSL 安全校验，没有放开 javascript/file URL；新增技能加载回归测试和安全加载日志。详见 [技能修复说明](SKILL_LOADING_FIX.md)。
3. **安卓 ICU 与 JVM 正则兼容性。** 新模板正则第一次在 JVM 测试通过，但安卓因未转义的右花括号启动崩溃；已修正并在设备重新验证启动、技能计数和截图。崩溃证据与超时回执保留在 [第二轮记录](../test-results/round2/) 和 [崩溃日志](../test-results/emulator/round2-crash.txt)，没有删除失败历史。
4. **高风险动作默认确认边界。** 调试入口只在 Debug 对外导出，Release 不导出；MainActivity、TaskExecActivity、悬浮窗不再默认自动确认；补充 WRITE_SETTINGS 声明但不绕过系统授权。[安全修复说明](SECURITY_FIXES.md)、[变体清单核验](../test-results/build/security-manifests.json)。Release 的这一项是清单核验，没有冒充生产包安装实测。
5. **可重复构建和验收。** 修复 Windows 固定路径，补全 APK 验收中的 ABI/模型资产失败判断；增加 Linux 模拟器启动/就绪脚本、设备回归、UI 取证及仅测试 APK 的 instrumentation。回归工具现在遇到应用崩溃或回执超时会立即停止，不再继续堆积无效用例。

## 不能算通过的部分

**浏览器仍是明确未通过项。** 本地合成网页在内置 WebView 的导航返回 `BROWSER_READY` 后，读取实际得到 `NO_PAGE_TEXT`；截图为空白，随后一次任务触发还超时。见 [回执](../test-results/device/web_local_final.json) 和 [空白画面](../test-results/device/web_local_final.png)。这说明“页面 ready”不能替代 DOM 读取成功。尚未充分定位到应用实现、WebView 生命周期或该精简镜像兼容性的哪一层，不作已确定根因的猜测；也没有为了让测试变绿而全局放开明文网络或削弱 URL 校验。

**本地模型不能生成。** ONNX Runtime 1.18.0 的原生初始化已在设备通过，但 SmolVLM 的 3 个模型权重文件没有随包提供，`generative_ready=false`。`local_info.available=true` 只能说明运行时类存在，不能证明视觉推理或决策闭环可用。

**云模型未打通。** 严格按授权仅从临时服务端进程发出 **1 次最小合成请求、0 次重试**，目标为 `https://api.inceptionlabs.ai/v1/chat/completions`，请求 mercury-2，返回 **HTTP 404**。只保存 [脱敏状态](../test-results/api/healthcheck.json)，未把密钥加入 APK、源码、持久环境配置或日志。没有把该结果冒充为“安卓已接入云模型”，也未再次请求或更换模型重试。

**测试前置状态和适配边界仍需改善。** `agent_test.open_chat` 假定测试 App 位于可搜索的首页，从已经打开的聊天页重复执行会等待搜索控件超时；重跑消息用例前显式重置测试 App 后已通过，首轮失败仍保留。坐标 smoke 使用固定像素，因此本轮手势成功只代表选定的 480×960 屏幕，不代表任意尺寸自适应。

**不包含真实手机与厂商 ROM 结论。** 当前宿主没有 `/dev/kvm`，采用软件模拟加速模式。测试系统是官方 AOSP ATD API 30 镜像，不能代表 Google Play 完整镜像、Android 12–15、厂商后台限制或真机性能。耗时不可当作真机性能指标。

## 怎么继续复跑

四大界面、横屏和悬浮球已完成实际导航取证，自动确认默认未勾选。[主界面](../test-results/device/main_verified.png)、[设置](../test-results/device/settings.png)、[技能库](../test-results/device/skills.png)、[控制台](../test-results/device/console.png)、[横屏](../test-results/device/main_landscape.png)、[悬浮球](../test-results/device/overlay_bubble.png) 均为安卓 `screencap` 的原始截图，不是重绘图。这里证明可导航和渲染，不等于每个设置项或每种横竖屏交互都已验收；[UI 回执](../test-results/device/ui_checks.json) 对这一点作了区分。

当前工程：`/workspace/agentruntime`。SDK：`/workspace/android-sdk`；Gradle 8.8：`/workspace/gradle-dist/gradle-8.8`。新机器需要自行准备这些依赖并调整 `local.properties`；交付包不包含几 GB 的 SDK/AVD 缓存。

**当前虚拟机已在运行，不要对它再次执行 `--create`。** 以下创建/启动步骤只适用于从零搭建；现有环境从 `--ready` 和安装步骤继续即可。

```bash
cd /workspace/agentruntime
bash build.sh :agent:assembleDebug :agenttestapp:assembleDebug :agent:assembleDebugAndroidTest :agent:testDebugUnitTest
bash tools/emulator_linux.sh --create
# 在独立终端启动，进程需要保持运行：
bash tools/emulator_linux.sh
# 另一终端等待系统就绪，并打开 ATD 默认关闭的真实像素绘制：
bash tools/emulator_linux.sh --ready
export PATH=/workspace/android-sdk/platform-tools:$PATH
adb install -r apk/agent-debug.apk
adb install -r apk/agenttestapp-debug.apk
adb shell wm size 480x960
python3 tools/device_regression.py permissions
python3 tools/device_regression.py smoke
python3 tools/device_regression.py chat
python3 tools/device_ui.py
```

`baseline` 负向截图用例只适合没有开启无障碍权限的干净设备；不要在已授权设备上期待相同失败。界面截图脚本不把“生成了截图文件”自动解释为每个交互都已通过。

底层设备测试可独立复跑：

```bash
adb install -r apk/agent-debug-androidTest.apk
adb shell am instrument -w com.agentruntime.agent.test/com.agentruntime.DeviceSmokeInstrumentation
adb exec-out run-as com.agentruntime.agent cat files/device_instrumentation.json
```

Instrumentation 或 `force-stop` 后无障碍服务可能需要重新启用。交接末次环境观察任务本身成功，但明确报告 `a11y_connected=false / can_capture=false`，见 [末次环境回执](../test-results/device/handover_ready.json)；这不能被解释成截图服务持续在线。自动重设 secure 开关没有即时恢复连接，继续测试截图或手势前须从系统无障碍设置页重新开关服务，必要时重启虚拟机，并重新核查状态。先前截图和手势通过的回执来自服务已连接时的独立执行。

先修复浏览器就绪/读页一致性及任务恢复，再补齐合法来源的模型权重并验证推理闭环，最后扩展到完整系统镜像和真机矩阵；这是下一阶段的明确边界，而不是本轮已完成事项。
