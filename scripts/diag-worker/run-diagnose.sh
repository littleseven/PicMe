#!/usr/bin/env bash
# 用法: run-diagnose.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"

# W3：模板变量经 TPL_* 环境变量传给 python3 渲染（原样，不经 json_escape；
# json_escape 会把换行压成 \n 字面量，仅 sed 时代需要）。
export TPL_GIT_SHA="$gitSha"
export TPL_DESCRIPTION="$(printf '%s' "$claim" | jq -r '.description // ""')"
export TPL_CONVERSATION_SUMMARY="$(printf '%s' "$claim" | jq -r '.conversationSummary // ""')"
export TPL_LOGS="$(printf '%s' "$claim" | jq -r '.bundle.logs // ""')"
export TPL_CRASH_TRACE="$(printf '%s' "$claim" | jq -r '.bundle.crashTrace // ""')"

repo="$DIAG_WORKDIR/repo"
if [ ! -d "$repo/.git" ]; then
  git clone --quiet "$DIAG_REPO" "$repo" || { report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"clone failed\"}"; exit 0; }
fi
git -C "$repo" fetch --quiet origin 2>/dev/null || true
git -C "$repo" checkout --quiet "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet "$DIAG_BASE_BRANCH"

prompt="$(render_template "$SCRIPT_DIR/prompts/diagnose.md")"

claude_err_file="$(mktemp)"
out="$(run_with_timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --output-format json --max-turns "$DIAG_MAX_TURNS" 2>"$claude_err_file")"; rc=$?
claude_err="$(cat "$claude_err_file" 2>/dev/null)"; rm -f "$claude_err_file"

# 解析 rootCause，兼容 claude 多种输出形态：
#   a) .result 是 JSON 字符串 {rootCause,...}（claude --output-format json 标准形态）
#   b) .result.rootCause（result 本身为对象）
#   c) 顶层 .rootCause
rootCause=""
inner="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null | sed 's/```[a-zA-Z]*//g')"
[ -n "$inner" ] && rootCause="$(printf '%s' "$inner" | jq -r '.rootCause // empty' 2>/dev/null)"
# 容忍 prose 前后缀：把整段压成一行后抠出 { ... } 的 JSON 对象
if [ -z "$rootCause" ] || [ "$rootCause" = "null" ]; then
  if [ -n "$inner" ]; then
    json_only="$(printf '%s' "$inner" | tr '\n' ' ' | sed 's/.*\({.*}\).*/\1/' 2>/dev/null)"
    [ -n "$json_only" ] && rootCause="$(printf '%s' "$json_only" | jq -r '.rootCause // empty' 2>/dev/null)"
  fi
fi
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.result.rootCause // empty' 2>/dev/null)"
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.rootCause // empty' 2>/dev/null)"

# 容错：claude(GLM) 偶发不守「只输出 JSON」、直接吐 prose 根因。
# 若 prose 含源码文件线索（.kt/.java[:行]），当作 rootCause 收下（截断 800 字），避免诊断交白卷。
if { [ -z "$rootCause" ] || [ "$rootCause" = "null" ]; } && [ -n "$inner" ]; then
  if printf '%s' "$inner" | grep -qE '[A-Za-z_/]+\.(kt|java)(:[0-9]+)?'; then
    rootCause="$(printf '%.800s' "$inner" | tr '\n' ' ')"
    wlog "job #$jobId diagnose: JSON 未解析，回退用 prose 根因(len=${#rootCause})"
  fi
fi

# W1：从同一份 claude 输出抠 suspectFiles / suggestedFix（best-effort；仅 rootCause 成功时才回传）。
# inner 是 .result 文本（形态 a），抠不出 JSON 时退到整段 out（形态 b/c 由 jq 直接兜）。
suspectFiles=""; suggestedFix=""
if [ -n "$rootCause" ] && [ "$rootCause" != "null" ]; then
  json_src="$inner"
  [ -z "$json_src" ] && json_src="$out"
  json_obj="$(printf '%s' "$json_src" | tr '\n' ' ' | sed 's/.*\({.*}\).*/\1/' 2>/dev/null)"
  [ -n "$json_obj" ] && suspectFiles="$(printf '%s' "$json_obj" | jq -r '(.suspectFiles // []) | if type == "array" then join(", ") else . end' 2>/dev/null)"
  [ -n "$json_obj" ] && suggestedFix="$(printf '%s' "$json_obj" | jq -r '.suggestedFix // empty' 2>/dev/null)"
fi

if [ -n "$rootCause" ] && [ "$rootCause" != "null" ]; then
  rc_escaped="$(printf '%s' "$rootCause" | json_escape)"
  sf_escaped="$(printf '%s' "$suspectFiles" | json_escape)"
  fx_escaped="$(printf '%s' "$suggestedFix" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSED\",\"rootCause\":\"$rc_escaped\",\"suspectFiles\":\"$sf_escaped\",\"suggestedFix\":\"$fx_escaped\"}"
else
  # 解析失败：把 claude 的 .result（模型最终文本）+ num_turns + exit code 回传到 workerLog，便于排查
  result_field="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null)"
  nt="$(printf '%s' "$out" | jq -r '.num_turns // empty' 2>/dev/null)"
  raw="$(printf 'claude_exit=%s num_turns=%s | .result[0:600]=%.600s' "$rc" "$nt" "$result_field" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"no rootCause parsed; $raw\"}"
fi
