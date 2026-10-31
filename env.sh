# OS4FreeFromX 构建环境（arm64 容器；aapt2/zipalign 用 Debian arm64 版，Java 工具走本机 JDK21）
#
# 说明：容器 /tmp 会被清空，所以工具全部放在仓库内的 tools/（已在 .gitignore 里，不入库）。
#   重建方式见 NOTES 文末「工具链重建」。
export HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# JDK：容器里装的是 openjdk-21（jvm-target 17 由 kotlinc 指定）
# 2026-10-31 容器重建后 /usr/lib/jvm 消失，本机 JDK 位于 /opt/jdk-17.0.13+11，补一条探测。
if [ -d /usr/lib/jvm/java-21-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
elif [ -d /usr/lib/jvm/java-17-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
elif [ -d /opt/jdk-17.0.13+11 ]; then
  export JAVA_HOME=/opt/jdk-17.0.13+11
else
  export JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi

export SDK=$HERE/tools/sdk
export BT=$SDK/build-tools/34.0.0
export ANDROID_JAR=$HERE/tools/sdk/platforms/android-36/android.jar

export KOTLINC=$HERE/tools/kotlinc/bin/kotlinc
# 2026-10-31 容器重建后 Maven 版 stdlib 带版本号（kotlin-stdlib-2.1.0.jar），
# 不再有无版本号的 kotlin-stdlib.jar；这里按实际文件探测，找不到就让构建显式报错。
KOTLIN_STDLIB_FOUND=$(ls "$HERE"/tools/kotlinc/lib/kotlin-stdlib*.jar 2>/dev/null | head -1)
export KOTLIN_STDLIB=${KOTLIN_STDLIB_FOUND:-$HERE/tools/kotlinc/lib/kotlin-stdlib.jar}

export AAPT2=$HERE/tools/android-tools/aapt2
export ZIPALIGN=$HERE/tools/android-tools/zipalign
export LD_LIBRARY_PATH=$HERE/tools/android-tools${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export PATH="$JAVA_HOME/bin:$HERE/tools/kotlinc/bin:$PATH"
export ANDROID_RES_JAR=$HERE/tools/android-tools/root/usr/lib/android-sdk/platforms/android-23/android.jar
