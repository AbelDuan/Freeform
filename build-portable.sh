#!/usr/bin/env bash
# OS4FreeFromX 可移植构建脚本
#
# 目标：在**任何** arm64 Linux / proot 容器里，clone 下来就能编译，不依赖仓库外的私有路径。
#
# 需要的工具（按顺序自动探测，全部找不到时报错并给出获取方式）：
#   JDK 17+        系统包 / $JAVA_HOME
#   kotlin 编译器   $KOTLINC / PATH 里的 kotlinc / tools/kotlinc
#   d8 + apksigner $ANDROID_SDK / tools/sdk/build-tools/*
#   android.jar    $ANDROID_SDK/platforms/android-*/android.jar
#   aapt2+zipalign 系统音频 / tools/android-tools（Debian arm64 版）
#
# 仓库内已自带（无需外部获取）：
#   app/libs/libxposed-api-102.jar   LSPosed API 102
#   keystore/os4freeformx.jks        签名密钥（别名 os4freeformx，密码 android）
#
# 用法：./build-portable.sh            # 产物 dist/OS4FreeFromX-v<版本>.apk
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/app/src/main
OUT=${OUT:-$HERE/build}
LIBXP=$HERE/app/libs/libxposed-api-102.jar
KS=${KS:-$HERE/keystore/os4freeformx.jks}
KS_PASS=${KS_PASS:-android}
KS_ALIAS=${KS_ALIAS:-os4ffx}

die() { printf '错误：%s\n' "$*" >&2; exit 1; }

# ---------- 1. JDK ----------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    :
elif command -v javac >/dev/null 2>&1; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
else
    for d in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-arm64 /opt/jdk-17*; do
        if [ -x "$d/bin/javac" ]; then JAVA_HOME=$d; break; fi
    done
fi
[ -x "${JAVA_HOME:-}/bin/javac" ] || die "找不到 JDK 17+。安装：apt install openjdk-21-jdk-headless，或设 JAVA_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- 2. Kotlin 编译器 ----------
if [ -n "${KOTLINC:-}" ] && [ -x "$KOTLINC" ]; then
    :
elif command -v kotlinc >/dev/null 2>&1; then
    KOTLINC=$(command -v kotlinc)
elif [ -x "$HERE/tools/kotlinc/bin/kotlinc" ]; then
    KOTLINC=$HERE/tools/kotlinc/bin/kotlinc
else
    die "找不到 kotlinc。获取：https://github.com/JetBrains/kotlin/releases 下载 kotlin-compiler-*.zip 解压到 tools/kotlinc，或设 KOTLINC=/path/to/kotlinc"
fi
export PATH="$(dirname "$KOTLINC"):$PATH"

# kotlin-stdlib 直接取编译器发行版自带的那份。
# ⚠️ 必须**精确**取 kotlin-stdlib.jar：通配 kotlin-stdlib*.jar 会先命中
#    kotlin-stdlib-jdk7.jar（字母序在前），它不含核心类型，编译报
#    "cannot access built-in declaration 'kotlin.String'"。
KOTLIN_LIB=$(dirname "$(dirname "$KOTLINC")")/lib
for cand in "$KOTLIN_LIB/kotlin-stdlib.jar" "$KOTLIN_LIB"/kotlin-stdlib-2*.jar; do
    if [ -f "$cand" ]; then KOTLIN_STDLIB=$cand; break; fi
done
[ -n "${KOTLIN_STDLIB:-}" ] || die "找不到 kotlin-stdlib.jar（应与 kotlinc 同目录的 lib/ 下）"

# ---------- 3. Android 平台与构建工具 ----------
SDK_CAND=""
for d in "${ANDROID_SDK:-}" "${ANDROID_HOME:-}" "$HERE/tools/sdk" /opt/android-sdk /usr/lib/android-sdk; do
    if [ -n "$d" ] && [ -d "$d" ]; then SDK_CAND=$d; break; fi
