#!/usr/bin/env python3
"""极简 dex 扫描器：列类的字段/方法，或反查某方法在哪个类里声明。
用途：反射前先在反编译产物里确认成员名（AGENTS.md 硬性约定 #4）。"""
import sys, zipfile, struct

def uleb(d, o):
    r = 0; s = 0
    while True:
        b = d[o]; o += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, o
        s += 7

class Dex:
    def __init__(self, d):
        self.d = d; self.strings = []; self.types = []; self.fields = []; self.methods = []; self.classes = []
        self.protos = []
        ns, os_ = struct.unpack_from('<II', d, 0x38)
        nt, ot = struct.unpack_from('<II', d, 0x40)
        np_, op_ = struct.unpack_from('<II', d, 0x48)
        nf, of = struct.unpack_from('<II', d, 0x50)
        nm, om = struct.unpack_from('<II', d, 0x58)
        nc, oc = struct.unpack_from('<II', d, 0x60)
        for i in range(ns):
            o = struct.unpack_from('<I', d, os_ + 4 * i)[0]
            _, p = uleb(d, o); e = d.index(b'\x00', p); self.strings.append(d[p:e].decode('utf-8', 'replace'))
        for i in range(nt): self.types.append(struct.unpack_from('<I', d, ot + 4 * i)[0])
        for i in range(np_):
            sh, rt, par = struct.unpack_from('<III', d, op_ + 12 * i)
            self.protos.append((rt, par))
        for i in range(nf): self.fields.append(struct.unpack_from('<HHI', d, of + 8 * i))
        for i in range(nm): self.methods.append(struct.unpack_from('<HHI', d, om + 8 * i))
        for i in range(nc): self.classes.append(struct.unpack_from('<IIIIIIII', d, oc + 32 * i))
    def s(self, i): return self.strings[i]
    def tn(self, i): return self.s(self.types[i]) if i != 0xffffffff else '?'
    def proto(self, i):
        rt, par = self.protos[i]
        out = []
        if par:
            sz = struct.unpack_from('<I', self.d, par)[0]
            for k in range(sz):
                out.append(self.tn(struct.unpack_from('<H', self.d, par + 4 + 2 * k)[0]))
        return "(" + ",".join(x.replace('/', '.').strip('L;') for x in out) + ")->" + self.tn(rt).replace('/', '.').strip('L;')
    def members(self, cd):
        off = cd[6]
        if off == 0: return [], []
        st, off = uleb(self.d, off); it, off = uleb(self.d, off)
        dm, off = uleb(self.d, off); vm, off = uleb(self.d, off)
        fs = []
        for cnt in (st, it):
            idx = 0            # field_idx_diff 在**每个 list 内部**重新计数（dex 规范）
            for _ in range(cnt):
                df, off = uleb(self.d, off); _, off = uleb(self.d, off); idx += df
                c, t, n = self.fields[idx]; fs.append((self.s(n), self.tn(t)))
        ms = []
        for cnt in (dm, vm):
            idx = 0
            for _ in range(cnt):
                df, off = uleb(self.d, off); _, off = uleb(self.d, off); _, off = uleb(self.d, off); idx += df
                c, p, n = self.methods[idx]; ms.append(self.s(n) + self.proto(p))
        return fs, ms

def dexs(path):
    z = zipfile.ZipFile(path)
    out = []
    for n in z.namelist():
        if n.endswith('.dex'): out.append((n, Dex(z.read(n))))
    return out

def code_items(dx, cd):
    off = cd[6]
    if off == 0: return
    st, off = uleb(dx.d, off); it, off = uleb(dx.d, off)
    dm, off = uleb(dx.d, off); vm, off = uleb(dx.d, off)
    idx = 0
    for cnt in (st, it):
        idx = 0
        for _ in range(cnt):
            _, off = uleb(dx.d, off); _, off = uleb(dx.d, off)
    midx = 0
    for cnt in (dm, vm):
        midx = 0
        for _ in range(cnt):
            df, off = uleb(dx.d, off); _, off = uleb(dx.d, off); co, off = uleb(dx.d, off)
            midx += df
            if co: yield midx, co

def callers(jar, name):
    for dexname, dx in dexs(jar):
        tgt = {i for i, (c, p, n) in enumerate(dx.methods) if dx.s(n) == name}
        if not tgt: continue
        for cd in dx.classes:
            cn = dx.tn(cd[0])
            for mi, co in code_items(dx, cd):
                insns_size = struct.unpack_from('<I', dx.d, co + 12)[0]
                base = co + 16
                words = struct.unpack_from('<%dH' % insns_size, dx.d, base)
                for i in range(len(words) - 2):
                    op = words[i] & 0xff          # 35c 格式：op 在低字节，A 在高字节
                    if 0x6e <= op <= 0x72 and words[i + 2] in tgt:
                            c, pr, n = dx.methods[mi]
                            print(f"{name}  <-  {cn}.{dx.s(n)}")

def main():
    mode, jar, arg = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode == 'callers':
        callers(jar, arg); return
    for name, dx in dexs(jar):
        for cd in dx.classes:
            cn = dx.tn(cd[0])
            if mode == 'fields' and arg in cn:
                fs, ms = dx.members(cd)
                print(f"== {name} :: {cn}")
                print("   fields:", ", ".join(f"{n}:{t}" for n, t in fs) or "(none)")
                print("   super :", dx.tn(cd[2]))
                print("   methods:", ", ".join(sorted(set(ms))[:60]))
            elif mode == 'method' and arg:
                _, ms = dx.members(cd)
                if arg in ms:
                    print(f"{arg}  <-  {cn}")
            elif mode == 'field' and arg:
                fs, _ = dx.members(cd)
                if any(n == arg for n, t in fs):
                    kinds = ", ".join(f"{t}" for n, t in fs if n == arg)
                    print(f"field {arg} ({kinds})  <-  {cn}")

main()
