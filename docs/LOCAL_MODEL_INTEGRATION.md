# 本地小模型接入指南

本文说明 V1.1 的本地模型层怎么用、怎么接、以及哪些部分还没做完。

---

## 一、三个后端，先选一个

| 后端 | `local_backend` | 模型跑在哪 | 适用 | 当前状态 |
|---|---|---|---|---|
| 无 | `NONE` | — | 只用规则与云模型 | ✅ 完整 |
| HTTP | `HTTP` | 设备内或 PC 上的推理进程 | 调试、PC 辅助、Termux 部署 | ✅ 协议层已验证<br>⚠️ 真实模型未接 |
| ONNX | `ONNX` | Agent 进程内 | 需要离线、低延迟 | ⚠️ 仅探测骨架 |

**默认推荐 HTTP**。理由很实际：

- APK 不用背 NDK、不用背 250MB~1.2GB 权重；
- 模型可以单独升级，换模型不用重装 APK；
- 崩溃隔离——推理进程 OOM 不会带走 Agent 主进程；
- **调试期可以先用 PC 上的模型把整条链路跑通**，再考虑搬到手机上。

---

## 二、HTTP 后端：配置与自检

### 2.1 写入配置

```bash
adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity \
  --es local_backend HTTP \
  --es local_host 127.0.0.1 \
  --ei local_port 8080 \
  --es local_vision_model qwen2-vl-2b-instruct \
  --es local_decider_model qwen2.5-1.5b-instruct \
  --ei local_timeout_ms 15000 \
  --ez local_info true \
  --ez finish_after_run false
```

参数为「部分合并」：只传要改的项，其余保留原值。

### 2.2 自检

```bash
# 本地模型状态
adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity \
  --ez local_info true --ez finish_after_run false

# 环境快照（设备/屏幕/网络/无障碍）
adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity \
  --ez env_info true --ez finish_after_run false

# 综合能力自检：截图/手势/浏览器/本地模型/技能数/动作数
adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity \
  --ez capability_info true --ez finish_after_run false

# 读结果
adb shell run-as com.agentruntime.agent cat files/last_result.json
```

`capability_info` 的输出长这样：

```json
{
  "sdk_int": 34,
  "a11y_connected": true,
  "screenshot": {"supported": true, "requires_api": 30},
  "gesture": {"supported": true, "requires_api": 24},
  "browser": {"internal_attached": false, "internal_ready": false,
              "dom_readable": true, "js_arbitrary_exec": false},
  "local_model": {"backend": "http", "available": true, "supports_vision": true,
                  "vision_model": "qwen2-vl-2b-instruct", "runtime": "http endpoint ..."},
  "cloud_planner": {"configured": false},
  "skill_count": 19,
  "action_count": 26,
  "prompt_version": "1.1.0"
}
```

这是验收该看的东西——**每个能力都摊开说能不能用，而不是等任务失败了才猜**。

### 2.3 最快的调试路径：PC 上跑模型

手机端先不装模型，用 `adb reverse` 把手机的 8080 转发到 PC 的 Ollama：

```bash
# PC 上（Ollama 自带 OpenAI 兼容端点）
ollama serve
ollama pull qwen2.5:1.5b

# 把手机的 8080 转发到 PC 的 11434
adb reverse tcp:8080 tcp:11434

# 配置 Agent 指向 127.0.0.1:8080
adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity \
  --es local_backend HTTP --ei local_port 8080 \
  --es local_decider_model qwen2.5:1.5b --ez local_info true --ez finish_after_run false
```

这样能在不碰设备性能的前提下，验证：
- 提示词能不能被模型理解；
- 模型输出能不能通过 Task DSL Schema 校验；
- 路由的「本地失败回退云端」是否按预期工作。

### 2.4 设备内跑推理

Termux 方案（适合自测）：

```bash
pkg install llama-cpp
llama-server -m /data/data/com.termux/files/home/qwen2.5-1.5b-instruct-q4_k_m.gguf \
  --host 127.0.0.1 --port 8080 --ctx-size 2048
```

视觉模型还需要 `--mmproj` 指向对应的投影层文件。**注意：Agent 与推理服务在同一台设备上争内存**，4GB 内存机型上跑 2B 视觉模型极易被 LMK 杀掉，建议先只用决策模型（1.5B 量化后约 1GB）。

---

## 三、ONNX 后端：为什么只做了骨架

`OnnxLocalModelProvider` 目前只做一件事：反射探测 `ai.onnxruntime.OrtEnvironment` 是否存在，不在就返回 `ORT_RUNTIME_MISSING`。

