"""追加 V1.4 章节。"""
import io

PATH = r'E:/agentruntime/docs/PROJECT_CHANGELOG.md'

SECTION = """

---

## [任务 10] 2026-10-23 - V1.4 端侧视觉语言模型接入（SmolVLM-256M）

> 起因：用户指出「是视觉小模型和 jev 模型」，进一步澄清为「LLaVA 这类型的模型，你挑一个合适的」。
> 上一轮我把 jev 理解成了通用决策模型，做成了 17KB 的文本意图分类器——方向错了，这一轮纠正。

### 本次目标

- 选一个能在手机上跑的 LLaVA 类视觉语言模型并真正接入
- 把推理运行时打进 APK，而不是让用户自己部署服务
- 能确定的部分做到可验证，不能确定的部分明确留空

### 选型

**SmolVLM-256M-Instruct**（Idefics3 架构，SigLIP-base-93M + SmolLM2-135M）：
目前体积最小的标准 LLaVA 系模型，官方仓库直接提供 ONNX。

关键参数取自官方 config.json：image_token_id=49190、scale_factor=4、
vision 512×512/patch16/hidden768、text hidden=576/30 层。

### 修改记录

#### 修改 1：接入 ONNX Runtime

**文件：** `agent/build.gradle`
**修改内容：** `implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.18.0'`；
`abiFilters 'arm64-v8a'`（全 ABI 会让 APK 涨到 40MB+）。
**影响：** APK 232.4KB → **17.9MB**。这是"运行时内置"的真实代价，已确认可用。

#### 修改 2：紧凑分词器

**文件：** `tools/convert_tokenizer.py`（新）、`assets/models/smolvlm/{vocab.txt,merges.txt,tokenizer_meta.json}`（新）、
`vision/vlm/VlmTokenizer.java`（新）
**修改内容：** 把官方 tokenizer.json（3.5MB）转成逐行格式（890KB），避免 org.json 在设备上建整棵对象树；
Java 端实现 ByteLevel 映射、GPT-2 正则切分、数字逐位拆分、BPE 合并、反向字节还原。
**修改原因：** `tokenizers` 没有官方 Android 绑定，而它错了就是全链路乱码。

#### 修改 3：图像预处理

**文件：** `vision/vlm/VlmPreprocessor.java`（新）
**修改内容：** 按面积约束求解缩放比，保持宽高比切成 512 网格（1080×2340 → 1×3 而不是 2×2）；
双线性重采样；SigLIP 归一化 (v/255-0.5)/0.5 → [-1,1]。
**修改原因：** 硬切会把画面压扁，模型输出表现为"定位总偏一点"，这类 bug 极难查。
**刻意不依赖 Bitmap**：输入是 ARGB 数组，所以这段逻辑能在 JVM 单测里完整验证。

#### 修改 4：模型管理与引擎

**文件：** `vision/vlm/SmolVlmStore.java`、`vision/vlm/VlmEngine.java`（新）
**修改内容：** 模型放 `getExternalFilesDir/models/smolvlm/`（无需 root），带完整性校验；
`VlmEngine.open()` 创建三个 session，`describe()` 输出各会话输入输出名与形状供联调对齐。
**生成循环：** 未实现，`generate()` 返回 null，上层转成 `VISION_NOT_READY`——
因为没有真实权重无法确定 `past_key_values` 布局与 image token 替换位置，
硬编码猜测形状只会得到"能跑但输出乱码"的实现。

#### 修改 5：模型获取脚本

**文件：** `tools/fetch_vlm_model.py`（新）
**修改内容：** 从 hf-mirror 断点续传下载三个 ONNX（573MB），校验体积，可选自动 adb push。

#### 修改 6：自检接入

**文件：** `ui/TaskExecActivity.java`
**修改内容：** `capability_info` 新增 `vlm` 段：引擎类型、词表大小、模型文件状态、`generative_ready`。

### 遇到的问题

#### 问题 45：ONNX runtime 探测在 JVM 单测里误判
**现象：** 加了依赖后旧测试 `onnxProviderIsHonestAboutMissingRuntime` 失败，且新写的探测仍返回不可用。
**原因：** `Class.forName(name)` 默认会执行静态初始化，`OrtEnvironment` 的静态块加载 native 库，
JVM 环境没有 .so → UnsatisfiedLinkError → 被 catch 成"未打包"。
**解决：** 改用 `Class.forName(name, false, loader)`，只查类是否存在，native 初始化留到建 session 时。
**结果：** 已解决；对应测试改为断言"运行时已内置、但推理未就绪"。

#### 问题 46：旧测试断言与新事实冲突
**现象：** `onnxProviderIsHonestAboutMissingRuntime` 断言 runtime 不存在，而现在它真的被打包了。
**处理：** 改名 `onnxProviderDetectsBundledRuntime`，断言改为「探测到类 + complete 返回 INFERENCE_NOT_IMPLEMENTED」。
**这是真实进展而非测试妥协**：从"没有运行时"变成"有运行时但推理未联调"。

### 最终修改结果

**本次完成：**
- ONNX Runtime 打进 APK（arm64），APK 232.4KB → 17.9MB
- 分词器：49152 词表 / 48900 merges，可逆性验证通过
- 图像预处理：网格规划、双线性缩放、三通道归一化，全部单测覆盖
- 模型管理 + 引擎骨架 + 形状自述 + 下载脚本
- 单测 97 → **116**（+19，全绿）
- APK 核验：38/38 类、24/24 动作、Manifest 17 项全 OK

**验证情况：**
- 编译：通过
- 单测：116/116
- 分词与预处理：**用真实词表与构造像素验证**
- **生成循环：未联调**（无真实权重）
- 设备侧：全部未验证

### 当前状态

`运行时与前端管道完成并验证；生成循环待真实权重联调`

### 遗留问题

- 生成循环未实现，端侧 VLM 还不能真正"看图回答"
- 权重 573MB 需手动下载推送，首次使用门槛高
- 4GB 内存机型可能被 LMK 杀掉，未做内存自适应降级
- 未做量化选型对比（q4 vs q4f16 vs fp16 的精度/速度权衡）
- 视觉通道尚未接进 `VisualGrounder` 的定位链路（等生成循环可用）
"""

with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

if '[任务 10]' in content:
    print('SKIP')
else:
    with io.open(PATH, 'a', encoding='utf-8') as f:
        f.write(SECTION)
    print('APPENDED', len(SECTION))

with io.open(PATH, 'r', encoding='utf-8') as f:
    print('总行数', len(f.readlines()))
