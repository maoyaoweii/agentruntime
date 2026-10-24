#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ROOT/.avd}"
mkdir -p "$ANDROID_AVD_HOME"
AVD="${AVD:-agentruntime_api30}"
if [[ "${1:-}" == "--ready" ]]; then
  timeout 1200 adb -s emulator-5554 wait-for-device
  for _ in $(seq 1 120); do
    if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; then
      # ATD 默认禁止 HWUI 绘制：不开此项，控件树可用但像素截图全黑。
      adb -s emulator-5554 shell setprop debug.hwui.drawing_enabled true
      echo 'Android boot_completed=1; HWUI drawing enabled'
      exit 0
    fi
    sleep 10
  done
  echo 'Android boot timed out' >&2
  exit 1
fi
if [[ "${1:-}" == "--create" ]]; then
  echo no | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --force -n "$AVD" -k 'system-images;android-30;aosp_atd;x86_64' --device 'Nexus 5'
  CONFIG="$ANDROID_AVD_HOME/$AVD.avd/config.ini"
  printf '\nhw.lcd.width=480\nhw.lcd.height=800\nhw.lcd.density=160\nhw.ramSize=2048\n' >> "$CONFIG"
  exit 0
fi
mkdir -p "$ROOT/test-results/emulator"
exec emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect -memory 2048 -cores 2 -port 5554 -no-metrics
