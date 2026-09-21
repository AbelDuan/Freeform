#!/bin/bash
# 轻量 kotlinc 包装（arm64 容器无官方 distribution，用 Maven 版 kotlin-compiler.jar 起 K2JVMCompiler）
HERE=$(cd "$(dirname "$0")/.." && pwd)
CP=$(ls "$HERE"/lib/*.jar | tr '\n' ':')
exec "${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}/bin/java" -Xmx2600m -cp "${CP%:}" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect "$@"
