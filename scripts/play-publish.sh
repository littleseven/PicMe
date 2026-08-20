#!/bin/bash
#
# Play Publish - PoLang Google Play 自动发布脚本（基于 GPP / com.github.triplet.play）
# 用途: 上传 AAB、同步商店文案、跨轨道晋升，替代 Play Console 手动操作
# 调用: ./scripts/play-publish.sh [options]
#
# 认证（二选一，脚本会预检）：
#   本地: export POLANG_PLAY_SERVICE_ACCOUNT_JSON=/path/to/service-account.json
#   CI:   export ANDROID_PUBLISHER_CREDENTIALS='<service account json 全文>'
#
# Options:
#   --track <track>           目标轨道: internal|alpha|beta|production|自定义 (默认: internal)
#   --status <status>         发布状态: completed|draft|inProgress|halted (默认: completed)
#   --user-fraction <f>       分阶段发布比例 0-1（仅 inProgress/halted 生效；1.0 非法，收尾用 completed）
#   --artifact-dir <dir>      使用已有 AAB 目录（默认: androidApp/build/outputs/bundle/release，
#                             即 ./scripts/build.sh aab 的产物目录；不传则 GPP 从源码构建）
#   --notes <file>            发布说明（写入 play/release-notes/<lang>/<track>.txt，≤500 字符）
#   --notes-lang <lang>       --notes 的语言 (默认: en-US；zh-CN/zh-TW 需分别调用)
#   --listing-only            只同步商店文案（publishListing），不上传 AAB
#   --bootstrap               从线上拉取现有 listing 初始化 play/ 目录（会重置本地 play/ 目录！）
#   --promote                 晋升模式：不产新包，把 --from-track 的版本晋升到 --track
#   --from-track <track>      晋升源轨道（--promote 时使用）
#   --update-rollout <track>  仅调整某轨道在途发布的 rollout 比例（配合 --user-fraction）
#   --dry-run                 只打印将执行的 gradle 命令，不实际执行
#   --resumable               上传走 scripts/play-upload-resumable.py（Python 分块续传），
#                             直连网络下 GPP/JVM 客户端大文件上传易被掐断时的 fallback
#
# 示例:
#   ./scripts/play-publish.sh                                  # 构建 AAB → internal 全量
#   ./scripts/play-publish.sh --notes /tmp/notes.txt           # 带发布说明
#   ./scripts/play-publish.sh --artifact-dir androidApp/build/outputs/bundle/release
#   ./scripts/play-publish.sh --listing-only                   # 只同步三语商店文案
#   ./scripts/play-publish.sh --promote --from-track internal --track alpha --status completed
#                                                              # 晋升封闭式（beta 同理换 --track beta）
#   ./scripts/play-publish.sh --promote --from-track internal --track production --status draft
#   ./scripts/play-publish.sh --update-rollout production --user-fraction 0.5
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

TRACK="internal"
STATUS="completed"
USER_FRACTION=""
ARTIFACT_DIR=""
NOTES_FILE=""
NOTES_LANG="en-US"
LISTING_ONLY=false
BOOTSTRAP=false
PROMOTE=false
FROM_TRACK=""
UPDATE_ROLLOUT=""
DRY_RUN=false
RESUMABLE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --track)           TRACK="$2"; shift 2 ;;
        --status)          STATUS="$2"; shift 2 ;;
        --user-fraction)   USER_FRACTION="$2"; shift 2 ;;
        --artifact-dir)    ARTIFACT_DIR="$2"; shift 2 ;;
        --notes)           NOTES_FILE="$2"; shift 2 ;;
        --notes-lang)      NOTES_LANG="$2"; shift 2 ;;
        --listing-only)    LISTING_ONLY=true; shift ;;
        --bootstrap)       BOOTSTRAP=true; shift ;;
        --promote)         PROMOTE=true; shift ;;
        --from-track)      FROM_TRACK="$2"; shift 2 ;;
        --update-rollout)  UPDATE_ROLLOUT="$2"; shift 2 ;;
        --dry-run)         DRY_RUN=true; shift ;;
        --resumable)       RESUMABLE=true; shift ;;
        *) log_error "未知参数: $1"; exit 1 ;;
    esac
done

