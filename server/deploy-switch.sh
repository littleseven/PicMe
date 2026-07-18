#!/usr/bin/env bash
#
# deploy-switch.sh - PoLang Server 服务器端蓝绿发布
# 作用：把 ~/picme-server.new 切换为现网 ~/picme-server，重启 + healthz 校验 + 失败自动回滚。
# 执行者：deploy.sh（开发机 ssh 调用）或 OpenClaw（"发布 picme"，见 OPENCLAW_DEPLOY.md）。
#
# 前提（首次部署时确认）：
#   - ~/picme-server.new/ 已由 deploy.sh / CI rsync 就位
#   - ubuntu 用户对 systemctl 免密（sudoers NOPASSWD），否则 sudo 会卡
#   - 生产 picme-api 监听 127.0.0.1:8080（HEALTH_URL 不符时用环境变量覆盖）
#
set -euo pipefail
export LC_ALL=C

APP_DIR="$HOME/picme-server"
NEW_DIR="$HOME/picme-server.new"
PREV_DIR="$HOME/picme-server.prev"
SERVICE="picme-api"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/healthz}"   # 直连本地，绕过 nginx/DNS
WAIT_SECS="${WAIT_SECS:-30}"

log() { echo ">> $*"; }

if [[ ! -d "$NEW_DIR" ]]; then
    echo "❌ $NEW_DIR 不存在。先在开发机/CI 跑 deploy.sh 把新版本 rsync 成 .new。" >&2
    exit 2
fi

log "备份现网 → $PREV_DIR"
rm -rf "$PREV_DIR"
[[ -d "$APP_DIR" ]] && mv "$APP_DIR" "$PREV_DIR"

log "新版本上位：$NEW_DIR → $APP_DIR"
mv "$NEW_DIR" "$APP_DIR"

log "重启 $SERVICE"
sudo systemctl restart "$SERVICE"

log "健康检查（最长 ${WAIT_SECS}s，探 $HEALTH_URL）"
for _ in $(seq 1 "$WAIT_SECS"); do
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
        log "✅ 发布成功。回滚备份保留：$PREV_DIR"
        exit 0
    fi
    sleep 1
done

# 未通过 → 自动回滚到上一版
log "❌ 健康检查未通过，回滚到 $PREV_DIR" >&2
if [[ -d "$PREV_DIR" ]]; then
    rm -rf "$APP_DIR"
    mv "$PREV_DIR" "$APP_DIR"
    sudo systemctl restart "$SERVICE"
    sleep 2
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
        log "✅ 已回滚，旧版本恢复健康"
    else
        log "⚠️ 回滚后仍不健康，需人工介入" >&2
    fi
fi
log "最近日志（journalctl -u $SERVICE -n 40）："
journalctl -u "$SERVICE" -n 40 --no-pager || true
exit 1
