#!/usr/bin/env bash
# OS4FreeFromX 离线构建（arm64 proot 容器，无 gradle/AGP）
#   aapt2(资源+清单) → kotlinc → d8 → 组装 APK → 签名
#
# 本机踩过的坑（务必保留这些注释）：
#   1. aapt2 只能用 Debian 版；它读不了 SDK 35/36 的 android.jar，
#      但**读得了 android-framework-res.apk**（tools/android-framework-res.apk）。
#   2. kotlinc 跑在 proot 里，classpath 里**绝不能出现含 java.* 的 jar**
#      （会把 JDK 的 java.security 遮蔽 → InternalError）。
#      所以编译用 tools/android-api-only.jar（已剔除 java/*）。
#   3. kotlin classpath 只放 compiler/stdlib/reflect/script-runtime/annotations，
#      放 kotlin-daemon 或脚本引擎会触发插件 ServiceLoader 报错。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

JAVA_HOME=${JAVA_HOME_OVERRIDE:-/usr/lib/jvm/java-17-openjdk-arm64}
JAVA=$JAVA_HOME/bin/java
SRC=$HERE/app/src/main
OUT=$HERE/build
LIBXP=$HERE/app/libs/libxposed-api-102.jar
KDIR=$HERE/tools/kotlinc/lib
KSTDLIB=$KDIR/kotlin-stdlib.jar
KCP="$KDIR/kotlin-compiler.jar:$KDIR/kotlin-stdlib.jar:$KDIR/kotlin-reflect.jar:$KDIR/kotlin-script-runtime.jar:$KDIR/annotations-13.0.jar"
AJAR=$HERE/tools/android-api-only.jar
RESJAR=$HERE/tools/android-framework-res.apk
AAPT2=/usr/bin/aapt2
R8=$HERE/tools/r8.jar
KS=${KS:-$HERE/keystore/os4freeformx.jks}

VER=$(sed -n 's/^version=//p' "$SRC/resources/META-INF/xposed/module.prop")
VCODE=$(sed -n 's/^versionCode=//p' "$SRC/resources/META-INF/xposed/module.prop")

rm -rf "$OUT"; mkdir -p "$OUT/kt" "$OUT/dex" "$HERE/dist"

echo "== 1/6 aapt2 compile =="
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$AAPT2" link -o "$OUT/base.apk" -I "$RESJAR" \
    --manifest "$SRC/AndroidManifest.xml" -R "$OUT/res.zip" \
    --auto-add-overlay \
    --min-sdk-version 33 --target-sdk-version 36 \
    --version-code "$VCODE" --version-name "$VER"

echo "== 3/6 kotlinc =="
find "$SRC/kotlin" -name '*.kt' > "$OUT/sources.txt"
"$JAVA" -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -jvm-target 17 -no-stdlib \
    -classpath "$AJAR:$LIBXP:$KSTDLIB" \
    -d "$OUT/kt" @"$OUT/sources.txt"

echo "== 4/6 d8 =="
STDLIB_DEX=$HERE/tools/kotlin-stdlib.dex
if [ ! -f "$STDLIB_DEX" ]; then
    echo "   （首次）预编译 kotlin-stdlib.dex"
    "$JAVA" -cp "$R8" com.android.tools.r8.D8 --release --min-api 33 \
        --lib "$AJAR" --output "$OUT/dex" "$KSTDLIB"
    mv "$OUT/dex/classes.dex" "$STDLIB_DEX"
    rm -rf "$OUT/dex"; mkdir -p "$OUT/dex"
fi
"$JAVA" -cp "$R8" com.android.tools.r8.D8 --release --min-api 33 \
    --lib "$AJAR" --classpath "$LIBXP" --classpath "$KSTDLIB" \
    --output "$OUT/dex" $(find "$OUT/kt" -name '*.class')

# kotlin-stdlib 必须一起打包为 classes2.dex：
#   LSPosed 用 InMemoryDexFile 加载模块 dex，只给 classes.dex 时会缺
#   kotlin.jvm.internal.Intrinsics → onPackageLoaded 抛 ClassNotFoundException，
#   表现为「模块日志一句都没有」。2026-10-22 实测踩坑。
cp -f "$STDLIB_DEX" "$OUT/dex/classes2.dex"

echo "== 5/6 组装 APK =="
python3 - "$OUT/base.apk" "$OUT/dex" "$SRC/resources" "$OUT/unsigned.apk" <<'PY'
import sys, os, zipfile
base, dexdir, resdir, outapk = sys.argv[1:5]
if os.path.exists(outapk):
    os.remove(outapk)
with zipfile.ZipFile(base) as zbase, zipfile.ZipFile(outapk, 'w', zipfile.ZIP_DEFLATED) as out:
    for n in zbase.namelist():
        out.writestr(n, zbase.read(n))
    for f in sorted(os.listdir(dexdir)):
        if f.endswith('.dex'):
            out.write(os.path.join(dexdir, f), f)
    for root, _, files in os.walk(resdir):
        for f in files:
            full = os.path.join(root, f)
            out.write(full, os.path.relpath(full, resdir))
print('packed', outapk)
PY

echo "== 6/6 签名 =="
if [ ! -f "$KS" ]; then
    echo "   生成签名密钥"
    mkdir -p "$(dirname "$KS")"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias os4freeformx \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass os4freeformx -keypass os4freeformx \
        -dname "CN=AbelDuan, OU=OS4FreeFromX, O=Abel, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
APKOUT=$HERE/dist/OS4FreeFromX-v$VER.apk
cp -f "$OUT/unsigned.apk" "$APKOUT"
"$JAVA_HOME/bin/jarsigner" -keystore "$KS" -storepass os4freeformx -keypass os4freeformx \
    -sigalg SHA256withRSA -digestalg SHA-256 "$APKOUT" os4freeformx >/dev/null 2>&1 \
    && echo "   已签名" || echo "   ⚠️ 签名失败（产物为未签名包）"

echo
echo "✅ $APKOUT ($(du -h "$APKOUT" | cut -f1))"
