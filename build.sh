#!/usr/bin/env bash
# OS4FreeFromX 离线构建（arm64 容器，无 gradle/AGP）
#   aapt2(编译资源+链接清单) → kotlinc → d8 → zip(classes.dex + META-INF/xposed) → zipalign → apksigner
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/env.sh"

SRC=$HERE/app/src/main
OUT=${OUT:-$HERE/build}
LIBXP=$HERE/app/libs/libxposed-api-102.jar
KS=${KS:-$HERE/keystore/os4freeformx.jks}   # 必须放在 build/ 之外：build 每轮会被清空，否则签名每轮都变
VER=$(sed -n 's/^version=//p' "$SRC/resources/META-INF/xposed/module.prop")
VCODE=$(sed -n 's/^versionCode=//p' "$SRC/resources/META-INF/xposed/module.prop")
# 每次构建递增 versionCode：pm install -r 只有版本号变化才会分配新的 codePath，
# 而 lspd 按路径缓存 dex —— 路径不变就一直加载旧字节码（本机实测踩坑）。
VCODE=$((VCODE + 1))
sed -i "s/^versionCode=.*/versionCode=$VCODE/" "$SRC/resources/META-INF/xposed/module.prop"

# 容器没有 zip（/usr/local/bin 会被 app 更新/重建清掉）→ 自愈：从仓库里的 shim 装一份
if ! command -v zip >/dev/null 2>&1 && [ -f "$HERE/tools/zip-shim.py" ]; then
    cp -f "$HERE/tools/zip-shim.py" /usr/local/bin/zip && chmod +x /usr/local/bin/zip
fi

rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/kt" "$OUT/dex" "$OUT/stdlib" "$HERE/dist"

echo "== 1/6 aapt2 compile =="
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$AAPT2" link -o "$OUT/base.apk" -I "${ANDROID_RES_JAR:-$ANDROID_JAR}" \
    --manifest "$SRC/AndroidManifest.xml" -R "$OUT/res.zip" \
    --auto-add-overlay --java "$OUT/gen" \
    --min-sdk-version 33 --target-sdk-version 36 \
    --version-code "$VCODE" --version-name "$VER"

echo "== 3/6 kotlinc =="
find "$SRC/kotlin" -name '*.kt' > "$OUT/sources.txt"
"$KOTLINC" -nowarn -jvm-target 17 -no-stdlib \
    -classpath "$ANDROID_JAR:$LIBXP:$KOTLIN_STDLIB" \
    -d "$OUT/kt" @"$OUT/sources.txt"

echo "== 4/6 d8 =="
# kotlin-stdlib 大且不变：只 dex 一次，缓存为 classes2.dex（多 dex APK，minSdk 33 原生支持）
STDLIB_DEX=$HERE/tools/kotlin-stdlib.dex
if [ ! -f "$STDLIB_DEX" ]; then
    echo "   （首次）预编译 kotlin-stdlib.dex"
    "$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --output "$OUT/stdlib" "$KOTLIN_STDLIB"
    mv "$OUT/stdlib/classes.dex" "$STDLIB_DEX"
fi
"$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --classpath "$LIBXP" --classpath "$KOTLIN_STDLIB" \
    --output "$OUT/dex" \
    $(find "$OUT/kt" -name '*.class')

echo "== 5/6 组装 APK =="
cp -f "$OUT/base.apk" "$OUT/app-unsigned.apk"
cp -f "$STDLIB_DEX" "$OUT/dex/classes2.dex"
( cd "$OUT/dex" && zip -q -X "$OUT/app-unsigned.apk" classes.dex classes2.dex )
( cd "$SRC/resources" && zip -q -X -r "$OUT/app-unsigned.apk" META-INF )

# 可选 native 库（LSPosed native_init.list 声明的 so；APK 规范要求位于 lib/<abi>/ 下）
NATIVE_LIBS=${NATIVE_LIBS:-$HERE/app/libs}
if [ -d "$NATIVE_LIBS/arm64-v8a" ] && [ -n "$(ls -A "$NATIVE_LIBS/arm64-v8a" 2>/dev/null)" ]; then
    echo "  附加 native 库："
    python3 - "$NATIVE_LIBS" "$OUT/app-unsigned.apk" <<'PY'
import os, sys, zipfile
root, apk = sys.argv[1], sys.argv[2]
abi = "arm64-v8a"
d = os.path.join(root, abi)
# .so 必须以 STORED（不压缩）写入：安装时 PackageManager 要按页对齐直接映射，
# 压缩过的 .so 会报 INSTALL_FAILED_INVALID_APK: Failed to extract native libraries
with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as z:
    for f in sorted(os.listdir(d)):
        if not f.endswith(".so"):
            continue
        arc = "lib/%s/%s" % (abi, f)
        src = os.path.join(d, f)
        zi = zipfile.ZipInfo.from_file(src, arc)
        zi.compress_type = zipfile.ZIP_STORED
        with open(src, "rb") as fh, z.open(zi, "w") as out:
            out.write(fh.read())
        print("    " + arc)
PY
else
    echo "  （无 native 库需要打包）"
fi

"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 6/6 签名 =="
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"   # keytool 不自建父目录，缺目录会报 FileNotFoundException
    keytool -genkeypair -keystore "$KS" -alias os4freeformx -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass android -keypass android \
        -dname "CN=OS4FreeFromX,O=AbelDuan,C=CN"
fi
APK="$HERE/dist/OS4FreeFromX-v$VER.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$APK" >/dev/null && echo "签名校验通过"

echo
echo "产物：$APK"
unzip -l "$APK" | grep -E "classes.dex|META-INF/xposed|AndroidManifest" || true
