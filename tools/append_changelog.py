"""向 PROJECT_CHANGELOG.md 追加 V1.1 章节（原文件只追加，不改写）。"""
import io
import os

PATH = r'E:/agentruntime/docs/PROJECT_CHANGELOG.md'

SECTION = """

---

## [任务 7] 2026-10-23 - V1.1 能力增强：环境感知 / Computer Use / 浏览器 / 提示词 / 本地模型

> 说明：本条承接 V1.0 交付包（任务 1-6）。V1.0 交付的 `source/` 是扁平化归档，
> 无法直接构建；本次先把它还原成标准 Gradle 双模块工程，再在其上做增强。

### 本次目标

- 修复 V1.0 的三个硬伤：① 规划器不知道设备环境；② 只有无障碍节点一条操作通道；
  ③ 所谓「AI 规划」在基准测试中从未真正调用过模型（cloud_calls 全为 0）
- 补齐 Computer Use 式能力：截图、坐标手势、视觉定位
- 补齐浏览器控制能力：内置 WebView 读 DOM、选择器点击、外部浏览器打开
- 建立提示词约束框架：模板集中、注入防护、长度预算、版本化
- 接入本地小模型：视觉快速模型 + 决策模型，纳入 ModelRouter 分层路由

### 修改前状态

- `source/` 为扁平化目录（`agent_src/main/...`），`settings.gradle` 写 `include(":agent")`
  但目录名不匹配，且两个模块的 `build.gradle` 缺失 → **不可构建**
- 动作原语 16 种，技能 12 个，单测 17 项
- 云端 Planner 有 OpenAI 兼容实现，但基准测试实测 `cloud_calls = 0`，
  即全部走 L0 本地规则；「Agent 的智能」从未被验证
- 无环境感知、无截图、无坐标动作、无 DOM 读取、无本地模型

### 预计修改

- 还原工程结构并跑通基线（编译 + 17 项单测），作为后续一切改动的对照点
- 新增 `env/` `prompt/` `local/` `vision/` `web/` `policy/` 六个包
- 扩展 Action DSL 至 26 种、技能至 19 个
- ModelRouter 插入 L1 本地决策层，形成「规则 → 本地 → 云端」成本递增路由

### 修改记录

#### 修改 1：还原可构建工程

**文件：** `settings.gradle`、`build.gradle`、`gradle.properties`、`local.properties`、
`agent/build.gradle`、`agenttestapp/build.gradle`、`build.sh`、目录结构
**修改内容：** 按 `include(":agent")` 的声明把 `agent_src/main` 还原为 `agent/src/main`；
补齐两个模块的构建脚本（AGP 8.6.1 / compileSdk 34 / minSdk 26 / Java 17）；
`gradle.properties` 保留 `android.overridePathCheck=true`；`build.sh` 固定 JDK 21 并从
环境变量解析代理注入 `GRADLE_OPTS`。
**修改原因：** V1.0 的 source 只是给人工阅读的归档，无法复现构建，更无法在其上迭代。
**预期效果：** `assembleDebug` 通过，17 项单测全绿，作为对照基线。

#### 修改 2：环境感知层

**文件：** `env/EnvironmentSnapshot.java`（新增）、`env/EnvironmentProbe.java`（新增）
**修改内容：** 采集 16 项事实（sdk/release/厂商型号/屏幕宽高与 dpi/fontScale/旋转/语言/
深色模式/电量与充电/网络/亮度/无障碍连接/截图能力）；`EnvironmentSnapshot` 为纯 Java
不可变对象，提供 `toJson()` 与 `toPromptBlock()`；Probe 带 3 秒缓存，采集失败降级为
known-unknown 而不抛异常。
**修改原因：** 屏幕尺寸、语言、字体缩放直接决定动作是否成立，环境不进上下文就只能靠猜。
**预期效果：** 提示词里能看到当前设备实况，审计里能回放当时环境。

#### 修改 3：提示词框架

**文件：** `prompt/PromptLibrary.java`、`prompt/PromptBuilder.java`、`prompt/PromptGuard.java`（均新增）
**修改内容：** 三套模板（planner / grounding / decider），每套含系统约束与输出结构；
Builder 负责「环境 + 技能清单 + 不可信屏幕数据 + 目标」组装与预算裁剪；
Guard 负责注入剥离（12 条中英文正则）、限长、可疑行标记、`<untrusted_screen_data>` 包裹。
**修改原因：** 原实现把提示词写在 `CloudPlannerAdapter` 里做字符串拼接，无法审计、
无法统一改约束，也无法单测。
**预期效果：** 约束可测试、可版本化（`PROMPT_VERSION = "1.1.0"`）；屏幕文本中的伪指令被剥离。

#### 修改 4：Computer Use 式视觉与坐标

**文件：** `vision/ScreenFrame.java`、`vision/CoordinateMapper.java`、`vision/ScreenCapturer.java`、
`vision/GestureBridge.java`、`vision/VisualGrounder.java`（均新增）
**修改内容：** `takeScreenshot` 截图（API 30+，无需 MediaProjection 授权）并缩放编码；
`dispatchGesture` 注入 tap / swipe / long press；坐标越界直接拒绝而非夹取；
`CoordinateMapper` 宽高分别映射；`VisualGrounder` 三级降级（本地 VLM → 节点中心 → 失败）。
**修改原因：** 自绘控件、Canvas 页面、WebView 里节点树经常残缺，只有节点通道等于放弃这些场景。
**预期效果：** 新增 `tap_xy` / `swipe_xy` / `long_press_xy` / `screenshot` / `visual_tap` 五个动作。

#### 修改 5：浏览器控制

**文件：** `web/BrowserBridge.java`、`web/BrowserActivity.java`、`web/BrowserController.java`（均新增）
**修改内容：** 内置受控 WebView（禁 file 访问、禁地理位置、不注册 `addJavascriptInterface`）；
只暴露四个参数化 JS 模板（readText / clickSelector / readSelector / scrollPage）；
外部浏览器走 `ACTION_VIEW`，能力降级为无障碍树读文本。
**修改原因：** 直接控制第三方浏览器读不到 DOM 也执行不了脚本，能力边界必须显式化。
**预期效果：** 新增 `navigate` / `read_page` / `page_click` / `page_find` 四个动作，
以及 `web.open_and_read` / `web.click_selector` / `web.find_text` 三个技能。

**刻意不做 `evalJs(任意脚本)`：** 那等于把「执行任意代码」的口子重新打开，
与本项目「设备操作只能通过白名单原语」的核心约束直接冲突。

#### 修改 6：本地小模型接入

**文件：** `local/LocalModelConfig.java`、`LocalModelRequest.java`、`LocalModelReply.java`、
`LocalModelProvider.java`、`HttpLocalModelProvider.java`、`OnnxLocalModelProvider.java`、
`LocalModelRegistry.java`、`planner/LocalPlanAdapter.java`（均新增）；`planner/ModelRouter.java`（改写）
**修改内容：** 三后端（none / http / onnx）；HTTP 走 OpenAI 兼容 `/v1/chat/completions`，
支持多模态 base64；ONNX 仅做 runtime 反射探测，明确返回 `ORT_RUNTIME_MISSING`；
Registry 负责配置持久化与自检；`LocalPlanAdapter` 与云端适配器完全对称。
ModelRouter 路由改为 `L0 规则 → L1 本地决策 → L3 云端`，**本地失败回退云端而非直接失败**。
**修改原因：** 需求要求「内部搭配视觉快速模型与决策模型」；同时 V1.0 的实测云调用为 0，
路由层需要一条真正会被走到的模型路径。
**预期效果：** 本地模型可用时优先本地（隐私 + 延迟 + 成本），不可用或输出不合规时自动上云。

#### 修改 7：安全策略收口

**文件：** `policy/SafetyPolicy.java`（新增）；`dsl/DslParser.java`、`web/BrowserBridge.java`（改为委托）
**修改内容：** URL 与 CSS 选择器白名单从两处实现收敛为一处；DSL 层与执行层共用同一判断。
**修改原因：** 两份规则一漂移就会出现「解析放过、执行拦下」或反过来的漏洞。
**预期效果：** 规则只有一处，改一次全生效；单测可用一致性断言钉死。

#### 修改 8：执行器与装配

**文件：** `executor/ExtendedCapabilities.java`（新增）、`executor/ActionExecutor.java`、
`AgentApp.java`、`ui/TaskExecActivity.java`、`AndroidManifest.xml`
**修改内容：** 新增能力容器（可空注入，缺能力时明确拒绝而非静默降级）；
ActionExecutor 增加 10 个动作分支与 5 个辅助方法；AgentApp 调整装配顺序
（环境与本地模型需先于路由器）；TaskExecActivity 增加 `local_*` 配置、
`local_info` / `env_info` / `capability_info` 三个自检入口；Manifest 注册 BrowserActivity
与 ACCESS_NETWORK_STATE 权限。

### 遇到的问题

#### 问题 33：中文路径导致单元测试 ClassNotFoundException（阻塞级）
**现象：** `assembleDebug` 正常；`testDebugUnitTest` 报 `ClassNotFoundException: com.agentruntime.AgentCoreTest`，
但 `build/intermediates/javac/debugUnitTest/.../AgentCoreTest.class` 确实已生成。
**可能原因：** fork 出的测试 JVM 在解析含中文的 classpath 时编码不一致。
**排查过程：** ① 复制到 `E:\\agentrt_probe`（纯英文）→ 测试立即通过，排除代码问题；
② 用 `mklink /J E:\\agent_rt <中文路径>` 做英文别名 → 仍失败，Gradle 报告里的路径还原成中文，
说明 Java 的 canonical path 解析会穿透 junction。
**解决方案：** 构建与测试改在 `E:\\agentruntime` 进行，源码同步回中文 workspace。
**结果：** 已解决（67 项单测可运行）

#### 问题 34：GRADLE_OPTS 中的 `|` 触发 xargs 断言
**现象：** 加入 `-Dhttp.nonProxyHosts=localhost|127.0.0.1` 后，输出
`assertion "bc_ctl.arg_max >= LINE_MAX" failed: file "xargs.c"`，随后插件解析失败。
**可能原因：** Gradle 启动脚本用 xargs 处理 `GRADLE_OPTS`，竖线被当作分隔符。
**解决方案：** 去掉含 `|` 的参数，只保留 `proxyHost/proxyPort`。
**结果：** 已解决

#### 问题 35：首次失败结果被 Gradle 模块缓存
**现象：** 配好代理后仍报插件找不到，10 秒内失败且无网络错误。
**可能原因：** 首次无代理时的 "missing module" 结果被缓存。
**解决方案：** `--refresh-dependencies` 强制重查。
**结果：** 已解决

#### 问题 36：`com.sun.net.httpserver` 不在 Android 编译 classpath
**现象：** 单测 import 后 javac 报「程序包不存在」。
**解决方案：** 改用 `java.net.ServerSocket` 手写最小 HTTP 服务。
**结果：** 已解决（副作用是更贴近 Android 真实约束）

#### 问题 37：CSS 选择器白名单误伤合法选择器
**现象：** 单测 `selectorAcceptsCommonCssForms` 失败，`div.result > span` 被拒。
**可能原因：** 初版为防注入把 `>` 一并禁掉，但 `>` 是合法子选择器符号。
**解决方案：** 只禁引号、分号、反引号、`<`；放宽后仍能拒绝 `#btn');alert(1);//`。
**结果：** 已解决

#### 问题 38：`web.click_selector` 技能初版用 `tap_xy` 占位
**现象：** 写技能 JSON 时发现用它串接会让真机真的点击左上角 (0,0)。
**解决方案：** 新增 `page_click` / `page_find` 两个动作，技能改为真实调用。
**结果：** 已解决

### 最终修改结果

**本次完成：**
- 工程可构建（AGP 8.6.1 + Gradle 8.8 + JDK 21），两模块 `assembleDebug` 通过
- 动作原语 16 → 26（+10），技能 12 → 19（+7）
- 主源码 27 文件 / 3589 行 → 50 文件 / 7084 行（+23 文件 / +3495 行）
- 单测 17 → 67（+50，全部通过）
- APK 98.8KB → 160.2KB；产物核验：20/20 关键类、10/10 新动作、19 技能、Manifest 三项齐全

**涉及文件：** 见上述修改 1-8，共 23 个新增 Java 文件 + 9 个改动文件 + 7 个新增技能 JSON

**关键参数/配置变化：**
- 新增能力容器 `ExtendedCapabilities`（可空注入，缺能力明确拒绝）
- ModelRouter 路由顺序 `L0 → L1 本地 → L3 云端`，本地失败回退云端
- 提示词版本 `PROMPT_VERSION = "1.1.0"`，预算上限 12000 字符
- 截图默认最长边 1024 / JPEG 70；视觉置信度阈值 0.5

**验证情况：**
- 编译：两模块通过
- 单测：67/67 通过（`AgentCoreTest` 17 + `AgentV11Test` 50）
- APK 核验：`tools/verify_apk.py` PASS
- 本地模型协议层：用真实 `ServerSocket` 本地服务验证请求体、响应解析、超时、拒连
- **设备侧行为：全部未验证**（本机无真机、无模拟器）

### 当前状态

`代码完成，编译与单测通过；设备侧能力（截图/手势/WebView/环境采集/本地推理）未验证`

### 遗留问题

- 真机验证缺失（截图、手势、WebView、环境取值全部只有编译保证）
- ONNX 进程内推理仅有探测骨架，无 runtime、无权重、无 tokenizer
- 本地模型未接过真实模型，「能否稳定输出合规 Task DSL」无证据
- 提示词预算 12000 字符对 2K ctx 的小模型偏大，需按模型下调
- `LocalModelProvider` 无并发保护，多任务下发会串行阻塞
- 性能数据（截图耗时、模型延迟、手势成功率）一个都没有实测
"""

with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

if '[任务 7]' in content:
    print('SKIP: 任务 7 章节已存在')
else:
    with io.open(PATH, 'a', encoding='utf-8') as f:
        f.write(SECTION)
    print('APPENDED', len(SECTION), 'chars ->', os.path.basename(PATH))

with io.open(PATH, 'r', encoding='utf-8') as f:
    lines = f.readlines()
print('总行数', len(lines))
