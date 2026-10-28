#!/usr/bin/env bash
# 真实云模型规划（讯飞 MaaS spark-x2.6）下发自然语言目标，记录真实规划与执行回执。
#
# 与 device_regression.sh 的区别：这里下发的是 goal（自然语言），
# 由 Agent 内的 ModelRouter 走 L0 规则 → L0.5 意图 → L3 云端 链路真实规划，
# 因此每个用例的 model_router.cloud_calls / 规划耗时都是真实证据。
#
# 目标措辞刻意避开 L0 规则关键词（网页/发送文件/Wi-Fi/笔记/截图…），
# 否则会被本地规则直接接走，测不到云端规划。
#
# 前置：
#   1) 宿主机 cloud_proxy.py 已在 8900 监听（MAAS_API_KEY 走环境变量）
#   2) 模拟器已启动、Agent 已安装、无障碍已绑定
# 运行：MAAS_API_KEY=... bash cloud_real_plan.sh
set -u
export PATH=/workspace/android-sdk/platform-tools:$PATH
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.agentruntime.agent
TAPP=com.agenttestapp
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/cloud_plan"
mkdir -p "$OUT"

KEY="${MAAS_API_KEY:?需要环境变量 MAAS_API_KEY}"
BASE="${CLOUD_BASE_URL:-http://10.0.2.2:8900/v2/chat/completions}"
MODEL="${CLOUD_MODEL:-spark-x2.6}"

b64() { python3 -c "import sys,base64;print(base64.b64encode(sys.argv[1].encode()).decode())" "$1"; }

run_goal () {
  local name="$1" goal="$2"
  adb shell am force-stop "$TAPP" >/dev/null 2>&1
  adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
  adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" \
      --ez auto_confirm true --ez allow_replan false \
      --es goal_b64 "$(b64 "$goal")" \
      --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" \
      >/dev/null 2>&1
  local r="" t0=$(date +%s)
  for _ in $(seq 1 130); do
    sleep 3
    r=$(adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null)
    echo "$r" | grep -q '"status"' && break
  done
  echo "$r" > "$OUT/$name.json"
  echo "$r" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('  $name  PARSE_FAIL', e); sys.exit()
det=d.get('detail',{})
mr=det.get('model_router',{})
print('  $name  status=%s  wall=%ss  cloud_calls=%s  intent_hits=%s  compound_skips=%s  rule=%s' % (
    d.get('status'), $(( $(date +%s) - t0 )), mr.get('cloud_calls'), mr.get('intent_hits'),
    mr.get('compound_skips'), mr.get('rule_plans')))
if d.get('plan'):
    p=d['plan']
    print('  plan: task_id=%s steps=%d allowed_apps=%s' % (p.get('task_id'), len(p.get('steps',[])), p.get('allowed_apps')))
    for s in p.get('steps',[]): print('     - %s %s' % (s.get('skill'), json.dumps(s.get('args',{}),ensure_ascii=False)[:90]))
for s in det.get('steps',[]):
    print('     exec %-24s %-18s ok=%s' % (s.get('skill'), s.get('code'), s.get('ok')))
" | tee -a "$OUT/last_run.txt"
}

# ONLY="G01_chat_text G05_admin_text" 可只跑指定用例（录屏演示时用）
run_case () {
  if [ -n "${ONLY:-}" ] && ! printf '%s' " $ONLY " | grep -q " $1 "; then
    echo "  $1  SKIPPED（不在 ONLY 列表）"
    return 0
  fi
  run_goal "$1" "$2"
}

echo "=== 云配置自检 ==="
adb shell run-as "$PKG" rm -f files/last_result.json >/dev/null 2>&1
adb shell am start -n "$PKG/com.agentruntime.ui.TaskExecActivity" --ez cloud_ping true --ez behind_after_launch false \
    --es cloud_base_url "$BASE" --es cloud_api_key "$KEY" --es cloud_model "$MODEL" >/dev/null 2>&1
sleep 15
adb shell run-as "$PKG" cat files/last_result.json 2>/dev/null | tee "$OUT/cloud_ping.json"; echo

echo "=== 真实自然语言规划用例（云端承担规划） ==="
run_case G01_chat_text    "在测试应用里打开和 TestUser 的对话，然后发送一条消息 云规划链路已打通"
run_case G02_chat_file    "在测试应用里进入和 TestUser 的会话，然后把 report.pdf 交给他"
run_case G03_read_net     "打开测试应用的配置页面，然后读取当前网络连接信息"
run_case G05_admin_text   "在测试应用里找到 Admin，然后给他发一条消息 云端规划第二轮"
run_case G06_user2_file   "在测试应用里进入和 TestUser2 的会话，然后把 report.pdf 交给他"
# 已知限制用例：写入成功，回读在 API 29+ 非焦点场景被系统拒绝（CLIPBOARD_EMPTY）
run_case G04_clipboard    "把 CLOUD_PLAN_MARK_7788 这段文字放进系统剪贴板"
# 第三方应用（非 AgentTestApp）：验证云端规划能落到别的 App。
# 措辞必须避开 L0 规则关键词（笔记/note、时钟/clock/闹钟…），否则 rule=1 被本地规则接走。
run_case N01_memo_new     "打开备忘应用，然后点一下新建按钮"
run_case N02_clock_stop   "打开计时器应用，然后切换到秒表页"
# 日历：月份视图里"上个月"按钮的 content-desc 稳定存在（Go to previous month），
# 比"选中某一天"更可靠——后者需要模型猜日期单元格文字，容易落在不存在的锚点上。
run_case N03_calendar_day "打开日历应用，然后切换到上个月"
echo "=== 完成，结果见 $OUT ==="
