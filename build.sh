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
"$KOTLINC" -nowarn -jvm-target 17 \
    -classpath "$ANDROID_JAR:$LIBXP" \
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
"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 6/6 签名 =="
if [ ! -f "$KS" ]; then
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
