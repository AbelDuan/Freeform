#!/usr/bin/env bash
# 只换 dex 的构建路径（容器里 aapt2 被重建弄坏时的等价方案）
#
# 为什么等价：这一步只重编译 Kotlin → classes.dex，资源/清单/版本号都不变，
# 所以直接复用 BASE apk 里已编译好的 resources.arsc + AndroidManifest.xml + META-INF/xposed。
# 用法： ./build_dexswap.sh [BASE.apk]     产物：dist/OS4FreeFromX-v<VER>-dexswap.apk
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/env.sh"

SRC=$HERE/app/src/main
OUT=$HERE/build
LIBXP=$HERE/app/libs/libxposed-api-102.jar
KS=${KS:-$HERE/keystore/os4freeformx.jks}
BASE=${1:-$HERE/dist/OS4FreeFromX-v0.4.0-nbi-nosplit.apk}
VER=$(sed -n 's/^version=//p' "$SRC/resources/META-INF/xposed/module.prop")

[ -f "$BASE" ] || { echo "缺少 BASE apk: $BASE"; exit 1; }
[ -f "$KS" ]   || { echo "缺少签名密钥: $KS"; exit 1; }

rm -rf "$OUT/kt" "$OUT/dex"; mkdir -p "$OUT/kt" "$OUT/dex" "$HERE/dist"

echo "== 1/4 kotlinc =="
find "$SRC/kotlin" -name '*.kt' > "$OUT/sources.txt"
"$KOTLINC" -nowarn -jvm-target 17 -no-stdlib -classpath "$ANDROID_JAR:$LIBXP:$KOTLIN_STDLIB" -d "$OUT/kt" @"$OUT/sources.txt"

echo "== 2/4 d8 =="
"$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --classpath "$LIBXP" --classpath "$KOTLIN_STDLIB" \
    --output "$OUT/dex" $(find "$OUT/kt" -name '*.class')

echo "== 3/4 组装（复用 BASE 的 manifest/资源，只换 dex）=="
cp -f "$HERE/tools/kotlin-stdlib.dex" "$OUT/dex/classes2.dex"
python3 - "$BASE" "$OUT/dex" "$OUT/app-unsigned.apk" <<'PY'
import sys, zipfile
base, dexdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
drop = ("classes.dex", "classes2.dex", "META-INF/MANIFEST.MF")
zin = zipfile.ZipFile(base)
with zipfile.ZipFile(out, "w") as zout:
    for it in zin.infolist():
        n = it.filename
        if n in drop or (n.startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))):
            continue
        zi = zipfile.ZipInfo(n, date_time=it.date_time)
        zi.compress_type = it.compress_type        # 保留 STORED/DEFLATED（resources.arsc 必须不压缩）
        zi.external_attr = it.external_attr
        zout.writestr(zi, zin.read(n))
    for d in ("classes.dex", "classes2.dex"):
        zi = zipfile.ZipInfo(d, date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        with open(f"{dexdir}/{d}", "rb") as f:
            zout.writestr(zi, f.read())
print("zip 重写完成:", out)
PY

echo "== 4/4 zipalign + 签名 =="
"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
APK="$HERE/dist/OS4FreeFromX-v$VER-dexswap.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$APK" >/dev/null && echo "签名校验通过"
echo "产物：$APK"
python3 -c "
import zipfile,sys
z=zipfile.ZipFile('$APK')
names=[n.filename for n in z.infolist()]
print('条目数',len(names),'| dex:',[n for n in names if n.endswith('.dex')],'| xposed:',[n for n in names if 'xposed' in n])
d=z.read('classes.dex').count('落盘('.encode())
print('新代码标记「落盘(」出现',d,'次')
"
