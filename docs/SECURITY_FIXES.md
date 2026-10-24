# 安全最小修复与回归说明

日期：2026-10-23

## 已修复

1. **TaskExecActivity 生产边界**
   - `agent/src/main/AndroidManifest.xml` 改为使用 `${taskExecExported}`，不再无条件 `exported=true`。
   - `agent/build.gradle` 中 debug 设置 `taskExecExported=true`，保留测试和显式 `adb` 调用；release 设置为 `false`，生产 APK 不接受外部隐式/显式启动该入口。
2. **自动确认默认关闭**
   - `TaskExecActivity` 的 `auto_confirm` 缺省值改为 `false`；只有调用方明确传入 `--ez auto_confirm true`（或等价 Intent extra）才会启用测试自动确认。
   - MainActivity 的自动确认复选框默认取消，旋转恢复缺省值也为 `false`。用户必须显式勾选“自动确认高风险动作（发送/支付/删除；仅测试环境）”。
   - OverlayService 原先无条件向 TaskRunner 传 `true`，已改为 `false`。ConsoleActivity 未发现自动确认入口；其执行路径不会默认打开测试模式。
3. **系统设置权限声明**
   - Manifest 增加 `android.permission.WRITE_SETTINGS`，与亮度设置能力的运行时授权检查保持一致。该声明不等于自动获得授权，仍应通过系统设置页由用户授权。

## 静态验收

未运行 Gradle（避免与并行构建冲突）。可独立运行以下不需要 Android SDK/Gradle 的检查：

```bash
cd /workspace/agentruntime
python3 tools/verify_security_fixes.py
```

该脚本检查：WRITE_SETTINGS、TaskExecActivity placeholder、debug/release 导出值、TaskExecActivity/MainActivity/布局的自动确认默认值，以及 OverlayService 不再强制自动确认。

## 构建代理回归建议

构建完成后分别检查合并 Manifest（路径通常为 `agent/build/intermediates/merged_manifests/<variant>/process<Variant>MainManifest/AndroidManifest.xml`）：

```bash
# 不要与构建同时运行；由构建代理执行
python3 tools/verify_security_fixes.py
rg -n 'TaskExecActivity|WRITE_SETTINGS' agent/build/intermediates/merged_manifests/debug/processDebugMainManifest/AndroidManifest.xml
rg -n 'TaskExecActivity|WRITE_SETTINGS' agent/build/intermediates/merged_manifests/release/processReleaseMainManifest/AndroidManifest.xml
```

预期：debug 合并 Manifest 中 TaskExecActivity `exported="true"`，release 为 `exported="false"`；两者都包含 WRITE_SETTINGS。之后可用 `aapt dump xmltree` 或 `apkanalyzer manifest permissions` 对最终 APK 再确认一次。

## 行为回归建议

- debug：显式 adb Intent 带 `--ez auto_confirm true` 可进入测试执行路径；不带该 extra 的风险动作应继续经过用户确认/无确认通道时拒绝。
- release：尝试显式 `adb shell am start -n com.agentruntime.agent/com.agentruntime.ui.TaskExecActivity` 应被系统拒绝（Activity 未导出）。
- UI：首次进入 MainActivity，自动确认复选框应未选中；只有用户主动勾选后风险动作才允许测试自动确认。
- 亮度技能：未授予“修改系统设置”时应仍返回 NEED_PERMISSION 并引导系统授权，不应因 Manifest 声明而假定已授权。
