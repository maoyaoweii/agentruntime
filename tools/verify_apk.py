"""APK 产物核验：解包检查类、技能、Manifest 声明。

不依赖 aapt / dexdump（本机未必装），直接读 zip 与 dex 二进制里的字符串表。
"""
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APKS = {
    'agent': os.path.join(ROOT, 'agent/build/outputs/apk/debug/agent-debug.apk'),
    'agenttestapp': os.path.join(ROOT, 'agenttestapp/build/outputs/apk/debug/agenttestapp-debug.apk'),
}

EXPECTED_CLASSES = [
    # V1.1 引擎层
    'com/agentruntime/env/EnvironmentProbe',
    'com/agentruntime/env/EnvironmentSnapshot',
    'com/agentruntime/prompt/PromptBuilder',
    'com/agentruntime/prompt/PromptGuard',
    'com/agentruntime/prompt/PromptLibrary',
    'com/agentruntime/local/LocalModelRegistry',
    'com/agentruntime/local/HttpLocalModelProvider',
    'com/agentruntime/local/OnnxLocalModelProvider',
    'com/agentruntime/local/LocalModelConfig',
    'com/agentruntime/vision/ScreenCapturer',
    'com/agentruntime/vision/GestureBridge',
    'com/agentruntime/vision/CoordinateMapper',
    'com/agentruntime/vision/VisualGrounder',
    'com/agentruntime/vision/ScreenFrame',
    'com/agentruntime/web/BrowserBridge',
    'com/agentruntime/web/BrowserActivity',
    'com/agentruntime/web/BrowserController',
    'com/agentruntime/policy/SafetyPolicy',
    'com/agentruntime/planner/LocalPlanAdapter',
    'com/agentruntime/executor/ExtendedCapabilities',
    # V1.2 界面与唤起层
    'com/agentruntime/ui/MainActivity',
    'com/agentruntime/ui/ConsoleActivity',
    'com/agentruntime/ui/SettingsActivity',
    'com/agentruntime/ui/SkillsActivity',
    'com/agentruntime/ui/UiKit',
    'com/agentruntime/runtime/TaskBus',
    'com/agentruntime/overlay/OverlayService',
    'com/agentruntime/quick/AgentTileService',
    'com/agentruntime/notify/AgentNotifier',
    # V1.3 内置模型 / 输出格式 / 系统工具 / 输入法
    'com/agentruntime/intent/IntentModel',
    'com/agentruntime/intent/IntentPlanner',
    'com/agentruntime/dsl/OutputFormat',
    'com/agentruntime/device/SystemBridge',
    'com/agentruntime/ime/AgentImeService',
    # V1.4 端侧视觉模型
    'com/agentruntime/vision/vlm/VlmTokenizer',
    'com/agentruntime/vision/vlm/VlmPreprocessor',
    'com/agentruntime/vision/vlm/VlmEngine',
    'com/agentruntime/vision/vlm/SmolVlmStore',
    # V1.5 System-1 / Jev-like 决策器
    'com/agentruntime/system1/JevFeatures',
    'com/agentruntime/system1/JevDecider',
    # V1.6 厂商适配 + Codex 式界面
    'com/agentruntime/device/RomInfo',
    'com/agentruntime/ui/CodexTranscript',
]

# V1.6：两个 ABI 都必须带 native 库，否则"能在模拟器上装、一加载就崩"
EXPECTED_ABIS = ['arm64-v8a', 'x86_64']

NEW_ACTIONS = ['tap_xy', 'swipe_xy', 'long_press_xy', 'screenshot',
               'visual_tap', 'read_page', 'navigate', 'page_click',
               'page_find', 'observe',
               'set_volume', 'get_volume', 'set_brightness',
               'clipboard_set', 'clipboard_get', 'media_key', 'vibrate',
               'open_settings_page', 'list_apps', 'read_file', 'write_file',
               'ime_commit', 'ime_read', 'ime_key']

# V1.5：assets 下的内置模型文件，必须在 APK 里真实存在
MODEL_ASSETS = ['jev_decider.json', 'intent_nb.json']

MANIFEST_CHECKS = {
    'MainActivity': 'com.agentruntime.ui.MainActivity',
    'ConsoleActivity': 'com.agentruntime.ui.ConsoleActivity',
    'SettingsActivity': 'com.agentruntime.ui.SettingsActivity',
    'SkillsActivity': 'com.agentruntime.ui.SkillsActivity',
    'BrowserActivity': 'com.agentruntime.web.BrowserActivity',
    'OverlayService': 'com.agentruntime.overlay.OverlayService',
    'TileService': 'com.agentruntime.quick.AgentTileService',
    'ImeService': 'com.agentruntime.ime.AgentImeService',
    'A11yService': 'com.agentruntime.service.AgentAccessibilityService',
    'QS_TILE action': 'android.service.quicksettings.action.QS_TILE',
    'InputMethod action': 'android.view.InputMethod',
    'shortcuts meta': 'android.app.shortcuts',
    'ACCESS_NETWORK_STATE': 'ACCESS_NETWORK_STATE',
    'SYSTEM_ALERT_WINDOW': 'SYSTEM_ALERT_WINDOW',
    'FOREGROUND_SERVICE_SPECIAL_USE': 'FOREGROUND_SERVICE_SPECIAL_USE',
    'WRITE_SETTINGS': 'android.permission.WRITE_SETTINGS',
    'VIBRATE': 'android.permission.VIBRATE',
    'foregroundServiceType attr': 'foregroundServiceType',
}