**这是刻意的诚实，不是偷懒。** 在进程内跑一个视觉语言模型，需要做完下面四件事，而其中没有一件能在无真机、无权重、无 runtime 的环境里验证：

1. **把 runtime 打进 APK**
   ```gradle
   implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.18.0'
   ```
   体积增加约 15~20MB。

2. **准备权重**
   量化后的 SmolVLM-256M 约 250MB，Qwen2-VL-2B-int4 约 1.2GB。放 `files/models/` 或 assets（assets 会被压进 APK，不推荐）。

3. **自己实现预处理与解码**
   - 图像：resize 到模型要求尺寸、归一化、NCHW 排布
   - 文本：Tokenizer（SentencePiece 或 BPE），纯 Java 实现或 JNI 调 native
   - 解码：自回归采样 + KV cache 复用（不复用的话每生成一个 token 都要重跑整个前缀）

4. **内存与延迟压测**
   2B 模型 int4 量化在 8GB 机型上勉强可跑，单次推理 1~3 秒；4GB 机型基本不可用。

接入时把 `complete()` 里的 `INFERENCE_NOT_IMPLEMENTED` 替换成真实推理即可，上层（`LocalModelRegistry` / `LocalPlanAdapter` / `VisualGrounder`）无需改动——这是当初分层的目的。

---

## 四、模型选型建议

### 视觉快速模型（元素定位）

| 模型 | 参数量 | 量化后 | 评价 |
|---|---|---|---|
| SmolVLM-256M | 256M | ~250MB | 最快，但定位精度一般，适合大按钮 |
| Qwen2-VL-2B | 2B | ~1.2GB | 精度可用，延迟 1~3s |
| Moondream2 | 1.8B | ~1GB | 定位专用，英文界面更准 |
| Florence-2-base | 232M | ~250MB | 支持区域描述，需转 ONNX |

选择标准不是「越准越好」，而是**「在可接受的延迟内，比无障碍节点兜底强」**。如果节点树已经能定位，视觉就是浪费。

### 决策模型（下一步动作）

| 模型 | 参数量 | 量化后 | 评价 |
|---|---|---|---|
| Qwen2.5-1.5B-Instruct | 1.5B | ~1GB | 中文指令遵循较好，推荐起步 |
| Gemma-2-2B-it | 2B | ~1.5GB | 英文强，中文一般 |
| Phi-3-mini-3.8B | 3.8B | ~2.3GB | 能力最强但手机难扛 |

**小模型的真实瓶颈不是能力，是指令遵循。** 1.5B 级别的模型经常输出多余解释、漏字段、把 JSON 包在 markdown 里。所以：

- `PromptLibrary` 的约束写得比云端版本更硬；
- 输出必须过 `DslParser.validateTaskJson()`；
- 失败就回退云端，不要指望重试能救回来。

---

## 五、提示词适配（改模型时必须一起看）

`PromptLibrary.PROMPT_VERSION = "1.1.0"`。改模板必须递增版本号，审计里会记录实际使用的版本。

小模型有三个常见失效模式，模板里已经针对性处理：

| 失效模式 | 模板对策 |
|---|---|
| 把界面文本当指令执行 | `<untrusted_screen_data>` 包裹 + 第 7 条硬约束 + `PromptGuard` 剥离 |
| 输出 markdown 代码块 | 系统提示词开头即声明「不要 Markdown 代码块」；解析器仍会抽取首个 `{...}` 兜底 |
| 编造不存在的技能 | `<skills>` 白名单 + 第 1 条硬约束 + Schema 校验拒绝未知 skill |

如果换的模型仍然输出不合规，优先调 `temperature`（当前 0.1）和 `max_tokens`，而不是放宽校验。

---

## 六、未验证与风险

| 项 | 说明 |
|---|---|
| 真实模型推理 | 一次都没跑过。协议层验证 ≠ 模型可用 |
| 上下文长度 | 小模型常见 2K~4K ctx，屏幕树摘要 + 历史很容易超；`PromptGuard.enforceBudget` 默认 12000 字符，对 2K ctx 的模型偏大，需要按模型下调 |
| 内存 | 同机跑推理 + Agent + 被测 App，4GB 机型基本不可行 |
| 延迟 | 未实测。视觉定位一次 1~3s，在 24 步的任务里会累积成分钟级 |
| 并发 | `LocalModelProvider` 无并发保护，多任务同时下发会串行阻塞 |

---

## 七、一句话总结

**HTTP 通道是通的，模型是空的。** 接上真实模型只需要改配置；但「本地小模型到底能不能撑起规划」这个问题，本仓库没有答案——需要一次真机 + 真实模型的联合验证。