done

ANDROID_JAR=${ANDROID_JAR:-}
if [ -z "$ANDROID_JAR" ] && [ -n "$SDK_CAND" ]; then
    ANDROID_JAR=$(ls -d "$SDK_CAND"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -z "${ANDROID_JAR:-}" ]; then
    for j in /usr/lib/android-sdk/platforms/android-36/android.jar \
             /usr/lib/android-sdk/platforms/android-*/android.jar; do
        if [ -f "$j" ]; then ANDROID_JAR=$j; break; fi
    done
fi
[ -f "${ANDROID_JAR:-}" ] || die "找不到 android.jar。安装 android-sdk platform 35+，或设 ANDROID_JAR=/path/to/android.jar"

BT=""
if [ -n "$SDK_CAND" ] && [ -d "$SDK_CAND/build-tools" ]; then
    BT=$(ls -d "$SDK_CAND"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$BT" ] && [ -x "$BT/d8" ] || die "找不到 d8（Android build-tools）。安装 build-tools 34+，或设 ANDROID_SDK"

# ---------- 4. aapt2 / zipalign ----------
run_tool() {
    # proot 下 aapt2 需要显式指定动态库路径，且只认相对路径
    local exe=$1; shift
    if [ -d "$HERE/tools/android-tools" ]; then
        /lib/ld-linux-aarch64.so.1 --library-path "$HERE/tools/android-tools" "$exe" "$@"
    else
        LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}" "$exe" "$@"
    fi
}
AAPT2=${AAPT2:-$(command -v aapt2 || true)}
ZIPALIGN=${ZIPALIGN:-$(command -v zipalign || true)}

# 两条构建路径：
#   A. 有 aapt2 → 从 res/ + AndroidManifest.xml 完整编译（正规做法）
#   B. 没有    → 用 lib/template.apk 的资源与清单，只替换 classes*.dex
#      （proot 容器里 aapt2 常因缺 libdl.so 跑不起来，这条路径专门兜住它）
TEMPLATE=$HERE/app/libs/template.apk
if [ -x "${AAPT2:-}" ] && run_tool "$AAPT2" version >/dev/null 2>&1; then
    BUILD_MODE=aapt2
elif [ -f "$TEMPLATE" ]; then
    BUILD_MODE=template
    echo "提示：aapt2 不可用，改用模板 APK（$TEMPLATE）换 dex"
else
    die "既没有可用的 aapt2，也没有 app/libs/template.apk。二选一：安装 android-sdk，或用预置模板"
fi

[ -f "$LIBXP" ] || die "缺少 $LIBXP（LSPosed API jar，应随仓库分发）"
[ -f "$KS" ] || die "缺少签名密钥 $KS（应随仓库分发）"

# ---------- 5. 版本号 ----------
VER=$(sed -n 's/^version=//p' "$SRC/resources/META-INF/xposed/module.prop")
VCODE=$(sed -n 's/^versionCode=//p' "$SRC/resources/META-INF/xposed/module.prop")
# 递增 versionCode：pm install -r 只有版本号变化才会分配新 codePath，
# 而 lspd 按路径缓存 dex —— 路径不变会一直加载旧字节码。
VCODE=$((VCODE + 1))
sed -i "s/^versionCode=.*/versionCode=$VCODE/" "$SRC/resources/META-INF/xposed/module.prop"

rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/kt" "$OUT/java" "$OUT/dex" "$OUT/stdlib" "$HERE/dist"

