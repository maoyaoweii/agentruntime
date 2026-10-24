# 本地合成测试页：受限调试入口

此入口只用于 Android 11 / API 30 / x86_64 AVD 内置的合成 HTML 页。所有实现均在 `agent/src/debug`，release 包不包含组件或实现类。没有连接模型，不需要 API 密钥；不得将旧密钥或新密钥传入此入口。

**本次验收状态：尚未完成设备端正向验收。** 157 项单元测试（新增 9 项）及实际 debug/release APK 隔离检查通过；两次设备测试均在首次合成页 `read` 时超过主机侧 15 秒超时，未得到成功读取/点击证据，也未执行到 TTL 设备测试。第二次已将新增无障碍查询移出 UI 线程，但仍超时。诊断时发现系统当前活动窗口为 “Process system isn't responding”；它是已观察到的环境阻塞，但不能据此证明所有超时都由它造成。没有点击系统弹窗、重启/清除模拟器或放宽安全限制。以下步骤是复现与后续验收流程，不代表正向功能已经验证成功。

## 能做什么

只有四个固定方法：`open` 打开内置页面，`read` 返回该页面当前可见的无障碍文本，`click` 对唯一固定链接执行一次 `ACTION_CLICK`，`close` 关闭该测试页。链接只指向同页 `https://synthetic.invalid/fixture#allowed`；这个地址只是内存 HTML 的标识，不是真正要访问的网站。`read` 的来源是现有 AccessibilityService 的当前活动窗口，而非 DOM、截图 OCR 或模型。

入口不接受网址、HTML、选择器、坐标、目标包名、技能或任务；未知方法、额外参数、错误会话都会拒绝。它不是远程控制端口，也不启动 HTTP/TCP 服务。

## 安全边界

- 只有 ADB shell UID 2000 能调用，还要求 `DUMP` 权限、debug 构建及运行态 debuggable 标记、API 30、主 ABI x86_64、ranchu/goldfish 模拟器硬件。root、系统 UID、应用自身 UID、其他应用 UID 均不获授权。模拟器属性只是额外范围检查，不能替代 UID 和构建隔离。
- 新 Activity 不导出；Provider 仅在 debug manifest 注册，无 URI 授权，无 CRUD/文件接口。不复用通用 TaskRunner、BrowserBridge、执行器或任意点击接口。原有防护、正式源集及已有入口不变。
- 每次打开生成 256 位随机会话，120 秒有效；失焦到后台/暂停、销毁或关闭会撤销，会话超时自动关闭固定页面。每会话至多提交一次链接点击，失败不自动重试；读取可在有效会话内重复。关闭后旧会话不可重用。
- 读取/点击要求专用 Activity 前台且有窗口焦点、固定页已就绪、精确 URL、当前无障碍根包名、相同 windowId、固定页面标记。只输出 WebView 子树内可见文本；树深、节点数及输出长度均有上限，超限拒绝。
- WebView 仅加载编译期内置 HTML，JavaScript、DOM storage、数据库、file/content 访问、网络加载、网络图片均关闭，所有资源请求返回空响应，禁止第三方 Cookie、缓存复用和新窗口。只有精确固定页/固定 fragment 可以导航；无外部 intent、JS 桥、祖先节点兜底、坐标点击或全局系统动作。
- Provider 的 shell 权限是本地调试边界，不防御已被攻陷的 ADB shell、root 主机或篡改 APK。新功能不对项目原有广泛调试功能作额外安全承诺。

## 前置条件

在项目根目录运行。已有 AVD 必须是 API 30 / x86_64，且原有无障碍服务已启用并绑定。不要为了测试修改其他权限、账户、消息、支付或无关设置。缺少绑定时停止并报告，不由此流程自动修改系统设置。

```bash
cd /workspace/agentruntime
./build.sh --offline :agent:testDebugUnitTest :agent:assembleDebug :agent:assembleRelease
A=/workspace/android-sdk/platform-tools/adb
$A -s emulator-5554 shell getprop ro.build.version.sdk
$A -s emulator-5554 shell getprop ro.product.cpu.abi
$A -s emulator-5554 shell getprop ro.hardware
$A -s emulator-5554 shell settings get secure enabled_accessibility_services
# 应为 30 / x86_64 / ranchu（或 goldfish），绑定已存在。
$A -s emulator-5554 install -r agent/build/outputs/apk/debug/agent-debug.apk
```

