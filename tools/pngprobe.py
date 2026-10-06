#!/usr/bin/env python3
"""纯标准库 PNG 探针：解码 screencap 出的 PNG，取像素颜色 / 裁剪导出。

用法：
  pngprobe.py color <png> <x> <y> [<x2> <y2> ...]      # 打印若干坐标的 RGBA
  pngprobe.py row   <png> <y> <x0> <x1> [step]         # 打印某行一段的颜色（找文字/底色）
  pngprobe.py crop  <png> <x> <y> <w> <h> <out.png>    # 裁剪导出（放大 3 倍便于看清）
"""
import struct, sys, zlib

def decode(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', 'not png'
    pos, idat, w, h, bd, ct = 8, b'', 0, 0, 8, 6
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]
        typ = d[pos+4:pos+8]
        data = d[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', data)
            assert bd == 8 and inter == 0, f'unsupported bd={bd} interlace={inter}'
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    stride = w * ch
    out = bytearray(stride * h)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(ch, stride): line[i] = (line[i] + line[i-ch]) & 0xFF
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

def px(w, ch, buf, x, y):
    i = (y*w + x)*ch
    return tuple(buf[i:i+ch]) if ch == 4 else (buf[i], buf[i+1], buf[i+2], 255)

def write_png(path, w, h, rows_rgba):
    raw = b''.join(b'\x00' + bytes(r) for r in rows_rgba)
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t+d) & 0xFFFFFFFF)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 6))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)

if __name__ == '__main__':
    cmd = sys.argv[1]
    w, h, ch, buf = decode(sys.argv[2])
    if cmd == 'color':
        rest = [int(v) for v in sys.argv[3:]]
        for i in range(0, len(rest), 2):
            x, y = rest[i], rest[i+1]
            r, g, b, a = px(w, ch, buf, x, y)
            lum = 0.299*r + 0.587*g + 0.114*b
            print(f"({x},{y}) = #{r:02X}{g:02X}{b:02X} a={a} lum={lum:.0f}")
    elif cmd == 'row':
        y, x0, x1 = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        step = int(sys.argv[6]) if len(sys.argv) > 6 else 4
        print(f"row y={y} x={x0}..{x1} step={step} (size {w}x{h})")
        out = []
        for x in range(x0, x1, step):
            r, g, b, a = px(w, ch, buf, x, y)
            out.append(f"{x}:#{r:02X}{g:02X}{b:02X}")
        for i in range(0, len(out), 8):
            print('  ' + ' '.join(out[i:i+8]))
    elif cmd == 'crop':
        x, y, cw, chh, outp = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), sys.argv[7]
        sc = 3
        rows = []
        for j in range(chh):
            row = bytearray()
            for i in range(cw):
                r, g, b, a = px(w, ch, buf, x+i, y+j)
                row += bytes((r, g, b, a)) * sc
            for _ in range(sc):
                rows.append(row)
        write_png(outp, cw*sc, chh*sc, rows)
        print(f"wrote {outp} {cw*sc}x{chh*sc}")
