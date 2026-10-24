# 本机 Android 模拟器：装好了，但卡在固件开关上

| 项 | 状态 |
|---|---|
| emulator 37.2.12 | ✅ 已安装 |
| system image | ✅ `system-images;android-30;aosp_atd;x86_64`（官方 CI 精简镜像） |
| AVD `agent_api30` | ✅ 已创建（Nexus 5 规格，1080×1920） |
| 启动脚本 | ✅ `tools/emu_start.py` |
| **实际启动** | ❌ **起不来：固件里没开 CPU 虚拟化** |

## 为什么起不来

```
$ emulator -accel-check
accel: 6
Android Emulator hypervisor driver is not installed on this machine
```

硬件是 AMD Ryzen 9 7900X（`VMMonitorModeExtensions=True`，CPU 本身支持 SVM），但：

```
Win32_Processor.VirtualizationFirmwareEnabled = False
Win32_ComputerSystem.HypervisorPresent        = False
```

**主板固件里的 SVM 是关的。** 于是三条路都断了：

| 方案 | 结果 |
|---|---|
| WHPX（Windows Hypervisor Platform） | 需要 SVM，且要装系统功能 + 重启 |
| AEHD（Android Emulator hypervisor driver） | 包已下载到 `extras/google/Android_Emulator_Hypervisor_Driver`，但驱动需要管理员安装，且同样依赖 SVM |
| 纯软件模拟（`-accel off`） | 试了 5 种组合：launcher 静默退出，或 qemu 起来后 **CPU 时间恒为 0**（挂在初始化里），设备始终 `offline` |

最后一条是这一轮最花时间的地方。误判过两次：

1. 第一次以为"qemu 起来就算成功"——其实它只是挂着，`tasklist` 里 CPU 时间是 `0:00:00`。
2. 第二次以为"launcher 退出就是崩溃"——其实 launcher 只是前端，它把 qemu 拉起来后自己就退了。
   所以判断标准必须是 **adbd 上线 + `sys.boot_completed=1`**，不是"进程还在"。

## 要跑起来，只需要开一个开关

进 BIOS（这台机器是 Colorful BATTLE-AX B650M）：

1. 开机按 `Del` 进 BIOS
2. 找 **Advanced → CPU Configuration → SVM Mode**（有的版本叫 `SVM`、`AMD-V`、`Secure Virtual Machine`）
3. 设为 **Enabled**
4. 保存退出，进系统后确认：任务管理器 → 性能 → CPU → 右下角应显示「虚拟化：已启用」

然后任选其一：

```bash
# 方案 A：用 Windows 自带的 WHPX（推荐，不用装第三方驱动）
#   控制面板 → 启用或关闭 Windows 功能 → 勾选「Windows 虚拟机监控程序平台」→ 重启
python tools/emu_start.py --accel auto

# 方案 B：装 AEHD 驱动（需要管理员命令行）
#   cd %LOCALAPPDATA%\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver
#   silent_install.bat
python tools/emu_start.py --accel auto
```

`emu_start.py` 会：脱离进程组拉起模拟器 → 轮询到 `sys.boot_completed=1` → 打印耗时。
日志写 `emu.log`。启动完成后：

```bash
ADB=%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe
"$ADB" install -r agent/build/outputs/apk/debug/agent-debug.apk
"$ADB" shell am start -n com.agentruntime.agent/.ui.MainActivity
"$ADB" exec-out screencap -p > shot.png
```

## 本机现在的替代验证

没法出像素图，但这一轮把"能不能正确画出来"拆成了两层验证：

| 层 | 手段 | 能证明 |
|---|---|---|
| 布局结构 | Robolectric `UiLayoutTest`（7 项） | 四种机型尺寸下都能 inflate 并完成测量；横屏真的走 `layout-land` 两栏；底栏不被挤出屏幕；无横向溢出；深色配色解析正确 |
| 像素外观 | ❌ 两台都堵死 | 模拟器要 SVM；Robolectric NATIVE 图形**没有 Windows 版运行时**（`resource native/windows/x86_64/robolectric-nativeruntime.dll not found`） |

所以「字体渲染、GPU 合成、刘海与手势条避让」仍然是**未验证**，不能因为结构测试绿了就当成真机可用。
