#!/usr/bin/env bash
# 用法: run-fix.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
# W1：诊断阶段回传的 rootCause/suggestedFix 经 claim 传入（旧 server 无此字段 → 空串，同现状）
export TPL_ROOT_CAUSE="$(printf '%s' "$claim" | jq -r '.rootCause // ""')"
export TPL_SUGGESTED_FIX="$(printf '%s' "$claim" | jq -r '.suggestedFix // ""')"
mode="$(printf '%s' "$claim" | jq -r '.fixMode // "push"')"
branch="diag-fix/$jobId"
wlog "job #$jobId FIX start (sha=$gitSha mode=$mode)"

repo="$DIAG_WORKDIR/repo"
git -C "$repo" remote set-url origin "$DIAG_REPO" 2>/dev/null || true
git -C "$repo" fetch --quiet origin 2>/dev/null || true
git -C "$repo" checkout --quiet -B "$branch" "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet -B "$branch" "$DIAG_BASE_BRANCH"

prompt="$(render_template "$SCRIPT_DIR/prompts/fix.md")"
wlog "job #$jobId claude fix start (<= ${DIAG_PHASE_TIMEOUT}s)"
# claude 必须在 repo 根跑（prompt 假设在 repo）；输出存档供诊断（不再 /dev/null）。
claude_out="$DIAG_WORKDIR/claude-fix-$jobId.out"
( cd "$repo" && run_with_timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --dangerously-skip-permissions --output-format json --max-turns "$DIAG_MAX_TURNS" ) >"$claude_out" 2>&1 || true
wlog "job #$jobId claude fix done rc=$? ; out: $(head -c 300 "$claude_out" 2>/dev/null | tr '\n' ' ')"

# 自检：跑 server JVM 单测（资源允许）；失败/超时不阻断，只标 tested=false。
tested=false
if run_with_timeout 240 "$repo/gradlew" -p "$repo/server" test -q >/dev/null 2>&1; then tested=true; fi

# 显式配 local git user（云主机若无全局 user，commit 会失败 → 空提交；历次空修复高度疑似此因）。
git -C "$repo" config user.email "diag-worker@polang" 2>/dev/null || true
git -C "$repo" config user.name "diag-worker" 2>/dev/null || true
git -C "$repo" add -A
if git -C "$repo" commit --quiet -m "fix(diag): 远程诊断自动修复 job #$jobId" >/dev/null 2>&1; then
  wlog "job #$jobId commit ok: $(git -C "$repo" show --stat --oneline HEAD | head -1 | cut -c1-120)"
else
  wlog "job #$jobId commit 无改动或失败（claude 是否真改了文件？见 $claude_out）；status: $(git -C "$repo" status --short | head -5 | tr '\n' ' ')"
fi
wlog "job #$jobId push $branch"
if ! run_with_timeout 120 git -C "$repo" push --quiet origin "$branch" >/dev/null 2>&1; then
  wlog "job #$jobId push FAILED"
  report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"error\":\"push failed\"}"; exit 0
fi

status="FIXED"; [ "$tested" = "false" ] && status="FIXED_UNVERIFIED"
case "$mode" in
  push)
    wlog "job #$jobId mode=push (保守：仅推分支)"
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    ;;
  pr)
    wlog "job #$jobId mode=pr (待审：建真 PR)"
    if ! gh_auth; then
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"fixBranch\":\"$branch\",\"error\":\"gh not configured (GITHUB_TOKEN missing or gh absent)\"}"; exit 0
    fi
    pr_url="$(cd "$repo" && gh pr create --base "$DIAG_BASE_BRANCH" --head "$branch" \
      --title "fix(diag): 远程诊断自动修复 job #$jobId" \
      --body "由远程诊断 worker 自动修复。根因见 server /admin/diag job #$jobId。" 2>/dev/null)"
    if [ -n "$pr_url" ]; then
      wlog "job #$jobId PR created: $pr_url"
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"compareUrl\":\"$(printf '%s' "$pr_url" | json_escape)\"}"
    else
      wlog "job #$jobId gh pr create FAILED"
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"fixBranch\":\"$branch\",\"error\":\"gh pr create failed\"}"
    fi
    ;;
  auto)
    wlog "job #$jobId mode=auto (自动：自检过则合并 main)"
    auto_merged=0
    if [ "$tested" = "true" ] && git -C "$repo" fetch --quiet origin "$DIAG_BASE_BRANCH"; then
      base="origin/$DIAG_BASE_BRANCH"
      # 1) 先试 ff（diag-fix 是 main 的直接祖先）
      if git -C "$repo" checkout --quiet -B "$DIAG_BASE_BRANCH" "$base" && git -C "$repo" merge --ff-only "$branch"; then
        auto_merged=1
      # 2) ff 失败（main 已前进）：把 diag-fix rebase 到 main 之上再 ff，避免被新提交 block
      elif git -C "$repo" checkout --quiet "$branch" 2>/dev/null \
           && git -C "$repo" rebase --quiet "$base" 2>/dev/null \
           && git -C "$repo" checkout --quiet -B "$DIAG_BASE_BRANCH" "$base" \
           && git -C "$repo" merge --ff-only "$branch"; then
        wlog "job #$jobId auto: ff 失败，rebase 到 $DIAG_BASE_BRANCH 后合并"
        auto_merged=1
      fi
      if [ "$auto_merged" = "1" ] && run_with_timeout 120 git -C "$repo" push --quiet origin "$DIAG_BASE_BRANCH"; then
        wlog "job #$jobId auto-merged to $DIAG_BASE_BRANCH"
        report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED\",\"fixBranch\":\"$DIAG_BASE_BRANCH\",\"tested\":true}"; exit 0
      fi
    fi
    # 自检失败 / rebase 冲突 / push 失败 → 降级留分支（不 block，可手动收尾）
    wlog "job #$jobId auto aborted (自检失败/rebase冲突/push失败)，留 $branch 分支"
    git -C "$repo" checkout --quiet "$branch" 2>/dev/null || true
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED_UNVERIFIED\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    ;;
  *)
    wlog "job #$jobId unknown mode=$mode，按 push 处理"
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    ;;
esac
