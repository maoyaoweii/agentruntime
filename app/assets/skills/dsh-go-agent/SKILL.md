---
# dsh-go:managed — the dsh-go app keeps this skill up to date; delete this line to keep your own edits.
name: dsh-go-agent
description: 手机智能体的操作准则（移植自 Mika 架构，Apache-2.0）。当智能体要真正在手机上把事办完时加载：点按、滑动、读屏、翻长列表、填写表单、跨应用连续操作。它讲的是「怎么把事做成」——先看清现场、再动手、做完必须确认、连续没进展就换方法。Operational doctrine for an on-device mobile agent: observe before acting, verify after, change approach when stuck.
whenToUse: 智能体要在手机上实际完成一件事（点按、输入、滑动、读屏、跨应用流程），或需要手机操控的行为纪律时。只回答 App 自身用法用 dsh-go-guide；改界面用 dsh-go-ui；写插件用 dsh-go-plugin。
---

# 手机智能体的操作准则

移植自 [Mika](https://github.com/wangzan101/Mika)（Apache-2.0），并按 **dsh-go 的真实工具集**做了适配。

## 装什么、不装什么

Mika 的提示词不是「一堆说明」，而是**用途完全不同的几类资源**。混装会出问题：

| 层 | 用途 | 进哪 |
|---|---|---|
| `system/` | 主 Agent 的身份与总原则 | **主 System Prompt** |
| `capabilities/` | 某类能力怎么用（本机已适配） | **主 System Prompt** |
| `tools/` `entries/` | 单个工具的 description 模板 | 工具定义，**不进**提示词 |
| `roles/` | 子任务（校验器、意图门）的提示词 | 只给那个子任务 |
| `feedback/` | 运行时错误模板字典（按 key 取） | 报错时注入 |
| `reference/` | Mika 原版留档、设计与移植说明 | **不加载** |

只有前两层进主提示词。其余各层各有归宿，硬塞进 System Prompt 会让模型角色混乱，
还会把 197 处 `{{...}}` 模板占位符暴露给模型。

## 主提示词怎么拼

`assembleSystemPrompt()`（`lib/ui_config/agent_prompt.dart`）：

1. `system/identity.md` → `system/principles.md`
2. 展开的 `capabilities/*.md`
3. 每段先过门控，再渲染占位符；渲染后仍有残留的段落**丢弃并记录**

不存在的资产直接跳过，缺文件不会让拼装失败。

## 门控机制

正文里的这一行是门控：

```
<!-- needs: <工具名>, <工具名> -->
```

列出的工具**全部**在本次工具列表里，这一行才留下（并去掉标记）；否则整行删。
这样同一份提示词能适配能力不同的设备，工具少时不会教模型去调它没有的工具。

`capabilities/` 层当前没有门控行（本机工具集固定），机制保留给以后用。

## 本机适配的关键改动

原版 Mika 面向 `phone_observe` / `phone_tap(@eN)` / 0–999 归一化网格；
**dsh-go 实际只有 14 个固定 `mcp__phone__*` 工具，坐标是真实像素**。
`capabilities/phone.md` 已按本机重写，原版留在 `reference/mika-phone-original.md`。

同理已移出主提示词层的：`web.md`（本机无 `web_*` 工具）、`batch.md`（无 `do_steps`）、
`channels.md` / `general.md` 原版（无 `end_not_done`，已改写）、`groups.md`、`handover.md`。

## 为什么工具集是固定的

`PhoneToolCatalog.kt` 明确保持 14 个工具不随权限增减：工具名、描述、schema 每轮都进
请求，任何变化都会让 DeepSeek 的 KV cache 从第一个变化的 token 起失效。所以本技能
**不会**动态增删工具，只做提示词层的裁剪。

## 目录

- `system/` — 身份与总原则（进提示词）
- `capabilities/` — 按能力分组的操作说明（进提示词，已适配本机）
- `tools/` `entries/` — 逐工具模板（给工具定义用）
- `roles/` — 子任务角色提示词
- `feedback/` — 错误模板字典，`parseFeedbackTemplates()` 按 `## key` 解析
- `reference/` — 原版留档、门控规则、移植说明
