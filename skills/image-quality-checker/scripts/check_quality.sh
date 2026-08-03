#!/bin/bash
# 图片质量自动化验证脚本
# 截屏 -> 拉取 -> 分析

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="/tmp/picme_quality_check"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=== PoLang 图片质量验证 ==="
echo ""

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "错误: 没有设备连接"
    exit 1
fi

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 1. 截屏
echo "[1/3] 正在截屏..."
SCREENSHOT_PATH="$OUTPUT_DIR/screenshot_${TIMESTAMP}.png"
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png "$SCREENSHOT_PATH" > /dev/null 2>&1

if [ ! -f "$SCREENSHOT_PATH" ]; then
    echo "错误: 截屏失败"
    exit 1
fi

echo "✓ 截屏成功: $SCREENSHOT_PATH"
echo ""

# 2. 分析图片
echo "[2/3] 正在分析图片质量..."
python3 "$SCRIPT_DIR/analyze_image.py" "$SCREENSHOT_PATH" --detect-face

ANALYSIS_RESULT=$?

echo ""

# 3. 收集日志（如果检测到问题）
if [ $ANALYSIS_RESULT -ne 0 ]; then
    echo "[3/3] 检测到问题，收集诊断日志..."
    LOG_PATH="$OUTPUT_DIR/logcat_${TIMESTAMP}.txt"
    adb logcat -d | grep -i "PoLang" > "$LOG_PATH" || true
    echo "✓ 日志已保存: $LOG_PATH"
    echo ""
    echo "⚠ 建议查看日志排查问题"
else
    echo "[3/3] 画面质量合格，无需收集日志"
fi

echo ""
echo "=== 验证完成 ==="
echo "输出目录: $OUTPUT_DIR"
echo ""

exit $ANALYSIS_RESULT
