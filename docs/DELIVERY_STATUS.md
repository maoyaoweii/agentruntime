# V1.6 交付状态与验证边界

**状态日期：2026-10-24。** 本页描述此次打包所用的当前源码与 APK；旧版本报告和历史测试记录不覆盖本页结论。

> **2026-10-24 更新（V1.7）：** 已在本地真实 Android 模拟器（Linux + TCG 软件模拟，无 KVM）上完成当前 APK 的
> 设备端端到端验证：**核心基准 6/6、内置浏览器 DOM 2/2、安全负例 3/3 全部通过**，
> 截图/手势/无障碍连接首次取得真实设备回执。过程中修复 4 个真机缺陷（见下）。
> 详细报告：`docs/V1.7_DEVICE_VERIFICATION.md`；可复现脚本：`verification/device_regression.sh`。

## 已完成

- 最终全量命令 `bash build.sh --offline --rerun-tasks :agent:testDebugUnitTest :agent:assembleDebug :agent:assembleRelease` 成功，退出码为 0。
- JVM 测试共 **297 项，0 失败、0 错误、0 跳过**。
- Debug 与 Release 变体均构建成功。对应日志及最终JUnit汇总随交付包放在 `verification/`。
- Debug APK：`apk/agent-debug.apk`，应用版本 1.6.0（versionCode 7），使用 Android 调试签名，只适合测试。
- Release APK：`apk/agent-release-unsigned.apk`，构建成功但**未签名**，不可作为生产发布安装包。
- **（V1.7）设备端 E2E 已在真实模拟器上完成**：核心基准 6/6（观察/截图/打开会话/发文本/发文件/读Wi-Fi）、
  内置受控浏览器 DOM 读写 2/2、安全负例 3/3；a11y/screenshot/gesture 能力自检均为 true。

## V1.7 修复的真机缺陷

1. `TaskExecActivity` 占前台导致无障碍只看得到自身窗口 → 执行前 `moveTaskToBack(true)`。
2. 内置 WebView 打不开 http 页面（`ERR_CLEARTEXT_NOT_PERMITTED`）→ 网络配置仅对回环地址放行明文。
3. `ScreenCapturer` 失败静默 → 增加可诊断日志（异常类型/超时/errorCode）。
4. `SystemBridge.readFile` 可读性判定 → 增加 `canRead()` 脱敏分支，单测契约修正为「不泄露路径」。

## 尚未通过或未验证

- **第三方真实应用技能未在本轮执行**：ATD 精简镜像无 Fossify Notes/DeskClock/Chromium webview_shell，
  且沙箱网络策略屏蔽 F-Droid，无法离线获取；`notes.create_note` / `sys.open_clock` /
  `web.browse_local_page`（含 sqlite3 落库断言）本轮未跑。
- 最新构建验证没有调用真实模型供应商 API。云端连接、真实模型推理及完整 Agent 闭环不在本次通过项内。
- 端侧 SmolVLM 权重未提供，`generative_ready=false`，生成循环未联调。
- 厂商 ROM 授权页跳转：本环境仅 AOSP ATD，无厂商真机，未测。

## 安全与交付内容

- 交付包不包含 API 密钥、密码或其他凭据文件；本机专属 `local.properties`、其备份、构建缓存、AVD 镜像和生成目录均排除在包外。
- APK 与文本源码的常见密钥特征扫描未发现私钥块、Google API Key 或 Bearer Token 特征。扫描只作为打包卫生检查，不等于对所有二进制或所有秘密格式的形式化证明。
- `verification/` 保存本轮全量构建/测试日志、297 项摘要、Release 测试代码隔离检查、日志脱敏检查和源码变更摘要。

## 在其他机器上构建

需要 JDK 21、Android SDK Platform 34、Build Tools 34.0.0 和 Gradle 8.8。工程没有打包 SDK、Gradle 安装或依赖缓存。复制 `local.properties.example` 为 `local.properties`，将 `sdk.dir` 改为目标机器的 SDK 绝对路径；`build.sh` 中的 SDK/Gradle 默认路径也应按目标环境通过 `ANDROID_HOME`、`GRADLE_BIN` 等环境变量调整。未经许可不要自动安装或下载依赖。

重新运行测试和构建：

```bash
bash build.sh --offline --rerun-tasks :agent:testDebugUnitTest :agent:assembleDebug :agent:assembleRelease
```

项目较早的 README、V1.x 报告和 `test-results/` 中含历史测试数字与设备结论；当前汇总请优先看本页及随包 `verification/` 的最终记录。
