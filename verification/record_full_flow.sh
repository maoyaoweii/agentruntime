#!/usr/bin/env bash
# V2.0 全流程录屏：主界面 -> 系统通知 -> 真实第三方应用（AntennaPod 队列）-> 系统时钟 -> 桌面 -> 原生手势上滑应用抽屉。
#
# 前置：模拟器已启动（AOSP 11，720x1280）、宿主机内存允许（模拟器运行期间不要跑 gradle）。
# 产物：verification/recordings/flow_20261028/*.mp4 + 各任务 result json + 界面 xml。
#
# 说明：任务之间的「回桌面」由宿主 adb keyevent HOME 完成（相当于用户自己按 Home），
#      视频里有明确分界；Agent 自身的动作（通知读取 / 打开第三方应用并点队列 / 开时钟 / 上滑）均走 Action DSL。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/recordings/flow_20261028"
APK="$HERE/../agent/build/outputs/apk/debug/agent-debug.apk"
mkdir -p "$OUT"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

wait_result () {
  local tries="${1:-150}" r=""
  for _ in $(seq 1 "$tries"); do
    sleep 2
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r"
}

focus () { adb shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=[^}]*' | head -1; }

show_result () {
  local name="$1" r="$2"
  echo "$r" > "$OUT/$name.json"
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb pull /sdcard/u.xml "$OUT/$name.xml" >/dev/null 2>&1
  echo "  [$name] $(focus)"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e:
    print('    PARSE_FAIL', e); raise SystemExit
det=d.get('detail',{}) or {}
print('    status=%s duration=%sms' % (d.get('status'), det.get('duration_ms')))
for s in det.get('steps',[]):
    print('    step %-24s ok=%s' % (s.get('skill'), s.get('ok')))
    for a in s.get('actions',[]):
        res=a['result']
        print('      %-14s %-9s %s' % (a.get('action'), res.get('status'), res.get('code')))
"
}

run_task () {
  local name="$1" js="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false --ez behind_after_launch true \
      --es task_json_b64 "$(b64 "$js")" >/dev/null 2>&1
  show_result "$name" "$(wait_result 200)"
}

echo '=== 0. 安装最新 APK / 绑定无障碍与通知监听 ==='
adb install -r "$APK" 2>&1 | tail -1
adb shell settings put secure accessibility_enabled 0 >/dev/null 2>&1
adb shell settings put secure enabled_accessibility_services "$PKG/com.agentruntime.service.AgentAccessibilityService" >/dev/null
adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
adb shell cmd notification allow_listener "$PKG/com.agentruntime.notify.AgentNotificationListener" >/dev/null 2>&1
sleep 12
adb shell dumpsys accessibility 2>/dev/null | grep -o 'Bound services:{[^}]*}' | head -1

echo '=== 1. 清理测试现场 ==='
adb shell am force-stop de.danoeh.antennapod >/dev/null 2>&1
adb shell am force-stop com.android.deskclock >/dev/null 2>&1
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2

echo '=== 2. 开始录屏 ==='
adb shell screenrecord --bit-rate 6000000 --time-limit 180 /sdcard/flow_20261028.mp4 &
REC=$!
sleep 2

echo '=== 3. 打开 Agent 主界面（软件实际使用起点） ==='
adb shell am start -n "$PKG/com.agentruntime.ui.MainActivity" >/dev/null 2>&1
sleep 6
adb exec-out screencap -p > "$OUT/01_main.png" 2>/dev/null

echo '=== 4. 发一条系统通知（供 Agent 读取） ==='
adb shell cmd notification post -t '日程提醒' tag_demo '10 分钟后开会：周会同步' >/dev/null 2>&1
sleep 3

echo '=== 5. 任务一：读通知 -> 打开真实第三方应用 AntennaPod 并点队列 -> 打开系统时钟 ==='
run_task T1_notify_app_clock '{"task_version":"1.0","task_id":"full_flow_1","goal":"看通知，打开播客应用队列，再打开时钟","allowed_apps":["de.danoeh.antennapod","com.android.deskclock"],"preconditions":[],"steps":[{"skill":"sys.notifications","args":{}},{"skill":"sys.launch_and_tap_text","args":{"app_package":"de.danoeh.antennapod","text":"Queue"}},{"skill":"sys.open_clock","args":{}}],"postconditions":["package_is:com.android.deskclock"],"limits":{"max_steps":40,"max_replans":0,"timeout_seconds":420}}'
adb exec-out screencap -p > "$OUT/02_after_clock.png" 2>/dev/null

echo '=== 6. 回桌面（宿主按键，视频分界） ==='
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 3

echo '=== 7. 任务二：原生手势上滑，进应用抽屉 ==='
run_task T2_swipe_drawer '{"task_version":"1.0","task_id":"full_flow_2","goal":"在桌面上上滑打开应用抽屉","allowed_apps":[],"preconditions":[],"steps":[{"skill":"sys.swipe","args":{"direction":"up"}}],"postconditions":[],"limits":{"max_steps":6,"max_replans":0,"timeout_seconds":60}}'
adb exec-out screencap -p > "$OUT/03_drawer.png" 2>/dev/null

echo '=== 8. 结束录屏并取回视频 ==='
sleep 2
adb shell pidof screenrecord 2>/dev/null | tr -d '\r' | xargs -r -n1 adb shell kill -2 >/dev/null 2>&1
wait "$REC" 2>/dev/null
sleep 2
adb pull /sdcard/flow_20261028.mp4 "$OUT/flow_20261028.mp4" 2>&1 | tail -1
ls -lh "$OUT/flow_20261028.mp4"
echo '=== 完成，产物在 verification/recordings/flow_20261028/ ==='
