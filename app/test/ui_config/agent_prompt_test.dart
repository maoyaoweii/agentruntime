import 'package:dsh_app/ui_config/agent_prompt.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('parseAgentPromptGate', () {
    test('returns null for a plain line', () {
      expect(parseAgentPromptGate('plain text'), isNull);
    });

    test('reads tools and body on one line', () {
      final r = parseAgentPromptGate('<!-- needs: a, b -->body');
      expect(r, isNotNull);
      expect(r!.$1, '');
      expect(r.$2, ['a', 'b']);
      expect(r.$3, 'body');
    });

    test('keeps leading indentation separately', () {
      final r = parseAgentPromptGate('   <!-- needs: x -->- item');
      expect(r!.$1, '   ');
      expect(r.$2, ['x']);
      expect(r.$3, '- item');
    });

    test('a line with prose before the marker is not a gate', () {
      expect(parseAgentPromptGate('text <!-- needs: x -->'), isNull);
    });
  });

  group('gateAgentPromptText', () {
    test('no marker keeps every line', () {
      const t = '# title\n\nplain.';
      expect(gateAgentPromptText(t, {}), t);
    });

    test('all tools present keeps the line body', () {
      const t = '<!-- needs: a -->body';
      expect(gateAgentPromptText(t, {'a'}), 'body');
    });

    test('one missing tool drops the line', () {
      const t = '<!-- needs: a, b -->body';
      expect(gateAgentPromptText(t, {'a'}), '');
    });

    test('drops only the failing line among many', () {
      const t = 'head\n<!-- needs: have -->kept\n<!-- needs: lack -->dropped\ntail';
      expect(gateAgentPromptText(t, {'have'}), 'head\nkept\ntail');
    });

    test('empty offered drops every gated line', () {
      // 正文与标记同一行：门控不过时整行一起删。
      const t = 'a\n<!-- needs: x -->b\nplain\n<!-- needs: y -->c';
      expect(gateAgentPromptText(t, {}), 'a\nplain');
    });
  });

  group('placeholder rendering', () {
    test('replaces known names', () {
      expect(
        renderAgentPrompt('call {{tap}} now', {'tap': 'phone_tap'}),
        'call phone_tap now',
      );
    });

    test('leaves unknown names untouched', () {
      expect(renderAgentPrompt('{{a}} and {{b}}', {'a': '1'}), '1 and {{b}}');
    });

    test('fill returns null when something is left unfilled', () {
      expect(fillAgentPrompt('{{a}}', {'a': '1'}), '1');
      expect(fillAgentPrompt('{{a}} {{b}}', {'a': '1'}), isNull);
    });

    test('missing placeholders are reported sorted and unique', () {
      expect(
        agentPromptMissingPlaceholders('{{b}} {{a}} {{b}}'),
        ['a', 'b'],
      );
      expect(agentPromptMissingPlaceholders('none'), isEmpty);
    });
  });

  group('assembleSystemPrompt — 只装 system 与 capabilities', () {
    final files = <String, String>{
      'system/identity.md': 'IDENTITY',
      'system/principles.md': 'PRINCIPLES',
      'capabilities/phone.md': 'PHONE\n<!-- needs: tap -->TAP-ONLY',
      'capabilities/sandbox.md': 'SANDBOX net={{network}}',
      // 下面这些层都不该进主提示词
      'tools/tap.md': 'TOOL-TAP {{observe}}',
      'entries/run_command.md': 'ENTRY-RUN',
      'roles/verifier.md': 'ROLE-VERIFIER',
      'feedback/loop.md': '## repeated_action_advice\nLOOP {{count}}',
    };

    test('system layers come first, in order', () {
      final out = assembleSystemPrompt(files: files, offered: {});
      expect(out, startsWith('IDENTITY'));
      expect(out.indexOf('PRINCIPLES'), greaterThan(out.indexOf('IDENTITY')));
    });

    test('tools, entries, roles and feedback never enter the system prompt', () {
      final out = assembleSystemPrompt(
        files: files,
        offered: {'tap'},
        capabilities: {'phone'},
      );
      expect(out, isNot(contains('TOOL-TAP')));
      expect(out, isNot(contains('ENTRY-RUN')));
      expect(out, isNot(contains('ROLE-VERIFIER')));
      expect(out, isNot(contains('LOOP')));
    });

    test('capability body is gated by offered tools', () {
      final withTap =
          assembleSystemPrompt(files: files, offered: {'tap'}, capabilities: {'phone'});
      expect(withTap, contains('TAP-ONLY'));

      final withoutTap =
          assembleSystemPrompt(files: files, offered: {'other'}, capabilities: {'phone'});
      expect(withoutTap, isNot(contains('TAP-ONLY')));
      expect(withoutTap, contains('PHONE'));
    });

    test('a section whose placeholders cannot be filled is dropped and reported', () {
      final dropped = <String>[];
      final out = assembleSystemPrompt(
        files: files,
        offered: {},
        capabilities: {'sandbox'},
        variables: const {},
        droppedForPlaceholders: dropped,
      );
      expect(out, isNot(contains('{{network}}')));
      expect(out, isNot(contains('SANDBOX')));
      expect(dropped, contains('capabilities/sandbox.md'));
    });

    test('a section is kept once its placeholders are supplied', () {
      final dropped = <String>[];
      final out = assembleSystemPrompt(
        files: files,
        offered: {},
        capabilities: {'sandbox'},
        variables: const {'network': 'available'},
        droppedForPlaceholders: dropped,
      );
      expect(out, contains('SANDBOX net=available'));
      expect(dropped, isEmpty);
    });
  });

  group('toolPromptTemplate', () {
    final files = <String, String>{
      'tools/tap.md': 'TAP {{observe}}',
      'entries/run_command.md': 'RUN',
      'tools/plain.md': 'PLAIN',
    };

    test('renders the tool template with variables', () {
      expect(
        toolPromptTemplate(
          files: files,
          name: 'tap',
          variables: const {'observe': 'phone_observe'},
        ),
        'TAP phone_observe',
      );
    });

    test('returns null instead of leaking an unfilled placeholder', () {
      expect(toolPromptTemplate(files: files, name: 'tap'), isNull);
    });

    test('falls back to entries/ for entry tools', () {
      expect(toolPromptTemplate(files: files, name: 'run_command'), 'RUN');
    });

    test('returns null when there is no template', () {
      expect(toolPromptTemplate(files: files, name: 'absent'), isNull);
    });
  });

  group('rolePromptTemplate', () {
    test('reads roles/ only', () {
      final files = <String, String>{'roles/verifier.md': 'VERIFY {{count}}'};
      expect(
        rolePromptTemplate(files: files, role: 'verifier', variables: const {'count': '3'}),
        'VERIFY 3',
      );
      expect(rolePromptTemplate(files: files, role: 'nope'), isNull);
    });
  });

  group('parseFeedbackTemplates', () {
    test('splits on ## headings and keeps the bodies', () {
      const text = '## first\nline one\n\n## second\nline two {{count}}\n';
      final out = parseFeedbackTemplates(text);
      expect(out.keys, ['first', 'second']);
      expect(out['first'], 'line one');
      expect(out['second'], contains('{{count}}'));
    });

    test('ignores text before the first heading', () {
      expect(parseFeedbackTemplates('preamble\n## a\nbody')['a'], 'body');
    });

    test('returns empty for text without headings', () {
      expect(parseFeedbackTemplates('just prose'), isEmpty);
    });
  });
}
