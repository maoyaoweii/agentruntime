#!/usr/bin/env bash
# V1.9 补充验证：对「市面上常见应用」的下载与真实操控。
#
# 被测应用（全部为真实第三方应用包，来自 F-Droid / 官方渠道，均含 x86_64 原生库）：
#   org.fdroid.fdroid        F-Droid 应用商店
#   org.schabi.newpipe       NewPipe（YouTube 客户端）
#   com.aurora.store         Aurora Store（Google Play 客户端）
#   de.danoeh.antennapod     AntennaPod（播客）
#
# 用例走云端真实规划（spark-x2.6），验证 Agent 能把自然语言落到这些真实包名上，
# 并在真实界面里点中真实控件。每一步后截图留证。
#
# 前置：模拟器已启动、Agent 已安装、无障碍已绑定、宿主 cloud_proxy.py 在 8900 监听。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/market_apps"
mkdir -p "$OUT"

KEY="${MAAS_API_KEY:-}"
BASE="${CLOUD_BASE_URL:-http://10.0.2.2:8900/v2/chat/completions}"
MODEL="${CLOUD_MODEL:-spark-x2.6}"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

shot () { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }

wait_result () {
  local tries="${1:-100}" r=""
  for _ in $(seq 1 "$tries"); do
    sleep 2
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r"
}

run_goal () {
  local name="$1" goal="$2" app="$3"
  adb shell am force-stop "$app" >/dev/null 2>&1
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan true --ez behind_after_launch true \
      --es goal_b64 "$(b64 "$goal")" \
      --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" \
      >/dev/null 2>&1
  local r; r=$(wait_result)
  echo "$r" > "$OUT/$name.json"
  shot "$name"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('  $name  PARSE_FAIL', e); raise SystemExit
det=d.get('detail',{}); mr=det.get('model_router',{})
print('  %-20s status=%-8s cloud=%s %sms' % ('$name', d.get('status'), mr.get('cloud_calls'), det.get('duration_ms')))
p=d.get('plan') or {}
for s in p.get('steps',[]): print('      plan %-24s %s' % (s.get('skill'), json.dumps(s.get('args',{}),ensure_ascii=False)[:70]))
for s in det.get('steps',[]): print('      exec %-22s %-16s ok=%s' % (s.get('skill'), s.get('code'), s.get('ok')))
"
  echo "      ui: $(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed 's/^ *//')"
}

echo "=== 已安装的真实第三方应用 ==="
adb shell pm list packages 2>/dev/null | grep -E "org.fdroid.fdroid|org.schabi.newpipe|com.aurora.store|de.danoeh.antennapod" | sed 's/^/  /'

if [ -z "$KEY" ]; then
  echo "  SKIPPED：未设置 MAAS_API_KEY"
else
  # 先把云端配置落盘：TaskExecActivity 只在「无目标的自检请求」里写配置，
  # 带 goal 的请求不会顺带保存，这里显式做一次。
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez behind_after_launch false --ez cloud_info true \
      --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" \
      >/dev/null 2>&1
  sleep 6
  echo "  cloud config: $(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null | head -c 200)"

  run_goal B01_newpipe_settings   "打开 NewPipe 应用，然后打开它的设置界面" org.schabi.newpipe
  run_goal B02_antennapod_queue   "打开 AntennaPod 播客应用，然后进入队列"   de.danoeh.antennapod
  run_goal B03_aurora_store       "打开 Aurora 商店应用"                     com.aurora.store
  run_goal B04_fdroid_store       "打开 F-Droid 应用商店"                    org.fdroid.fdroid
fi

echo "=== 完成，结果 JSON 与截图见 $OUT ==="
