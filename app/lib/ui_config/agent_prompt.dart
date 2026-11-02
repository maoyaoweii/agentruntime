// 手机智能体的分层提示词拼装（移植自 Mika 的 PromptEnv，Apache-2.0）。
//
// Mika 的提示词不是「一堆说明」，而是**五类用途完全不同的资源**，混在一起会
// 出问题（详见本目录 reference/adaptation.md）：
//
//   system/       主 Agent 的身份与总原则     → 进 System Prompt
//   capabilities/ 某类能力怎么用               → 进 System Prompt
//   tools/        单个工具的 description 模板   → 进工具定义，不进 System Prompt
//   entries/      入口工具的 description 模板   → 同上
//   roles/        子任务（校验器/意图门）的提示词 → 只给那个子任务，不进主提示词
//   feedback/     运行时错误模板字典            → 出错时按 key 注入，不进提示词
//
// 所以这里只提供两个入口：
//   assembleSystemPrompt()  —— 只拼 system + capabilities（主提示词）
//   toolPromptTemplate()    —— 取单个工具的模板并渲染占位符（给工具定义用）
// 其余层不拼进主提示词。
//
// 裁剪靠正文里的门控行：
//
//   <!-- needs: <工具名> -->
//
// 标记里列出的工具全部在本次工具列表里，这一行才留下（并去掉标记）；
// 否则整行删除。这样同一份提示词能适配能力不同的设备，工具少的时候
// 不会教模型去用一个它没有的工具。
//
// 资产在 assets/skills/dsh-go-agent/，规则说明见该目录的 reference/gating.md。
library;

/// 门控标记的名字，用于识别与剥离。
const agentPromptNeedsMarker = '<!-- needs:';

/// 未被渲染的 `{{名字}}` 占位符。
///
/// Mika 在 Prompts.fill() 里强制校验所有占位符都被替换过：留着 `{{tap}}`
/// 这种东西给模型看，等于教它照着模板字面去执行。
final _placeholder = RegExp(r'\{\{([a-zA-Z0-9_]+)\}\}');

/// 渲染 `{{名字}}` 占位符。
///
/// [values] 里没有的名字保持原样——是否容忍由调用方决定：[fillAgentPrompt]
/// 会做残留校验，[renderAgentPrompt] 则直接返回。
String renderAgentPrompt(String text, Map<String, String> values) {
  return text.replaceAllMapped(_placeholder, (match) {
    final name = match.group(1)!;
    return values[name] ?? match.group(0)!;
  });
}

/// 渲染并校验：渲染后仍有 `{{...}}` 残留就返回 null（调用方应丢弃这段）。
///
/// 返回 null 表示这段模板缺变量，不能直接给模型看。
String? fillAgentPrompt(String text, Map<String, String> values) {
  final rendered = renderAgentPrompt(text, values);
  return _placeholder.hasMatch(rendered) ? null : rendered;
}

/// 这段文本里还有哪些占位符没填（排好序、去重），用于诊断。
List<String> agentPromptMissingPlaceholders(String text) {
  final names = <String>{};
  for (final match in _placeholder.allMatches(text)) {
    names.add(match.group(1)!);
  }
  final list = names.toList()..sort();
  return list;
}

/// 拼装出来的一段，带上它来自哪个文件，便于排查。
class AgentPromptSection {
  const AgentPromptSection(this.path, this.text);

  /// 相对技能目录的路径，例如 `capabilities/phone.md`。
  final String path;

  /// 裁剪后的正文。
  final String text;

  @override
  String toString() => 'AgentPromptSection($path, ${text.length} chars)';
}

/// 解析一行门控标记。不是门控行时返回 null。
///
/// 返回 (缩进, 工具名列表, 正文)。手工解析而不是用正则：这行的语法就是
/// 固定几个记号，手写反而更清楚，也不会被各层转义坑到。
(String, List<String>, String)? parseAgentPromptGate(String line) {
  const marker = '<!-- needs:';
  const closer = '-->';
  final at = line.indexOf(marker);
  if (at < 0) return null;
  final end = line.indexOf(closer, at + marker.length);
  if (end < 0) return null;
  // 标记前只能是空白，否则这行是正文里提到这三个词，不是门控。
  final indent = line.substring(0, at);
  if (indent.trim().isNotEmpty) return null;
  final tools = line
      .substring(at + marker.length, end)
      .split(',')
      .map((name) => name.trim())
      .where((name) => name.isNotEmpty)
      .toList();
  if (tools.isEmpty) return null;
  final body = line.substring(end + closer.length);
  return (indent, tools, body);
}


/// 把 [text] 里不满足门控的行去掉。
///
/// [offered] 是本次真正提供给模型的工具名集合。没有标记的行原样保留；
/// 有标记的行只有在列出的工具全部命中时才保留正文（标记本身剥掉）。
String gateAgentPromptText(String text, Set<String> offered) {
  final kept = <String>[];
  for (final line in text.split('\n')) {
    final parsed = parseAgentPromptGate(line);
    if (parsed == null) {
      kept.add(line);
      continue;
    }
    final (indent, tools, body) = parsed;
    if (!tools.every(offered.contains)) continue;
    final keptLine = '$indent$body';
    if (keptLine.trim().isNotEmpty) kept.add(keptLine);
  }
  return kept.join('\n');
}


