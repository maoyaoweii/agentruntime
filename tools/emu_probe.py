# -*- coding: utf-8 -*-
"""探测模拟器可用的加速/渲染组合。

为什么需要这个：本机 emulator.exe 在 `-accel off` 下直接段错误（exit 139），
而固件里 SVM 未开启、也没装 hypervisor 驱动。这里把组合穷举一遍，
拿到每种组合的真实退出码与最后几行输出，而不是靠猜。
"""
import os
import subprocess
import sys

SDK = os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk')
EMU = os.path.join(SDK, 'emulator', 'emulator.exe')

BASE = ['-avd', 'agent_api30', '-no-window', '-no-audio', '-no-boot-anim',
        '-no-snapshot', '-memory', '2048', '-cores', '2', '-port', '5554']

CASES = [
    ('auto-accel + swiftshader', ['-gpu', 'swiftshader_indirect']),
    ('accel-off + gpu-off',      ['-accel', 'off', '-gpu', 'off']),
    ('accel-off + gpu-guest',    ['-accel', 'off', '-gpu', 'guest']),
    ('accel-off + swiftshader',  ['-accel', 'off', '-gpu', 'swiftshader_indirect']),
    ('accel-on + gpu-off',       ['-accel', 'on', '-gpu', 'off']),
]


def run(name, extra, timeout=45):
    cmd = [EMU] + BASE + extra
    print('=' * 70)
    print('CASE:', name)
    print('CMD :', ' '.join(extra))
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout,
                           env=dict(os.environ))
        out = (p.stdout or b'').decode('utf-8', 'ignore')
        err = (p.stderr or b'').decode('utf-8', 'ignore')
        print('EXIT:', p.returncode)
        merged = (out + err).strip().split('\n')
        for line in merged[-12:]:
            print('  |', line[:160])
        return p.returncode
    except subprocess.TimeoutExpired:
        print('RESULT: 进程活过 %ds —— 可能已启动成功' % timeout)
        subprocess.run(['taskkill', '/F', '/IM', 'qemu-system-x86_64-headless.exe'],
                       capture_output=True)
        subprocess.run(['taskkill', '/F', '/IM', 'emulator.exe'], capture_output=True)
        return None


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, extra in CASES:
        if only and only not in name:
            continue
        run(name, extra)


if __name__ == '__main__':
    main()
