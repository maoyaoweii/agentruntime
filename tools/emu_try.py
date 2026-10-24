# -*- coding: utf-8 -*-
"""模拟器启动矩阵探测：逐个组合尝试，60s 内看是否出现设备，记录结论。

上一版探针用 capture_output，子进程被 kill 后 qemu 仍握着管道导致挂死；
这一版一律写文件、进程显式 kill，结果只打一行结论。
"""
import os
import subprocess
import sys
import time

SDK = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
EMU = os.path.join(SDK, 'emulator', 'emulator.exe')
ADB = os.path.join(SDK, 'platform-tools', 'adb.exe')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DETACHED = 0x00000008
NEWGRP = 0x00000200

COMBOS = [
    ('a', ['-accel', 'off', '-gpu', 'off']),
    ('b', ['-accel', 'off', '-gpu', 'guest']),
    ('c', ['-accel', 'auto', '-gpu', 'off']),
    ('d', ['-accel', 'off']),                      # 默认 gpu
    ('e', ['-gpu', 'swiftshader_indirect']),       # 默认 accel
]


def qemu_alive():
    r = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq qemu-system-x86_64-headless.exe', '/NH'],
                       capture_output=True)
    return 'qemu-system' in r.stdout.decode('gbk', 'ignore')


def kill_all():
    for exe in ('qemu-system-x86_64-headless.exe', 'emulator.exe'):
        subprocess.run(['taskkill', '/F', '/IM', exe], capture_output=True)
    time.sleep(2)


def adb_state(port):
    r = subprocess.run([ADB, 'devices'], capture_output=True)
    out = (r.stdout or b'').decode('utf-8', 'ignore')
    for line in out.split('\n'):
        if line.startswith('emulator-%d' % port):
            return line.split('\t')[-1].strip()
    return ''


def try_combo(tag, extra, port=5554, observe=60):
    kill_all()
    logpath = os.path.join(ROOT, 'emu_%s.log' % tag)
    args = [EMU, '-avd', 'agent_api30', '-no-window', '-no-audio', '-no-boot-anim',
            '-no-snapshot', '-memory', '2048', '-cores', '2', '-port', str(port)] + extra
    with open(logpath, 'wb') as f:
        p = subprocess.Popen(args, stdout=f, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL,
                             creationflags=DETACHED | NEWGRP, close_fds=True, cwd=ROOT)
    t0 = time.time()
    seen = ''
    while time.time() - t0 < observe:
        if not qemu_alive():
            break
        st = adb_state(port)
        if st:
            seen = st
        if st == 'device':
            rc = subprocess.run([ADB, '-s', 'emulator-%d' % port, 'shell',
                                 'getprop', 'sys.boot_completed'], capture_output=True)
            if (rc.stdout or b'').decode('utf-8', 'ignore').strip() == '1':
                print('%-3s %-46s => OK booted in %.0fs' % (tag, ' '.join(extra), time.time() - t0))
                return True
        time.sleep(5)
    alive = qemu_alive()
    print('%-3s %-46s => %s (device=%s, %.0fs)' %
          (tag, ' '.join(extra), '进程存活但未起设备' if alive else '进程退出', seen or '-', time.time() - t0))
    with open(logpath, 'rb') as f:
        lines = f.read().decode('utf-8', 'ignore').strip().split('\n')
    bad = [l for l in lines if any(k in l.lower() for k in
           ('error', 'fatal', 'fail', 'abort', 'accel', 'hypervisor', 'not supported'))]
    for l in bad[-3:]:
        print('      !', l[:160])
    kill_all()
    return False


def main():
    for tag, extra in COMBOS:
        if len(sys.argv) > 1 and sys.argv[1] != tag:
            continue
        if try_combo(tag, extra):
            print('\n可用组合：-accel ' + ' '.join(extra))
            return 0
    print('\n所有组合均失败')
    return 1


if __name__ == '__main__':
    sys.exit(main())