def contains_str(blob, s):
    """AXML 字符串池可能是 UTF-8 或 UTF-16LE，两种编码都要试。"""
    return s.encode('utf-8') in blob or s.encode('utf-16-le') in blob


def inspect(name, path):
    print('=' * 72)
    print(f'APK: {name}  ({os.path.getsize(path) / 1024:.1f} KB)')
    out = {'apk': name, 'size_kb': round(os.path.getsize(path) / 1024, 1)}
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        dex = [n for n in names if n.endswith('.dex')]
        out['dex_files'] = dex
        skills = sorted(n for n in names if n.startswith('assets/skills/'))
        out['skill_files'] = len(skills)
        print(f'  dex: {len(dex)} 个 -> {dex}')
        print(f'  assets/skills: {len(skills)} 个')

        blob = b''
        for d in dex:
            blob += z.read(d)

        if name == 'agent':
            abis = sorted({n.split('/')[1] for n in names
                           if n.startswith('lib/') and n.count('/') >= 2})
            out['abis'] = abis
            print(f'  ABI: {abis or "无 native 库"}')
            miss_abi = [a for a in EXPECTED_ABIS if a not in abis]
            if miss_abi:
                print(f'  !! 缺少 ABI: {miss_abi}')

            found, missing = [], []
            for c in EXPECTED_CLASSES:
                (found if c.encode() in blob else missing).append(c)
            out['classes_found'] = len(found)
            out['classes_missing'] = missing
            print(f'  关键类存在: {len(found)}/{len(EXPECTED_CLASSES)}')
            if missing:
                print(f'  !! 缺失: {missing}')

            acts = [a for a in NEW_ACTIONS if a.encode() in blob]
            out['new_actions_in_dex'] = acts
            print(f'  新动作 wire name 命中: {len(acts)}/{len(NEW_ACTIONS)}  {acts}')

            ids = []
            for s in skills:
                try:
                    ids.append(json.loads(z.read(s).decode('utf-8')).get('skill_id'))
                except Exception as e:
                    print(f'  !! 解析失败 {s}: {e}')
            out['skill_ids'] = ids
            print(f'  skill_id ({len(ids)}): {ids}')

            # 内置模型必须真的在包里 —— 缺失时 JevDecider.fromAssets 返回 null，
            # 运行期静默回退到规则实现，界面一切正常但决策器根本没生效，极难发现。
            out['models'] = {}
            for m in MODEL_ASSETS:
                key = 'assets/models/' + m
                if key not in names:
                    out['models'][m] = 'MISSING'
                    print(f'  !! assets 缺失: {key}')
                    continue
                raw = z.read(key)
                try:
                    obj = json.loads(raw.decode('utf-8'))
                    out['models'][m] = {'bytes': len(raw), 'version': obj.get('version')}
                    if m == 'jev_decider.json':
                        w = obj.get('weights') or []
                        out['models'][m]['weights'] = len(w)
                        print(f'  assets/models/{m}: {len(raw)}B, weights={len(w)}, '
                              f'train_acc={obj.get("train_accuracy")}')
                    else:
                        print(f'  assets/models/{m}: {len(raw)}B')
                except Exception as e:
                    out['models'][m] = f'PARSE_FAIL {e}'
                    print(f'  !! 解析失败 {key}: {e}')

            # 资源条目数量（APK 里 res/ 下是二进制 XML，只数个数）
            res = [n for n in names if n.startswith('res/')]
            out['res_entries'] = len(res)
            print(f'  res 条目: {len(res)}')

        mani = z.read('AndroidManifest.xml') if 'AndroidManifest.xml' in names else b''
        if name == 'agent':
            checks = MANIFEST_CHECKS
        else:
            checks = {'TestApp': 'com.agenttestapp.TestApp'}
        out['manifest'] = {}
        for label, probe in checks.items():
            mark = 'OK' if contains_str(mani, probe) else 'MISSING'
            print(f'  manifest {label}: {mark}')
            out['manifest'][label] = mark
    return out


def main():
    results = []
    for name, path in APKS.items():
        if not os.path.exists(path):
            print(f'MISSING APK: {path}')
            sys.exit(2)
        results.append(inspect(name, path))

    out_path = os.path.join(ROOT, 'test-results/apk_verify.json')
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print('=' * 72)
    print('written', out_path)

    agent = results[0]
    mani_missing = [k for k, v in agent['manifest'].items() if v != 'OK']
    ok = (not agent['classes_missing']
          and agent['skill_files'] >= 23
          and len(agent['new_actions_in_dex']) == len(NEW_ACTIONS)
          and all(abi in agent['abis'] for abi in EXPECTED_ABIS)
          and all(isinstance(agent['models'].get(m), dict) for m in MODEL_ASSETS)
          and not mani_missing)
    if mani_missing:
        print('MANIFEST MISSING:', mani_missing)
    print('VERDICT:', 'PASS' if ok else 'FAIL')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
