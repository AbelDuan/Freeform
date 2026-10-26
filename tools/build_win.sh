#!/usr/bin/env bash
# OS4FreeFromX Windows 构建（bash 直译 build_ps.ps1）。
# 关键点：所有传给 Windows .exe / java 的参数必须用 C:/ 盘符形式，
# 否则 MSYS 不会转换 /c/...，导致 .exe 找不到文件。
set -u
# 仓库根目录：从脚本位置推导（原来写死某台机器的绝对路径，换目录/换工作树就跑不了）
HERE_U="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE_W="$(cd "$HERE_U" && pwd -W 2>/dev/null || echo "$HERE_U")"
SRC_W="$HERE_W/app/src/main"
SRC_U="$HERE_U/app/src/main"
OUT_W="$HERE_W/build"; OUT_U="$HERE_U/build"
DIST_W="$HERE_W/dist"; DIST_U="$HERE_U/dist"
KS_W="$HERE_W/keystore/os4freeformx.jks"

JAVA_HOME_W="C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot"
JAVA="$JAVA_HOME_W/bin/java.exe"
JAR="$JAVA_HOME_W/bin/jar.exe"
KEYTOOL="$JAVA_HOME_W/bin/keytool.exe"
AAPT2="C:/Android/Sdk/build-tools/35.0.0/aapt2.exe"
ZIPALIGN="C:/Android/Sdk/build-tools/35.0.0/zipalign.exe"
D8_JAR="C:/Android/Sdk/build-tools/35.0.0/lib/d8.jar"
APKSIGNER_JAR="C:/Android/Sdk/build-tools/35.0.0/lib/apksigner.jar"
ANDROID_JAR="C:/Android/Sdk/platforms/android-36/android.jar"

