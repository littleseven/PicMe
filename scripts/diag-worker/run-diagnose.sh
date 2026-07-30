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

out="$(run_with_timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --output-format json --max-turns "$DIAG_MAX_TURNS" 2>/dev/null)" || true
# claude --output-format json 把模型文本放在 .result；模型文本本身是一段 JSON。
rootCause="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null | jq -r '.rootCause // empty' 2>/dev/null)"
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.rootCause // empty' 2>/dev/null)"

if [ -z "$rootCause" ]; then
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"no rootCause parsed\"}"
else
  rc_escaped="$(printf '%s' "$rootCause" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSED\",\"rootCause\":\"$rc_escaped\"}"
fi