if [ "$BUILD_MODE" = aapt2 ]; then
    echo "== 1/7 aapt2 compile =="
    ( cd "$HERE" && run_tool "$AAPT2" compile --dir ./app/src/main/res -o ./build/res.zip )

    echo "== 2/7 aapt2 link =="
    RES_JAR_REL=$(python3 -c "import os,sys;print(os.path.relpath(sys.argv[1], sys.argv[2]))" "${ANDROID_RES_JAR:-$ANDROID_JAR}" "$HERE")
    ( cd "$HERE" && run_tool "$AAPT2" link -o ./build/base.apk -I "$RES_JAR_REL" \
        --manifest ./app/src/main/AndroidManifest.xml -R ./build/res.zip \
        --auto-add-overlay --java ./build/gen \
        --min-sdk-version 33 --target-sdk-version 36 \
        --version-code "$VCODE" --version-name "$VER" )
else
    echo "== 1-2/7 模板 APK（跳过 aapt2）=="
    cp -f "$TEMPLATE" "$OUT/base.apk"
fi

echo "== 3a/7 引导编译（打破 Java ↔ Kotlin 双向依赖）=="
# Java 里大量调用 Kotlin 的 LogUtil / AppCtx，而 kotlinc 又需要 Java 的 class 作 classpath。
# 解法：先用 kotlinc 只编这几个「叶子」Kotlin 文件，javac 才能解析到它们。
BOOT_KT=""
for f in LogUtil.kt Logx.kt Constants.kt AppCtx.kt; do
    if [ -f "$SRC/kotlin/com/abel/os4freeformx/$f" ]; then
        BOOT_KT="$BOOT_KT $SRC/kotlin/com/abel/os4freeformx/$f"
    fi
done
mkdir -p "$OUT/boot"
if [ -n "$BOOT_KT" ]; then
    "$KOTLINC" -nowarn -jvm-target 17 -no-stdlib \
        -classpath "$ANDROID_JAR:$LIBXP:$KOTLIN_STDLIB" \
        -d "$OUT/boot" $BOOT_KT
    echo "   引导 class: $(find "$OUT/boot" -name '*.class' | wc -l)"
fi

echo "== 3b/7 javac =="
if find "$SRC/java" -name '*.java' 2>/dev/null | grep -q .; then
    find "$SRC/java" -name '*.java' > "$OUT/java-sources.txt"
    "$JAVA_HOME/bin/javac" -nowarn -source 17 -target 17 -encoding UTF-8 \
        -classpath "$ANDROID_JAR:$LIBXP:$KOTLIN_STDLIB:$OUT/boot" \
        -d "$OUT/java" @"$OUT/java-sources.txt"
else
    echo "   （无 Java 源，跳过）"
fi

echo "== 3c/7 kotlinc（全量）=="
find "$SRC/kotlin" -name '*.kt' > "$OUT/sources.txt"
"$KOTLINC" -nowarn -jvm-target 17 -no-stdlib \
    -classpath "$ANDROID_JAR:$LIBXP:$KOTLIN_STDLIB:$OUT/java:$OUT/boot" \
    -d "$OUT/kt" @"$OUT/sources.txt"

echo "== 4/7 d8 =="
STDLIB_DEX=$HERE/tools/kotlin-stdlib.dex
if [ ! -f "$STDLIB_DEX" ]; then
    echo "   （首次）预编译 kotlin-stdlib.dex"
    mkdir -p "$HERE/tools"
    "$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --output "$OUT/stdlib" "$KOTLIN_STDLIB"
    cp -f "$OUT/stdlib/classes.dex" "$STDLIB_DEX"
