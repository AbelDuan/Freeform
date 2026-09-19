# OS4FreeFromX 构建环境（arm64 容器；aapt2/zipalign 用 Debian arm64 版，Java 工具走 JDK17）
export JAVA_HOME=/tmp/tools/jdk/root/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:/root/projects/OS4FreeFromX/tools/jadx/bin:$PATH"
export SDK=/tmp/tools/sdk/android
export BT=$SDK/build-tools/34.0.0
export ANDROID_JAR=$SDK/platforms/android-34/android.jar
export KOTLINC=/root/projects/OS4FreeFromX/tools/kotlinc/bin/kotlinc
export KOTLIN_STDLIB=/root/projects/OS4FreeFromX/tools/kotlinc/lib/kotlin-stdlib.jar
export AAPT2=/root/projects/OS4FreeFromX/tools/android-tools/aapt2
export AAPT=/root/projects/OS4FreeFromX/tools/android-tools/aapt
export ZIPALIGN=/root/projects/OS4FreeFromX/tools/android-tools/zipalign
