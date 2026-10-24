# -*- coding: utf-8 -*-
"""追加 V1.6（任务 12）章节到 docs/PROJECT_CHANGELOG.md。只追加，不改历史。"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, 'docs', 'PROJECT_CHANGELOG.md')

SECTION = r"""
## [任务 12] 2026-10-23 - V1.6 Codex 式界面 / 多厂商适配 / 本机模拟器

> 起因：用户要求「继续推进优化，界面做成 codex 那种形式，对各个厂商的手机做适配，
> 允许在本电脑上搞一个最小的安卓模拟器进行测试」。

### 本次目标

- 主页从「四块摘要卡」改成 Codex 那种会话流水
- 厂商 ROM 适配：识别 + 把用户送到该授权的页面
- 在本机搭最小模拟器，把之前一直「未验证」的界面行为真正跑一遍

### 修改记录

#### 修改 1：配色与主题整体转深色

**文件：** `res/values/colors.xml`、`res/values/styles.xml`
**修改内容：** 语义色值全部换成终端深色（bg `#0B0C0E`、surface `#121418`、文字 `#E6E9EF`）；
`AppTheme` 从 `Theme.Material.Light` 换成 `Theme.Material`，让 EditText 光标、Toast、对话框
一起走暗色。
**为什么整体换而不是加"深色模式"开关：** 主界面现在是等宽长文本 + 状态色，
浅底下状态色（ok/warn/danger）被柔化到几乎看不出差别。而且只换色值就能让四个界面
一起变，不留"主页深、设置浅"的割裂。

#### 修改 2：会话流水组件

**文件：** `ui/CodexTranscript.java`（新，198 行）
**修改内容：** 七种条目——用户发言（左侧竖线+淡底）、工具调用（`● skill  args`，参数压暗）、
结果（`↳ ✓/✗ code · 123ms`）、分组标题、状态行、警告、收尾横幅；流水上限 240 条，
超出从头部丢弃；用户往上翻时停止自动滚动。
**为什么用代码拼装：** 七种条目结构都是「一行等宽文本 + 缩进」，XML 会变成七份重复文件；
混排变色（参数压暗）在 `SpannableStringBuilder` 里三行就能写。

#### 修改 3：主页重写

**文件：** `ui/MainActivity.java`（重写，435 行）、`res/layout/activity_main.xml`、
`res/layout-land/activity_main.xml`
**修改内容：** 顶栏（`▌agent runtime` + 会话 id + 三个文字入口）、状态条（一行 `键 值` 等宽小字，
八项）、流水区、输入区（圆角框 + 运行按钮 + 五个 chip + 自动确认 + 运行态提示）。
横屏改两栏：左侧流水，右侧 248dp 状态与入口面板。
**状态展示的取舍：** 六枚彩色徽章占两行高度只传 6 个布尔值，压成一行小字信息量相同。

#### 修改 4：事件流到流水的映射

**文件：** `ui/MainActivity.java`
**修改内容：** `TaskBus` 事件翻译成条目。`step` 的两行消息（`[1/3] skill` 与 `     ✓ CODE  123ms`）
在这里解析；`plan` / `task` 各自映射。
**为什么不改 TaskBus 协议：** 它还有悬浮窗、控制台、通知三个消费者，改协议影响面远大于
在 UI 里解析。

#### 修改 5：厂商适配层

**文件：** `device/RomInfo.java`（新，252 行）
**修改内容：** 识别 MIUI / HyperOS / EMUI / HarmonyOS / MagicOS / ColorOS / OriginOS /
Flyme / ZUI / One UI；`guides()` 按 ROM 生成授权清单（无障碍 / 悬浮窗 / 自启动 /
后台弹出 / 电池白名单），每个入口带候选 Intent 列表，逐个试，全失败退回应用详情页。
**刻意不做：** 不用反射改系统私有设置。"自启动管理"那类 API 在多数 ROM 上要么不存在、
要么要系统签名，硬试的结果是随机崩溃。只负责把用户送到门口。

#### 修改 6：设置页接入口

**文件：** `ui/SettingsActivity.java`、`res/layout/activity_settings.xml`
**修改内容：** 新增「机型适配」一行，显示当前 ROM 与是否需要额外授权；
点开是带说明的自绘清单（`AlertDialog.setItems` 单行塞不下"为什么需要"）。

#### 修改 7：补齐 x86_64 ABI

