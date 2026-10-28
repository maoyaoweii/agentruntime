#!/usr/bin/env bash
# V1.8 补充验证：本地文件「创建 / 修改 / 读取 / 删除」+ 真实系统应用操控。
#
# 两条独立的证据链：
#   1) 文件类用例用 **外部校验**：Agent 执行完，由 adb run-as 直接读磁盘字节，
#      不采信 Agent 自己上报的 code。删除后再 ls 确认文件真的不在了。
#   2) 应用类用例走 **真实云规划**（spark-x2.6），目标是 AOSP 内置应用
#      （Settings / DeskClock / Calendar / DocumentsUI），不是自研测试应用。
#
# 前置：
#   1) 宿主机 cloud_proxy.py 已在 8900 监听
#   2) 模拟器已启动、Agent 已安装、无障碍已绑定、悬浮窗已授权
# 运行：MAAS_API_KEY=... bash file_and_app_ops.sh
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/file_ops"
mkdir -p "$OUT"

KEY="${MAAS_API_KEY:-}"
BASE="${CLOUD_BASE_URL:-http://10.0.2.2:8900/v2/chat/completions}"
MODEL="${CLOUD_MODEL:-spark-x2.6}"

# 文件用例统一落在 app 私有目录：这是 run-as 能读到的位置，
# 因而「磁盘上到底有没有这个文件、内容是什么」可以被独立核对。
PRIV_DIR="/data/data/$PKG/files"
NOTE="$PRIV_DIR/agent_note.txt"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

wait_result () {
  local tries="${1:-60}" t0=$(date +%s) r=""
  for _ in $(seq 1 "$tries"); do
    sleep 2
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r"
}

# run_json <name> <task_json>
run_json () {
  local name="$1" js="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false --ez behind_after_launch true \
      --es task_json_b64 "$(b64 "$js")" >/dev/null 2>&1
  local r; r=$(wait_result)
  echo "$r" > "$OUT/$name.json"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('  $name  PARSE_FAIL', e); raise SystemExit
det=d.get('detail',{})
print('  %-18s status=%-8s %sms' % ('$name', d.get('status'), det.get('duration_ms')))
for s in det.get('steps',[]):
    for a in s.get('actions',[]):
        res=a['result']
        print('      %-12s %-9s %s' % (a.get('action'), res.get('status'), res.get('code')))
"
}

# run_goal <name> <goal> —— 走云端真实规划
run_goal () {
  local name="$1" goal="$2"
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan true --ez behind_after_launch true \
      --es goal_b64 "$(b64 "$goal")" \
      --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" \
      >/dev/null 2>&1
  local r; r=$(wait_result 100)
  echo "$r" > "$OUT/$name.json"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('  $name  PARSE_FAIL', e); raise SystemExit
det=d.get('detail',{}); mr=det.get('model_router',{})
print('  %-18s status=%-8s cloud_calls=%s %sms' % ('$name', d.get('status'), mr.get('cloud_calls'), det.get('duration_ms')))
p=d.get('plan') or {}
for s in p.get('steps',[]): print('      plan  %s %s' % (s.get('skill'), json.dumps(s.get('args',{}),ensure_ascii=False)[:80]))
for s in det.get('steps',[]): print('      exec  %-22s %-20s ok=%s' % (s.get('skill'), s.get('code'), s.get('ok')))
"
}

task_json () { # task_json <id> <skill> <path>
  printf '{"task_version":"1.0","task_id":"%s","goal":"local file op","allowed_apps":[],"preconditions":[],"steps":[{"skill":"%s","args":{"path":"%s"}}],"postconditions":[],"limits":{"max_steps":8,"max_replans":0,"timeout_seconds":90}}' "$1" "$2" "$3"
}

echo "=== F 系列：本地文件 创建/修改/读取/删除（含外部磁盘校验） ==="
adb shell run-as "$PKG" rm -f files/agent_note.txt >/dev/null 2>&1
run_json F01_file_create_modify_read "$(task_json f01 sys.file_ops "$NOTE")"
echo "-- 外部校验：磁盘上的实际内容 --"
adb shell run-as "$PKG" cat "$NOTE" 2>/dev/null | sed 's/^/      | /'
echo "-- 外部校验：字节数 --"
adb shell run-as "$PKG" wc -c "$NOTE" 2>/dev/null | sed 's/^/      /'

run_json F02_file_delete "$(task_json f02 sys.file_delete "$NOTE")"
echo "-- 外部校验：删除后是否还存在 --"
if adb shell run-as "$PKG" ls "$NOTE" 2>/dev/null | grep -q agent_note; then
  echo "      STILL_EXISTS（删除失败）"
else
  echo "      GONE（磁盘上已不存在）"
fi

echo
echo "=== F 系列负例：拒绝面必须明确，不能静默成功 ==="
run_json F03_delete_missing "$(task_json f03 sys.file_delete "$PRIV_DIR/no_such_file.txt")"
run_json F04_delete_root    "$(task_json f04 sys.file_delete "$PRIV_DIR")"
run_json F05_delete_dir     "$(task_json f05 sys.file_delete "$PRIV_DIR/audit")"
run_json F06_shared_storage "$(task_json f06 sys.file_ops "/sdcard/agent_shared_probe.txt")"

echo
echo "=== A 系列：真实系统应用操控（云端规划） ==="
if [ -z "$KEY" ]; then
  echo "  SKIPPED：未设置 MAAS_API_KEY，无法走云端规划"
else
  run_goal A01_system_settings "打开系统设置，然后进入网络和互联网设置"
  run_goal A02_clock_stopwatch "打开时钟应用，然后切换到秒表"
  # 日历的「上个月」在 AOSP Calendar 里是 ViewPager 手势，视图树里没有任何节点
  # （实测只有 top_button_date 与 Today 两个可点节点），节点式点击必然超时。
  # 因此改用真实存在的节点「今天」，验证的是"启动真实应用 + 点中真实控件"。
  run_goal A03_calendar_today  "打开日历应用，然后回到今天"
  run_goal A04_documents_dl    "打开文件管理应用，然后打开下载目录"
fi

echo "=== 完成，结果 JSON 见 $OUT ==="
