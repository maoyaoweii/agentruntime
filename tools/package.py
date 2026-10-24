"""打包 V1.6 交付 zip（排除 build/.gradle/日志），并在包内附一份可校验的 MANIFEST.txt。

为什么要 MANIFEST：交付包会被复制、解压、再转发，中间任何一次传输损坏都很难发现。
清单里带每个文件的 SHA-256 与体积，收包的人可以先核对 APK 的哈希再安装，
而不必"装上去跑一遍看看崩不崩"。
"""
import hashlib
import io
import os
import re
import time
import zipfile

SRC = r'E:/女朋友恋爱软件/agentruntime'
OUT = r'E:/女朋友恋爱软件/AgentRuntime_V1.6_delivery.zip'
GRADLE = os.path.join(SRC, 'agent', 'build.gradle')

EXCLUDE_DIRS = {
    'build', '.gradle', '.idea', '__pycache__',
    # .workbuddy 是 agent 会话的临时数据（文档解析中间产物等），不属于工程源码，
    # 打进去只会让交付包变成"看不懂的一堆 json"。保留在目录里，只是不进 zip。
    '.workbuddy',
}
EXCLUDE_SUFFIX = ('.log',)
MANIFEST_NAME = 'MANIFEST.txt'


def collect(root):
    out = []
    for dp, dn, fn in os.walk(root):
        parts = set(dp.split(os.sep))
        if parts & EXCLUDE_DIRS:
            continue
        dn[:] = [d for d in dn if d not in EXCLUDE_DIRS]
        for f in fn:
            if f.endswith(EXCLUDE_SUFFIX):
                continue
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, os.path.dirname(root))
            out.append((full, rel.replace(os.sep, '/')))
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def version_info():
    try:
        s = io.open(GRADLE, encoding='utf-8').read()
        name = re.search(r"versionName\s+'([^']+)'", s)
        code = re.search(r'versionCode\s+(\d+)', s)
        return (name.group(1) if name else '?'), (code.group(1) if code else '?')
    except Exception:
        return '?', '?'


def build_manifest(files):
    name, code = version_info()
    rows = []
    for full, rel in sorted(files):
        rows.append((rel, os.path.getsize(full), sha256(full)))

    total = sum(r[1] for r in rows)
    apk = [r for r in rows if r[0].endswith('agent-debug.apk')]

    lines = [
        'Android AI Agent Runtime — 交付包清单',
        '=' * 60,
        '版本        : %s (versionCode %s)' % (name, code),
        '打包时间    : %s' % time.strftime('%Y-%m-%d %H:%M:%S'),
        '文件数      : %d' % len(rows),
        '解压后总大小: %.1f MiB (%.1f MB)' % (total / 1024 / 1024, total / 1e6),
        '',
    ]
    if apk:
        lines += [
            '主 APK',
            '  %s' % apk[0][0],
            '  体积    : %.1f MiB (%.1f MB)' % (apk[0][1] / 1024 / 1024, apk[0][1] / 1e6),
            '  SHA-256 : %s' % apk[0][2],
            '',
        ]
    lines += [
        '校验方法',
        '  Windows : certutil -hashfile agent-debug.apk SHA256',
        '  Linux   : sha256sum agent-debug.apk',
        '',
        '包内不含 build/ 与 .gradle/ 目录，也不含 *.log。',
        '源码在 agent/src/main/java 与 agent/src/test/java，',
        '文档在 docs/（含 CHANGELOG、各版本报告、模拟器说明、界面预览图）。',
        '',
        '-' * 60,
        '%-64s %10s  %s' % ('文件', '字节', 'SHA-256'),
        '-' * 60,
    ]
    for rel, size, digest in rows:
        lines.append('%-64s %10d  %s' % (rel, size, digest))
    lines.append('')
    return '\n'.join(lines)


def main():
    files = collect(SRC)
    manifest = build_manifest(files)

    if os.path.exists(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for full, rel in sorted(files):
            z.write(full, rel)
        z.writestr(MANIFEST_NAME, manifest)

    size = os.path.getsize(OUT)
    print('packed %d files (+%s) -> %s' % (len(files), MANIFEST_NAME, OUT))
    print('zip: %.1f MB' % (size / 1024 / 1024))
    print('解压后: %.1f MB' % (sum(os.path.getsize(f) for f, _ in files) / 1024 / 1024))
    big = [(r, os.path.getsize(f)) for f, r in files if os.path.getsize(f) > 300 * 1024]
    for r, s in sorted(big, key=lambda x: -x[1])[:6]:
        print('  %8.1f KB  %s' % (s / 1024, r))


if __name__ == '__main__':
    main()