# ── Kotlin 编译器：必须 K2（≥2.0）────────────────────────────────────────────
# 0.6.x 的源码里有「布尔局部变量 + 空判断 → 智能转换」的写法（如 Hooks.kt 的
#   `val ff = now != null && ...; when { ff -> taskBounds(now) }`），
# 这种数据流智能转换只有 K2 支持；用 Gradle 8.10 自带的 1.9.24（K1）会直接报
#   `type mismatch: inferred type is Any? but Any was expected` —— 报的却是业务代码，
# 极易误判成自己写错了。这里优先挑 Gradle 8.14.5 自带的 2.0.21，找不到才退回 1.9.24。
KVER=""; KDIR=""
for base in C:/Users/Abel/.gradle/wrapper/dists/gradle-8.14.5-bin/*/gradle-8.14.5/lib; do
  if [ -f "$base/kotlin-compiler-embeddable-2.0.21.jar" ]; then KVER="2.0.21"; KDIR="$base"; break; fi
done
if [ -z "$KDIR" ]; then
  KVER="1.9.24"
  KDIR="C:/Users/Abel/.gradle/wrapper/dists/gradle-8.10-bin/deqhafrv1ntovfmgh0nh3npr9/gradle-8.10/lib"
  echo "!!! 没找到 Kotlin 2.x 编译器，退回 $KVER —— 0.6.x 源码可能编不过（见脚本注释）"
fi
echo "Kotlin: $KVER ($KDIR)"
KOTLINC_JAR="$KDIR/kotlin-compiler-embeddable-$KVER.jar"
KOTLIN_STDLIB="$KDIR/kotlin-stdlib-$KVER.jar"
TROVE4J="$KDIR/trove4j-1.0.20200330.jar"
ANNOTATIONS="$KDIR/annotations-24.0.1.jar"
KCP3="$KOTLINC_JAR;$KOTLIN_STDLIB;$TROVE4J;$ANNOTATIONS"
# K2 的 embeddable 编译器还要 kotlinx-coroutines（否则 ClassNotFoundException: CoroutineScope）
COROUTINES="$KDIR/kotlinx-coroutines-core-jvm-1.6.4.jar"
[ -f "$COROUTINES" ] && KCP3="$KCP3;$COROUTINES"
LIBXP="$HERE_W/app/libs/libxposed-api-102.jar"

# 版本单一来源：从 module.prop 读，避免与 aapt2 versionName 打架
# ⚠ module.prop 是 CRLF 的话值会带 \r → 产物名变成 "v0.2.0\r.apk"，必须 tr -d '\r'
MPROP_U="$SRC_U/resources/META-INF/xposed/module.prop"
VER="$(sed -n 's/^version=//p' "$MPROP_U" | tr -d '\r\n')"
VCODE="$(sed -n 's/^versionCode=//p' "$MPROP_U" | tr -d '\r\n')"
[ -n "$VER" ] || { echo "!!! 读不到 module.prop 的 version"; exit 1; }
[ -n "$VCODE" ] || { echo "!!! 读不到 module.prop 的 versionCode"; exit 1; }
echo "版本: version=$VER versionCode=$VCODE (来自 module.prop)"
fail(){ echo "!!! $1 (exit $2)"; exit $2; }

mkdir -p "$HERE_W/keystore"
rm -rf "$OUT_U"; mkdir -p "$OUT_U/res" "$OUT_U/gen" "$OUT_U/kt" "$OUT_U/dex" "$OUT_U/stdlib_dex" "$DIST_U"

echo "== 1/6 aapt2 compile =="
"$AAPT2" compile --dir "$SRC_W/res" -o "$OUT_W/res.zip" || fail "aapt2 compile" $?
echo "== 2/6 aapt2 link =="
"$AAPT2" link -o "$OUT_W/base.apk" -I "$ANDROID_JAR" --manifest "$SRC_W/AndroidManifest.xml" \
  -R "$OUT_W/res.zip" --auto-add-overlay --java "$OUT_W/gen" \
  --min-sdk-version 33 --target-sdk-version 36 --version-code "$VCODE" --version-name "$VER" || fail "aapt2 link" $?

echo "== 3/6 kotlinc =="
find "$SRC_U/kotlin" -name '*.kt' | sed 's|^/c/|C:/|' > "$OUT_W/sources.txt"
wc -l < "$OUT_W/sources.txt"
CP3="$ANDROID_JAR;$LIBXP;$KOTLIN_STDLIB"
"$JAVA" -Xmx3000m -cp "$KCP3" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -jvm-target 17 \
  -classpath "$CP3" -d "$OUT_W/kt" "@$OUT_W/sources.txt" || fail "kotlinc" $?

echo "== 4/6 d8 =="
( cd "$OUT_U/kt" && "$JAR" cf "$OUT_W/kt.jar" . ) || fail "jar kt" $?
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 33 --lib "$ANDROID_JAR" \
  --classpath "$LIBXP" --classpath "$KOTLIN_STDLIB" --output "$OUT_W/dex" "$OUT_W/kt.jar" || fail "d8(1)" $?
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 33 --lib "$ANDROID_JAR" \
  --output "$OUT_W/stdlib_dex" "$KOTLIN_STDLIB" || fail "d8(2)" $?
mv -f "$OUT_W/stdlib_dex/classes.dex" "$OUT_W/dex/classes2.dex" || fail "move classes2" $?

echo "== 5/6 assemble =="
cp -f "$OUT_W/base.apk" "$OUT_W/app-unsigned.apk"
( cd "$OUT_U/dex" && "$JAR" uf "$OUT_W/app-unsigned.apk" classes.dex classes2.dex ) || fail "jar dex" $?
( cd "$SRC_U/resources" && "$JAR" uf "$OUT_W/app-unsigned.apk" META-INF ) || fail "jar meta" $?
"$ZIPALIGN" -f -p 4 "$OUT_W/app-unsigned.apk" "$OUT_W/app-aligned.apk" || fail "zipalign" $?

echo "== 6/6 sign =="
if [ ! -f "$KS_W" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS_W" -alias os4freeformx -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=OS4FreeFromX,O=AbelDuan,C=CN"
fi
APK="$DIST_W/OS4FreeFromX-v$VER.apk"
"$JAVA" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS_W" --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT_W/app-aligned.apk" || fail "sign" $?
"$JAVA" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool verify "$APK" >/dev/null 2>&1
echo "签名校验退出码=$?"
ls -l "$APK"
echo "=== BUILD DONE ==="
