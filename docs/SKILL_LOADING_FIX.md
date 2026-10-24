# Skill 加载修复

## 根因

`SkillManifest.fromJson` 在读取 assets 时直接调用 `DslParser.parseAction`。`web.open_and_read` 的 `navigate` 参数是 `${url}`，它在加载阶段不是最终 URL，因此被公共 DSL 校验当作非 `http/https` URL 拒绝；`SkillEngine` 又吞掉了异常，设备上最终表现为 22 个技能且没有明确错误。

## 修复

- `SkillManifest` 现在保存动作 JSON 模板，不在加载阶段执行最终动作参数校验。
- `SkillEngine.resolve` 才进行变量展开，然后把最终动作交给现有 `DslParser.parseAction`。因此没有放宽公共 DSL 校验，运行时仍严格复用 `SafetyPolicy`：`javascript:`、`file:` 等替换结果继续拒绝。
- 模板变量必须是已声明的 `args`，变量名、缺失值、非标量值和坏模板都会明确失败。
- 内置技能加载失败不再静默：记录文件名和异常类型的摘要并输出安全日志，不输出技能正文或参数；加载继续处理其他技能。

## 回归覆盖

`SkillLoadingRegressionTest` 覆盖：

1. 23 个内置技能全部加载且无加载错误；
2. 合法 HTTPS URL 变量展开并成功解析；
3. 替换后的 `javascript:` / `file:` URL 仍被拒绝；
4. 坏模板和未声明变量明确失败；
5. 模板 URL 在 manifest 加载阶段不会被误判。

按要求本次只做独立 JVM/源码层准备，未运行 Gradle、未使用 ADB 或真实 API。
