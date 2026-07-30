#!/usr/bin/env bash
# 远程诊断 worker 共享工具：加载配置、claim/result、JSON 解析、compare URL。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 加载 worker.env（存在则覆盖默认）
load_env() {
  local f="${SCRIPT_DIR}/worker.env"
  if [ -f "$f" ]; then . "$f"; fi
  : "${DIAG_SERVER:?worker.env missing DIAG_SERVER}"
  : "${DIAG_WORKER_TOKEN:?worker.env missing DIAG_WORKER_TOKEN}"
  : "${DIAG_REPO:?worker.env missing DIAG_REPO}"
  : "${DIAG_WORKDIR:=/tmp/diag-work}"
  : "${DIAG_POLL_INTERVAL:=60}"
  : "${DIAG_BASE_BRANCH:=main}"
  : "${DIAG_CLAUDE:=claude}"
  : "${DIAG_MAX_TURNS:=20}"
  : "${DIAG_PHASE_TIMEOUT:=300}"
  mkdir -p "$DIAG_WORKDIR"
}

# 领一个任务；stdout 输出 claim JSON（空则返回 1）。
claim_next() {
  local body
  body="$(curl -sf -m 15 -H "X-Diag-Worker-Token: $DIAG_WORKER_TOKEN" "$DIAG_SERVER/diag/work/jobs" 2>/dev/null)" || return 1
  [ -z "$body" ] && return 1
  printf '%s' "$body"
}

# report_result <jobId> <json body>
report_result() {
  local jobId="$1" body="$2"
  curl -sf -m 15 -H "X-Diag-Worker-Token: $DIAG_WORKER_TOKEN" \
    -H "Content-Type: application/json" --data "$body" \
    "$DIAG_SERVER/diag/work/jobs/$jobId/result" >/dev/null
}

# 从 repo URL 派生 compare URL：compare/<base>...<branch>
# compare_url <branch>
compare_url() {
  local branch="$1"
  local rest="${DIAG_REPO#*://}"      # strip scheme
  rest="${rest%.git}"                  # strip .git
  rest="${rest#*github.com/}"          # strip host → owner/repo
  printf 'https://github.com/%s/compare/%s...%s' "$rest" "$DIAG_BASE_BRANCH" "$branch"
}

# JSON 字符串转义（最小：处理 " 和 \ 和换行），用于把变量安全嵌入 prompt/JSON。
json_escape() {
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {print}' | sed 's/\\n$//'
}

# 用 timeout 包裹命令；macOS 默认无 timeout（GNU coreutils），无则裸跑。云主机 Ubuntu 有 timeout。
# 用法: run_with_timeout <secs> <cmd...>
run_with_timeout() {
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$@"
  else
    "$@"
  fi
}
