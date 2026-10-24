#!/usr/bin/env python3
"""Offline fixture-only regression. Never reads credentials or calls model/network APIs."""
import argparse
import json
import pathlib
import re
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default='/workspace/android-sdk/platform-tools/adb')
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--evidence', required=True, help='New, nonexistent output directory')
    p.add_argument('--expiry-test', action='store_true')
    p.add_argument('--command-timeout', type=int, default=15, choices=range(15, 61),
                   metavar='15..60', help='Bounded host ADB timeout; does not change device/session TTL')
    args = p.parse_args()
    out = pathlib.Path(args.evidence)
    out.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, '-s', args.serial]
    events = []
    session = None
    uri = 'content://com.agentruntime.agent.localfixture'

    def run(parts):
        r = subprocess.run(adb + parts, capture_output=True, text=True, timeout=args.command_timeout)
        return r.returncode, r.stdout + r.stderr

    def call(method, token=None, extra=None, as_app=False):
        cmd = ['shell']
        if as_app:
            cmd += ['run-as', 'com.agentruntime.agent']
        cmd += ['content', 'call', '--uri', uri, '--method', method]
        if token is not None:
            cmd += ['--arg', token]
        if extra:
            cmd += ['--extra', extra]
        started = time.monotonic()
        try:
            rc, text = run(cmd)
        except subprocess.TimeoutExpired:
            events.append({'method': method, 'as_app': as_app, 'status': 'HOST_ADB_TIMEOUT',
                           'elapsed_host_seconds': round(time.monotonic() - started, 3)})
            raise
        safe = re.sub(r'[0-9a-f]{64}', '<session-redacted>', text)
        events.append({'method': method, 'as_app': as_app, 'returncode': rc, 'output': safe,
                       'elapsed_host_seconds': round(time.monotonic() - started, 3)})
        return text

    def success(text, expected):
        assert expected in text and 'Exception' not in text, text

    def denied(text):
        # Android's content CLI can return 0 even when the provider rejects a request.
        assert 'SecurityException' in text or 'Permission Denial' in text, text
        assert 'TIMEOUT' not in text and 'INTERNAL_ERROR' not in text, text
        assert 'text_json=' not in text and 'clicked=true' not in text, text

    def fixture_text(text):
        success(text, 'source=AccessibilityService.getRootInActiveWindow')
        values = json.loads(re.search(r'text_json=(\[.*?\])(?:,|})', text).group(1))
        allowed = {'Local synthetic test', 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1',
                   'Synthetic content only. No account, messages, or payment data.',
                   'Allowed local link', 'Allowed local destination', 'CUE_FIXED_LINK_ALLOWED_V1'}
        assert set(values) <= allowed and 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1' in values, values

    try:
        for prop, expected in [('ro.build.version.sdk', '30'), ('ro.product.cpu.abi', 'x86_64'),
                               ('ro.hardware', 'ranchu')]:
            rc, value = run(['shell', 'getprop', prop])
            assert rc == 0 and value.strip() == expected, (prop, value)
        denied(call('read', '0' * 64))
        denied(call('open', as_app=True))
        denied(call('open', 'https://example.com'))
        opened = call('open')
        match = re.search(r'session=([0-9a-f]{64})', opened)
        assert match, opened
        session = match.group(1)
        deadline = time.monotonic() + 25
        while True:
            text = call('read', session)
            if 'text_json=' in text:
                break
            assert time.monotonic() < deadline, text
            time.sleep(1)
        success(text, 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1')
        success(text, 'fixture_url=https://synthetic.invalid/fixture')
        fixture_text(text)
        for method in ['tap', 'navigate', 'setText', 'launch', 'query']:
            denied(call(method, session))
        for extra in ['url:s:https://example.com', 'selector:s:a', 'x:i:10', 'goal:s:open']:
            denied(call('click', session, extra))
        denied(call('click', 'f' * 64))
        click = call('click', session)
        success(click, 'clicked=true')
        deadline = time.monotonic() + 10
        while True:
            text = call('read', session)
            if 'fixture_url=https://synthetic.invalid/fixture#allowed' in text:
                break
            assert time.monotonic() < deadline, text
            time.sleep(0.5)
        success(text, 'CUE_LOCAL_SYNTHETIC_FIXTURE_V1')
        fixture_text(text)
        denied(call('click', session))
        success(call('close', session), 'closed=true')
        denied(call('read', session))
        session = None
        if args.expiry_test:
            time.sleep(1)
            opened = call('open')
            session = re.search(r'session=([0-9a-f]{64})', opened).group(1)
            print('TTL test: waiting 121 seconds; no other actions', flush=True)
            time.sleep(121)
            denied(call('read', session))
            session = None  # Fixture auto-closes at TTL.
        result = {'passed': True, 'model_api_invoked_by_script': False,
                  'host_command_timeout_seconds': args.command_timeout, 'events': events}
        print('PASS: local fixture read/click, denied controls, shell-only, close/replay' +
              (', TTL expiry' if args.expiry_test else ''), flush=True)
        return 0
    except Exception as e:
        result = {'passed': False, 'error': re.sub(r'[0-9a-f]{64}', '<session-redacted>', str(e)),
                  'model_api_invoked_by_script': False,
                  'host_command_timeout_seconds': args.command_timeout, 'events': events}
        print('FAIL:', result['error'], flush=True)
        return 1
    finally:
        if session:
            try:
                call('close', session)
            except Exception as e:
                result['cleanup_error'] = re.sub(r'[0-9a-f]{64}', '<session-redacted>', str(e))
        (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
