# tools/（不入库，仅说明如何重建）

容器 /tmp 会被清空，所以构建工具全部落在本目录。重建步骤：

1. **JDK**：`apt-get install -y openjdk-21-jdk-headless`（容器里只有 JRE 时才要装）
2. **aapt2 / zipalign**（arm64）：
   `apt-get download aapt2 zipalign libzopfli1 && dpkg-deb -x <deb> android-tools/root`
   → `android-tools/aapt2`、`android-tools/zipalign`（zipalign 需 `LD_LIBRARY_PATH=android-tools`）
3. **d8 / apksigner**（纯 Java，x86 包里的 jar 在 arm64 上照样跑）：
   `node /tmp/dl.mjs https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip bt.zip`
   取 `android-14/{d8,apksigner}` + `android-14/lib/{d8.jar,apksigner.jar}` → `sdk/build-tools/34.0.0/`
4. **android.jar**：
   - 编译用：`https://dl.google.com/android/repository/platform-36_r01.zip` → `sdk/platforms/android-36/android.jar`
   - aapt2 链接资源用（带 resources.arsc）：`libandroid-23-java` 包里的 `android.jar`
5. **kotlinc**（官方 distribution 在极速镜像上没有，用 Maven 版自己包一层）：
   - `kotlin-compiler-2.1.0.jar` + `kotlin-stdlib-2.1.0.jar` + `kotlinx-coroutines-core-jvm-1.8.0.jar`
     + `trove4j-1.0.20200330.jar` + `annotations-13.0.jar`（全部取自 `maven.aliyun.com/repository/central`）
   - 入口：`java -cp <上述 jar> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`
   - 包装脚本 `kotlinc/bin/kotlinc`（见本目录同名文件）
6. **zip**：容器没有 `zip`，用 `tools/zip-shim.py` 装到 `/usr/local/bin/zip`
7. **签名密钥**：`keystore/os4freeformx.jks`（不入库；缺失时 build.sh 会自建，但**换了密钥就得
   `adb uninstall` 再装**，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）

⚠️ **重装 APK 后 LSPosed 可能不再注入**（v2.2.0 实测）：重装会新建 codePath，而
`/data/adb/lspd/config/modules_config.db` 里 `modules.apk_path` 仍是旧路径。
本文档记录的处理办法见 NOTES-resize-handle.md「LSPosed 重装后不注入」一节。
