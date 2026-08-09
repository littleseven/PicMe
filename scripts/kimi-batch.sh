#!/bin/bash
#
# kimi-batch.sh — 多实例并行批量执行 kimi 任务（C 方案）
#
# 在隔离的 git worktree 上用 `kimi -p`（非交互）并行跑「独立、自包含」的任务，
# 并发数受 Allegro 会员「同时 4 个 agent」上限约束。
#
# ─── 核心思想 ───────────────────────────────────────────────────────────
# 单个 kimi -p 实例 = 1 个 agent 占 1 个并发名额。开 N 个实例并行 = 占 N 个名额。
# Allegro 上限 4 → MAX_PARALLEL=4 刚好打满。任务之间彼此独立（不同 worktree），
# 互不冲突，且把 4 个名额用在 4 件不同的事上（而非 1 件事的 4 个子 agent）。
#
# ─── 用法 ───────────────────────────────────────────────────────────────
# 1) 任务文件模式（TSV：<worktree>\t<prompt>，# 开头为注释）：
#      ./scripts/kimi-batch.sh scripts/kimi-batch.example.tsv
#
# 2) 扇出模式：同一 prompt 跑在多个 worktree：
#      ./scripts/kimi-batch.sh --prompt "跑 ./gradlew ktlintCheck 并修复报错" \
#          .worktrees/p4-task5 .worktrees/p4-task8-memory
#    自动发现全部 worktree：
#      ./scripts/kimi-batch.sh --prompt "..." --all-worktrees
#
# ─── 最佳参数（环境变量，均有默认值）─────────────────────────────────────
#   MAX_PARALLEL=4     并发 kimi 实例数（= Allegro 4-agent 上限；若同时开着
#                      交互式 kimi 会话，调到 3 留一个名额给主会话）
#   TASK_TIMEOUT=900   单任务超时秒（对齐 config.toml 的 agent_task_timeout_s）
#   RETRY_MAX=3        命中速率限制(429)时的重试次数（指数退避 5/10/20s）
#   KIMI_MODEL=""      可选模型覆盖；批量杂活建议 kimi-code/kimi-for-coding-highspeed
#                      （更快更省，把 K3-high 留给交互式硬任务）
#   KIMI_EXTRA=""      透传给 kimi 的附加参数
#   DRY_RUN=0          1=只打印将执行的命令，不真正调用 kimi
#
# ─── 重要约束 ───────────────────────────────────────────────────────────
# · 每个任务的 prompt 应「自包含」——别让子任务再 fan-out 子 agent，否则会突破 4 名额。
# · 批量期间请勿另开交互式 kimi 会话抢名额（或将 MAX_PARALLEL 降到 3）。
# · 结果：每任务一份 .kimi-batch/logs/<name>.log + .status；末尾打印汇总。

set -uo pipefail

# ─── 前置：定位仓库根 + kimi 可执行文件 ─────────────────────────────────
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "❌ 不在 git 仓库内" >&2; exit 1; }

KIMI_BIN="${KIMI_BIN:-kimi}"
if ! command -v "$KIMI_BIN" >/dev/null 2>&1; then
  [ -x "$HOME/.kimi-code/bin/kimi" ] && KIMI_BIN="$HOME/.kimi-code/bin/kimi"
fi
command -v "$KIMI_BIN" >/dev/null 2>&1 || { echo "❌ 找不到 kimi 可执行文件（设 KIMI_BIN 覆盖）" >&2; exit 1; }

# ─── 参数（默认值 = Allegro 最佳）───────────────────────────────────────
MAX_PARALLEL="${MAX_PARALLEL:-4}"
TASK_TIMEOUT="${TASK_TIMEOUT:-900}"
RETRY_MAX="${RETRY_MAX:-3}"
KIMI_MODEL="${KIMI_MODEL:-}"
KIMI_EXTRA="${KIMI_EXTRA:-}"
DRY_RUN="${DRY_RUN:-0}"

# 单机超时命令：macOS 无自带 timeout，优先 gtimeout(brew coreutils)
TIMEOUT_BIN=""
command -v gtimeout >/dev/null 2>&1 && TIMEOUT_BIN=gtimeout
command -v timeout  >/dev/null 2>&1 && TIMEOUT_BIN=timeout

