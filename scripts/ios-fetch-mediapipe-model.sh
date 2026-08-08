#!/bin/bash
# 下载 MediaPipe Face Landmarker 模型到 iosApp bundle 资源目录
# 模型文件不入 git（.gitignore 加 *.task）；脚本幂等。
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task
mkdir -p "$(dirname "$DEST")"
if [ ! -f "$DEST" ]; then
    curl -L -o "$DEST" \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float32/1/face_landmarker.task"
fi
ls -lh "$DEST"
