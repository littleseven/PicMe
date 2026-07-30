#!/usr/bin/env bash
# 远程诊断 worker 主循环。常驻；poll 不调 LLM，无任务时仅 sleep。
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

echo "[diag-worker] polling $DIAG_SERVER every ${DIAG_POLL_INTERVAL}s"
while true; do
  claim="$(claim_next)" || { sleep "$DIAG_POLL_INTERVAL"; continue; }
  jobId="$(printf '%s' "$claim" | jq -r .jobId)"
  phase="$(printf '%s' "$claim" | jq -r .phase)"
  echo "[diag-worker] claimed job #$jobId phase=$phase"
  case "$phase" in
    diagnose) bash "$SCRIPT_DIR/run-diagnose.sh" "$jobId" "$claim" ;;
    fix)      bash "$SCRIPT_DIR/run-fix.sh" "$jobId" "$claim" ;;
    *) echo "[diag-worker] unknown phase=$phase; skipping" ;;
  esac
  sleep "$DIAG_POLL_INTERVAL"
done