RUN_DIR="$REPO_ROOT/.kimi-batch"
LOG_DIR="$RUN_DIR/logs"
mkdir -p "$LOG_DIR"

usage() {
  sed -n '3,40p' "$0"
}

# ─── 解析命令行 ─────────────────────────────────────────────────────────
MODE=""
PROMPT=""
TASK_FILE=""
ALL_WT=0
WORKTREES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --prompt) PROMPT="$2"; shift 2 ;;
    --all-worktrees) ALL_WT=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "未知选项: $1" >&2; usage >&2; exit 1 ;;
    *)
      if [ -z "$PROMPT" ] && [ -z "$TASK_FILE" ]; then
        TASK_FILE="$1"; MODE="file"
      else
        WORKTREES+=("$1")
      fi
      shift
      ;;
  esac
done

# 推断模式
if [ -n "$PROMPT" ]; then
  MODE="fanout"
elif [ -n "$TASK_FILE" ]; then
  MODE="file"
else
  echo "❌ 需指定任务文件或 --prompt" >&2; usage >&2; exit 1
fi

# ─── worktree 路径解析：相对路径基于 REPO_ROOT；. 或 - 表示主仓库 ────────
resolve_wt() {
  local p="$1"
  case "$p" in
    .|-) printf '%s' "$REPO_ROOT" ;;
    /*) printf '%s' "$p" ;;
    *)  printf '%s' "$REPO_ROOT/$p" ;;
  esac
}

# 任务序号计数器（必须在主 shell 自增——别放进 $(...) 子 shell，否则不累加）
name_counter=0

# ─── 收集任务到数组 ─────────────────────────────────────────────────────
declare -a T_WT=() T_LOG=() T_PROMPT=()

add_task() {
  local wt="$1" prompt="$2"
  [ -z "$prompt" ] && return
  local resolved; resolved="$(resolve_wt "$wt")"
  local base
  if [ "$resolved" = "$REPO_ROOT" ]; then base="main"; else base="$(basename "$resolved")"; fi
  name_counter=$((name_counter + 1))   # 主 shell 自增，保证唯一序号
  local logname; printf -v logname '%s-%02d' "$base" "$name_counter"
  T_WT+=("$resolved")
  T_LOG+=("$logname")
  T_PROMPT+=("$prompt")
}

if [ "$MODE" = "file" ]; then
  [ -f "$TASK_FILE" ] || { echo "❌ 任务文件不存在: $TASK_FILE" >&2; exit 1; }
  # TSV：第一列 worktree，其余为 prompt（支持 prompt 内含空格，但不含 TAB）
  while IFS=$'\t' read -r wt rest || [ -n "$wt" ]; do
    case "$wt" in ''|\#*) continue ;; esac   # 跳过空行 / 注释
    add_task "$wt" "$rest"
  done < "$TASK_FILE"
else  # fanout
  if [ "$ALL_WT" = "1" ]; then
    while IFS= read -r line; do
      [ "$line" = "$REPO_ROOT" ] && continue   # 扇出默认跳过主仓库，专注 worktree
      WORKTREES+=("$line")
    done < <(git worktree list --porcelain | awk '/^worktree /{print $2}')
  fi
  [ "${#WORKTREES[@]}" -eq 0 ] && { echo "❌ 扇出模式未给出 worktree（传路径或 --all-worktrees）" >&2; exit 1; }
  for wt in "${WORKTREES[@]}"; do add_task "$wt" "$PROMPT"; done
fi

[ "${#T_WT[@]}" -eq 0 ] && { echo "❌ 没有可执行的任务" >&2; exit 1; }

# ─── 打印计划 ───────────────────────────────────────────────────────────
echo "🤖 kimi-batch  并发=$MAX_PARALLEL  任务数=${#T_WT[@]}  超时=${TASK_TIMEOUT}s  重试=$RETRY_MAX"
[ -n "$KIMI_MODEL" ] && echo "   模型覆盖: $KIMI_MODEL"
[ -z "$TIMEOUT_BIN" ] && echo "   ⚠️  未找到 timeout/gtimeout，本次不设单任务超时"
[ "$DRY_RUN" = "1" ] && echo "   🏷️  DRY-RUN：仅打印，不调用 kimi"
echo "   日志目录: $LOG_DIR"
echo

# ─── 单任务执行（含 429 退避重试）────────────────────────────────────────
run_task() {
  local wt="$1" logname="$2" prompt="$3"
  local log="$LOG_DIR/$logname.log"
  local status_file="$LOG_DIR/$logname.status"
  local wt_disp="$wt"; [ "$wt" = "$REPO_ROOT" ] && wt_disp="(main repo)"

  {
    echo "=== $(date '+%F %T') ==="
    echo "worktree: $wt_disp"
    echo "prompt:   $prompt"
    echo "model:    ${KIMI_MODEL:-<default>}"
    echo "---"
  } > "$log"

  # DRY-RUN：只记录命令，不执行
  if [ "$DRY_RUN" = "1" ]; then
    echo "[dry-run] cd \"$wt\" && $KIMI_BIN ${KIMI_MODEL:+-m $KIMI_MODEL} -p \"<prompt>\" $KIMI_EXTRA" >> "$log"
    echo "OK $logname (dry-run)" > "$status_file"
    return 0
  fi

  local attempt=0 rc=1
  local model_args=()
  [ -n "$KIMI_MODEL" ] && model_args=(-m "$KIMI_MODEL")

  while [ "$attempt" -le "$RETRY_MAX" ]; do
    if [ -n "$TIMEOUT_BIN" ]; then
      ( cd "$wt" && "$TIMEOUT_BIN" "$TASK_TIMEOUT" "$KIMI_BIN" ${model_args[@]+"${model_args[@]}"} -p "$prompt" $KIMI_EXTRA ) >> "$log" 2>&1
    else
      ( cd "$wt" && "$KIMI_BIN" ${model_args[@]+"${model_args[@]}"} -p "$prompt" $KIMI_EXTRA ) >> "$log" 2>&1
    fi
    rc=$?
    [ "$rc" -eq 0 ] && break

    # 124 = timeout；其余先查是否速率限制
    if [ "$rc" -ne 124 ] && grep -qiE '429|rate.?limit|too many requests|throttl|quota|exceeded' "$log"; then
      attempt=$((attempt + 1))
      if [ "$attempt" -le "$RETRY_MAX" ]; then
        backoff=$(( 5 * (1 << (attempt - 1)) ))   # 5, 10, 20 …
        echo "[retry $attempt/$RETRY_MAX] 命中速率限制，${backoff}s 后重试" >> "$log"
        sleep "$backoff"
        continue
      fi
    fi
    break   # 非速率限制错误，不重试
  done

  if [ "$rc" -eq 0 ]; then      echo "OK $logname" > "$status_file"
  elif [ "$rc" -eq 124 ]; then  echo "TIMEOUT $logname (${TASK_TIMEOUT}s)" > "$status_file"
  else                          echo "FAIL $logname (rc=$rc)" > "$status_file"
  fi
}

# ─── 并发分发（bash 3.2 兼容的计数信号量）────────────────────────────────
echo "🚀 启动 ${#T_WT[@]} 个任务，最多 $MAX_PARALLEL 并行…"
for i in "${!T_WT[@]}"; do
  # 等待空出名额
  while [ "$(jobs -rp | wc -l)" -ge "$MAX_PARALLEL" ]; do
    sleep 0.3
  done
  echo "  ▶ 启动 ${T_LOG[$i]}  ← ${T_WT[$i]}"
  run_task "${T_WT[$i]}" "${T_LOG[$i]}" "${T_PROMPT[$i]}" &
done

wait   # 等待全部完成
echo

# ─── 汇总 ───────────────────────────────────────────────────────────────
ok=0; fail=0; timeout_n=0
echo "═══ 结果汇总 ═══"
for i in "${!T_LOG[@]}"; do
  s="${T_LOG[$i]}.status"
  line="$(cat "$LOG_DIR/$s" 2>/dev/null || echo "MISSING ${T_LOG[$i]}")"
  echo "  $line"
  case "$line" in OK*) ok=$((ok+1));; FAIL*) fail=$((fail+1));; TIMEOUT*) timeout_n=$((timeout_n+1));; esac
done
echo
echo "✅ OK=$ok   ❌ FAIL=$fail   ⏱ TIMEOUT=$timeout_n   （共 ${#T_WT[@]}）"
echo "📄 日志: $LOG_DIR/<name>.log"
[ "$fail" -gt 0 ] || [ "$timeout_n" -gt 0 ] && exit 1
exit 0