fi
# 收集待 dex 的 class，**Kotlin 优先**：
# 本项目里 OrientationForcer / RotationButtonOverlay / RotationHint 同时存在
# .java 与 .kt 两份实现（Java 版是早期从 OS4 Themer 原样移植的，Kotlin 版是后来的
# 重写，两者都还保留）。原 build.sh 靠「javac 先跑、kotlinc 后跑覆盖同名 class」
# 隐式实现「Kotlin 胜出」；这里显式做，避免同一个类被 dex 两次
# （报 "Type X is defined multiple times"）。
: > "$OUT/dex-classes.txt"
find "$OUT/kt" -name '*.class' >> "$OUT/dex-classes.txt"
find "$OUT/java" -name '*.class' | while read -r f; do
    rel=${f#"$OUT/java/"}
    if [ ! -f "$OUT/kt/$rel" ]; then
        echo "$f" >> "$OUT/dex-classes.txt"
    fi
done
echo "   待 dex: kt=$(find "$OUT/kt" -name '*.class' | wc -l) java=$(find "$OUT/java" -name '*.class' | wc -l) 合并后=$(wc -l < "$OUT/dex-classes.txt")"
"$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --classpath "$LIBXP" --classpath "$KOTLIN_STDLIB" \
    --output "$OUT/dex" $(cat "$OUT/dex-classes.txt")

echo "== 5/7 组装 APK =="
cp -f "$STDLIB_DEX" "$OUT/dex/classes2.dex"
# 用 Python zipfile 重打包：
#   · 先剔除已有的 classes*.dex（模板里带的是旧 dex）
#   · resources.arsc 必须 STORED（targetSdk 30+ 强制要求未压缩且 4 字节对齐）
#   · META-INF/xposed/* 来自 app/src/main/resources
python3 - "$OUT/base.apk" "$OUT/app-unsigned.apk" "$OUT/dex" "$SRC/resources" <<'PY'
import sys, os, zipfile
src, dst, dexdir, resdir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
STORED = {".arsc", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ogg", ".mp3", ".mp4", ".so"}
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith("classes") and n.endswith(".dex"):
            continue            # 旧 dex 丢掉
        if n.startswith("META-INF/") and n.endswith((".SF", ".RSA", ".DSA", ".MF")):
            continue            # 旧签名丢掉
        data = zin.read(n)
        if os.path.splitext(n)[1].lower() in STORED:
            zi = zipfile.ZipInfo(n, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            zout.writestr(zi, data)
        else:
            zout.writestr(n, data, zipfile.ZIP_DEFLATED)
    for f in ("classes.dex", "classes2.dex"):
        p = os.path.join(dexdir, f)
        if os.path.exists(p):
            # dex 必须压缩（DEFLATED）：用 zout.write 会沿用文件系统的 STORED，
            # APK 体积会翻倍（实测 898KB → 2.5MB）
            zout.write(p, f, zipfile.ZIP_DEFLATED)
    existing = set(zout.namelist())
    for dp, _, fs in os.walk(resdir):
        for f in fs:
            p = os.path.join(dp, f)
            arc = os.path.relpath(p, resdir)
            if arc in existing:
                continue        # 模板里已有同名条目，避免重复
            zout.write(p, arc)
PY

echo "== 6/7 zipalign =="
if [ -x "${ZIPALIGN:-}" ]; then
    ( cd "$HERE" && run_tool "$ZIPALIGN" -f -p 4 ./build/app-unsigned.apk ./build/app-aligned.apk )
else
    echo "   （无 zipalign，跳过；资源已按 STORED 写入，通常不影响安装）"
    cp -f "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
fi

echo "== 7/7 签名 =="
APKSIGNER=""
for c in "$BT/apksigner" "$BT/lib/apksigner.jar" "$HERE/tools/apksigner.jar"; do
    if [ -e "$c" ]; then APKSIGNER=$c; break; fi
done
[ -n "$APKSIGNER" ] || die "找不到 apksigner（Android build-tools 34+）"

mkdir -p "$HERE/dist"
FINAL="$HERE/dist/OS4FreeFromX-v$VER.apk"
if [[ "$APKSIGNER" == *.jar ]]; then
    "$JAVA_HOME/bin/java" -jar "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
        --out "$FINAL" "$OUT/app-aligned.apk"
else
    "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
        --out "$FINAL" "$OUT/app-aligned.apk"
fi
echo
echo "构建完成：$FINAL ($(stat -c%s "$FINAL") 字节)"
echo "安装：adb install -r \"$FINAL\"（或 pm install -r）"
