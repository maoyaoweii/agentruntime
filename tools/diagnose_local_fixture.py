#!/usr/bin/env python3
"""Fixed local debug fixture diagnostics only. No network, tokens, settings or generic control."""
import argparse
import json
import pathlib
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--evidence', required=True)
args = p.parse_args()
out = pathlib.Path(args.evidence)
out.mkdir(parents=True, exist_ok=False)
adb = ['/workspace/android-sdk/platform-tools/adb', '-s', 'emulator-5554']
uri = 'content://com.agentruntime.agent.localfixture'
result = {'passed': False, 'api_requests_initiated': 0, 'fixed_clicks_initiated': 0, 'events': []}
session = None
allowed = {'Local synthetic test', 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1',
           'Synthetic content only. No account, messages, or payment data.',
           'Allowed local link', 'Allowed local destination', 'CUE_FIXED_LINK_ALLOWED_V1'}

def safe(text):
    return re.sub(r'[0-9a-f]{64}', '<session-redacted>', text)

def command(parts, timeout=45):
    x = subprocess.run(adb + parts, capture_output=True, text=True, timeout=timeout)
    assert x.returncode == 0, safe(x.stdout + x.stderr)
    return x.stdout + x.stderr

def call(method):
    parts = ['shell', 'content', 'call', '--uri', uri, '--method', method]
    if method != 'open':
        parts += ['--arg', session]
    start = time.monotonic()
    try:
        text = command(parts)
        result['events'].append({'method': method, 'elapsed_host_seconds': round(time.monotonic() - start, 3),
                                 'output': safe(text)})
        return text
    except subprocess.TimeoutExpired:
        result['events'].append({'method': method, 'elapsed_host_seconds': round(time.monotonic() - start, 3),
                                 'status': 'HOST_ADB_TIMEOUT'})
        raise RuntimeError('HOST_ADB_TIMEOUT:' + method)

def read_page(expected_url):
    text = call('read')
    assert 'source=AccessibilityService.getRootInActiveWindow' in text and 'Exception' not in text, safe(text)
    match = re.search(r'text_json=(\[.*?\])(?:,|})', text)
    assert match, 'No accessibility text'
    values = json.loads(match.group(1))
    assert set(values) <= allowed and 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1' in values, 'Unexpected synthetic text'
    match = re.search(r'(?:\[\{|,\s*)fixture_url=([^,}]+)', text)
    assert match and match.group(1).strip() == expected_url, 'Not the exact compiled fixture URL'
    return text

try:
    assert command(['get-state']).strip() == 'device', 'AVD offline'
    assert command(['emu', 'avd', 'name']).splitlines()[0] == 'agentruntime_api30', 'Wrong AVD'
    assert command(['shell', 'getprop', 'ro.build.version.sdk']).strip() == '30', 'Wrong SDK'
    assert command(['shell', 'getprop', 'ro.product.cpu.abi']).strip() == 'x86_64', 'Wrong ABI'
    assert command(['shell', 'getprop', 'sys.boot_completed']).strip() == '1', 'Boot incomplete'
    services = command(['shell', 'dumpsys', 'accessibility'])
    (out / 'accessibility-before.txt').write_text(services)
    bound = next((line for line in services.splitlines() if 'Bound services:' in line), '')
    assert 'Service[label=AgentRuntime 辅助服务' in bound, 'Service not bound'
    window = command(['shell', 'dumpsys', 'window', 'windows'])
    (out / 'window-before.txt').write_text(window)
    assert 'Application Not Responding' not in window, 'System ANR; no dismissal permitted'
    opened = call('open')
    match = re.search(r'session=([0-9a-f]{64})', opened)
    assert match, safe(opened)
    session = match.group(1)
    # One cold-page settle period, not a read retry and not a change to the 120-second session TTL.
    time.sleep(15)
    initial = read_page('https://synthetic.invalid/fixture')
    result['read_passed'] = True
    candidate = re.search(r'(?:\[\{|,\s*)fixed_link_diagnostic=([^,}]+)', initial)
    assert candidate, 'Missing fixed link diagnostic'
    result['fixed_link_diagnostic'] = candidate.group(1).strip()
    assert result['fixed_link_diagnostic'].startswith('exact_labels=1;'), 'Fixed link is not unique'
    assert result['fixed_link_diagnostic'].endswith(';reject=NONE'), result['fixed_link_diagnostic']
    result['fixed_clicks_initiated'] = 1
    clicked = call('click')
    assert 'clicked=true' in clicked and 'Exception' not in clicked, safe(clicked)
    time.sleep(1)
    after = read_page('https://synthetic.invalid/fixture#allowed')
    result['navigation_passed'] = True
    result['passed'] = True
except Exception as error:
    result['error'] = safe(str(error))
finally:
    # Screenshot is evidence only, never an OCR/DOM replacement for accessibility text.
    if session:
        try:
            image = subprocess.run(adb + ['exec-out', 'screencap', '-p'], capture_output=True, timeout=15)
            if image.returncode == 0 and image.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                (out / 'screen.png').write_bytes(image.stdout)
                result['screenshot'] = str(out / 'screen.png')
        except subprocess.TimeoutExpired:
            result['screenshot_status'] = 'TIMEOUT'
        try:
            closed = call('close')
            result['closed'] = 'closed=true' in closed and 'Exception' not in closed
        except Exception as error:
            result['cleanup_error'] = safe(str(error))
    (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': result['passed'], 'read_passed': result.get('read_passed', False),
                      'navigation_passed': result.get('navigation_passed', False),
                      'error': result.get('error'), 'closed': result.get('closed'),
                      'api_requests': 0}, ensure_ascii=False), flush=True)
raise SystemExit(0 if result['passed'] else 1)
