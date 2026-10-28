#!/usr/bin/env bash
# 本地小模型（离线通道）演示：不配置任何云端密钥，只用设备自带的规划能力。
#
# 三条证据：
#   1) L0 规则 / L0.5 内置意图模型（17KB 朴素贝叶斯）能各自兜住自然说法；
#   2) 复合目标会被内置模型主动让位（compound → 交给上层），不会用单技能硬跑；
#   3) 本地通道兜不住且没有云端时，必须明确失败，不允许编步骤。
#
# 前置：模拟器已启动、Agent 已安装、无障碍已绑定。
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/local_model"
mkdir -p "$OUT"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

run_local () {
  local name="$1" goal="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false --ez behind_after_launch true \
      --es goal_b64 "$(b64 "$goal")" >/dev/null 2>&1
  local r="" i
  for i in $(seq 1 45); do
    sleep 2
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r" > "$OUT/$name.json"
  echo "--- $name  goal=$goal"
  adb logcat -d -s TaskBus:I ModelRouter:I 2>/dev/null | grep -E "意图|plan|本地" | tail -5 | sed 's/^/    log /'
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('    PARSE_FAIL', e); raise SystemExit
mr=d.get('detail',{}).get('model_router',{})
det=d.get('detail',{})
print('    status=%s  %sms  rule=%s intent=%s local=%s cloud=%s skips=%s' % (
    d.get('status'), det.get('duration_ms'), mr.get('rule_plans'), mr.get('intent_hits'),
    mr.get('local_calls'), mr.get('cloud_calls'), mr.get('compound_skips')))
for s in (d.get('detail',{}).get('steps') or []):
    print('    exec %-22s code=%s ok=%s' % (s.get('skill'), s.get('code'), s.get('ok')))
if d.get('status')!='SUCCESS':
    print('    error=%s' % json.dumps(d.get('detail',{}).get('error') or d.get('error'), ensure_ascii=False)[:200])
"
}

echo "=== 本地小模型：离线可用的部分 ==="
run_local M01_rule_screenshot   "截个图"
run_local M02_intent_paraphrase "把它截下来"
run_local M03_intent_observe    "看看现在屏幕上有什么"
run_local M04_open_clock        "打开时钟"
run_local M05_intent_open_clock "帮我打开闹钟应用"

echo
echo "=== 边界：复合目标与小模型不认识的意图 ==="
run_local M06_compound_goal "打开时钟然后截个图"
run_local M07_unknown_goal  "帮我把手机的飞行模式关掉"

echo "=== 完成，结果 JSON 见 $OUT ==="
