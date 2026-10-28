#!/usr/bin/env bash
# V1.9 加固验证：目标核验 / 云端配置落盘 / 按文本点击（找 + 滚动 + 点）。
#
# 三条修复的独立证据：
#   C01/C02  目标句点名真实应用时，内置意图模型不能再把它判成 open_clock；
#            同一次请求里直接带云端配置，不再需要「先发一个无目标自检请求」落盘（问题 85）。
#   C03      故意构造「允许应用 = 商店，实际执行 = 开时钟」的任务，
#            必须得到 TASK_TARGET_MISMATCH，而不是 SUCCESS（问题 88）。
#   C04/C05  按可见文本点击（首屏找不到自动滚动），用于 NewPipe 抽屉里的 Settings（B01 失败点）。
#
# 前置：模拟器已启动、Agent 已安装、无障碍已绑定；宿主 cloud_proxy.py 在 8900 监听。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/hardening"
mkdir -p "$OUT"

KEY="${MAAS_API_KEY:-}"
BASE="${CLOUD_BASE_URL:-http://10.0.2.2:8900/v2/chat/completions}"
MODEL="${CLOUD_MODEL:-spark-x2.6}"

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

# 同一次请求里带 goal + 云端配置（验证问题 85：配置无条件落盘）
run_goal () {
  local name="$1" goal="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan true --ez behind_after_launch true \
      --es goal_b64 "$(b64 "$goal")" \
      --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" \
      >/dev/null 2>&1
  report "$name" "$(wait_result 100)"
}

run_json () {
  local name="$1" js="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false --ez behind_after_launch true \
      --es task_json_b64 "$(b64 "$js")" >/dev/null 2>&1
  report "$name" "$(wait_result 60)"
}

echo '=== C01/C02 目标句点名真实应用 + 同请求云端配置 ==='
# 冷启动应用，避免上一条用例留下的界面（例如 AntennaPod 停在 Queue）干扰断言。
adb shell am force-stop com.aurora.store >/dev/null 2>&1
adb shell am force-stop org.fdroid.fdroid >/dev/null 2>&1
run_goal C01_aurora_store "打开 Aurora 商店应用"
run_goal C02_fdroid_store "打开 F-Droid 应用商店"

echo '=== C03 目标核验：允许商店却执行开时钟，必须在派发前被拦下 ==='
# 期望 FAILED / APP_NOT_ALLOWED：错误目标连启动都不允许，而不是跑完再报错。
run_json C03_wrong_launch_blocked '{"task_version":"1.0","task_id":"wrong_launch_probe","goal":"故意跑错目标","allowed_apps":["com.aurora.store"],"preconditions":[],"steps":[{"skill":"sys.open_clock","args":{}}],"postconditions":[],"limits":{"max_steps":10,"max_replans":0,"timeout_seconds":120}}'

echo '=== C04 按文本点击：AntennaPod 底部 Queue ==='
adb shell am force-stop de.danoeh.antennapod >/dev/null 2>&1
run_json C04_antennapod_queue_tap_text '{"task_version":"1.0","task_id":"tap_text_queue","goal":"打开 AntennaPod 并进入队列","allowed_apps":["de.danoeh.antennapod"],"preconditions":[],"steps":[{"skill":"sys.launch_and_tap_text","args":{"app_package":"de.danoeh.antennapod","text":"Queue"}}],"postconditions":["ui_contains:Queue"],"limits":{"max_steps":12,"max_replans":1,"timeout_seconds":180}}'

echo '=== C05 按文本点击：NewPipe 抽屉里的 Settings（B01 的失败点）==='
adb shell am force-stop org.schabi.newpipe >/dev/null 2>&1
adb shell uiautomator dump /sdcard/newpipe_dump.xml >/dev/null 2>&1
adb pull /sdcard/newpipe_dump.xml "$OUT/newpipe_dump.xml" >/dev/null 2>&1
run_json C05_newpipe_settings_tap_text '{"task_version":"1.0","task_id":"tap_text_newpipe_settings","goal":"打开 NewPipe 并进入设置","allowed_apps":["org.schabi.newpipe"],"preconditions":[],"steps":[{"skill":"sys.launch_and_tap_text","args":{"app_package":"org.schabi.newpipe","text":"Open Drawer"}},{"skill":"sys.tap_text","args":{"text":"Settings"}}],"postconditions":["ui_contains:Settings"],"limits":{"max_steps":14,"max_replans":1,"timeout_seconds":240}}'

echo '=== 结束，结果在 verification/hardening/ ==='
