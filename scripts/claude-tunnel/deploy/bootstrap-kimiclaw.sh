#!/usr/bin/env bash
#
# KimiClaw 重置/重启后一键恢复 + 反向 SSH 配置。
# 用法（腾讯云 console，root）：bash bootstrap-kimiclaw.sh
#
# 做：① git pull → ② gh credential（git push 用 gh token）→ ③ 反向 SSH
# （sshd 允许 root key + 注入 prod 公钥）→ ④ 起 chisel client（R:3001 gateway
# + R:3022 ssh 反向）→ ⑤ 起 gateway → ⑥ 验证。
#
# 之后日常运维不用再登 console：从 prod 跑 `ssh -p 3022 root@127.0.0.1` 直达 KimiClaw。
#
# 前提：repo 已在 /root/polang、chisel binary 已装（重启级重置；若重装系统需先手动
# clone repo + 装 chisel/gh，再跑本脚本）。
set -uo pipefail
export LC_ALL=C

REPO="${CT_REPO:-/root/polang}"
GW_DIR="$REPO/scripts/claude-tunnel/gateway"
TUNNEL_HOST="${CT_TUNNEL_HOST:-api.polang.net}"
DEF_PSK="tunnel:d1a88674601fe6442c043acef68d96657515c3bbed4da57d"
# prod 公钥（反向 SSH：prod → KimiClaw root）。公钥非敏感，可入库；换 prod 机器时改这里。
PROD_PUBKEY="${PROD_PUBKEY:-ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILx1qiszO3XiT2CiZj10lTs7g41x4yZ0gSytQitFdeBr ubuntu@VM-0-14-ubuntu}"

ok()   { echo "✅ $*"; }
warn() { echo "⚠️  $*"; }
die()  { echo "❌ $*"; exit 1; }

# 找 tunnel.env（PSK / CT_REPO_URL 等）
ENVF=""
for f in /root/claude-tunnel.env /root/claude-tunnel/tunnel.env "$REPO/scripts/claude-tunnel/tunnel.env"; do
  [ -f "$f" ] && ENVF="$f" && break
done
psk_from_env() {
  [ -n "$ENVF" ] || return 1
  grep -E "^CHISEL_PSK=" "$ENVF" 2>/dev/null | head -1 | cut -d= -f2- | tr -d "\"' "
}

echo "### 1/6 git pull（$REPO）"
[ -d "$REPO/.git" ] || die "repo 不存在：$REPO（重装系统需先 git clone）"
cd "$REPO" && git pull --ff-only || warn "git pull 失败（无网络/冲突），继续用现有代码"

echo "### 2/6 gh credential（让 git push 走 gh token）"
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  gh auth setup-git && ok "gh credential configured"
else
  warn "gh 未登录 —— 先 gh auth login（需 GitHub token），否则交付推不动"
fi

echo "### 3/6 反向 SSH：sshd 允许 root key 登录 + 注入 prod 公钥"
if pgrep -x sshd >/dev/null 2>&1 || command -v sshd >/dev/null 2>&1; then
  SSHD_CFG=/etc/ssh/sshd_config
  if grep -qi "^PermitRootLogin" "$SSHD_CFG"; then
    sed -i "s/^PermitRootLogin.*/PermitRootLogin prohibit-password/I" "$SSHD_CFG"
  else
    echo "PermitRootLogin prohibit-password" >> "$SSHD_CFG"
  fi
  grep -qi "^PubkeyAuthentication yes" "$SSHD_CFG" || echo "PubkeyAuthentication yes" >> "$SSHD_CFG"
  systemctl restart sshd 2>/dev/null || systemctl restart ssh 2>/dev/null || service ssh restart 2>/dev/null || warn "sshd restart 失败（手动 restart）"
  mkdir -p /root/.ssh && chmod 700 /root/.ssh
  touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys
  grep -qF "$PROD_PUBKEY" /root/.ssh/authorized_keys || echo "$PROD_PUBKEY" >> /root/.ssh/authorized_keys
  ok "sshd（prohibit-password）+ prod pubkey 注入完成"
else
  warn "未检测到 sshd —— 反向 SSH 不可用；apt install -y openssh-server 后重跑本脚本"
fi

echo "### 4/6 起 chisel client（R:3001 gateway + R:3022 ssh 反向）"
CHISEL="${CT_CHISEL:-}"
[ -x "$CHISEL" ] || CHISEL="$(command -v chisel 2>/dev/null)"
[ -x "$CHISEL" ] || CHISEL=/usr/local/bin/chisel
[ -x "$CHISEL" ] || die "找不到 chisel binary（重装系统需先装 chisel）"
PSK="$(psk_from_env || true)"; PSK="${PSK:-$DEF_PSK}"
pkill -f "chisel client" 2>/dev/null || true
nohup "$CHISEL" client --auth "$PSK" "https://$TUNNEL_HOST/tunnel" \
  R:3001:127.0.0.1:3000 R:3022:127.0.0.1:22 >/tmp/ct-cc.log 2>&1 &
sleep 3
ok "chisel started（PSK 来源：$([ "$PSK" = "$DEF_PSK" ] && echo 默认 || echo tunnel.env)；含 R:3022 反向 SSH）"

echo "### 5/6 起 gateway（$GW_DIR）"
PY="$(command -v python3 2>/dev/null || echo python3)"
pkill -f "server.py" 2>/dev/null || true
(
  cd "$GW_DIR" || exit 1
  set -a; [ -n "$ENVF" ] && . "$ENVF"; set +a
  exec "$PY" server.py
) >/tmp/ct-gw.log 2>&1 &
sleep 3
GWHZ="$(curl -s -m5 http://127.0.0.1:3000/healthz || true)"
[ -n "$GWHZ" ] && ok "gateway up: $GWHZ" || warn "gateway 未起 —— tail /tmp/ct-gw.log 查"

echo "### 6/6 完成"
echo "--- chisel tail ---"; tail -2 /tmp/ct-cc.log 2>/dev/null
echo ""
echo "=== 反向 SSH 用法（在 prod 43.161.201.142 上跑）==="
echo "    ssh -o StrictHostKeyChecking=accept-new -p 3022 root@127.0.0.1"
echo "之后日常远程运维（从开发机经 prod）："
echo "    ssh ubuntu@api.polang.net 'ssh -p 3022 root@127.0.0.1 \"<命令>\"'"