# ---- 认证预检 ----
check_credentials() {
    $DRY_RUN && return 0
    if [ -n "${POLANG_PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then
        if [ ! -f "$POLANG_PLAY_SERVICE_ACCOUNT_JSON" ]; then
            log_error "POLANG_PLAY_SERVICE_ACCOUNT_JSON 指向的文件不存在: $POLANG_PLAY_SERVICE_ACCOUNT_JSON"
            exit 1
        fi
        log_info "认证方式: service account 文件 ($POLANG_PLAY_SERVICE_ACCOUNT_JSON)"
    elif [ -n "${ANDROID_PUBLISHER_CREDENTIALS:-}" ]; then
        log_info "认证方式: ANDROID_PUBLISHER_CREDENTIALS 环境变量"
    else
        log_error "未配置 Play 发布凭据。请设置 POLANG_PLAY_SERVICE_ACCOUNT_JSON（文件路径）或 ANDROID_PUBLISHER_CREDENTIALS（JSON 全文）"
        exit 1
    fi
}

# ---- 发布说明写入 ----
write_release_notes() {
    [ -z "$NOTES_FILE" ] && return 0
    if [ ! -f "$NOTES_FILE" ]; then
        log_error "发布说明文件不存在: $NOTES_FILE"
        exit 1
    fi
    local chars
    chars=$(wc -m < "$NOTES_FILE" | tr -d ' ')
    if [ "$chars" -gt 500 ]; then
        log_error "发布说明超长（${chars} 字符，Play 上限 500）"
        exit 1
    fi
    local notes_dir="androidApp/src/main/play/release-notes/$NOTES_LANG"
    mkdir -p "$notes_dir"
    cp "$NOTES_FILE" "$notes_dir/$TRACK.txt"
    log_info "发布说明已写入 $notes_dir/$TRACK.txt（${chars} 字符）"
}

run_gradle() {
    if $DRY_RUN; then
        log_info "[dry-run] $*"
    else
        "$@"
    fi
}

# ---- 主流程 ----
if $BOOTSTRAP; then
    check_credentials
    log_warn "bootstrapListing 会用线上内容重置本地 androidApp/src/main/play/ 目录"
    run_gradle ./gradlew :androidApp:bootstrapReleaseListing
    log_success "已从线上拉取 listing"
    exit 0
fi

if $LISTING_ONLY; then
    check_credentials
    if $RESUMABLE; then
        # 直连网络下 GPP publishListing 不可靠（graphics 全量上传卡死 / OAuth token 超时）：
        # 文本+详情+图像全部走 Python 通道，单 edit 一次 commit，图像 sha256 比对只传增量。
        PLAY_DIR="androidApp/src/main/play/listings"
        log_info "同步商店文案（Python 通道：文本+全局详情+图像增量）..."
        if $DRY_RUN; then
            log_info "[dry-run] python3 scripts/play-upload-resumable.py --listing $PLAY_DIR"
        else
            python3 scripts/play-upload-resumable.py --listing "$PLAY_DIR"
        fi
        log_success "商店文案已同步（resumable 通道）"
        exit 0
    fi
    log_info "同步商店文案（listings + 全局元数据）..."
    run_gradle ./gradlew :androidApp:publishReleaseListing
    log_success "商店文案已同步"
    exit 0
fi

if [ -n "$UPDATE_ROLLOUT" ]; then
    check_credentials
    if [ -z "$USER_FRACTION" ]; then
        log_error "--update-rollout 需要配合 --user-fraction"
        exit 1
    fi
    log_info "调整 $UPDATE_ROLLOUT 轨道 rollout 比例 → $USER_FRACTION"
    run_gradle ./gradlew :androidApp:promoteReleaseArtifact --update "$UPDATE_ROLLOUT" --user-fraction "$USER_FRACTION"
    log_success "rollout 已更新"
    exit 0
fi

if $PROMOTE; then
    check_credentials
    if [ -z "$FROM_TRACK" ]; then
        log_error "--promote 需要指定 --from-track"
        exit 1
    fi
    log_info "晋升: $FROM_TRACK → $TRACK (status=$STATUS${USER_FRACTION:+, fraction=$USER_FRACTION})"
    args=(./gradlew :androidApp:promoteReleaseArtifact --from-track "$FROM_TRACK" --promote-track "$TRACK" --release-status "$STATUS")
    [ -n "$USER_FRACTION" ] && args+=(--user-fraction "$USER_FRACTION")
    run_gradle "${args[@]}"
    log_success "晋升完成"
    exit 0
fi

# 默认：上传 AAB
check_credentials
write_release_notes

if $RESUMABLE; then
    # Python 分块续传路径：直连网络下 GPP/JVM 大文件上传易被掐断时的 fallback
    aab_dir="${ARTIFACT_DIR:-androidApp/build/outputs/bundle/release}"
    aab_file=$(find "$aab_dir" -maxdepth 1 -name "*.aab" | head -1)
    if [ -z "$aab_file" ]; then
        log_error "未找到 AAB: $aab_dir（先跑 ./scripts/build.sh aab 或用 --artifact-dir 指定）"
        exit 1
    fi
    py_args=(scripts/play-upload-resumable.py --aab "$aab_file" --track "$TRACK" --status "$STATUS")
    [ -n "$USER_FRACTION" ] && py_args+=(--user-fraction "$USER_FRACTION")
    log_info "分块续传发布 AAB → track=${TRACK}, status=${STATUS}（${aab_file}）"
    if $DRY_RUN; then
        log_info "[dry-run] python3 ${py_args[*]}"
    else
        python3 "${py_args[@]}"
    fi
    log_success "发布完成（track=${TRACK}，resumable 通道）"
    exit 0
fi

args=(./gradlew :androidApp:publishReleaseBundle --track "$TRACK" --release-status "$STATUS")
[ -n "$USER_FRACTION" ] && args+=(--user-fraction "$USER_FRACTION")
if [ -n "$ARTIFACT_DIR" ]; then
    if [ ! -d "$ARTIFACT_DIR" ]; then
        log_error "artifact 目录不存在: $ARTIFACT_DIR（先跑 ./scripts/build.sh aab）"
        exit 1
    fi
    args+=(--artifact-dir "$ARTIFACT_DIR")
    log_info "使用已有 AAB: $ARTIFACT_DIR"
else
    log_info "GPP 将从源码构建 release AAB（需 POLANG_RELEASE_* 签名环境变量）"
fi

log_info "发布 AAB → track=$TRACK, status=$STATUS${USER_FRACTION:+, fraction=$USER_FRACTION}"
run_gradle "${args[@]}"
log_success "发布完成（track=${TRACK}）"