安装只覆盖现有调试 APK，不卸载、不清除数据；原始 `apk/` 文件和上传 ZIP 不覆盖。

## 手动验证

```bash
URI=content://com.agentruntime.agent.localfixture
$A -s emulator-5554 shell content call --uri "$URI" --method open
```

从返回的 `session=` 复制 64 位十六进制值到本地变量 `SESSION`。它不是模型 API 密钥；不要放入公开日志。等待页面出现，再于两分钟内执行：

```bash
SESSION=从本次open返回值复制
$A -s emulator-5554 shell content call --uri "$URI" --method read --arg "$SESSION"
$A -s emulator-5554 shell content call --uri "$URI" --method click --arg "$SESSION"
sleep 1
$A -s emulator-5554 shell content call --uri "$URI" --method read --arg "$SESSION"
```

期望首次 `read` 包含 `CUE_LOCAL_SYNTHETIC_FIXTURE_V1`、固定文本和 `source=AccessibilityService.getRootInActiveWindow`；点击回执 `clicked=true`；再次读取的 `fixture_url` 为固定 `#allowed` 地址。`clicked=true` 仅表示无障碍动作提交成功，导航是否完成以随后的 `read` 为准，不把提交回执误当成导航完成。

以下应全部拒绝且不返回页面文本/点击成功；这些请求不会打开外部网页：

```bash
$A -s emulator-5554 shell content call --uri "$URI" --method tap --arg "$SESSION"
$A -s emulator-5554 shell content call --uri "$URI" --method click --arg "$SESSION" --extra url:s:https://example.com
$A -s emulator-5554 shell content call --uri "$URI" --method click --arg "$SESSION" --extra x:i:10
$A -s emulator-5554 shell content call --uri "$URI" --method click --arg "$SESSION"  # 第二次点击应拒绝
$A -s emulator-5554 shell run-as com.agentruntime.agent content call --uri "$URI" --method open
$A -s emulator-5554 shell content call --uri "$URI" --method close --arg "$SESSION"
$A -s emulator-5554 shell content call --uri "$URI" --method read --arg "$SESSION"  # 已关闭应拒绝
unset SESSION
```

Android 的 `content` 命令可能在 Provider 拒绝时仍退出 0，因此不能只看 shell 退出码：必须检查返回内容中的拒绝异常及没有 `text_json`/`clicked=true`。若页面未就绪，稍等后仅重试 `read`；不要重复 `click`。若会话已过期，页面会自动关闭，重新 `open`，不能复用旧 session。

## 自动验证

证据目录参数必须使用尚不存在的子目录；脚本不覆盖旧证据。

```bash
python3 tools/verify_local_fixture_release.py --evidence /workspace/agentruntime-evidence/新的验证目录/release
python3 tools/test_local_fixture.py --evidence /workspace/agentruntime-evidence/新的验证目录/device --expiry-test
```

发布隔离脚本同时检查 merged manifest、APK 内实际 manifest 及 DEX：debug 有受权限保护的组件；release 无 Provider、Activity、Policy 和固定页面标记，已有 TaskExecActivity 仍不导出。设备脚本只对合成页操作，覆盖文本来源、固定链接实际导航、拒绝任意控制参数、错误会话、普通应用 UID、重复点击、关闭后重放及真实 120 秒 TTL。证据不保存 session 原值。

## 限制

此版本不连接已有外部浏览器或任意 localhost HTTP 页面，不能读取任意网页，也不能让调用者指定另一个白名单链接。要换页面/链接必须修改 debug 编译期常量、重建并重新验收。普通桌面本地进程不能直接调用，必须经已授权 ADB shell。release 包只做静态构建/包内验证，不安装到现有 AVD，以免更换安装签名、清数据或破坏现有绑定。

窗口焦点检查与节点刷新采用失败即拒绝的尽力抗竞态策略；Android 不提供“确认活动窗口并点击”的跨窗口原子事务。这里额外依赖专用不可导出 Activity、不可变 HTML、固定同页链接、同一无障碍树及无坐标兜底来收窄风险。此能力不能推广到不可信网页或第三方应用。

本地测试不等于密钥已经轮换。此前暴露的 Spark 密钥仍须由用户在服务商后台撤销并轮换；本任务不读取、使用、保存或发送任何 API token，不发模型请求。
