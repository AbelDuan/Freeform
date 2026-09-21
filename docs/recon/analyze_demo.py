import re

W = r"C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
lines = open(W + "/cap_demo.txt", encoding="utf-8", errors="replace").read().splitlines()

TIME = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\S+\s*/\s*(.+?)\(\s*\d+\):\s?(.*)$")

NOISE = {"AppCompatLetterboxPolicyImpl", "flutter", "InsetsPolicy", "WindowManager",
         "xring_gralloc", "hyos_spawner", "BeautyService", "VRI[]"}


def parse(l):
    m = TIME.match(l)
    if not m:
        return None
    return m.group(1), m.group(2).strip(), m.group(3)


def window(a, b, title):
    print("=" * 24, title, f"[{a} ~ {b}]")
    for l in lines:
        r = parse(l)
        if not r:
            continue
        t, tag, msg = r
        ts = t[6:]
        if not (a <= ts <= b):
            continue
        if tag in NOISE:
            continue
        print(f"{ts}  {tag[:38]:38s} | {msg[:170]}")


window("18:29:04.100", "18:29:08.310", "★ 用户手势窗口（拖到左上 / dock 前夜）")
