#!/usr/bin/env bash
# Run only against a fresh, disposable emulator; this changes its accessibility setting.
set -euo pipefail
if [[ "${DSH_OCR_DISPOSABLE_EMULATOR:-}" != "yes" || "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo 'Use only a disposable Android emulator with DSH_OCR_DISPOSABLE_EMULATOR=yes.' >&2
  exit 1
fi

SERVICE='com.agentruntime.agent/com.agentruntime.service.AgentAccessibilityService'

# 开关顺序在 Android 11 上有讲究：先置 accessibility_enabled=1，再写服务列表，
# 否则系统可能整条忽略；写完再确认一次，让系统重新扫描。
enable_accessibility() {
  adb shell settings put secure accessibility_enabled 1
  adb shell settings put secure enabled_accessibility_services "$SERVICE"
  adb shell settings put secure accessibility_enabled 1
}

diagnose() {
  echo '--- settings ---'
  adb shell settings get secure accessibility_enabled || true
  adb shell settings get secure enabled_accessibility_services || true
  echo '--- dumpsys accessibility ---'
  adb shell dumpsys accessibility | grep -i -E 'agentruntime|Bound services|Enabled services' | head -40 || true
  echo '--- logcat (agentruntime) ---'
  adb logcat -d -t 400 | grep -i -E 'AgentA11y|A11ySnap|agentruntime' | tail -60 || true
}

adb install -r agent/build/outputs/apk/debug/agent-debug.apk
adb install -r agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk
adb install -r agenttestapp/build/outputs/apk/debug/agenttestapp-debug.apk

enable_accessibility
bound=0
for attempt in $(seq 1 40); do
  if adb shell dumpsys accessibility | grep -q 'AgentAccessibilityService'; then bound=1; break; fi
  # 每 10 秒重写一次设置：部分镜像首次写入时还没扫描完包信息。
  if [ $((attempt % 10)) -eq 0 ]; then enable_accessibility; fi
  sleep 1
done
if [ "$bound" != "1" ]; then
  echo 'Accessibility service never appeared in dumpsys after 40s.'
  diagnose
  exit 1
fi

adb shell am start -W -n com.agenttestapp/.OcrFixtureActivity
output="$(timeout 180 adb shell am instrument -w -e ocr_only true com.agentruntime.agent.test/com.agentruntime.DeviceSmokeInstrumentation)" || true
printf '%s\n' "$output"
if ! printf '%s\n' "$output" | grep -F '"passed":true'; then
  diagnose
  exit 1
fi
