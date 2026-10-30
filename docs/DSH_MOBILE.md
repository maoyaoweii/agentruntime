# DSH Mobile 2.0 预览版

Android 独立助手：在手机上管理会话、调用模型、运行已启用的手机工具与技能。入口是新的原生 DSH Mobile 界面，不需要桌面电脑、Termux 或本地 Node 服务。

预览版 2 根据 [四屏设计稿](design/dsh-mobile-ui-reference.png) 调整了对话、会话、插件和连接设置页面，统一图标与底部导航；设置中可临时显示自己输入的密钥，离开页面时会重新隐藏。

交互设计也参考了 [ccch1mneyyy/dsh-go](https://github.com/ccch1mneyyy/dsh-go/tree/b96322910c9ebd1ae10ac89283ba1f28c3c904f2)：已结束轮次中的工具记录默认收起，点开可查看每一步与结果；横屏和平板宽窗口使用侧栏并限制内容宽度。

这是本仓库的独立 Android 实现，参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness/tree/f3803c96974ad4a94195fb97e5c637e9c2a3afe3) 的架构，不是 DeepSeek 官方客户端。桌面版 Node / Cordis 插件不能直接装进本应用。

## 安装与开始

1. 安装 Release 预览 APK。包名继续使用 `com.agentruntime.agent`，最低 Android 8.0，包含 arm64-v8a 和 x86_64。
2. 打开「设置」，填写模型服务地址、模型名称和 API Key，然后保存。默认地址 `https://api.deepseek.com`，模型 `deepseek-flash`；也可以填写自己的 Chat Completions 兼容服务和模型。协议默认自动识别官方 DeepSeek 地址，其余使用 OpenAI 兼容格式；DeepSeek 代理服务请选择「DeepSeek（含代理服务）」。服务须支持本应用使用的流式响应和函数工具协议。
3. 如需测试，点「测试已保存的连接」。这会发起一次实际模型请求，按服务账户计费。应用不会在启动时自动调用模型。
4. 返回「对话」。纯对话模式不开放工具；手机助手模式按需开放已启用插件。模型和插件集合在每次发送时确定，之后新启用的插件从下一次发送起生效。

预览 APK 使用公开的 Android 测试签名，方便安装与验证，不是正式发行签名。若已安装同包名但签名不同的旧版本，系统会拒绝覆盖；请先自行备份需要的数据，再决定是否卸载。卸载会清除本机会话、文件和密钥。

## 能做什么

| 功能 | 当前行为 |
| --- | --- |
| 对话 | SSE 流式回复、连续多轮、生成期间停止、旋转屏幕后继续查看 |
| 会话 | 新建、切换、重命名、删除；SQLite 本机持久化，列表显示最近 100 个 |
| 工具记录 | 展示运行状态，可点击查看完整入参与结果；按会话轮次区分相同调用 ID |
| 设备观察 | 查看电量、网络、系统环境及可用权限，不读取其他应用正文 |
| 本机工作区 | 列出、读取、确认后保存文本；每个文件最多 16 KiB；通过系统文档选择器导出 |
| 手机技能 | 列出并调用仓库内置的 Android 技能；复用原有目标包校验、风险确认与权限限制 |
| 插件控制 | 每个插件单独开关；停用会取消该插件的当前调用，取消后新产生的只读结果不进入模型上下文 |
| 后台任务 | 用户发送后启动前台服务并显示停止通知，切到目标应用时继续当前工具流程；系统终止后不自动恢复执行 |

部分手机技能需要用户主动开启无障碍、悬浮窗、通知访问等权限，可从「设置 → 手机权限与高级设置」进入。普通模型对话和本机工作区不要求这些特殊权限。权限缺失时工具返回失败，不会假装操作成功。高风险确认需要本应用位于前台，或用户已授予可显示确认窗口的悬浮窗权限；否则拒绝执行。

示例：

- 「查看这部手机的环境状态，说明哪些权限还没开启。」
- 「列出可用技能，告诉我哪些操作需要确认。」
- 「帮我整理今天的安排，确认内容后保存到工作区 notes/today.txt。」

## 数据与安全边界

- API Key 使用 Android Keystore 的 AES-GCM 密钥加密；密文保存在应用私有设置内。API Key 不写入会话、审计日志、源码或发布包。密钥表单不会进入页面恢复状态；新密钥只在用户保存时写入。
- 发送到模型的内容包括当前用户消息、最近完整轮次和工具结果。请选择自己信任的服务；「保存在本机」不表示模型推理完全离线。
- 模型服务只允许 HTTPS，测试用途仅对真正的 `localhost` / `127.0.0.1` 开放 HTTP。不允许 URL 用户名、查询参数或片段；不跟随重定向，避免转发凭据。
- 独立工作区限制在 `files/dsh-workspace`，阻止绝对路径、目录穿越与指向外部的符号链接。凭据设置和会话数据库不位于该目录。遗留文件技能仍遵守其原有存储范围与风险策略，生成笔记优先使用工作区工具。
- 工作区写入每次确认。手机技能的高风险动作复用原有确认门；DSH 调用不打开 Debug 自动确认，不触发遗留引擎的自动云端重规划。
- 每次只有一个 DSH 轮次；手机技能还受全局 TaskRunner 互斥约束。停止 DSH 任务只取消属于该轮次的手机技能，不会取消无关入口的任务。
- 每轮最多 8 次模型请求、12 次工具执行、3 分钟；单次模型输出、工具参数、历史上下文均有大小限制。没有自动重试。
- 未完成流只记作未完成片段；已执行动作保留结果。进程退出后，未取得结果的调用记为 `RESULT_UNAVAILABLE`，提示动作可能已生效，绝不自动重放。用户停止、插件关闭或系统回收，都不能保证撤销已经完成的外部动作。
- 插件是随 APK 编译的 Android 代码；不下载并执行第三方脚本。提示词将文件和屏幕内容视为不可信数据，但这不构成消除所有模型提示注入风险的保证。

