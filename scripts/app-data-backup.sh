#!/usr/bin/env bash
#
# PoLang 应用数据备份/恢复脚本
#
# 用途：
#   同一部测试机反复安装 release/debug 包时，签名不同导致必须卸载重装，
#   本脚本通过 adb 调用应用内已实现的 TAG 数据备份/还原能力，
#   将 TAG 扫描结果、聊天历史、人物关系、事实记忆、编辑配方、
#   用户偏好（账号/Token/设置）保存为本地快照，重装后一键恢复，
#   避免重新花大量时间生成。
#
# 注意：
#   - 脚本化入口 BackupRestoreBroadcastReceiver 仅存在于 debug 构建
#     （androidApp/src/debug/）；release 包请使用应用内 设置 → 备份与恢复（SAF）。
#   - 备份文件存放在外部媒体目录 /sdcard/Android/media/<package>/PoLangBackup/，
#     该目录属于应用自身存储区域，adb 可直接 pull/push。
#   - 恢复依赖媒体 URI 匹配（content://media/external/...），恢复前请确保：
#     1) 已安装目标 APK 并授予媒体读取权限；
#     2) 设备上的媒体文件与备份时一致（或至少 URI 对应文件存在）。

set -euo pipefail

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

readonly DEFAULT_PACKAGE_NAME="com.mamba.picme"
readonly AGENT_TEST_ACTION="com.mamba.picme.AGENT_TEST"
readonly RECEIVER_CLASS=".testing.backup.BackupRestoreBroadcastReceiver"
readonly MAIN_ACTIVITY=".MainActivity"
readonly BACKUP_SUBDIR="PoLangBackup"
readonly BACKUP_FILENAME="tag_data_backup.json"
readonly DEFAULT_WAIT_TIMEOUT_SEC=300

# ---------------------------------------------------------------------------
# 运行时状态（由 parse_args 填充）
# ---------------------------------------------------------------------------

PACKAGE_NAME="$DEFAULT_PACKAGE_NAME"
SCRIPT_DIR=""
SNAPSHOT_DIR=""
WAIT_TIMEOUT_SEC="$DEFAULT_WAIT_TIMEOUT_SEC"
VERBOSE=0

# ---------------------------------------------------------------------------
# 日志与错误处理
# ---------------------------------------------------------------------------

log_info()  { printf 'ℹ️  %s\n' "$*"; }
log_ok()    { printf '✅ %s\n' "$*"; }
log_warn()  { printf '⚠️  %s\n' "$*" >&2; }
log_err()   { printf '❌ %s\n' "$*" >&2; }
log_debug() { [[ "$VERBOSE" -eq 1 ]] && printf '🔍 %s\n' "$*" >&2 || true; }

die() {
  log_err "$*"
  exit 1
}

