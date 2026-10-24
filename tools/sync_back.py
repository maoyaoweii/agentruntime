"""把英文工作副本同步回中文交付目录，并把 APK 归入 apk/。

单一方向：E:\\agentruntime  ->  E:\\女朋友恋爱软件\\agentruntime
（构建在英文路径，交付在用户 workspace）
"""
import os
import shutil
import subprocess
import sys

SRC = r'E:/agentruntime'
DST = r'E:/女朋友恋爱软件/agentruntime'

EXCLUDE_DIRS = {'build', '.gradle', '.idea', '__pycache__'}
EXCLUDE_SUFFIX = ('.log',)


def clean_dir(path):
    if os.path.isdir(path):
        subprocess.run(['cmd', '/c', 'rmdir', '/S', '/Q', path], capture_output=True)


def copy_tree(src, dst):
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(src):
        s = os.path.join(src, name)
        d = os.path.join(dst, name)
        if os.path.isdir(s):
            if name in EXCLUDE_DIRS:
                continue
            copy_tree(s, d)
        else:
            if name.endswith(EXCLUDE_SUFFIX):
                continue
            shutil.copy2(s, d)


def main():
    if not os.path.isdir(SRC):
        print('source missing:', SRC)
        sys.exit(2)

    # 清掉旧的源码树，避免已删除的文件残留
    for sub in ('agent', 'agenttestapp', 'docs', 'tools'):
        clean_dir(os.path.join(DST, sub))

    copy_tree(SRC, DST)
    print('synced ->', DST)

    # APK 归位
    apk_dst = os.path.join(DST, 'apk')
    os.makedirs(apk_dst, exist_ok=True)
    pairs = [
        (os.path.join(SRC, 'agent/build/outputs/apk/debug/agent-debug.apk'),
         os.path.join(apk_dst, 'agent-debug.apk')),
        (os.path.join(SRC, 'agenttestapp/build/outputs/apk/debug/agenttestapp-debug.apk'),
         os.path.join(apk_dst, 'agenttestapp-debug.apk')),
    ]
    for s, d in pairs:
        if os.path.exists(s):
            shutil.copy2(s, d)
            print('apk:', d, round(os.path.getsize(d) / 1024, 1), 'KB')
        else:
            print('!! apk missing:', s)

    # 统计
    java = 0
    lines = 0
    skills = 0
    for dp, dn, fn in os.walk(DST):
        if 'build' in dp.split(os.sep):
            continue
        for f in fn:
            if f.endswith('.java'):
                java += 1
                with open(os.path.join(dp, f), encoding='utf-8', errors='ignore') as fh:
                    lines += sum(1 for _ in fh)
            elif f.endswith('.json') and os.sep + 'skills' in dp:
                skills += 1
    print(f'java files={java} lines={lines} skills={skills}')


if __name__ == '__main__':
    main()
