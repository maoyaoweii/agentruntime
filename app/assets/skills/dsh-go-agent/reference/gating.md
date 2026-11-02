# 提示词门控机制

源自 Mika `core/src/main/kotlin/com/wangzan101/mika/core/capability/Capability.kt` 的 `PromptEnv`。

## 数据结构

```kotlin
class PromptEnv(
    val offered: Set<String>,      // 本次真实提供给模型的工具名
    val inlineScreen: Boolean,     // 是否内联屏幕
    val screens: Int = 1,          // 本次提供给模型的工具里有几种屏幕
    val open: Set<String>? = null, // 展开了的能力；null = 全部展开
    val openScreens: Int = screens,
) {
    fun isOpen(id: String): Boolean = open == null || id in open
    fun gate(text: String): String = ...
}
```

## 门控语法

行首（允许前置空白）写：

```
<!-- needs: 工具名1, 工具名2 -->
```

判定逻辑：

1. 用正则 `^(\s*)<!-- needs: ([a-z0-9_, ]+) -->\s?(.*)$` 匹配整行。
2. 取出工具名列表，逐个检查是否都在 `offered` 里。
3. **全部命中** → 保留，去掉标记、保留缩进和正文。
4. **任一缺失** → 整行丢弃。
5. **没有标记的行** → 原样保留。

## 拼装顺序

```
1. system/identity.md          （恒定）
2. system/principles.md        （恒定）
3. 对每个 open 的 capability： capabilities/<id>.md 经过 gate()
4. 对每个 offered 的工具：     tools/<name>.md
5. entries/<name>.md           仅当是入口类工具
6. 子任务时：                  roles/<role>.md
7. 运行循环时：                feedback/loop.md, feedback/tools.md
```

## 参考实现（Dart）

如果要在 DshGo 侧实现同等能力：

```dart
final _needs = RegExp(r'^(\s*)<!-- needs: ([a-z0-9_, ]+) -->\s?(.*)$');

String gate(String text, Set<String> offered) {
  return text.split('\n').map((line) {
    final m = _needs.firstMatch(line);
    if (m == null) return line;
    final tools = m.group(2)!.split(',').map((e) => e.trim())
        .where((e) => e.isNotEmpty);
    if (tools.every(offered.contains)) return '${m.group(1)}${m.group(3)}';
    return null;
  }).whereType<String>().join('\n');
}
```

## 为什么这样做

- **不教模型用没有的工具**。工具少时提示词自动变短，减少幻觉调用。
- **一份源文件适配多档能力**。免费版、完整版、远程主机用同一套 `capabilities/`，靠门控区分。
- **提示词可测**。给定工具集，拼装结果是确定的，可以写单测。
