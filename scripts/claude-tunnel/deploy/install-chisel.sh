#!/usr/bin/env bash
# 下载 chisel 到 /usr/local/bin/chisel。用法: bash install-chisel.sh [version]
# ⚠️ 执行时确认 chisel 最新 release tag 与下载 URL 的 owner/repo（以 GitHub 实际为准），
#    调整下方 VER 与 URL。
set -euo pipefail
VER="${1:-1.10.3}"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64 | amd64) GOARCH="amd64" ;;
  aarch64 | arm64) GOARCH="arm64" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
URL="https://github.com/jpilloura/chisel/releases/download/v${VER}/chisel_${VER}_${OS}_${GOARCH}.gz"
echo "downloading $URL"
curl -fSL "$URL" | gunzip >/tmp/chisel
chmod +x /tmp/chisel
sudo mv /tmp/chisel /usr/local/bin/chisel
chisel version
