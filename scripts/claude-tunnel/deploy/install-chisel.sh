#!/usr/bin/env bash
# 下载 chisel 到 /usr/local/bin/chisel。用法: bash install-chisel.sh [version]
# chisel repo: jpillora/chisel（已确认）；VER 默认 1.11.8（最新见 https://github.com/jpillora/chisel/releases）。
set -euo pipefail
VER="${1:-1.11.8}"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64 | amd64) GOARCH="amd64" ;;
  aarch64 | arm64) GOARCH="arm64" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
URL="https://github.com/jpillora/chisel/releases/download/v${VER}/chisel_${VER}_${OS}_${GOARCH}.gz"
echo "downloading $URL"
curl -fSL "$URL" | gunzip >/tmp/chisel
chmod +x /tmp/chisel
sudo mv /tmp/chisel /usr/local/bin/chisel
chisel version
