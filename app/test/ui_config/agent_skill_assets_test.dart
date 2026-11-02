// 守住 dsh-go-agent 技能包的资产一致性。
//
// 背景：这个技能包曾经因为 manifest 的 key（扁平名 `capabilities__foo.md`）
// 和 APK 里的真实资产名（嵌套路径 `capabilities/foo.md`）对不上，加载时抛
// Unable to load asset，被上层的 catch 静默吞掉——技能从来没被写进 rootfs，
// 而当时所有测试都是绿的。这个文件专门盯这条链：
//
//   1. manifest 里每个 key，在 bundle 里都得有对应资产（按 key 直接找）
//   2. bundle 里的每个文件，都要写进目标路径
//   3. 进主提示词的两层，不许留 `{{...}}` 占位符
//   4. 进主提示词的两层，不许出现本机没有的工具名

import 'dart:convert';

import 'package:dsh_app/ui_config/agent_prompt.dart';
import 'package:dsh_app/ui_config/ui_skill.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_test/flutter_test.dart';

/// 本机真正暴露给模型的手机工具（见 PhoneToolCatalog.kt，共 14 个）。
const _dshGoPhoneTools = {
  'apps',
  'open',
  'intent',
  'notify',
  'clipboard',
  'screen',
  'collect',
  'tap',
  'type',
  'scroll',
  'key',
  'tap_xy',
  'handoff',
  'batch',
};

/// Mika 的工具名：出现在主提示词里会教模型调不存在的东西。
///
/// `mcp__phone__tap` 是本机合法写法，所以匹配 `phone_` 时要排除前面紧跟 `__` 的情况。
final _mikaOnlyTools = RegExp(
  r'(?<![_a-z])(phone_[a-z_]+|web_[a-z_]+|do_steps|hand_to_user|handle_obstacle|end_not_done)\b',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, String> manifest;

  setUpAll(() async {
    final raw = await rootBundle.loadString('assets/skills/$agentSkillName/manifest.json');
    final decoded = jsonDecode(raw) as Map<String, dynamic>;
    manifest = (decoded['files'] as Map).cast<String, String>();
  });

  group('manifest 与真实资产必须一一对上', () {
    test('manifest 不为空', () {
      expect(manifest, isNotEmpty);
    });

    test('每个 key 都能按原样加载（这就是当初的 bug）', () async {
      final missing = <String>[];
      for (final key in manifest.keys) {
        try {
          await rootBundle.loadString('assets/skills/$agentSkillName/$key');
        } catch (_) {
          missing.add(key);
        }
      }
      expect(missing, isEmpty, reason: '这些 key 在 APK 里不存在：$missing');
    });

    test('key 与目标路径用的是同一种写法，没有 __ 扁平名', () {
      final flattened = manifest.keys.where((k) => k.contains('__')).toList();
      expect(flattened, isEmpty, reason: '扁平名会和真实资产对不上：$flattened');
    });

    test('整包能被 loadUiSkillBundle 读完', () async {
      final bundle = await loadUiSkillBundle(null, agentSkillName);
      expect(bundle.skillMd, isNotEmpty);
      expect(bundle.files, isNotEmpty);
      // 写盘顺序里 SKILL.md 必须最后
      expect(bundle.writeOrder.last.key, 'SKILL.md');
    });

    test('SKILL.md 带托管标记（App 才能持续更新它）', () async {
      final bundle = await loadUiSkillBundle(null, agentSkillName);
      expect(isManagedUiSkill(bundle.skillMd), isTrue);
    });
  });

  group('进主提示词的两层必须干净', () {
    late Map<String, String> files;
    late Set<String> systemLayerPaths;

    setUpAll(() async {
      final bundle = await loadUiSkillBundle(null, agentSkillName);
      files = bundle.files;
      systemLayerPaths = files.keys
          .where((p) => p.startsWith('system/') || p.startsWith('capabilities/'))
          .toSet();
    });

    test('system/ 与 capabilities/ 都有文件', () {
      expect(systemLayerPaths.where((p) => p.startsWith('system/')), isNotEmpty);
      expect(systemLayerPaths.where((p) => p.startsWith('capabilities/')), isNotEmpty);
    });

    test('这两层不含未渲染的 {{...}} 占位符', () {
      final offenders = <String>[];
      for (final path in systemLayerPaths) {
        final left = agentPromptMissingPlaceholders(files[path]!);
        if (left.isNotEmpty) offenders.add('$path -> $left');
      }
      expect(offenders, isEmpty, reason: '模板字面量不能给模型看：$offenders');
    });

    test('这两层不引用本机没有的 Mika 工具名', () {
      final offenders = <String>[];
      for (final path in systemLayerPaths) {
        final hits = _mikaOnlyTools.allMatches(files[path]!).map((m) => m.group(0)!).toSet();
        if (hits.isNotEmpty) offenders.add('$path -> $hits');
      }
      expect(offenders, isEmpty, reason: '会教模型调不存在的工具：$offenders');
    });

    test('拼出来的主提示词里不含 tools/roles/feedback 的内容', () {
      final prompt = assembleSystemPrompt(
        files: files,
        offered: _dshGoPhoneTools,
        capabilities: const {'phone', 'general', 'sandbox', 'app-access', 'inline-screen'},
      );
      expect(prompt, isNotEmpty);
      // feedback 的模板键不会出现在主提示词里
      expect(prompt, isNot(contains('## repeated_action_advice')));
    });
  });

  group('feedback 模板能被解析', () {
    test('feedback/*.md 按 ## 分节', () async {
      final bundle = await loadUiSkillBundle(null, agentSkillName);
      final feedbackPaths = bundle.files.keys.where((p) => p.startsWith('feedback/'));
      expect(feedbackPaths, isNotEmpty);
      var total = 0;
      for (final path in feedbackPaths) {
        total += parseFeedbackTemplates(bundle.files[path]!).length;
      }
      expect(total, greaterThan(0), reason: '错误模板字典应该有可用的 key');
    });
  });
}
