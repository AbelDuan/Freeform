#!/usr/bin/env python3
"""极小 zip 兼容层：`zip -q -X 目标.zip 文件...` / `zip -q -X -r 目标.zip 目录`（保留相对路径）"""
import os, sys, zipfile
quiet = recursive = False
pos = []
for a in sys.argv[1:]:
    if a.startswith('-'):
        if 'q' in a: quiet = True
        if 'r' in a: recursive = True
    else:
        pos.append(a)
if not pos:
    sys.exit("zip: 需要目标文件与源文件")
out, srcs = pos[0], pos[1:]
mode = 'a' if os.path.exists(out) else 'w'
with zipfile.ZipFile(out, mode, zipfile.ZIP_DEFLATED) as z:
    for s in srcs:
        if os.path.isdir(s):
            if not recursive:
                continue
            base = os.path.dirname(os.path.abspath(s))   # 相对路径按 s 的父目录算，保留 s 本身这一层
            for root, _, files in os.walk(s):
                for f in sorted(files):
                    p = os.path.join(root, f)
                    z.write(p, os.path.relpath(os.path.abspath(p), base))
        else:
            z.write(s, os.path.basename(s))
if not quiet:
    print("zip: wrote", out, file=sys.stderr)
