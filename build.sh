#!/usr/bin/env bash
# Android Agent Runtime 可复现 Linux 构建脚本（JDK 21 + Gradle 8.8）
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE_BIN="${GRADLE_BIN:-/workspace/gradle-dist/gradle-8.8/bin/gradle}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8}"
PROXY_RAW="${HTTPS_PROXY:-${HTTP_PROXY:-}}"
if [ -n "$PROXY_RAW" ]; then
  P="${PROXY_RAW#*//}"; PH="${P%%:*}"; PP="${P##*:}"; PP="${PP%%/*}"
  if [ -n "$PH" ] && [ -n "$PP" ]; then
    export GRADLE_OPTS="$GRADLE_OPTS -Dhttp.proxyHost=$PH -Dhttp.proxyPort=$PP -Dhttps.proxyHost=$PH -Dhttps.proxyPort=$PP"
  fi
fi
cd "$ROOT_DIR"
TASKS=("$@"); [ ${#TASKS[@]} -gt 0 ] || TASKS=(":agent:assembleDebug")
exec "$GRADLE_BIN" --console=plain --no-daemon "${TASKS[@]}"
