#!/usr/bin/env bash
# 一键部署：本地构建 installDist → rsync 到服务器 ~/picme-server.new/ → ssh 触发 deploy-switch.sh
# deploy-switch.sh 负责蓝绿切换 + healthz 校验 + 失败自动回滚。
# 不在电脑前时，可由 OpenClaw 直接跑 ~/deploy-switch.sh（artifact 已就位）。
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
HOST="${DEPLOY_HOST:-ubuntu@43.161.201.142}"   # 也可 ubuntu@api.polang.net

echo ">> 构建 installDist"
"$REPO_ROOT/gradlew" -p "$SCRIPT_DIR" clean installDist

echo ">> 同步 deploy-switch.sh 到 $HOST:~/"
scp -q "$SCRIPT_DIR/deploy-switch.sh" "$HOST:~/deploy-switch.sh"

echo ">> 上传 artifact → $HOST:~/picme-server.new/"
rsync -az --delete \
    --exclude='*.log' --exclude='logs/' --exclude='*.db*' --exclude='.env' \
    "$SCRIPT_DIR/build/install/picme-server/" "$HOST":~/picme-server.new/

echo ">> 触发蓝绿切换（restart + healthz + 失败回滚）"
ssh "$HOST" 'bash ~/deploy-switch.sh'
