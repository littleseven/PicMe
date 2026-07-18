#!/bin/bash
# PoLang 快速功能验证脚本
# 依次测试所有主要功能

set -e

PACKAGE="com.picme"
ACTION="com.picme.TEST_COMMAND"

echo "=== PoLang 功能快速验证 ==="

# 检查设备
if ! adb devices | grep -q "device$"; then
    echo "错误: 没有设备连接"
    exit 1
fi

# 启动应用
echo "[1/8] 启动应用..."
adb shell am start -n ${PACKAGE}/.MainActivity
sleep 3

# 测试1: 获取状态
echo "[2/8] 测试: 获取状态..."
adb shell am broadcast -a ${ACTION} --es action "get_state"
sleep 1

# 测试2: 切换摄像头
echo "[3/8] 测试: 切换摄像头..."
adb shell am broadcast -a ${ACTION} --es action "flip_camera"
sleep 2

# 测试3: 设置美颜
echo "[4/8] 测试: 设置美颜..."
adb shell am broadcast -a ${ACTION} \
    --es action "set_beauty" \
    --ei smooth 80 \
    --ei whiten 60
sleep 1

# 测试4: 设置滤镜
echo "[5/8] 测试: 设置滤镜..."
adb shell am broadcast -a ${ACTION} --es action "set_filter" --es filter "leica_classic"
sleep 1

# 测试5: 设置场景
echo "[6/8] 测试: 设置场景..."
adb shell am broadcast -a ${ACTION} --es action "set_scene" --es scene "night"
sleep 1

# 测试6: 设置画幅
echo "[7/8] 测试: 设置画幅..."
adb shell am broadcast -a ${ACTION} --es action "set_ratio" --es ratio "16_9"
sleep 1

# 测试7: 拍照
echo "[8/8] 测试: 拍照..."
adb shell am broadcast -a ${ACTION} --es action "capture"
sleep 2

# 恢复默认
echo "恢复默认设置..."
adb shell am broadcast -a ${ACTION} --es action "set_filter" --es filter "none"
adb shell am broadcast -a ${ACTION} --es action "set_scene" --es scene "none"
adb shell am broadcast -a ${ACTION} --es action "set_ratio" --es ratio "4_3"
adb shell am broadcast -a ${ACTION} --es action "set_beauty" --ei smooth 0 --ei whiten 0

echo ""
echo "=== 验证完成 ==="
echo ""
echo "查看日志确认结果:"
echo "  adb logcat -d | grep PoLang:CameraTest"
