#!/usr/bin/env bash
# 一键部署：本地构建 → rsync → 重启服务 → 验证 healthz
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # .../langchain4android/server
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
HOST=ubuntu@43.161.201.142                            # 也可 ubuntu@api.polang.net

echo ">> 构建 installDist"
"$REPO_ROOT/gradlew" -p "$SCRIPT_DIR" clean installDist

echo ">> 上传到 $HOST:~/picme-server/"
rsync -az --delete "$SCRIPT_DIR/build/install/picme-server/" "$HOST":~/picme-server/

echo ">> 重启 picme-api"
ssh "$HOST" 'sudo systemctl restart picme-api'

echo ">> 验证"
sleep 1
curl -fsS https://api.polang.net/healthz && echo
