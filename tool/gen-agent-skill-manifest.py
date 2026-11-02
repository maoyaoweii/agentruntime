"""生成 dsh-go-agent 技能的 manifest.json。

manifest 的 key 是「APK 里真实存在的资产名」，value 是「装进技能目录后的相对路径」。
两者必须对得上：loadUiSkillBundle() 按 key 去 rootBundle 里找文件，找不到就抛
Unable to load asset，而调用方会静默吞掉异常，技能就永远装不上。

早期版本把 key 里的 `/` 换成了 `__`（扁平名），但磁盘上文件仍在嵌套目录、pubspec
也只声明了嵌套目录，于是 APK 里根本没有 `capabilities__foo.md` 这种名字 —— key 和
实际资产对不上，加载必然失败。这里改成 key 直接等于相对路径。

参考 dsh-go-guide 的做法（它把物理文件重命名成扁平名，所以 key 才带 `-`）；我们
不重命名文件，让 key == rel，最直观。
"""

import json
import os
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: gen-agent-skill-manifest.py <技能目录>", file=sys.stderr)
        return 2

    sk = sys.argv[1]
    files = {}

    for root, dirs, names in os.walk(sk):
        dirs.sort()
        for name in sorted(names):
            if not name.endswith(".md"):
                continue
            full = os.path.join(root, name)
            rel = os.path.relpath(full, sk).replace("\\", "/")
            # key == value == 相对路径：资产名与目标路径一一对应，不会错位。
            files[rel] = rel

    payload = {"name": "dsh-go-agent", "files": files}
    out = os.path.join(sk, "manifest.json")
    with open(out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"生成 {out}: {len(files)} 个文件映射")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
