# 移植说明：Mika → dsh-go

## 最关键的一条：这五层不是同一类东西

初版移植把五层当成「五段提示词」拼进同一个 System Prompt，这是错的。上游 Mika
里它们用途完全不同：

| 层 | 上游真实用途 | 归宿 |
|---|---|---|
| `system/` + `capabilities/` | 主 Agent 的 System Prompt 正文 | **进主提示词** |
| `tools/` + `entries/` | 函数调用 `ToolSpec.description` 模板 | 进**工具定义**，不进提示词 |
| `roles/` | 独立子模型（Verifier / IntentGate / OutcomeJudge / Locator）的 System Prompt | 只给那个**子任务** |
| `feedback/` | 运行时错误与督促消息的 **Key-Value 模板字典**（按 `## key` 解析） | 在**特定异常发生时**注入 Tool Result |
| `reference/` | 原版留档与设计说明 | **不加载** |

混装的后果：主 Agent 同时扮演校验器和执行者（角色混乱），并把 152 处
`{{...}}` 报错模板占位符直接暴露给模型。

## 占位符必须渲染

上游 `Prompts.fill()` 强制校验所有 `{{...}}` 都被替换。初版没有插值逻辑，
21 个文件里的 197 处模板字面量会原样给模型看。

现在：
- `renderAgentPrompt()` 做替换
- `fillAgentPrompt()` 替换后仍有残留就返回 null（**宁可丢掉整段，也不给模型看模板**）
- `agentPromptMissingPlaceholders()` 用于诊断

进主提示词的两层（`system/` + `capabilities/`）现在**零占位符**——不是靠渲染兜底，
而是内容本身就不该有模板。

## 工具名必须换成真能调的那套

上游 Mika 面向 `phone_observe` / `phone_tap(@eN)` / `web_*` / `do_steps` 等 65+ 个工具，
坐标系是 0–999 归一化网格。

dsh-go 实际只有 **14 个固定的 `mcp__phone__*`**（`PhoneToolCatalog.kt`），
坐标是**真实像素**，元素引用是 `screen` 代号 + 整数 `id`。

所以做了这些替换：
- `capabilities/phone.md` — **整篇重写**为本机工具集
- `capabilities/sandbox.md` — 路径改为 `/root/workspace`、`/root/.dsh/skills/`
- `capabilities/general.md` — 去掉不存在的 `end_not_done`，收尾改成「靠回复本身」
- `capabilities/web.md` — 移出（本机无 `web_*` 工具）
- `capabilities/batch.md` — 移出（无 `do_steps` / `phone_run`）
- `capabilities/channels.md` — 移出（无 `end_not_done`）
- `capabilities/groups.md` — 移出（无 Entry 工具分组机制）
- `capabilities/handover.md` — 移出（本机对应工具是 `handoff`，已写进 `phone.md`）

移出的原版都在 `reference/mika-*.md` 留档。

## 工具集为什么不能动态改

`PhoneToolCatalog.kt` 的注释写得很明确：工具名、描述、schema 每轮都进请求，
任何变化都会让 DeepSeek 的 KV cache 从第一个变化的 token 起失效。

所以本技能的定位是**提示词层的裁剪**，不碰工具集。门控（`<!-- needs: -->`）
保留着，但 `capabilities/` 层当前没有使用——本机工具集固定，没有可裁的东西。

## 与上游同步

`tools/`、`roles/`、`feedback/` 三层保持英文原文未改，便于以后 `git diff` 比对上游。
适配只发生在进主提示词的两层。

## 许可

Mika 是 Apache-2.0，dsh-go 是 AGPL-3.0，两者兼容。使用本目录内容时请保留
Mika 的 `LICENSE` 与 `NOTICE` 归属。
