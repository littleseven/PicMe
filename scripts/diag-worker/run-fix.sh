#!/usr/bin/env bash
# 用法: run-fix.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
rootCause="$(printf '%s' "$claim" | jq -r '.rootCause // ""' | json_escape)"
suggested=""   # 诊断阶段未回传 suggestedFix，留空
mode="$(printf '%s' "$claim" | jq -r '.fixMode // "push"')"
branch="diag-fix/$jobId"

repo="$DIAG_WORKDIR/repo"
git -C "$repo" fetch --quiet origin 2>/dev/null || true
git -C "$repo" checkout --quiet -B "$branch" "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet -B "$branch" "$DIAG_BASE_BRANCH"

prompt="$(sed -e "s|__ROOT_CAUSE__|$rootCause|g" -e "s|__SUGGESTED_FIX__|$suggested|g" "$SCRIPT_DIR/prompts/fix.md")"
run_with_timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --dangerously-skip-permissions --output-format json --max-turns "$DIAG_MAX_TURNS" >/dev/null 2>&1 || true

# 自检：跑 server JVM 单测（资源允许）；失败/超时不阻断，只标 tested=false。
tested=false
if run_with_timeout 240 ./gradlew -p "$repo/server" test -q >/dev/null 2>&1; then tested=true; fi

git -C "$repo" add -A
git -C "$repo" commit --quiet -m "fix(diag): 远程诊断自动修复 job #$jobId" >/dev/null 2>&1 || true
git -C "$repo" push --quiet origin "$branch" >/dev/null 2>&1 || { report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"error\":\"push failed\"}"; exit 0; }

extra=""
[ "$mode" = "pr" ] && extra=",\"compareUrl\":\"$(compare_url "$branch")\""
status="FIXED"; [ "$tested" = "false" ] && status="FIXED_UNVERIFIED"
report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested$extra}"
