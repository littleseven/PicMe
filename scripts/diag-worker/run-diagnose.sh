#!/usr/bin/env bash
# 用法: run-diagnose.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
desc="$(printf '%s' "$claim" | jq -r .description | json_escape)"
logs="$(printf '%s' "$claim" | jq -r '.bundle.logs // ""' | json_escape)"
crash="$(printf '%s' "$claim" | jq -r '.bundle.crashTrace // ""' | json_escape)"

repo="$DIAG_WORKDIR/repo"
if [ ! -d "$repo/.git" ]; then
  git clone --quiet "$DIAG_REPO" "$repo" || { report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"clone failed\"}"; exit 0; }
fi
git -C "$repo" fetch --quiet origin 2>/dev/null || true
git -C "$repo" checkout --quiet "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet "$DIAG_BASE_BRANCH"

prompt="$(sed -e "s|__GIT_SHA__|$gitSha|g" -e "s|__DESCRIPTION__|$desc|g" -e "s|__LOGS__|$logs|g" -e "s|__CRASH_TRACE__|$crash|g" "$SCRIPT_DIR/prompts/diagnose.md")"

claude_err_file="$(mktemp)"
out="$(run_with_timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --output-format json --max-turns "$DIAG_MAX_TURNS" 2>"$claude_err_file")"; rc=$?
claude_err="$(cat "$claude_err_file" 2>/dev/null)"; rm -f "$claude_err_file"

# 解析 rootCause，兼容 claude 多种输出形态：
#   a) .result 是 JSON 字符串 {rootCause,...}（claude --output-format json 标准形态）
#   b) .result.rootCause（result 本身为对象）
#   c) 顶层 .rootCause
rootCause=""
inner="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null | sed 's/```[a-zA-Z]*//g; s/^[[:space:]]*//; s/[[:space:]]*$//')"
[ -n "$inner" ] && rootCause="$(printf '%s' "$inner" | jq -r '.rootCause // empty' 2>/dev/null)"
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.result.rootCause // empty' 2>/dev/null)"
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.rootCause // empty' 2>/dev/null)"

if [ -n "$rootCause" ] && [ "$rootCause" != "null" ]; then
  rc_escaped="$(printf '%s' "$rootCause" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSED\",\"rootCause\":\"$rc_escaped\"}"
else
  # 解析失败：把 claude 原始输出（截断）+ stderr + exit code 回传到 workerLog，便于排查
  raw="$(printf 'claude_exit=%s | stdout[0:800]=%.800s | stderr[0:500]=%.500s' "$rc" "$out" "$claude_err" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"no rootCause parsed; $raw\"}"
fi
