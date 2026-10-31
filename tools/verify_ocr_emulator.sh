#!/usr/bin/env bash
# Run only against a fresh, disposable emulator; this changes its accessibility setting.
set -euo pipefail
if [[ "${DSH_OCR_DISPOSABLE_EMULATOR:-}" != "yes" || "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo 'Use only a disposable Android emulator with DSH_OCR_DISPOSABLE_EMULATOR=yes.' >&2
  exit 1
fi
adb install -r agent/build/outputs/apk/debug/agent-debug.apk
adb install -r agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk
adb install -r agenttestapp/build/outputs/apk/debug/agenttestapp-debug.apk
adb shell settings put secure enabled_accessibility_services 'com.agentruntime.agent/com.agentruntime.service.AgentAccessibilityService'
adb shell settings put secure accessibility_enabled 1
adb shell am start -W -n com.agenttestapp/.OcrFixtureActivity
output="$(timeout 120 adb shell am instrument -w -e ocr_only true com.agentruntime.agent.test/com.agentruntime.DeviceSmokeInstrumentation)"
printf '%s\n' "$output"
printf '%s\n' "$output" | grep -F '"passed":true'
