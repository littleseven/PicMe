#!/bin/bash
# PoLang 拍照质量自动化检查脚本
# 用途：每次代码修改后自动触发拍照并验证图片质量

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/output_quality_check"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=================================================="
echo "PoLang 拍照质量自动化检查"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=================================================="

# 1. 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 2. 清除旧日志
echo ""
echo "[1/6] 清除旧日志..."
adb logcat -c

# 3. 触发拍照
echo "[2/6] 触发拍照..."
adb shell input tap 540 2200
sleep 3

# 4. 获取最新照片（按修改时间排序）
echo "[3/6] 获取最新照片..."
# [关键修复] 使用 ls -lt 按时间排序，而不是字母排序
LATEST_PHOTO=$(adb shell ls -lt /sdcard/Pictures/PoLang/*.jpg 2>/dev/null | grep -v "total" | head -1 | awk '{print $NF}' | xargs basename)
if [ -z "$LATEST_PHOTO" ]; then
    echo "❌ 错误：未找到照片"
    exit 1
fi

echo "   最新照片: $LATEST_PHOTO"
adb pull "/sdcard/Pictures/PoLang/$LATEST_PHOTO" "$OUTPUT_DIR/photo_$TIMESTAMP.jpg" > /dev/null 2>&1

# 5. 分析照片质量
echo "[4/6] 分析照片质量..."
python3 "$SCRIPT_DIR/analyze_image.py" "$OUTPUT_DIR/photo_$TIMESTAMP.jpg" --detect-face > "$OUTPUT_DIR/report_$TIMESTAMP.txt" 2>&1

# 6. 检查关键日志
echo "[5/6] 检查 GPU 拍照日志..."
GPU_LOG=$(adb logcat -d | grep -E "(PhotoProcessor|GPU photo processing)" | tail -10)
echo "$GPU_LOG" > "$OUTPUT_DIR/gpu_log_$TIMESTAMP.txt"

# 7. 生成总结报告
echo "[6/6] 生成总结报告..."
REPORT_FILE="$OUTPUT_DIR/report_$TIMESTAMP.txt"

echo ""
echo "=================================================="
echo "=== 检查结果汇总 ==="
echo "=================================================="
echo ""

# 读取分析报告
if [ -f "$REPORT_FILE" ]; then
    cat "$REPORT_FILE"
else
    echo "❌ 错误：分析报告生成失败"
    exit 1
fi

echo ""
echo "=================================================="
echo "GPU 拍照日志摘要:"
echo "=================================================="
grep -E "(process DONE|GPU photo processing succeeded|GL error)" "$OUTPUT_DIR/gpu_log_$TIMESTAMP.txt" || echo "无相关日志"

echo ""
echo "=================================================="
echo "详细文件已保存到: $OUTPUT_DIR/"
echo "  - 照片: photo_$TIMESTAMP.jpg"
echo "  - 报告: report_$TIMESTAMP.txt"
echo "  - 日志: gpu_log_$TIMESTAMP.txt"
echo "=================================================="

# 检查是否有问题
if grep -q "黑屏" "$REPORT_FILE" && ! grep -q "正常" "$REPORT_FILE"; then
    echo ""
    echo "⚠️  警告：检测到黑屏问题！"
    exit 1
fi

if grep -q "GL error" "$OUTPUT_DIR/gpu_log_$TIMESTAMP.txt"; then
    echo ""
    echo "⚠️  警告：检测到 GL 错误，请检查日志"
fi

echo ""
echo "✅ 检查完成！"
