#!/usr/bin/env bash
# OS4FreeFromX Windows 构建（bash 直译 build_ps.ps1）。
# 关键点：所有传给 Windows .exe / java 的参数必须用 C:/ 盘符形式，
# 否则 MSYS 不会转换 /c/...，导致 .exe 找不到文件。
set -u
HERE_W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/Freeform"
HERE_U="/c/Users/Abel/WorkBuddy/2026-09-20-12-28-11/Freeform"
SRC_W="$HERE_W/app/src/main"
SRC_U="$HERE_U/app/src/main"
OUT_W="$HERE_W/build"; OUT_U="$HERE_U/build"
DIST_W="$HERE_W/dist"; DIST_U="$HERE_U/dist"
KS_W="$HERE_W/keystore/os4freeformx.jks"

JAVA_HOME_W="C:/AndroidBuild/jdk17_extract/jdk-17.0.20+8"
JAVA="$JAVA_HOME_W/bin/java.exe"
JAR="$JAVA_HOME_W/bin/jar.exe"
KEYTOOL="$JAVA_HOME_W/bin/keytool.exe"
AAPT2="C:/android/sdk/build-tools/35.0.0/aapt2.exe"
ZIPALIGN="C:/android/sdk/build-tools/35.0.0/zipalign.exe"
D8_JAR="C:/android/sdk/build-tools/35.0.0/lib/d8.jar"
APKSIGNER_JAR="C:/android/sdk/build-tools/35.0.0/lib/apksigner.jar"
ANDROID_JAR="C:/android/sdk/platforms/android-36/android.jar"
KOTLINC_JAR="C:/AndroidBuild/gradle-8.10_extract/gradle-8.10/lib/kotlin-compiler-embeddable-1.9.24.jar"
KOTLIN_STDLIB="C:/AndroidBuild/gradle-8.10_extract/gradle-8.10/lib/kotlin-stdlib-1.9.24.jar"
TROVE4J="C:/AndroidBuild/gradle-8.10_extract/gradle-8.10/lib/trove4j-1.0.20200330.jar"
ANNOTATIONS="C:/AndroidBuild/gradle-8.10_extract/gradle-8.10/lib/annotations-24.0.1.jar"
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
KCP="$KOTLINC_JAR;$KOTLIN_STDLIB;$TROVE4J;$ANNOTATIONS"
"$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -jvm-target 17 \
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