## 与参考架构的对应

| 官方 Harness 概念 | 本实现 |
| --- | --- |
| 会话及追加事件 | `dsh/store/SessionStore`：轮次、用户消息、已完成模型消息、工具调用与结果 |
| 模型适配器 | `dsh/model/ModelTransport` / `ChatClient`：HTTP、SSE、Chat Completions 工具协议 |
| 插件与工具注册 | `dsh/plugin/MobilePlugin` / `PluginRegistry`：原生工具、冻结准入与即时撤销 |
| Agent turn loop | `dsh/runtime/DshRuntime`：模型 → 顺序工具 → 模型，逐个边界持久化 |
| 宿主与生命周期 | `DshGraph` / `DshController` / `DshTurnService`：进程状态、任务互斥与前台服务 |
| 手机界面 | `dsh/ui`：会话、聊天、插件、设置和文档导出 |

参考版本固定在 `f3803c96974ad4a94195fb97e5c637e9c2a3afe3`，并参考其 [架构说明](https://github.com/deepseek-ai/deepseek-harness/blob/f3803c96974ad4a94195fb97e5c637e9c2a3afe3/docs/architecture.md) 和 [品牌说明](https://github.com/deepseek-ai/deepseek-harness/blob/f3803c96974ad4a94195fb97e5c637e9c2a3afe3/BRAND_GUIDELINES.zh.md)。使用 DSH Mobile 名称与独立字母图标，没有复制官方标识。

DeepSeek 的思考字段保留用于多轮工具协议，界面不展示推理过程；默认关闭深度思考，可在设置开启。切换到 OpenAI 兼容协议时，适配器仅从出站请求移除 DeepSeek 扩展字段，本机事件仍保留原始模型消息。自定义模型服务的其他字段兼容性由服务决定，不支持的服务会显示错误而非自动降级到其他地址。协议依据：[工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)。

## 构建与验证

使用 JDK 17 或 21、Gradle 8.8、Android SDK 34 / Build Tools 34.0.0，在项目根目录运行：

```sh
gradle :agent:testDebugUnitTest :agent:testReleaseUnitTest :agent:lintDebug :agent:lintRelease :agent:assembleDebug :agent:assembleRelease
```

仓库未附 Gradle Wrapper，需安装 Gradle 或由 CI 的 setup-gradle 提供；`local.properties` 填写个人 SDK 路径，不应提交。正式发行需要自行设置私有签名。GitHub Actions 只构建与测试，不配置 API Key、不自动调用外部模型。

验证覆盖真实回环 HTTP + SSE、确认后的工作区写入、工具返回及下一轮历史、加密凭据、撤销与取消、服务失败恢复，以及 320 / 411 dp 手机、横屏和平板的原生布局测量。全部模型测试使用本机模拟服务和合成密钥，没有实际调用付费模型。底部导航、插件开关、设置页旋转、密钥隐藏和通知返回草稿也有回归检查。上一预览版的 [机器可读验证记录](../verification/dsh-mobile/verification.json) 保留作为历史记录，本次构建结果以 GitHub Actions 检查为准。

当前环境无可用 Android 设备，且固件未开启虚拟化，未完成本构建的真机安装、系统权限弹窗、Keystore 硬件实现、跨应用技能及厂商后台策略验收。HTML 外观预览只用于查看视觉设计，不是真机截图，也不提供模型功能。最终测试数量和文件 SHA-256 见随包验证记录。

## 扩展 Android 插件

实现 `MobilePlugin`，通过 `PluginRegistry.register()` 注册到 `DshGraph`。工具提供名称、说明、JSON 对象参数模式、是否确认、是否产生副作用及 Handler。当前参数验证支持字符串和对象，拒绝未声明键；新增类型需同步扩展验证器和测试。

只读工具保持 `sideEffect=false`，在读取循环中检查 `Cancellation`，撤销后的结果会被丢弃。会改变设备或文件的工具明确设 `sideEffect=true` 并设置确认策略；写操作应在每个副作用边界检查令牌，返回实际状态。`run_skill` 自带原有风险门，因此不额外重复弹插件级确认。不要在插件中读模型密钥、记录秘密、绕过路径限制或自行重试未知结果的动作。
