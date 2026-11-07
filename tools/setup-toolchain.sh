#!/usr/bin/env bash
# 重建 ~/buildtest 工具链（容器清理后 jar 会丢；apt 装的 aapt/zipalign/apksigner/JDK 一般还在）
set -euo pipefail
WORK="${WORK:-$HOME/buildtest}"
mkdir -p "$WORK" && cd "$WORK"
M=https://repo.huaweicloud.com/repository/maven
dl(){ [ -s "$1" ] && { echo "已有 $1"; return; }; curl -4 -fsSL --retry 3 -o "$1" "$2" && echo "下载 $1"; }
dl kotlin-compiler.jar $M/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar &
dl kotlin-stdlib.jar   $M/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar &
dl coroutines.jar      $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar &
dl trove.jar           $M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar &
dl annotations.jar     $M/org/jetbrains/annotations/24.0.0/annotations-24.0.0.jar &
dl r8.jar              $M/com/android/tools/r8/8.7.18/r8-8.7.18.jar &
dl platform-35.zip https://dl.google.com/android/repository/platform-35_r01.zip &
dl platform-28.zip https://dl.google.com/android/repository/platform-28_r06.zip &
wait
[ -f plat/android-35/android.jar ] || unzip -q -o platform-35.zip -d plat
[ -f plat28/android-9/android.jar ] || unzip -q -o platform-28.zip -d plat28
for t in aapt zipalign apksigner javac zip; do command -v $t >/dev/null || echo "⚠ 缺 $t：apt-get install -y aapt zipalign apksigner zip unzip openjdk-21-jdk-headless"; done
echo "✅ 工具链就绪：$WORK"
