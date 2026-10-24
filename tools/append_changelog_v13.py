"""追加 V1.3 章节。"""
import io

PATH = r'E:/agentruntime/docs/PROJECT_CHANGELOG.md'

SECTION = """

---

## [任务 9] 2026-10-23 - V1.3 横竖屏适配 / 内置模型 / 输出格式 / 底层工具 / 输入法

> 用户要求：适应竖屏横屏、内置模型提前装好、规定输出格式、准备底层调用工具、准备模拟键盘的输入输出能力。

### 本次目标

1. **横竖屏**：旋转后界面不丢状态，布局在横屏下重排，悬浮窗不跑位
2. **内置模型**：不再依赖用户自己部署推理服务，权重随 APK 安装，开箱可用
3. **输出格式**：模型输出统一走「抽取 → 修复 → 校验」，不再各处各写一套
4. **底层工具**：把音量/亮度/剪贴板/媒体键/振动/设置页/应用列表/受限文件读写收成一个桥
5. **输入法**：提供 InputConnection 层的文本注入，覆盖 WebView 与自绘控件

### 修改前状态

- 旋转会重建 Activity 并丢失输入；悬浮窗坐标在旋转后停在旧值，横屏会跑到屏幕外
- 「本地模型」只有 HTTP 与 ONNX 两个通道，都要用户自己部署；ONNX 仅有探测骨架
- JSON 解析在三个适配器里各写一份，用 `indexOf('{') + lastIndexOf('}')`，遇到多个对象会切错
- 无任何系统层调用（音量/亮度/剪贴板等），动作为 26 种
- 无输入法能力，`input_text` 对 WebView 内部输入框无效

### 修改记录

#### 修改 1：横竖屏适配

**文件：** `OverlayService.java`、`MainActivity.java`、`ConsoleActivity.java`、`SettingsActivity.java`、
`res/layout/card_*.xml`（4 个新）、`res/layout-land/activity_main.xml`（新）
**修改内容：**
- 悬浮窗服务实现 `onConfigurationChanged`：重算屏宽、钳制球坐标、重建面板；面板宽度加 420dp 上限
- 三个 Activity 实现 `onSaveInstanceState` 保存输入；`busy` 状态改从 `TaskBus.running()` 恢复
- ConsoleActivity 在旋转重建时**不重复下发任务**（靠 savedInstanceState 判断）
- 把主页四张卡抽成独立布局，竖屏纵排、横屏左右两栏复用同一批卡片，避免两份布局漂移
**修改原因：** 旋转重建会丢输入；悬浮窗坐标不刷新会跑到屏幕外。

#### 修改 2：内置意图模型

**文件：** `tools/train_intent_model.py`（新）、`assets/models/intent_nb.json`（新，17KB）、
`intent/IntentModel.java`（新）、`intent/IntentPlanner.java`（新）、`planner/ModelRouter.java`（改）
**修改内容：** 用纯 Python 训练多项式朴素贝叶斯（字符 uni/bi-gram，457 个 token，312 条样本，
留出验证 93.8%），导出稀疏权重 JSON；Java 端实现加载与推理；`IntentPlanner` 把高置信度意图
直接落成单技能任务；ModelRouter 新增 `L0_INTENT` 层，位置在规则之后、HTTP/云模型之前。
**修改原因：** 用户要求「内置模型提前安装好」。真跑 2B 视觉模型需要 1GB+ 权重与 runtime，
本环境无法验证；但一个 17KB 的意图模型可以真正随包安装、毫秒级响应、可被单测验证。
**能力边界：** 只做单句意图分类，不做推理链。需要参数提取的目标（写笔记、发消息）返回 null 交给上层。

#### 修改 3：输出格式规范

**文件：** `dsl/OutputFormat.java`（新）；`CloudPlannerAdapter`、`LocalPlanAdapter`、`VisualGrounder`（改）
**修改内容：** 括号计数 + 字符串感知的 JSON 抽取（能处理 markdown fence、前后废话、一次多个对象、
字符串内含花括号）；安全修复（去注释、去尾逗号、全角引号）；三个 schema 校验器。
三个适配器的解析统一到这里。
**修改原因：** 原实现 `indexOf('{') + lastIndexOf('}')` 在模型输出两个对象时会切出非法片段。
**修复原则：** 只做语法级修复，不补引号、不猜截断字段——把错误数据修成"看似合法的错误任务"比直接失败更危险。

#### 修改 4：系统底层工具

**文件：** `device/SystemBridge.java`（新）、`executor/ExtendedCapabilities.java`、`ActionExecutor.java`、
`dsl/ActionSpec.java`、`dsl/DslParser.java`
**修改内容：** 新增 11 个动作：`set_volume` `get_volume` `set_brightness` `clipboard_set` `clipboard_get`
`media_key` `vibrate` `open_settings_page` `list_apps` `read_file` `write_file`。
**权限边界：** 亮度需 `WRITE_SETTINGS`（未授权返回 NEED_PERMISSION 并给跳转）；
文件**只允许 app 私有目录**，且路径经 canonical path 校验防 `../` 穿越；不申请 MANAGE_EXTERNAL_STORAGE。

#### 修改 5：输入法注入通道

**文件：** `ime/AgentImeService.java`（新）、`res/xml/method.xml`（新）、`AndroidManifest.xml`
**修改内容：** 极简 `InputMethodService`，无键盘 UI（40dp 提示条），通过 `InputConnection`
提供 `commitText` / `getExtractedText` / `sendKeyEvent`；新增 `ime_commit` `ime_read` `ime_key` 三个动作。
**修改原因：** `ACTION_SET_TEXT` 对 WebView 内部输入框、游戏、Flutter/Compose 自绘控件无效，
而它们都认输入法——那是系统级通道。

#### 修改 6：技能与界面引导

**文件：** 4 个新技能 JSON（`sys.toolkit` / `sys.ime_input` / `sys.media_control` / `sys.clipboard_copy`）、
`activity_settings.xml`、`SettingsActivity.java`、`MainActivity.java`
**修改内容：** 设置页权限区新增「Agent 输入法」「修改系统设置」两行（状态徽章 + 一键跳转）；
主页新增「输入法注入」徽章；版本号与技能/动作计数改为运行时读取。

### 遇到的问题

#### 问题 42：`InputMethodService.onStartInput` 签名记错
**现象：** 编译报「方法不会覆盖或实现超类型的方法」，并连带报 InputConnection 无法转换为 EditorInfo。
**原因：** 正确签名是 `onStartInput(EditorInfo attribute, boolean restarting)`，
InputConnection 要通过 `getCurrentInputConnection()` 取，不能作为参数。
**解决：** 改签名并改用 getCurrentInputConnection()。
**结果：** 已解决

#### 问题 43：`IntentModel.tokenize` 可见性不足
**现象：** 单测编译失败「tokenize(String) 在 IntentModel 中不是公共的」。
**解决：** 改为 public（分词逻辑本身就是需要被验证的契约）。
**结果：** 已解决

#### 问题 44：重构后残留变量引用
**现象：** VisualGrounder 改用 OutputFormat 后，catch 分支仍在用已删除的 `json` 变量。
**解决：** 改用原始字符串 `raw`。
**结果：** 已解决

### 最终修改结果

**本次完成：**
- 动作原语 26 → **40**（+14：11 个系统工具 + 3 个输入法）
- 内置技能 19 → **23**（+4）
- 新增内置模型：17KB 意图分类器，留出验证 93.8%
- 单测 67 → **97**（+30，全绿）
- APK 199.6KB → **232.4KB**
- 新增 `layout-land` 横屏布局；四张主页卡片抽成可复用布局

**验证情况：**
- 编译：通过
- 单测：97/97（17 + 50 + 30）
- APK 核验：PASS（34/34 类、24/24 新动作、23 技能、38 条资源、17 项 Manifest）
- **内置模型的推理是真跑的**：单测直接读 assets 权重做预测，验证了 5 个意图 + 乱码拒绝
- **设备侧仍全部未验证**：横竖屏切换、悬浮窗旋转行为、输入法注入、系统工具调用

### 当前状态

`代码完成，模型与格式规范有单测证据；设备侧行为未验证`

### 遗留问题

- 内置意图模型只覆盖 12 个意图，需要参数的目标（写笔记/发消息/视觉点击）仍要上层规划
- 模型语料是手写的 312 条，泛化到口语长句时置信度会下降
- 输入法需要用户手动切换，切换后切回原生输入法的体验未优化
- 亮度调节依赖 WRITE_SETTINGS，未做"授权后自动重试"
- 横屏只做了主页；控制台/设置/技能库在横屏下仍是单列
- 系统工具全部未在设备验证（音量、振动、剪贴板、文件路径校验）
"""

with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

if '[任务 9]' in content:
    print('SKIP')
else:
    with io.open(PATH, 'a', encoding='utf-8') as f:
        f.write(SECTION)
    print('APPENDED', len(SECTION))

with io.open(PATH, 'r', encoding='utf-8') as f:
    print('总行数', len(f.readlines()))
