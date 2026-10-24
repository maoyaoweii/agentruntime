#!/usr/bin/env python3
"""One scoped re-enable of the existing sole AgentRuntime accessibility service."""
import json
import pathlib
import subprocess
import sys
import time

out = pathlib.Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=False)
a = ['/workspace/android-sdk/platform-tools/adb', '-s', 'emulator-5554']
target = 'com.agentruntime.agent/com.agentruntime.service.AgentAccessibilityService'
r = {'api_requests_initiated': 0, 'reboots_initiated': 0, 'events': [], 'bound': False}

def run(args):
    x = subprocess.run(a + args, capture_output=True, text=True, timeout=15)
    v = (x.stdout + x.stderr).strip()
    r['events'].append({'arguments': args, 'returncode': x.returncode, 'output': v})
    assert x.returncode == 0, 'ADB command failed'
    return v

try:
    assert run(['get-state']) == 'device', 'AVD not online'
    before = run(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'])
    flag = run(['shell', 'settings', 'get', 'secure', 'accessibility_enabled'])
    assert before == target, 'Refuse to affect other services or unexpected configuration'
    r['before_enabled_services'] = before
    r['before_accessibility_enabled'] = flag
    # Toggle only the existing sole component. Global activation enables that component only.
    run(['shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', 'null'])
    time.sleep(1)
    run(['shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', target])
    run(['shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1'])
    deadline = time.monotonic() + 60
    while True:
        text = run(['shell', 'dumpsys', 'accessibility'])
        (out / 'accessibility-latest.txt').write_text(text + '\n')
        line = next((x.strip() for x in text.splitlines() if 'Bound services:' in x), '')
        if 'AgentAccessibilityService' in line:
            r['bound'] = True
            r['status'] = 'BOUND'
            break
        if time.monotonic() >= deadline:
            r['status'] = 'STOPPED_NOT_BOUND'
            break
        print('Waiting for only AgentRuntime binding', flush=True)
        time.sleep(5)
    r['after_enabled_services'] = run(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'])
    r['after_accessibility_enabled'] = run(['shell', 'settings', 'get', 'secure', 'accessibility_enabled'])
    assert r['after_enabled_services'] == target, 'Unexpected enabled service list'
    print(r['status'], flush=True)
except Exception as e:
    r['status'] = 'STOPPED'
    r['error'] = str(e)
    print('STOPPED:', str(e), flush=True)
finally:
    (out / 'binding-result.json').write_text(json.dumps(r, ensure_ascii=False, indent=2) + '\n')
