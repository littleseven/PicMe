#!/bin/bash
# OpenGL 错误检查工具
# 用法: ./check-gl-errors.sh [adb_device_id]

set -e

DEVICE_ID=${1:-""}
ADB_CMD="adb"

if [ -n "$DEVICE_ID" ]; then
    ADB_CMD="adb -s $DEVICE_ID"
fi

echo "🔍 开始检查 OpenGL 错误..."
echo ""

# 清除旧日志
$ADB_CMD logcat -c

echo "📱 启动应用并捕获 GL 错误..."
echo "   提示: 请在应用中触发需要调试的操作，按 Ctrl+C 停止捕获"
echo ""

# 捕获 GL 错误
$ADB_CMD logcat | grep -E "(GL Error|glGetError|PoLang:.*Renderer|EGL)" --line-buffered | while read line; do
    # 高亮错误
    if echo "$line" | grep -q "Error\|error\|ERROR"; then
        echo -e "\033[31m❌ $line\033[0m"
    elif echo "$line" | grep -q "Warning\|warning\|WARN"; then
        echo -e "\033[33m⚠️  $line\033[0m"
    else
        echo -e "\033[32m✅ $line\033[0m"
    fi
done
