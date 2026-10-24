# Linux 构建与单元测试交接记录

**执行时间：** 2026-10-23 10:42（本机时区）  
**工程：** `/workspace/agentruntime`  
**执行范围：** 仅构建与 JVM 单元测试；未操作模拟器、未安装或启动模拟器。

## 结果

指定任务已实际执行并成功：

```text
:agent:assembleDebug       SUCCESS
:agenttestapp:assembleDebug SUCCESS
:agent:testDebugUnitTest   SUCCESS
BUILD SUCCESSFUL in 21s
```

JUnit XML 汇总为 **141 tests / 0 failures / 0 errors / 0 skipped**。测试明细位于：

- `/workspace/agentruntime/agent/build/test-results/testDebugUnitTest/`
- `/workspace/agentruntime/agent/build/reports/tests/testDebugUnitTest/index.html`

生成的 APK：

- `/workspace/agentruntime/agent/build/outputs/apk/debug/agent-debug.apk`（约 36 MB）
- `/workspace/agentruntime/agenttestapp/build/outputs/apk/debug/agenttestapp-debug.apk`（约 27 KB）

## 环境与安装

- JDK：OpenJDK 21.0.12.1；首次构建发现原环境只有 JRE，缺少 `javac`/`jlink`，已安装 `openjdk-21-jdk-headless`。
- Gradle：8.8，安装于 `/workspace/gradle-dist/gradle-8.8/`。
- Android SDK：`/workspace/android-sdk`。
- Android Platform 34：`/workspace/android-sdk/platforms/android-34`，API 34（官方 `platform-34-ext7_r03.zip`）。
- Build Tools 34.0.0：`/workspace/android-sdk/build-tools/34.0.0`（官方 `build-tools_r34-linux.zip`）。
- 其他 SDK（platform-tools、emulator、API 30 system image）由主代理安装；本任务未与 sdkmanager 并发操作。

## 配置修复与备份

为 Linux 可复现构建，仅修改构建配置，不修改应用业务源码：

1. `local.properties` 改为 `sdk.dir=/workspace/android-sdk`。
2. `build.sh` 改为 Linux 路径和 Gradle 8.8 路径，保留代理注入与任务参数传递；默认任务仍为 `:agent:assembleDebug`。
3. 原始配置已备份为：
   - `local.properties.orig`
   - `build.sh.orig`
   - `settings.gradle.orig`
   - `gradle.properties.orig`
4. `settings.gradle` 与 `gradle.properties` 未作内容修改；备份仍保留以便审计。

## 日志与注意事项

完整构建日志：

- `/workspace/agentruntime/test-results/build/build-20261023-104230.log`（最终成功、退出码 0）
- `/workspace/agentruntime/test-results/build/last-exit-code`（内容为 `0`）

首次尝试因 JDK 缺少 `jlink` 失败，错误为 `JdkImageTransform`；安装完整 JDK 后重跑成功。构建期间 AGP 报告 ONNX Runtime native 库无法 strip，并按 AGP 行为原样打包，这不是失败：`libonnxruntime.so`、`libonnxruntime4j_jni.so` 被保留。该问题不影响本次任务的构建成功状态。
