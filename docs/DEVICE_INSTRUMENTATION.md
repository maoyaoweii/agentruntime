# Device Smoke Instrumentation

`DeviceSmokeInstrumentation` 是一个**纯 Android framework** runner（继承 `android.app.Instrumentation`），没有 JUnit 或额外测试依赖。它在 `onStart` 启动单独工作线程，按顺序执行检查；每项异常记录为 `FAIL` 后继续，最后通过 `Instrumentation.finish` 返回 JSON Bundle：`device_instrumentation_json`。

## 覆盖范围

1. 真实初始化 `OrtEnvironment.getEnvironment()` / `getVersion()`；仅验证 ONNX Runtime native 环境，不执行、也不声称执行模型推理。
2. `SystemBridge` 在应用私有 `filesDir` 中读写测试文件；`../` 路径及外部绝对路径必须返回 `PATH_NOT_ALLOWED`。测试只创建并清理自己的文件。
3. 音乐音量读取、设置为原值并恢复；亮度不自动授权，未获 `WRITE_SETTINGS` 时必须返回 `NEED_PERMISSION`；剪贴板使用唯一 marker 做回读。
4. 通过 `GestureBridge` 对越界坐标执行明确 `COORD_OUT_OF_BOUNDS` 拒绝，不注入手势。
5. `LocalModelRegistry` 默认无权重/未配置后端时必须不可用（`available=false`），记录为 `not_ready_no_weights`。

测试不依赖特定前台页面，不调用 ADB，不修改业务逻辑，也不授予任何权限。

## 构建

在项目根目录执行：

```bash
bash build.sh :agent:assembleDebugAndroidTest
```

runner 已在 `agent/build.gradle` 的 `defaultConfig.testInstrumentationRunner` 声明：

```text
com.agentruntime.DeviceSmokeInstrumentation
```

## 运行

安装 debug APK 与测试 APK 后，在 API 30 模拟器上执行：

```bash
adb shell am instrument -w -r \\
  -e class com.agentruntime.DeviceSmokeInstrumentation \\
  com.agentruntime.agent.test/com.agentruntime.DeviceSmokeInstrumentation
```

也可不指定 `-e class`，直接运行该测试包中的 runner：

```bash
adb shell am instrument -w -r \\
  com.agentruntime.agent.test/com.agentruntime.DeviceSmokeInstrumentation
```

标准输出中的 `INSTRUMENTATION_STATUS`/结束 Bundle 包含 `device_instrumentation_json`。退出码非零表示至少一项失败或初始化发生 fatal 异常；失败不会被跳过或伪装为通过。


## 本次构建结果

已实际执行 `bash build.sh :agent:assembleDebugAndroidTest` 并通过。构建日志保存于 `test-results/build/instrumentation-build.log`；产物为 `agent/build/outputs/apk/debug/agent-debug.apk` 与 `agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk`。runner 会把完整七项结果写入目标应用 `getFilesDir()/device_instrumentation.json`，并通过 `onStart` 启动一次后台执行；VLM 项使用 `SmolVlmStore.check(context)`，不依赖可被改动的 provider 配置。