**文件：** `agent/build.gradle`
**修改内容：** `abiFilters 'arm64-v8a', 'x86_64'`。
**原因：** V1.5 只打 arm64，APK 能装进 x86_64 模拟器，但一旦真去加载 native 库就崩——
"能装不能跑"是假支持。代价是 APK 17.9MB → **35.1MB**（ONNX Runtime 每个 ABI 约 13MB）。

#### 修改 8：模拟器脚本

**文件：** `tools/emu_start.py`、`tools/emu_try.py`、`tools/emu_probe.py`（新）
**修改内容：** 脱离进程组拉起模拟器、轮询到 `sys.boot_completed=1`、日志写文件。
`emu_try.py` 是"加速×渲染"组合探测，`emu_probe.py` 是第一版（有管道挂死的 bug，留作对照）。
**结果：起不来**，详见「问题 52」。

#### 修改 9：布局结构测试

**文件：** `src/test/java/com/agentruntime/UiLayoutTest.java`（新，7 项）
**修改内容：** Robolectric 在 JVM 里真正 inflate 并测量四种机型尺寸，断言输入区不被挤出屏幕、
流水区占中间、横屏走两栏（右栏 248dp ± 8px）、无横向溢出、深色配色亮度。
**为什么不是截图：** 见「问题 53」。

#### 修改 10：产物核验补 ABI 断言

**文件：** `tools/verify_apk.py`
**修改内容：** 新增 ABI 检查（`lib/` 下必须同时有 arm64-v8a 与 x86_64）、
新增 `RomInfo` 与 `CodexTranscript` 两个关键类。

### 遇到的问题

#### 问题 50：后台任务里 `emulator ... | tail` 被判定"完成"
**现象：** 启动命令 6 秒就返回，`adb devices` 什么都没有。
**原因：** 两层误解。第一，`emulator.exe` 只是前端，它把 `qemu-system-x86_64-headless.exe`
拉起来后自己就退出，所以"进程退出"不代表模拟器停止。第二，管道到 `tail` 会缓冲，
看不到真实输出。
**处理：** 判断标准改成 `adbd 上线 + sys.boot_completed=1`，日志一律写文件。

#### 问题 51：用 `capture_output` 跑模拟器把父进程挂死
**现象：** 探测脚本跑了 6 分钟没有任何输出。
**原因：** `subprocess.run(capture_output=True)` 在超时后 kill 的是父进程，qemu 子进程
仍握着管道写端，读线程永远等不到 EOF。
**处理：** 改文件日志 + 显式 `taskkill`；并加 `CREATE_BREAKAWAY_FROM_JOB` 让 qemu 能脱离作业对象。

#### 问题 52：模拟器在纯软件模拟下起不来（阻塞级，未解决）
**现象：** `-accel off` 的五种组合：launcher 静默退出，或 qemu 存活但 **CPU 时间恒为 0:00:00**，
设备永远 `offline`。
**诊断：** `-accel-check` 报 6（无 hypervisor 驱动）；`VirtualizationFirmwareEnabled=False`、
`HypervisorPresent=False`，而 CPU 是 Ryzen 9 7900X（`VMMonitorModeExtensions=True`）——
**主板固件里的 SVM 被关了**。
**尝试：** WHPX（要装系统功能+重启）、AEHD（驱动包已下载，需管理员且依赖 SVM）、
TCG 兜底（失败）。
**结论：** 启用方法写进 `docs/EMULATOR_SETUP.md`（BIOS 里 SVM → Enabled），脚本已就位，
开完开关 `python tools/emu_start.py --accel auto` 即可。
**这一条必须说清：模拟器没跑起来，所以设备侧行为仍然是"未验证"，不是"已通过"。**

#### 问题 53：Robolectric 的像素渲染在 Windows 上没有运行时
**现象：** `resource native/windows/x86_64/robolectric-nativeruntime.dll not found`，
随后 `UnsatisfiedLinkError: RenderNodeNatives.nCreate`。
**原因：** Robolectric 的 NATIVE 图形模式只提供 Linux/macOS 运行时。
**处理：** 放弃像素截图，改成结构验证（7 项），并明确标注"字体渲染/GPU 合成/刘海避让未验证"。

#### 问题 54：Robolectric 拉 AndroidX 依赖导致 AGP 中断构建
**现象：** `Configuration contains AndroidX dependencies, but android.useAndroidX property is not enabled`。
**处理：** `gradle.properties` 打开 `android.useAndroidX=true`（仅测试期依赖需要，主 APK 依赖树不变）。

