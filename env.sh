# OS4FreeFromX 构建环境（arm64 容器；aapt2/zipalign 用 Debian arm64 版，Java 工具走本机 JDK21）
#
# 说明：容器 /tmp 会被清空，所以工具全部放在仓库内的 tools/（已在 .gitignore 里，不入库）。
#   重建方式见 NOTES 文末「工具链重建」。
export HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# JDK：容器里装的是 openjdk-21（jvm-target 17 由 kotlinc 指定）
if [ -d /usr/lib/jvm/java-21-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
elif [ -d /usr/lib/jvm/java-17-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
else
  export JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi

export SDK=$HERE/tools/sdk
export BT=$SDK/build-tools/34.0.0
export ANDROID_JAR=$HERE/tools/sdk/platforms/android-36/android.jar

export KOTLINC=$HERE/tools/kotlinc/bin/kotlinc
export KOTLIN_STDLIB=$HERE/tools/kotlinc/lib/kotlin-stdlib.jar

export AAPT2=$HERE/tools/android-tools/aapt2
export ZIPALIGN=$HERE/tools/android-tools/zipalign
export LD_LIBRARY_PATH=$HERE/tools/android-tools${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export PATH="$JAVA_HOME/bin:$HERE/tools/kotlinc/bin:$PATH"
export ANDROID_RES_JAR=$HERE/tools/android-tools/root/usr/lib/android-sdk/platforms/android-23/android.jar
