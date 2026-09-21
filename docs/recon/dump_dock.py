import re

W = r"C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
lines = open(W + "/fw/dex_code.txt", encoding="utf-8", errors="replace").read().splitlines()


def dump(a, b, title, only_ops=True):
    print("=" * 26, title)
    for k in range(a, min(b, len(lines))):
        s = lines[k]
        if only_ops:
            m = re.search(r"\|[0-9a-f]{4}: (.*?)(?://.*)?$", s)
            body = m.group(1).strip() if m else ""
            if "invoke" in body or "const-string" in body or "new-instance" in body or "sget" in body or "iput" in body or "iget" in body:
                print(f"  {body[:165]}")
            elif "name" in s or "type" in s:
                print(f"  {s.strip()[:120]}")
        else:
            print(s[:180])


dump(992536, 992700, "dockSoScTasks()", only_ops=True)
print()
dump(992378, 992540, "dockMultipleTasks()", only_ops=True)
