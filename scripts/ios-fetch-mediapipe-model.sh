#!/bin/bash
# 下载 MediaPipe Face Landmarker 模型到 iosApp bundle 资源目录
# 模型文件不入 git（.gitignore 加 *.task）；脚本幂等。
# 正确 URL：float16/latest（float32/1 已 404 NoSuchKey）
# 🟡10: sha256 校验，不符即删除 + exit 1
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task
EXPECTED_SHA256="64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
mkdir -p "$(dirname "$DEST")"
NEED_DOWNLOAD=false
if [ ! -f "$DEST" ] || [ "$(wc -c < "$DEST")" -lt 1000000 ]; then
    NEED_DOWNLOAD=true
fi
# 校验 sha256（即使文件存在但已损坏）
if [ "$NEED_DOWNLOAD" = "false" ]; then
    ACTUAL_SHA256=$(shasum -a 256 "$DEST" | cut -d' ' -f1)
    if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
        echo "WARN: sha256 mismatch (expected $EXPECTED_SHA256, got $ACTUAL_SHA256), re-downloading"
        rm -f "$DEST"
        NEED_DOWNLOAD=true
    fi
fi
if [ "$NEED_DOWNLOAD" = "true" ]; then
    curl -L -o "$DEST" \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
fi
# 下载后校验
ACTUAL_SHA256=$(shasum -a 256 "$DEST" | cut -d' ' -f1)
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: sha256 mismatch after download (expected $EXPECTED_SHA256, got $ACTUAL_SHA256)"
    rm -f "$DEST"
    exit 1
fi
ls -lh "$DEST"
echo "sha256: $ACTUAL_SHA256 ✓"
