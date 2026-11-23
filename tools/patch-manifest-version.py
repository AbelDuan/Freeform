#!/usr/bin/env python3
"""改写 APK 二进制 AndroidManifest.xml 里的 versionCode / versionName。

用于模板构建路径：没有 aapt2 时清单来自 app/libs/template.apk，无法像
aapt2 link 那样用 --version-code/--version-name 覆盖，只能原地补丁。

versionName 要求与旧值等长（UTF-8 字节数相同），否则拒绝修改——避免破坏
字符串池偏移。典型场景：模板 0.8.4 → 0.9.4（5 字节 → 5 字节）。

用法：
    python3 tools/patch-manifest-version.py <apk> <versionCode> <versionName>
"""
import struct
import sys
import zipfile

CHUNK_STRING_POOL = 0x0001
CHUNK_START_ELEMENT = 0x0102
TYPED_TYPE_INT_DEC = 0x10
TYPED_TYPE_STRING = 0x03


def walk_chunks(data):
    off, end = 8, len(data)
    while off + 8 <= end:
        typ, hsize, size = struct.unpack_from("<HHI", data, off)
        if size < 8:
            break
        yield typ, hsize, size, off
        off += size


def read_strings(data, off):
    """返回 [(byte_offset, byte_len, value)]，byte_len 指字符串内容字节数。"""
    typ, hsize, size = struct.unpack_from("<HHI", data, off)
    if typ != CHUNK_STRING_POOL:
        raise SystemExit("AndroidManifest.xml 里没有字符串池")
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, off + 8
    )
    utf8 = bool(flags & 0x100)
    base = off + strings_start
    offsets = struct.unpack_from("<%dI" % string_count, data, off + 28)
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            m = data[p]
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append((p, m, data[p:p + m].decode("utf-8", "replace")))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append((p, n * 2, data[p:p + n * 2].decode("utf-16-le", "replace")))
    return out, utf8


def find_attr_values(data, strings, name):
    """返回 [(value_offset, data_type, data_value)]。"""
    try:
        idx = [i for i, (_, _, s) in enumerate(strings) if s == name]
    except Exception:
        idx = []
    if not idx:
        return []
    idx = idx[0]
    hits = []
    for typ, hsize, size, off in walk_chunks(data):
        if typ != CHUNK_START_ELEMENT:
            continue
        attr_start, attr_size, attr_count, *_ = struct.unpack_from("<HHHHHH", data, off + 24)
        for i in range(attr_count):
            a = off + 16 + attr_start + i * attr_size
            ns, aname, raw, tvsize, res0, dtype, tdata = struct.unpack_from("<IIIHBBI", data, a)
            if aname == idx:
                hits.append((a + 16, dtype, tdata))
    return hits


def main(apk, version_code, version_name):
    version_code = int(version_code)
    with zipfile.ZipFile(apk) as z:
        manifest = z.read("AndroidManifest.xml")
        items = [(i, z.read(i.filename)) for i in z.infolist()]
    data = bytearray(manifest)

    strings, utf8 = read_strings(data, 8)

    # 1) versionCode
    hits = find_attr_values(data, strings, "versionCode")
    if not hits:
        raise SystemExit("清单里找不到 versionCode 属性")
    for value_off, dtype, tdata in hits:
        if dtype not in (TYPED_TYPE_INT_DEC,):
            raise SystemExit(f"versionCode 类型异常: {dtype:#x}")
        struct.pack_into("<I", data, value_off, version_code)
        print(f"versionCode: {tdata} -> {version_code}")

    # 2) versionName：只做等长替换，保证字符串池偏移不变
    hits = find_attr_values(data, strings, "versionName")
    if not hits:
        raise SystemExit("清单里找不到 versionName 属性")
    for value_off, dtype, tdata in hits:
        if dtype != TYPED_TYPE_STRING:
            raise SystemExit(f"versionName 类型异常: {dtype:#x}")
        entry = strings[tdata]
        str_off, str_len, old = entry
        new_bytes = version_name.encode("utf-8") if utf8 else version_name.encode("utf-16-le")
        if len(new_bytes) != str_len:
            raise SystemExit(
                f"versionName 长度不一致：旧 {old!r}（{str_len} 字节），"
                f"新 {version_name!r}（{len(new_bytes)} 字节）——等长才能原地替换"
            )
        data[str_off:str_off + str_len] = new_bytes
        print(f"versionName: {old!r} -> {version_name!r}")

    with zipfile.ZipFile(apk.replace(".apk", ".tmp.apk"), "w") as zout:
        for item, blob in items:
            if item.filename == "AndroidManifest.xml":
                blob = bytes(data)
            zout.writestr(item, blob)
    import os
    os.replace(apk.replace(".apk", ".tmp.apk"), apk)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
