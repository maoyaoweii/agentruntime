# -*- coding: utf-8 -*-
"""启动 Android 模拟器并等待系统启动完成（Windows）。

为什么不用 `emulator.exe ... &`：
1. Windows 上 emulator.exe 只是前端，真正的虚拟机是 qemu-system-x86_64-headless.exe 子进程；
   父进程退出时子进程会一起被回收。
2. 本机没有 hypervisor（固件 SVM 未开），只能 `-accel off` 走 TCG，冷启动是分钟级的，
   必须让进程在脚本退出后继续活着。

所以用 DETACHED_PROCESS + CREATE_BREAKAWAY_FROM_JOB 脱离作业对象，日志写文件而不是管道
（管道会让父进程退出后子进程写 stdout 时报错）。

用法：
    python tools/emu_start.py                 # 默认 -accel off
    python tools/emu_start.py --accel auto    # 有 hypervisor 时用
    python tools/emu_start.py --wait 1200
"""
import argparse
import os
import subprocess
import sys
import time

SDK = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
EMU = os.path.join(SDK, 'emulator', 'emulator.exe')
ADB = os.path.join(SDK, 'platform-tools', 'adb.exe')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, 'emu.log')

DETACHED_PROCESS = 0x00000008
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_BREAKAWAY_FROM_JOB = 0x01000000


def adb(*args, timeout=15):
    try:
        p = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
        return p.returncode, (p.stdout or b'').decode('utf-8', 'ignore').strip()
    except Exception as e:
        return -1, str(e)


def running():
    r = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq qemu-system-x86_64-headless.exe', '/NH'],
                       capture_output=True)
    return 'qemu-system' in r.stdout.decode('gbk', 'ignore')


def start(avd, accel, gpu, port, ram, cores, window=False):
    args = [EMU, '-avd', avd]
    if not window:
        # 无人值守时用无窗口模式；要"给人看"时由 --window 打开真正的窗口
        args += ['-no-window']
    args += ['-no-audio', '-no-boot-anim',
             '-gpu', gpu, '-memory', str(ram), '-cores', str(cores), '-port', str(port)]
    if accel and accel != 'auto':
        args += ['-accel', accel]
    log = open(LOG, 'wb')
    flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB
    try:
        p = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, creationflags=flags,
                             close_fds=True, cwd=ROOT)
    except OSError:
        # 作业对象不允许 breakaway 时退回普通分离
        p = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, creationflags=DETACHED_PROCESS,
                             close_fds=True, cwd=ROOT)
    print('launched pid=%d  %s' % (p.pid, ' '.join(args[1:])))
    return p


def tail_log(n=15):
    try:
        with open(LOG, 'rb') as f:
            data = f.read().decode('utf-8', 'ignore').strip().split('\n')
        for line in data[-n:]:
            print('  |', line[:170])
    except Exception as e:
        print('  | <无法读取日志>', e)


def wait_boot(port, limit):
    serial = 'emulator-%d' % port
    t0 = time.time()

    # emulator.exe 只是前端：它把真正的虚拟机 qemu 作为子进程拉起后自己就退出了。
    # 所以启动后要先等 qemu 出现，不能立刻判断"进程已退出"——这是上一版误判失败的原因。
    while time.time() - t0 < 25:
        if running():
            break
        time.sleep(1)
    if not running():
        print('\n!! qemu 未能在 25s 内启动')
        tail_log(20)
        return 1

    last = ''
    while time.time() - t0 < limit:
        if not running():
            print('\n!! qemu 进程已退出，启动失败')
            tail_log(20)
            return 1
        rc, out = adb('-s', serial, 'shell', 'getprop', 'sys.boot_completed')
        rc2, devs = adb('devices')
        state = ''
        for line in devs.split('\n'):
            if line.startswith(serial):
                state = line.split('\t')[-1].strip()
        if out.strip() == '1':
            print('\n启动完成，用时 %.0fs，设备 %s' % (time.time() - t0, serial))
            return 0
        msg = '%4.0fs  device=%s boot=%s' % (time.time() - t0, state or '?', out or '-')
        if msg != last:
            print(msg, flush=True)
            last = msg
        time.sleep(15)
    print('\n!! 等待超时（%ds）' % limit)
    tail_log(20)
    return 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--avd', default='agent_api30')
    ap.add_argument('--accel', default='off', help='off / auto / on')
    ap.add_argument('--gpu', default='swiftshader_indirect')
    ap.add_argument('--port', type=int, default=5554)
    ap.add_argument('--ram', type=int, default=2048)
    ap.add_argument('--cores', type=int, default=2)
    ap.add_argument('--wait', type=int, default=2400)
    ap.add_argument('--no-wait', action='store_true')
    ap.add_argument('--window', action='store_true', help='显示模拟器窗口（默认无窗口）')
    a = ap.parse_args()

    rc, devs = adb('devices')
    if ('emulator-%d' % a.port) in devs:
        print('已有实例在跑：emulator-%d' % a.port)
        return 0
    if running():
        print('检测到残留 qemu 进程，先清理')
        subprocess.run(['taskkill', '/F', '/IM', 'qemu-system-x86_64-headless.exe'],
                       capture_output=True)
        time.sleep(3)

    start(a.avd, a.accel, a.gpu, a.port, a.ram, a.cores, a.window)
    if a.no_wait:
        return 0
    return wait_boot(a.port, a.wait)


if __name__ == '__main__':
    sys.exit(main())
