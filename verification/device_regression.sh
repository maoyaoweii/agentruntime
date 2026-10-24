#!/usr/bin/env bash
# 真实 Android 模拟器端到端回归。
# 前置：模拟器已启动、adb 可用、Agent/AgentTestApp 已安装、无障碍已绑定。
# 运行期约定：不 force-stop Agent 进程（否则无障碍服务会被系统标记 crashed），
# 只 force-stop 被测应用，保证每个任务从干净界面开始。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
TAPP=com.agenttestapp
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/device"
mkdir -p "$OUT"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

run_task () {
  local name="$1" body="$2"
  adb shell am force-stop "$TAPP" >/dev/null 2>&1
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false \
      --es task_json_b64 "$(b64 "$body")" >/dev/null 2>&1
  local r=""
  for _ in $(seq 1 60); do
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
    sleep 3
  done
  echo "$r" > "$OUT/$name.json"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('  PARSE_FAIL', e); sys.exit()
det=d.get('detail',{})
print('%-16s %-8s dur=%sms' % ('$name', d.get('status'), det.get('duration_ms')))
for s in det.get('steps',[]):
    print('   %-28s %-14s ok=%s' % (s.get('skill'), s.get('code'), s.get('ok')))
"
}

spkg='"allowed_apps":["'"$PKG"'"]'
stapp='"allowed_apps":["'"$TAPP"'"]'
lim_short='"limits":{"max_steps":12,"max_replans":0,"timeout_seconds":180}'
lim_mid='"limits":{"max_steps":24,"max_replans":0,"timeout_seconds":300}'
lim_long='"limits":{"max_steps":36,"max_replans":1,"timeout_seconds":480}'
URL="http://10.0.2.2:8899/index.html"

echo "=== 核心基准（AgentTestApp） ==="
run_task T01_observe      "{\"task_version\":\"1.0\",\"task_id\":\"T01\",\"goal\":\"观察环境\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"sys.observe\",\"args\":{}}],\"postconditions\":[],$lim_short}"
run_task T02_screenshot   "{\"task_version\":\"1.0\",\"task_id\":\"T02\",\"goal\":\"截图留证\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"sys.screenshot\",\"args\":{}}],\"postconditions\":[],$lim_short}"
run_task T03_open_chat    "{\"task_version\":\"1.0\",\"task_id\":\"T03\",\"goal\":\"打开会话\",$stapp,\"preconditions\":[],\"steps\":[{\"skill\":\"agent_test.open_chat\",\"args\":{\"contact_alias\":\"TestUser\"}}],\"postconditions\":[],$lim_mid}"
run_task T04_send_text    "{\"task_version\":\"1.0\",\"task_id\":\"T04\",\"goal\":\"发文本\",$stapp,\"preconditions\":[],\"steps\":[{\"skill\":\"agent_test.open_chat\",\"args\":{\"contact_alias\":\"TestUser\"}},{\"skill\":\"agent_test.send_text\",\"args\":{\"text\":\"DEVICE_E2E_中文发送成功\"}}],\"postconditions\":[],$lim_long}"
run_task T05_send_file    "{\"task_version\":\"1.0\",\"task_id\":\"T05\",\"goal\":\"发文件\",$stapp,\"preconditions\":[],\"steps\":[{\"skill\":\"agent_test.open_chat\",\"args\":{\"contact_alias\":\"TestUser\"}},{\"skill\":\"agent_test.send_file\",\"args\":{\"contact_alias\":\"TestUser\",\"file_alias\":\"report.pdf\"}}],\"postconditions\":[],$lim_long}"
run_task T06_read_wifi    "{\"task_version\":\"1.0\",\"task_id\":\"T06\",\"goal\":\"读WiFi\",$stapp,\"preconditions\":[],\"steps\":[{\"skill\":\"agent_test.open_settings\",\"args\":{}},{\"skill\":\"agent_test.read_wifi\",\"args\":{}}],\"postconditions\":[],$lim_mid}"

echo "=== 内置受控浏览器 ==="
run_task T07_web_read     "{\"task_version\":\"1.0\",\"task_id\":\"T07\",\"goal\":\"读本地网页\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"web.open_and_read\",\"args\":{\"url\":\"$URL\",\"max_chars\":2000}}],\"postconditions\":[],$lim_mid}"
run_task T08_web_click    "{\"task_version\":\"1.0\",\"task_id\":\"T08\",\"goal\":\"网页点击\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"web.open_and_read\",\"args\":{\"url\":\"$URL\",\"max_chars\":2000}},{\"skill\":\"web.click_selector\",\"args\":{\"selector\":\"#confirm\",\"expect\":\"DEVICE_WEB_CLICK_OK\"}}],\"postconditions\":[],$lim_mid}"

echo "=== 负例 / 安全边界 ==="
run_task N01_unknown_skill "{\"task_version\":\"1.0\",\"task_id\":\"N01\",\"goal\":\"未知技能\",\"allowed_apps\":[],\"preconditions\":[],\"steps\":[{\"skill\":\"not.allowed\",\"args\":{}}],\"postconditions\":[],$lim_short}"
run_task N02_unsafe_url    "{\"task_version\":\"1.0\",\"task_id\":\"N02\",\"goal\":\"危险URL\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"web.open_and_read\",\"args\":{\"url\":\"javascript:alert(1)\",\"max_chars\":100}}],\"postconditions\":[],$lim_short}"
run_task N03_out_of_bounds "{\"task_version\":\"1.0\",\"task_id\":\"N03\",\"goal\":\"越界坐标\",$spkg,\"preconditions\":[],\"steps\":[{\"skill\":\"sys.coordinate_smoke\",\"args\":{}}],\"postconditions\":[],$lim_mid}"
echo "=== 完成，结果见 $OUT ==="