/// 这个文件里有没有门控标记（没有的话裁剪是空操作，可以跳过）。
bool hasAgentPromptGate(String text) => text.contains(agentPromptNeedsMarker);

/// 主 System Prompt 能用的层：只有身份原则与能力说明。
///
/// 工具描述（tools/ entries/）走工具定义，子任务提示词（roles/）只给子任务，
/// 错误模板（feedback/）只在该报错时注入——它们都不进这里。
const agentSystemLayers = ['system', 'capabilities'];

/// 拼装主 System Prompt：只取 system/ 与 capabilities/ 两层。
///
/// [files] 的 key 是相对技能目录的路径（如 `capabilities/phone.md`），
/// value 是文件原文。[offered] 是本次真实提供给模型的工具名，
/// 用来判定门控行留不留。
///
/// [capabilities] 指定展开哪些能力（对应 `capabilities/<id>.md`）；
/// 传 null 表示「照着 [files] 里有的全展开」。
///
/// [variables] 用于渲染 `{{名字}}` 占位符。渲染后仍有残留的段落会被丢弃
/// 并记入 [droppedForPlaceholders]——留着模板字面给模型看只会误导它。
String assembleSystemPrompt({
  required Map<String, String> files,
  required Set<String> offered,
  Set<String>? capabilities,
  Map<String, String> variables = const {},
  List<String>? droppedForPlaceholders,
  String separator = '\n\n',
}) {
  final parts = <String>[];

  void add(String path) {
    final text = files[path];
    if (text == null || text.trim().isEmpty) return;
    final gated = gateAgentPromptText(text, offered).trim();
    if (gated.isEmpty) return;
    final filled = fillAgentPrompt(gated, variables);
    if (filled == null) {
      droppedForPlaceholders?.add(path);
      return;
    }
    parts.add(filled.trim());
  }

  add('system/identity.md');
  add('system/principles.md');

  final caps = capabilities ??
      files.keys
          .where((path) => path.startsWith('capabilities/'))
          .map((path) => path.substring('capabilities/'.length).replaceAll('.md', ''))
          .toSet();
  for (final id in (caps.toList()..sort())) {
    add('capabilities/$id.md');
  }

  return parts.join(separator);
}

/// [assembleSystemPrompt] 的分段版本，保留每段来自哪个文件。
List<AgentPromptSection> assembleSystemPromptSections({
  required Map<String, String> files,
  required Set<String> offered,
  Set<String>? capabilities,
  Map<String, String> variables = const {},
  List<String>? droppedForPlaceholders,
}) {
  final out = <AgentPromptSection>[];

  void add(String path) {
    final text = files[path];
    if (text == null || text.trim().isEmpty) return;
    final gated = gateAgentPromptText(text, offered).trim();
    if (gated.isEmpty) return;
    final filled = fillAgentPrompt(gated, variables);
    if (filled == null) {
      droppedForPlaceholders?.add(path);
      return;
    }
    out.add(AgentPromptSection(path, filled.trim()));
  }

  add('system/identity.md');
  add('system/principles.md');

  final caps = capabilities ??
      files.keys
          .where((path) => path.startsWith('capabilities/'))
          .map((path) => path.substring('capabilities/'.length).replaceAll('.md', ''))
          .toSet();
  for (final id in (caps.toList()..sort())) {
    add('capabilities/$id.md');
  }

  return out;
}

/// 取一个工具的 description 模板并渲染。
///
/// 工具模板在 `tools/<name>.md` 与 `entries/<name>.md`（入口工具）。返回
/// null 表示没有这个模板、或渲染后仍有未填的占位符（此时宁可退回工具自己的
/// 代码内描述，也不要给模型看 `{{observe}}` 这种字面量）。
String? toolPromptTemplate({
  required Map<String, String> files,
  required String name,
  Map<String, String> variables = const {},
}) {
  final raw = files['tools/$name.md'] ?? files['entries/$name.md'];
  if (raw == null || raw.trim().isEmpty) return null;
  return fillAgentPrompt(raw.trim(), variables);
}

/// 取子任务提示词（roles/）。只给那个子任务用，不要拼进主提示词。
String? rolePromptTemplate({
  required Map<String, String> files,
  required String role,
  Map<String, String> variables = const {},
}) {
  final raw = files['roles/$role.md'];
  if (raw == null || raw.trim().isEmpty) return null;
  return fillAgentPrompt(raw.trim(), variables);
}

/// 解析 feedback/ 里的报错模板字典。
///
/// 源文件用 `## key` 分节，每节是一段带 `{{...}}` 占位符的模板。运行时按
/// 命中情形挑一段，填好变量后追加到工具结果末尾。**不要**整段塞进提示词。
Map<String, String> parseFeedbackTemplates(String text) {
  final out = <String, String>{};
  String? current;
  final buffer = <String>[];

  void flush() {
    final key = current;
    if (key == null) return;
    final body = buffer.join('\n').trim();
    if (body.isNotEmpty) out[key] = body;
    buffer.clear();
  }

  for (final line in text.split('\n')) {
    if (line.startsWith('## ')) {
      flush();
      current = line.substring(3).trim();
      continue;
    }
    if (current != null) buffer.add(line);
  }
  flush();
  return out;
}