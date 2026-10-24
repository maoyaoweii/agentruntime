#!/usr/bin/env python3
"""One authorized reboot of the existing AVD; no settings writes or credential use."""
import json
import pathlib
import subprocess
import sys
import time

out = pathlib.Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=False)
adb = ['/workspace/android-sdk/platform-tools/adb', '-s', 'emulator-5554']
state = {'api_requests_initiated': 0, 'reboots_initiated': 0, 'tests': []}

def save():
    (out / 'preflight-result.json').write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')

def run(args, timeout=15):
    return subprocess.run(adb + args, capture_output=True, timeout=timeout)

try:
    r = run(['emu', 'avd', 'name'])
    assert r.returncode == 0 and r.stdout.decode().splitlines()[0] == 'agentruntime_api30', 'Unexpected AVD'
    state['reboots_initiated'] = 1
    state['status'] = 'REBOOT_COMMAND_COMMITTED_NO_REPEAT'
    save()
    r = run(['reboot'])
    (out / 'reboot-command.txt').write_bytes(r.stdout + r.stderr)
    state['reboot_command_returncode'] = r.returncode
    assert r.returncode == 0, 'Reboot submission failed; never retry'
    print('One reboot submitted; waiting for existing AVD', flush=True)
    time.sleep(5)
    deadline = time.monotonic() + 150
    booted = False
    while time.monotonic() < deadline:
        try:
            r = run(['shell', 'getprop', 'sys.boot_completed'], 5)
            if r.returncode == 0 and r.stdout.strip() == b'1':
                booted = True
                break
        except subprocess.TimeoutExpired:
            pass
        print('Waiting for boot completion', flush=True)
        time.sleep(5)
    assert booted, 'Existing AVD did not complete boot; stop without another reboot'
    time.sleep(5)
    for label, args in [('accessibility', ['shell', 'dumpsys', 'accessibility']), ('windows', ['shell', 'dumpsys', 'window', 'windows'])]:
        r = run(args)
        assert r.returncode == 0, label + ' diagnostic failed'
        text = r.stdout.decode(errors='replace')
        (out / (label + '.txt')).write_text(text)
        if label == 'windows':
            assert 'Application Not Responding' not in text, 'System ANR persists; stop'
    state['status'] = 'REBOOT_COMPLETED_REQUIRES_BINDING_CHECK'
    print(state['status'], flush=True)
except Exception as e:
    state['status'] = 'STOPPED'
    state['error'] = str(e)
    print('STOPPED:', str(e), flush=True)
finally:
    try:
        r = run(['exec-out', 'screencap', '-p'])
        if r.returncode == 0 and r.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
            (out / 'after-reboot.png').write_bytes(r.stdout)
    except Exception:
        pass
    save()