usage() {
  cat <<EOF
PoLang 应用数据备份/恢复脚本

用法：
  $(basename "$0") backup [snapshot-name]
  $(basename "$0") restore <snapshot-name>
  $(basename "$0") dry-run <snapshot-name>
  $(basename "$0") list
  $(basename "$0") delete <snapshot-name>

命令：
  backup   从当前设备备份 TAG 数据与用户偏好到本地快照目录
  restore  将本地快照恢复到当前设备（需先安装应用并授权媒体权限）
  dry-run  模拟恢复，只统计能匹配到的媒体数量，不写入数据库
  list     列出所有本地快照
  delete   删除指定本地快照

选项：
  -p, --package <name>     目标包名，默认: $DEFAULT_PACKAGE_NAME
  -d, --snapshot-dir <dir> 快照存放目录，默认: <script-dir>/app-data-snapshots/
  -t, --timeout <sec>      等待设备端操作完成的超时时间，默认: $DEFAULT_WAIT_TIMEOUT_SEC
  -v, --verbose            打印调试信息
  -h, --help               显示帮助

示例：
  # 备份当前数据（自动生成带时间戳的快照名）
  $(basename "$0") backup

  # 备份并指定快照名
  $(basename "$0") backup before_release

  # 恢复指定快照
  $(basename "$0") restore before_release

  # 先试运行，确认匹配情况
  $(basename "$0") dry-run before_release
EOF
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------

parse_args() {
  COMMAND=""
  SNAPSHOT_NAME=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      -p|--package)
        [[ $# -lt 2 ]] && die "选项 $1 需要参数"
        PACKAGE_NAME="$2"
        shift 2
        ;;
      -d|--snapshot-dir)
        [[ $# -lt 2 ]] && die "选项 $1 需要参数"
        SNAPSHOT_DIR="$2"
        shift 2
        ;;
      -t|--timeout)
        [[ $# -lt 2 ]] && die "选项 $1 需要参数"
        WAIT_TIMEOUT_SEC="$2"
        shift 2
        ;;
      -v|--verbose)
        VERBOSE=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      -*)
        die "未知选项: $1"
        ;;
      *)
        if [[ -z "$COMMAND" ]]; then
          COMMAND="$1"
        elif [[ -z "$SNAPSHOT_NAME" ]]; then
          SNAPSHOT_NAME="$1"
        else
          die "多余参数: $1"
        fi
        shift
        ;;
    esac
  done

  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  [[ -z "$SNAPSHOT_DIR" ]] && SNAPSHOT_DIR="${SCRIPT_DIR}/app-data-snapshots"
}

# ---------------------------------------------------------------------------
# adb 命令封装
# ---------------------------------------------------------------------------

adb_shell() {
  log_debug "adb shell: $*"
  if ! adb shell "$@"; then
    die "adb shell 命令失败: $*"
  fi
}

# 检查 adb 环境与应用安装状态
ensure_adb() {
  command -v adb >/dev/null 2>&1 || die "未找到 adb，请确认 Android platform-tools 已安装且在 PATH 中"

  local state
  state="$(adb get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] || die "adb 设备未连接或未授权（state=${state:-unknown}）"

  adb shell "pm path $PACKAGE_NAME" >/dev/null 2>&1 || die "设备上未安装包 $PACKAGE_NAME，请先安装 APK"
}

# 确保应用在前台运行，否则 Android 16 上 exported receiver 可能无法及时被广播唤醒
ensure_app_running() {
  log_info "确保应用处于前台..."
  adb shell "am start -n $PACKAGE_NAME/$MAIN_ACTIVITY" >/dev/null 2>&1 || true
  sleep 3
}

# ---------------------------------------------------------------------------
# 路径与快照管理
# ---------------------------------------------------------------------------

# 设备端路径（外部媒体目录，无需 run-as）
remote_backup_dir()  { echo "/sdcard/Android/media/$PACKAGE_NAME/$BACKUP_SUBDIR"; }
remote_backup_file() { echo "$(remote_backup_dir)/$BACKUP_FILENAME"; }
remote_result_file() { echo "$(remote_backup_file).result.json"; }

# 本地快照路径
snapshot_dir()           { echo "$SNAPSHOT_DIR/$1"; }
snapshot_backup_file()   { echo "$(snapshot_dir "$1")/$BACKUP_FILENAME"; }
snapshot_result_file()   { echo "$(snapshot_dir "$1")/result.json"; }
snapshot_restore_file()  { echo "$(snapshot_dir "$1")/restore-result.json"; }

ensure_snapshot_dir() {
  local name="$1"
  mkdir -p "$(snapshot_dir "$name")"
}

# ---------------------------------------------------------------------------
# 结果轮询
# ---------------------------------------------------------------------------

wait_for_remote_result() {
  local remote_file="$1"
  local elapsed=0
  local interval=2

  log_info "等待设备端操作完成（最多 ${WAIT_TIMEOUT_SEC}s）..."

  while true; do
    if adb shell "test -f $remote_file" >/dev/null 2>&1; then
      return 0
    fi

    sleep "$interval"
    elapsed=$((elapsed + interval))

    if [[ "$elapsed" -ge "$WAIT_TIMEOUT_SEC" ]]; then
      return 1
    fi

    if [[ $((elapsed % 10)) -eq 0 ]]; then
      log_info "已等待 ${elapsed}s..."
    fi
  done
}

# ---------------------------------------------------------------------------
# 文件传输
# ---------------------------------------------------------------------------

ensure_remote_dir() {
  adb shell "mkdir -p '$(remote_backup_dir)'" >/dev/null 2>&1 || true
}

pull_remote_file() {
  local remote_path="$1"
  local local_path="$2"

  log_debug "拉取 $remote_path -> $local_path"
  if ! adb pull "$remote_path" "$local_path" >/dev/null 2>&1; then
    die "拉取文件失败: $remote_path"
  fi
}

push_remote_file() {
  local local_path="$1"
  local remote_path="$2"

  log_debug "推送 $local_path -> $remote_path"
  ensure_remote_dir
  if ! adb push "$local_path" "$remote_path" >/dev/null 2>&1; then
    die "推送文件失败: $remote_path"
  fi
}

remove_remote_file() {
  adb shell "rm -f '$1'" >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# 广播命令
# ---------------------------------------------------------------------------

send_agent_broadcast() {
  local json="$1"
  log_info "发送广播命令: $json"
  # Android 16 上必须显式指定 receiver 组件名
  adb shell "am broadcast -a $AGENT_TEST_ACTION -n $PACKAGE_NAME/$RECEIVER_CLASS --es json '$json' $PACKAGE_NAME" >/dev/null
}

build_backup_json() {
  printf '{"method":"backup_tag_data","params":{"path":"%s"}}' "$(remote_backup_file)"
}

build_restore_json() {
  local dry_run="${1:-false}"
  printf '{"method":"restore_tag_data","params":{"path":"%s","dryRun":%s}}' "$(remote_backup_file)" "$dry_run"
}

# ---------------------------------------------------------------------------
# 结果展示
# ---------------------------------------------------------------------------

print_result_summary() {
  local result_file="$1"
  [[ -f "$result_file" ]] || return 0

  log_info "操作结果："
  sed 's/^/    /' "$result_file" || true
}

# ---------------------------------------------------------------------------
# 子命令实现
# ---------------------------------------------------------------------------

cmd_backup() {
  local name="$1"
  ensure_snapshot_dir "$name"

  local local_backup="$(snapshot_backup_file "$name")"
  local local_result="$(snapshot_result_file "$name")"

  remove_remote_file "$(remote_result_file)"

  ensure_app_running
  send_agent_broadcast "$(build_backup_json)"

  if ! wait_for_remote_result "$(remote_result_file)"; then
    die "备份操作超时，请检查设备日志（tag=BackupRestoreReceiver）"
  fi

  log_info "拉取备份文件..."
  pull_remote_file "$(remote_backup_file)" "$local_backup"
  pull_remote_file "$(remote_result_file)" "$local_result"
  remove_remote_file "$(remote_result_file)"
  remove_remote_file "$(remote_backup_file)"

  local size
  size="$(du -h "$local_backup" 2>/dev/null | cut -f1)"
  log_ok "备份完成: $name"
  log_info "文件: $local_backup ($size)"
  print_result_summary "$local_result"
}

cmd_restore() {
  local name="$1"
  local dry_run="${2:-false}"
  local local_backup="$(snapshot_backup_file "$name")"

  [[ -f "$local_backup" ]] || die "快照备份文件不存在: $local_backup"

  log_info "推送备份文件到设备..."
  push_remote_file "$local_backup" "$(remote_backup_file)"
  remove_remote_file "$(remote_result_file)"

  ensure_app_running
  send_agent_broadcast "$(build_restore_json "$dry_run")"

  if ! wait_for_remote_result "$(remote_result_file)"; then
    die "恢复操作超时，请检查设备日志（tag=BackupRestoreReceiver）"
  fi

  local restore_result="$(snapshot_restore_file "$name")"
  pull_remote_file "$(remote_result_file)" "$restore_result"
  remove_remote_file "$(remote_result_file)"
  remove_remote_file "$(remote_backup_file)"

  if [[ "$dry_run" == "true" ]]; then
    log_ok "dry-run 完成: $name"
  else
    log_ok "恢复完成: $name"
  fi
  print_result_summary "$restore_result"
}

cmd_list() {
  if [[ ! -d "$SNAPSHOT_DIR" ]]; then
    log_info "暂无快照"
    return
  fi

  local found=0
  echo "📦 本地快照列表："

  for entry in "$SNAPSHOT_DIR"/*; do
    [[ -d "$entry" ]] || continue

    local name="$(basename "$entry")"
    local backup_file="$entry/$BACKUP_FILENAME"
    local result_file="$entry/result.json"
    local meta=""

    if [[ -f "$backup_file" ]]; then
      local size
      size="$(du -h "$backup_file" 2>/dev/null | cut -f1)"
      meta="size=$size"
    fi

    if [[ -f "$result_file" ]]; then
      local status
      status="$(grep -o '"status":"[^"]*"' "$result_file" 2>/dev/null | head -n1 | cut -d'"' -f4)"
      [[ -n "$status" ]] && meta="${meta:+$meta, }status=$status"
    fi

    printf '  - %-40s %s\n' "$name" "${meta:-N/A}"
    found=1
  done

  [[ "$found" -eq 0 ]] && log_info "暂无快照"
  return 0
}

cmd_delete() {
  local name="$1"
  local dir="$(snapshot_dir "$name")"

  [[ -d "$dir" ]] || die "快照不存在: $name"

  rm -rf "$dir"
  log_ok "已删除快照: $name"
}

# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

main() {
  parse_args "$@"

  case "${COMMAND:-}" in
    backup)
      [[ -z "$SNAPSHOT_NAME" ]] && SNAPSHOT_NAME="$(date +%Y%m%d_%H%M%S)"
      ensure_adb
      cmd_backup "$SNAPSHOT_NAME"
      ;;
    restore)
      [[ -z "$SNAPSHOT_NAME" ]] && die "restore 命令需要指定快照名"
      ensure_adb
      cmd_restore "$SNAPSHOT_NAME" "false"
      ;;
    dry-run)
      [[ -z "$SNAPSHOT_NAME" ]] && die "dry-run 命令需要指定快照名"
      ensure_adb
      cmd_restore "$SNAPSHOT_NAME" "true"
      ;;
    list)
      cmd_list
      ;;
    delete)
      [[ -z "$SNAPSHOT_NAME" ]] && die "delete 命令需要指定快照名"
      cmd_delete "$SNAPSHOT_NAME"
      ;;
    "")
      usage
      exit 1
      ;;
    *)
      die "未知命令: $COMMAND"
      ;;
  esac
}

main "$@"
