"""追加 V1.2 UI 产品化章节到 PROJECT_CHANGELOG.md。"""
import io
import os

PATH = r'E:/agentruntime/docs/PROJECT_CHANGELOG.md'

SECTION = """

---

## [任务 8] 2026-10-23 - V1.2 界面产品化：四大界面 / 悬浮球 / 四种唤起通道

> 触发点：用户反馈「软件太小了，用起来烧心」。
> 上一轮把引擎做厚了，但界面还是 V1.0 那个「一个输入框 + 五个按钮 + 一片日志」的样子——
> 用户既不知道无障碍开没开，也不知道任务跑到哪一步，更没法在别的 App 里随手唤起。

### 本次目标

- 把「引擎外壳」做成可日常使用的 App：主页 / 控制台 / 设置 / 技能库
- 补齐悬浮球与全局快捷面板：在其他 App 前台时直接下达任务
- 补齐四种快速唤起通道：悬浮球、QS 磁贴、桌面长按快捷方式、音量键长按
- 建立实时任务状态流，让执行过程可见

### 修改前状态

- 只有 `MainActivity`（LinearLayout + 4 按钮 + 日志 TextView）与 `TaskExecActivity`（代码建 TextView）
- 无主题、无配色、无图标、无卡片；`application` 用的是系统默认 `Theme.Material.Light`
- `AgentRuntime.runTask()` 同步阻塞，界面只能在任务结束后一次性打印结果
- 无通知渠道、无前台服务、无悬浮窗、无快捷方式

### 修改记录

#### 修改 1：视觉体系

**文件：** `res/values/colors.xml`（新）、`res/values/styles.xml`（新）、16 个 drawable（新）、
`res/drawable/ic_launcher.xml` 等 7 个矢量图标（新）
**修改内容：** 建立「靛蓝主色 + 浅灰底 + 白卡」的配色；定义 T.*（文字层级）、Btn.*（按钮）、
Card、Input 四组样式；16 个圆角/徽章/控制台/悬浮面板 drawable；矢量图标含应用图标。
**修改原因：** 默认 Material 外观让工具显得像半成品，也缺乏状态表达能力（成功/警告/失败无法区分）。
**预期效果：** 所有界面共用一套色值与圆角，状态用徽章颜色区分。

#### 修改 2：任务状态总线

**文件：** `runtime/TaskBus.java`（新）、`runtime/AgentRuntime.java`（改）、`ui/TaskRunner.java`（改）
**修改内容：** AgentRuntime 新增 `Listener`（onTaskStart / onStepStart / onStepEnd / onReplan / onFinish）；
`runTask(task, auto, replan)` 保留为无监听器重载，新增带 Listener 的重载并在内部包一层
「start → doRun → finish」；TaskBus 负责转日志、缓存最近 300 条、统一切主线程分发。
TaskRunner 是唯一执行入口，在其中统一挂总线，任何入口（UI/悬浮窗/磁贴/adb）的执行都能被界面看到。
**修改原因：** 没有事件流，界面只能等结果，用户看不到进展——这是「烧心」的核心原因之一。
**预期效果：** 执行中可显示「第 2/4 步 · notes.create_note」与每步耗时。

#### 修改 3：主页与控制台

**文件：** `ui/MainActivity.java`（重写）、`res/layout/activity_main.xml`（重写）、
`ui/ConsoleActivity.java`（新）、`res/layout/activity_console.xml`（新）
**修改内容：** 主页五张卡——运行状态（无障碍/截图/悬浮窗/本地模型/云端规划五枚徽章）、
下达任务（输入框 + 自动确认开关 + 执行/控制台 + 三个快捷指令）、最近任务（实时日志尾部）、
功能入口（技能库/悬浮球/设置）、版本页脚。控制台含进度条、当前步骤、带颜色的实时日志、
停止与再执行。
**修改原因：** 主页要回答「能不能用、现在什么状态、上次跑成什么样」；控制台要回答「卡在哪一步」。

#### 修改 4：设置页与技能库

**文件：** `ui/SettingsActivity.java`（新）、`ui/SkillsActivity.java`（新）、
`ui/UiKit.java`（新）、`res/layout/activity_settings.xml`、`activity_skills.xml`、`item_skill.xml`（新）
**修改内容：** 设置页四张卡——权限（无障碍/悬浮窗/通知，带状态徽章与跳转）、
云端模型（三项输入 + 保存 + 连通性自检）、本地模型（后端单选 + 端点 + 双模型名 + 保存 + 能力自检）、
环境快照（8 行结构化展示）、数据（审计查看 / 清理）。
技能库从 `assets/skills` 读出 19 个技能，逐条显示风险等级、步骤数、参数、后置断言，无参技能可一键执行。
**修改原因：** 「配了模型能不能用」必须当场可验，不能靠跑任务试错。

#### 修改 5：悬浮球与全局面板

**文件：** `overlay/OverlayService.java`（新）、`res/layout/overlay_bubble.xml`、`overlay_panel.xml`（新）、
`notify/AgentNotifier.java`（新）
**修改内容：** 前台服务承载 `TYPE_APPLICATION_OVERLAY` 悬浮球：可拖动、松手贴边吸附、
点击展开面板；面板支持输入目标、三个快捷指令、实时进度、执行。球的角标显示当前步骤号。
通知分两条渠道（状态 / 结果），常驻通知带「控制台」动作。
**修改原因：** Agent 要操作的 App 在前台，用户不可能先切回 Agent 再下指令——一执行就会被切走。
**关键技术约束：** 必须前台服务否则被回收；Android 14 要求 `foregroundServiceType`，这里用 `specialUse`；
球形窗口加 `FLAG_NOT_FOCUSABLE` 不抢焦点，面板窗口去掉该标志以支持输入法。

#### 修改 6：四种唤起通道

**文件：** `quick/AgentTileService.java`（新）、`res/xml/shortcuts.xml`（新）、
`service/AgentAccessibilityService.java`（改）、`res/xml/accessibility_service_config.xml`（改）
**修改内容：**
1. **悬浮球**：常驻屏幕边缘，一键展开面板
2. **QS 磁贴**：下拉快捷设置一键进控制台，副标题显示执行进度
3. **桌面长按**：`shortcuts.xml` 三个静态快捷方式（控制台 / 观察环境 / 设置）
4. **音量键长按**：无障碍 `canRequestFilterKeyEvents` + `onKeyEvent`，**长按 800ms 才消费**，短按放行
**修改原因：** 用户明确要求「快速唤起的快捷键」。
**预期效果：** 从任意界面 1~2 步内发起任务。

### 遇到的问题

#### 问题 39：AAPT 把样式名里的点号当成继承
**现象：** `error: resource style/T (aka ...:style/T) not found`，一次性报 6 处，都指向 `@style/T.Section`。
**可能原因：** AAPT 语义中 `T.Title` 表示「T 的子样式」，父样式 T 必须存在。
**解决方案：** 补两个空父样式 `<style name="T" />` 与 `<style name="Btn" />`。
**结果：** 已解决

#### 问题 40：MainActivity 缺 OverlayService 的 import
**现象：** javac 报「找不到符号」6 处，集中在 `OverlayService.isRunning()` 与 `toggleOverlay()`。
**解决方案：** 补 `import com.agentruntime.overlay.OverlayService;`
**结果：** 已解决

#### 问题 41：APK 核验脚本误判 foregroundServiceType
**现象：** 在 AXML 二进制里搜字符串 `specialUse` 找不到，报 MISSING，但实际已配置。
**可能原因：** `foregroundServiceType` 在 AXML 里存的是整型枚举位，不是字符串。
**解决方案：** 改为检查属性名 `foregroundServiceType` 是否存在；权限 `FOREGROUND_SERVICE_SPECIAL_USE` 另行校验。
**结果：** 已解决

### 最终修改结果

**本次完成：**
- 4 个完整界面 + 1 个悬浮球 + 1 个全局面板 + 1 个 QS 磁贴 + 3 个静态快捷方式
- 新增 Java 文件 9 个、布局文件 6 个、drawable 23 个、values 2 个、xml 1 个
- 动作 26 种、技能 19 个不变（本轮只做界面与唤起，不动引擎语义）
- 单测 67 项保持全绿（无回归）
- APK 160.2KB → **199.6KB**；核验 29/29 关键类、13 项 Manifest、资源 32 条、10/10 新动作

**涉及文件：** 见修改 1-6，共 9 个新增 Java、6 个布局、23 个 drawable、3 个 values/xml、4 个改动文件

**验证情况：**
- 编译：两模块通过
- 单测：67/67 通过（无回归）
- APK 核验：PASS（29 类 / 13 项 Manifest / 19 技能 / 10 动作）
- **界面外观与交互：完全未验证**（无真机、无模拟器，无法渲染）

### 当前状态

`界面代码完成，编译与资源链接通过；外观、悬浮窗、磁贴、快捷键均未在设备验证`

### 遗留问题

- 界面无任何真机渲染证据，配色/间距/文字截断都只经过代码审查
- 悬浮窗与输入法交互（弹出键盘时面板是否被顶起）未验证
- QS 磁贴需用户手动添加到快捷设置面板才可见
- 音量键长按在部分 ROM 上可能被系统抢占；未做开关，当前始终启用
- 深色模式未适配（固定浅色主题）
- 未做多语言（仅中文）
"""

with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

if '[任务 8]' in content:
    print('SKIP: 已存在')
else:
    with io.open(PATH, 'a', encoding='utf-8') as f:
        f.write(SECTION)
    print('APPENDED', len(SECTION))

with io.open(PATH, 'r', encoding='utf-8') as f:
    print('总行数', len(f.readlines()))
