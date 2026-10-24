"""下载 SmolVLM-256M 的 ONNX 权重并推到设备。

模型合计约 573MB，不能打进 APK（安装包会变 600MB 量级），所以走"运行时内置、权重外置"。
这个脚本负责把权重拉下来并给出 adb 推送命令。

用法：
    python tools/fetch_vlm_model.py --out D:/models/smolvlm
    python tools/fetch_vlm_model.py --out D:/models/smolvlm --push      # 自动 adb push
"""
import argparse
import os
import subprocess
import sys

FILES = [
    ('vision_encoder.onnx', 374316497),
    ('decoder_model_merged_q4.onnx', 86562901),
    ('embed_tokens.onnx', 113541419),
]

MIRRORS = [
    'https://hf-mirror.com',
    'https://huggingface.co',
]

REPO = 'HuggingFaceTB/SmolVLM-256M-Instruct'
REMOTE_DIR = '/sdcard/Android/data/com.agentruntime.agent/files/models/smolvlm'


def human(n):
    return f'{n / 1048576:.1f}MB'


def download(url, dest, expect):
    if os.path.exists(dest) and os.path.getsize(dest) >= expect:
        print(f'  skip (already {human(os.path.getsize(dest))})')
        return True
    print(f'  GET {url}')
    cmd = ['curl', '-L', '--fail', '--retry', '3', '--retry-delay', '2',
           '-C', '-', '-o', dest, url]
    r = subprocess.run(cmd)
    if r.returncode != 0:
        return False
    size = os.path.getsize(dest) if os.path.exists(dest) else 0
    if size < expect:
        print(f'  !! size mismatch {human(size)} < {human(expect)}')
        return False
    print(f'  ok {human(size)}')
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True, help='本地保存目录')
    ap.add_argument('--push', action='store_true', help='下载后自动 adb push 到设备')
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    ok = True
    for name, expect in FILES:
        dest = os.path.join(args.out, name)
        print(f'[{name}] 期望 {human(expect)}')
        downloaded = False
        for m in MIRRORS:
            url = f'{m}/{REPO}/resolve/main/onnx/{name}'
            if download(url, dest, expect):
                downloaded = True
                break
            print(f'  mirror failed: {m}')
        if not downloaded:
            ok = False
            print(f'  !! 无法获取 {name}')

    if not ok:
        print('\n部分文件失败。可重跑本脚本，curl -C - 会断点续传。')
        return 1

    total = sum(os.path.getsize(os.path.join(args.out, n)) for n, _ in FILES)
    print(f'\n全部就绪：{human(total)}  -> {args.out}')

    if args.push:
        print('\n推送到设备：')
        subprocess.run(['adb', 'shell', 'mkdir', '-p', REMOTE_DIR])
        for name, _ in FILES:
            local = os.path.join(args.out, name)
            print(f'  push {name}')
            subprocess.run(['adb', 'push', local, REMOTE_DIR + '/' + name])
        print('\n完成。在 App 的「设置 → 本地模型」里选 ONNX 并点能力自检。')
    else:
        print('\n推送到设备：')
        print(f'  adb shell mkdir -p {REMOTE_DIR}')
        for name, _ in FILES:
            print(f'  adb push "{os.path.join(args.out, name)}" {REMOTE_DIR}/')
        print('\n或者用 adb push 到任意位置后，在设置页填路径（当前版本固定读上面的目录）。')

    return 0


if __name__ == '__main__':
    sys.exit(main())
