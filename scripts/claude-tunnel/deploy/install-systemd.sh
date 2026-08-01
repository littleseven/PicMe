#!/usr/bin/env bash
#
# KimiClaw：装 gateway + chisel-client systemd unit（chisel 含反向 SSH R:3022），
# enable + 接管 nohup 进程。之后开机自启 + 崩溃自动重启。
#
# 用法（经反向 SSH 或 console）：bash install-systemd.sh
#
# ⚠️ 脚本会 pkill chisel（断反向 SSH），所以首次调用自我 setsid 脱离 ssh session，
#    真正逻辑在 detached 子进程跑，日志写 /tmp/install-systemd.log。
set -uo pipefail
export LC_ALL=C

if [ -z "${INSTALL_DETACHED:-}" ]; then
  INSTALL_DETACHED=1 setsid bash "$0" </dev/null >/tmp/install-systemd.log 2>&1 &
  echo "install 已脱离运行，日志：/tmp/install-systemd.log（约 15s 后查看）"
  exit 0
fi

# —— 以下为 detached 逻辑 ——
exec >>/tmp/install-systemd.log 2>&1
echo "=== $(date '+%F %T') install-systemd start ==="
REPO="${CT_REPO:-/root/polang}"
DEPLOY="$REPO/scripts/claude-tunnel/deploy"

echo "--- 1 copy unit → /etc/systemd/system/"
cp "$DEPLOY/gateway.service" "$DEPLOY/chisel-client.service" /etc/systemd/system/
systemctl daemon-reload

echo "--- 2 停 nohup 进程（systemd 接管）"
pkill -f "server.py" 2>/dev/null || true
pkill -f "chisel client" 2>/dev/null || true
sleep 2

echo "--- 3 enable + start gateway + chisel-client"
systemctl enable gateway chisel-client
systemctl restart gateway chisel-client
sleep 4

echo "--- 4 验证"
echo "is-active gateway: $(systemctl is-active gateway)"
echo "is-active chisel-client: $(systemctl is-active chisel-client)"
echo "gw healthz: $(curl -s -m5 http://127.0.0.1:3000/healthz || echo FAIL)"
echo "--- chisel-client 最近日志 ---"
journalctl -u chisel-client -n 3 --no-pager 2>/dev/null
echo "=== install-systemd done ==="
