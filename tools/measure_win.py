"""量截图里小窗卡片的可视 bbox（局部扫描 + 最长连续段法）。

为什么不用全局阈值/BFS：小窗白卡与壁纸亮区可能连通，BFS 会漏到壁纸；
而按 task 宽度算占比阈值又会因为窗口实际比 task 小（渲染 scale ≈0.63）而永远达不到。

做法：在 task 矩形内逐列/逐行统计近白像素数，取**最长连续达标段**当卡片边界，
既避免壁纸亮区把 bbox 拉大，也能容忍卡片内部有图片/深色块。
用法: measure_win.py <png> <taskX0> <taskY0> <taskX1> <taskY1>
"""
import sys
from PIL import Image

p, tx0, ty0, tx1, ty1 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
im = Image.open(p).convert('RGB')
W, H = im.size
px = im.load()


def white(x, y):
    r, g, b = px[x, y]
    return r >= 238 and g >= 238 and b >= 238


sx1 = min(tx1, W - 1)
sy1 = min(ty1, H - 1)
# 窗口实际只有 task 的 ~0.6，占比阈值按 task 尺寸的 1/12 取，留足余量
colneed = max(8, (sy1 - ty0) // 12)
rowneed = max(8, (sx1 - tx0) // 12)

colcnt = [sum(1 for y in range(ty0, sy1 + 1, 4) if white(x, y)) for x in range(tx0, sx1 + 1)]
rowcnt = [sum(1 for x in range(tx0, sx1 + 1, 4) if white(x, y)) for y in range(ty0, sy1 + 1)]


def longest_run(cnt, need, base):
    best = (0, -1)
    cur = None
    for i, v in enumerate(cnt):
        if v > need:
            if cur is None:
                cur = i
            if i - cur > best[1] - best[0]:
                best = (cur, i)
        else:
            cur = None
    return (base + best[0], base + best[1]) if best[1] > best[0] else None


cs = longest_run(colcnt, colneed, tx0)
rs = longest_run(rowcnt, rowneed, ty0)
if not cs or not rs:
    print(f"NONE colneed={colneed} rowneed={rowneed}")
else:
    print(f"visual=({cs[0]},{rs[0]})-({cs[1]},{rs[1]}) w={cs[1] - cs[0]} h={rs[1] - rs[0]}")