#### 问题 55：Maven Central TLS 握手被服务端终止
**现象：** 拉 `plugins-maven-dependency-resolver` 时 `Remote host terminated the handshake`。
**处理：** `settings.gradle` 加阿里云镜像，`mavenCentral()` 留作回落。

#### 问题 56：测试里写死 dpi 换算像素
**现象：** 横屏右栏断言"实际 744px"，按 440dpi 算的期望是 682px。
**原因：** Robolectric 里 `xxhdpi` 实际是 480dpi。写死 dpi 会让画布比目标机型窄，
断言照样通过但测的不是那台机器。
**处理：** 像素一律由 `DisplayMetrics.density` 换算。

#### 问题 57：横屏断言取错父节点
**现象：** 右栏宽度断言得到 2450px（整屏宽）。
**原因：** `statusRow.getParent().getParent()` 爬到根布局了，应该是 `getParent()`。

#### 问题 58：`View.setOnScrollChangeListener` 的 lambda 里调 `getChildAt`
**现象：** 编译报"找不到符号"。
**原因：** lambda 参数类型是 `View`，`getChildAt` 是 `ViewGroup` 的方法。
**处理：** 用外层的 `ScrollView` 引用取子视图。

### 最终修改结果

**本次完成：**
- 主页改为 Codex 式会话流水（七种条目、240 条上限、转屏重放、智能自动滚动）
- 四个界面统一深色主题
- `RomInfo` 覆盖 10 个厂商 ROM 的识别与授权导航
- 设置页「机型适配」入口 + 主页状态条 `rom` 项
- ABI 补 x86_64（真机 + 模拟器都能加载 native 库）
- 模拟器与镜像安装到位、启动脚本就位（受固件 SVM 限制未跑起来）
- Robolectric 布局结构测试 7 项
- 单测 132 → **139**（17 + 50 + 30 + 19 + 16 + 7）
- 主源码 69 → **71 文件 / 12446 行**（+2 / +506）
- APK 35.1MB（35972.7 KB），ABI 双架构，42/42 关键类

**验证情况：**
- 编译：通过（`BUILD SUCCESSFUL`）
- 单测：**139/139**，0 失败 0 跳过
- 布局结构：四种机型尺寸下 inflate + 测量 + 摆放断言全部通过
- 深色配置：`bg` 亮度 4.1、`text_primary` 亮度 227.6（阈值 <60 / >180）
- 横屏两栏：右栏实测 744px（248dp @480dpi），流水区窄于整屏
- APK 核验：42/42 类、24/24 动作、ABI `['arm64-v8a','x86_64']`、Manifest 17 项
- **模拟器：未跑起来**（固件 SVM 未开启）
- **像素级外观：未验证**（无设备、Windows 无 Robolectric native 运行时）
- **厂商页面跳转：未验证**（需要各厂商真机）

### 当前状态

`界面与适配层落地并通过结构验证；像素外观与设备侧行为仍待真机/模拟器`

### 遗留问题

- **模拟器要开 BIOS 的 SVM**，这是硬前提，agent 无法代改。开启后需重跑一遍全流程并补截图。
- **ABI 双打让 APK 翻倍**（17.9 → 35.1MB）。要压体积应改 ABI splits 出两个包，
  代价是"该装哪个"的认知成本。
- **厂商授权页跳转未验证**：各家 ROM 版本之间类名会变，候选列表只是"尽可能对"，
  真实命中率要逐机型测。
- **状态条只显示 ROM 名**，没有显示"哪些授权还没给"。真机上应该把它做成可点的待办。
- **流水只存内存**：进程被杀就没了，没有落盘与会话列表（Codex 有 thread 列表）。
- **控制台 / 设置 / 技能库仍是卡片式**，没有跟着流水风格重做，只是统一了配色。
- **深色是唯一主题**：没有浅色选项，强光下可读性未验证。
- **刘海与手势条避让未验证**：`windowLayoutInDisplayCutoutMode` 已设 `shortEdges`，
  但实际是否压到内容没见过。
"""


def main():
    with io.open(DOC, encoding='utf-8') as f:
        old = f.read()
    if '[任务 12]' in old:
        print('already appended, skip')
        return
    with io.open(DOC, 'a', encoding='utf-8') as f:
        f.write(SECTION)
    with io.open(DOC, encoding='utf-8') as f:
        new = f.read()
    print('appended: %d -> %d chars (+%d)' % (len(old), len(new), len(new) - len(old)))


if __name__ == '__main__':
    main()
