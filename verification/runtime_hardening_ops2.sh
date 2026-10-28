#!/usr/bin/env bash
# V1.9 加固验证（第二批）：C05 抽屉内按文本点击 / C06 任务结束时前台跑出允许集合。
#
# C05  sys.tap_text：NewPipe 导航抽屉里的 Settings（B01 的失败点，一步 click_node 永远定位不到）。
# C06  目标核验：允许集合 [AntennaPod, Aurora] 非空，任务最后一步打开系统设置页
#      （open_settings_page 不是 LAUNCH_APP，不会被动作层拦住），
#      结束时前台 = com.android.settings，不在允许集合内 -> 必须 TASK_TARGET_MISMATCH。
#
# 前置：模拟器已启动、Agent 已安装、无障碍已绑定。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/hardening"
mkdir -p "$OUT"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

wait_result () {
  local tries="${1:-60}" r=""
  for _ in $(seq 1 "$tries"); do
    sleep 2
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r"
}

focus () { adb shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=[^}]*' | head -1; }

report () {
  local name="$1" r="$2"
  echo "$r" > "$OUT/$name.json"
  adb exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  # 无窗口模式下 SwiftShader 偶尔整屏黑屏，截图不可用时用 uiautomator 树留证。
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb pull /sdcard/u.xml "$OUT/$name.xml" >/dev/null 2>&1
  echo "  [$name] $(focus)"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e:
    print('    PARSE_FAIL', e); raise SystemExit
det=d.get('detail',{}) or {}
mr=det.get('model_router',{}) or {}
print('    status=%-22s duration=%sms cloud_calls=%s intent_hits=%s rule_plans=%s' % (
    d.get('status'), det.get('duration_ms'), mr.get('cloud_calls'), mr.get('intent_hits'), mr.get('rule_plans')))
for s in det.get('steps',[]):
    if 'target_check' in s:
        print('    target_check ok=%s foreground=%s allowed=%s' % (s.get('ok'), s.get('foreground'), s.get('allowed')))
        continue
    for a in s.get('actions',[]):
        res=a['result']
        print('      %-14s %-9s %s' % (a.get('action'), res.get('status'), res.get('code')))
"
}

run_json () {
  local name="$1" js="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false --ez behind_after_launch true \
      --es task_json_b64 "$(b64 "$js")" >/dev/null 2>&1
  report "$name" "$(wait_result 150)"
}

echo '=== C05 抽屉内按文本点击：NewPipe Settings ==='
adb shell am force-stop org.schabi.newpipe >/dev/null 2>&1
adb shell am force-stop de.danoeh.antennapod >/dev/null 2>&1
run_json C05_newpipe_settings_tap_text '{"task_version":"1.0","task_id":"tap_text_newpipe_settings","goal":"打开 NewPipe 并进入设置","allowed_apps":["org.schabi.newpipe"],"preconditions":[],"steps":[{"skill":"sys.launch_and_tap_text","args":{"app_package":"org.schabi.newpipe","text":"Open Drawer"}},{"skill":"sys.tap_text","args":{"text":"Settings"}}],"postconditions":["ui_contains:Settings"],"limits":{"max_steps":14,"max_replans":0,"timeout_seconds":240}}'

echo '=== C06 目标核验：结束停在允许集合外，必须报 TASK_TARGET_MISMATCH ==='
adb shell am force-stop org.schabi.newpipe >/dev/null 2>&1
adb shell am force-stop com.android.settings >/dev/null 2>&1
run_json C06_target_mismatch '{"task_version":"1.0","task_id":"target_mismatch_probe","goal":"故意在收尾时跑出允许集合","allowed_apps":["de.danoeh.antennapod","com.aurora.store"],"preconditions":[],"steps":[{"skill":"sys.launch_and_tap_text","args":{"app_package":"de.danoeh.antennapod","text":"Queue"}},{"skill":"sys.open_settings_page","args":{"page":"wifi"}}],"postconditions":[],"limits":{"max_steps":12,"max_replans":0,"timeout_seconds":180}}'

echo '=== 结束，结果在 verification/hardening/ ==='
