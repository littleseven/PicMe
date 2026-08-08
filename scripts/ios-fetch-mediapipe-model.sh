#!/bin/bash
# 下载 MediaPipe Face Landmarker 模型到 iosApp bundle 资源目录
# 模型文件不入 git（.gitignore 加 *.task）；脚本幂等。
# 正确 URL：float16/latest（float32/1 已 404 NoSuchKey）
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task
mkdir -p "$(dirname "$DEST")"
if [ ! -f "$DEST" ] || [ "$(wc -c < "$DEST")" -lt 1000000 ]; then
    curl -L -o "$DEST" \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
fi
ls -lh "$DEST"
echo "sha256: $(shasum -a 256 "$DEST" | cut -d' ' -f1)"
