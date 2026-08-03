#!/bin/bash
# PoLang 批量拍照测试脚本
# 用法: ./batch_capture.sh [前置/后置] [滤镜列表]

set -e

# 默认配置
CAMERA=${1:-"back"}  # back 或 front
FILTERS=${2:-"none leica_classic leica_vibrant leica_bw film_gold"}
BEAUTY_SMOOTH=${3:-80}
BEAUTY_WHITEN=${4:-60}
DELAY=${5:-1}  # 每次拍照间隔秒数

PACKAGE="com.picme"
ACTIVITY="${PACKAGE}/.MainActivity"
ACTION="com.picme.TEST_COMMAND"

echo "=== PoLang 批量拍照测试 ==="
echo "摄像头: ${CAMERA}"
echo "滤镜: ${FILTERS}"
echo "美颜: smooth=${BEAUTY_SMOOTH}, whiten=${BEAUTY_WHITEN}"
echo "间隔: ${DELAY}s"
echo ""

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "错误: 没有设备连接"
    exit 1
fi

# 启动应用
echo "[1/5] 启动应用..."
adb shell am start -n ${ACTIVITY}
sleep 3

# 设置摄像头
echo "[2/5] 设置摄像头 (${CAMERA})..."
if [ "$CAMERA" = "back" ]; then
    # 切换到后置 (lensFacing=1)
    adb shell am broadcast -a ${ACTION} --es action "get_state"
    sleep 0.5
    # 如果需要切换，执行 flip
    adb shell am broadcast -a ${ACTION} --es action "flip_camera"
else
    # 切换到前置
    adb shell am broadcast -a ${ACTION} --es action "flip_camera"
fi
sleep 1

# 设置美颜
echo "[3/5] 设置美颜参数..."
adb shell am broadcast -a ${ACTION} \
    --es action "set_beauty" \
    --ei smooth ${BEAUTY_SMOOTH} \
    --ei whiten ${BEAUTY_WHITEN}
sleep 0.5

# 批量拍照
echo "[4/5] 开始批量拍照..."
COUNT=0
for filter in ${FILTERS}; do
    COUNT=$((COUNT + 1))
    echo "  [${COUNT}] 设置滤镜: ${filter}"
    adb shell am broadcast -a ${ACTION} --es action "set_filter" --es filter "${filter}"
    sleep 0.5
    
    echo "  [${COUNT}] 拍照..."
    adb shell am broadcast -a ${ACTION} --es action "capture"
    sleep ${DELAY}
done

# 恢复默认设置
echo "[5/5] 恢复默认设置..."
adb shell am broadcast -a ${ACTION} --es action "set_filter" --es filter "none"
adb shell am broadcast -a ${ACTION} --es action "set_beauty" --ei smooth 0 --ei whiten 0

echo ""
echo "=== 批量拍照完成 ==="
echo "共拍摄 ${COUNT} 张照片"
echo ""
echo "查看相册:"
echo "  adb shell ls /sdcard/DCIM/PoLang/"
