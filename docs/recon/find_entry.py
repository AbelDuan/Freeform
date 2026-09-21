import re

W = r"C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
lines = open(W + "/fw/dex_full.txt", encoding="utf-8", errors="replace").read().splitlines()

CLS = re.compile(r"^\s*Class descriptor\s*:\s*'(.+)'\s*$")


def class_of(i):
    for j in range(i, -1, -1):
        m = CLS.match(lines[j])
        if m:
            return m.group(1), j
    return "?", -1


def sig_at(i):
    """从 name 行往返找 type 行（dexdump: name 后跟 type）"""
    nm = re.search(r"name\s*:\s*'(.*)'", lines[i])
    name = nm.group(1) if nm else "?"
    for k in range(i, min(i + 4, len(lines))):
        tm = re.search(r"type\s*:\s*'(.*)'", lines[k])
        if tm:
            return name + tm.group(1)
    return name + " ?"


for name in ["dockMultipleSplitTasks", "requestOpenToExitDockMode", "handleOpenToExitDockMode",
             "animateOpenWithExitDockMode", "prepareDragTaskToMultipleSplit", "finishEnterMultipleSplit",
             "startDockChangeTransition", "setDockChangeTransition",
             "EXTRA_TRANSIT_TYPE_STAGE_DOCK_EXIT_SOSC_TO_THREE"]:
    print("=" * 22, name)
    for i, l in enumerate(lines):
        if f"'{name}'" not in l:
            continue
        c, ci = class_of(i)
        print(f"  {c}")
        print(f"      {sig_at(i)}")
