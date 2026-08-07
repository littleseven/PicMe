#!/bin/bash
#
# Kimi CLI 快捷命令脚本 for PoLang 项目
# 用法: source scripts/kimi-cli.sh 或 ./scripts/kimi-cli.sh <命令>
#

# 项目路径
PICME_PATH="$HOME/AndroidStudioProjects/langchain4android"

cd "$PICME_PATH" || exit 1

# 快捷命令函数

# 构建项目
kbuild() {
    echo "🔨 正在构建调试版本..."
    ./gradlew :androidApp:assembleDebug
    if [ $? -eq 0 ]; then
        echo "✅ 构建成功"
    else
        echo "❌ 构建失败"
    fi
}

# 运行测试
ktest() {
    echo "🧪 运行单元测试..."
    ./gradlew test
}

# 清理构建
kclean() {
    echo "🧹 清理构建..."
    ./gradlew clean
}

# 安装调试 APK 到设备
kinstall() {
    echo "📱 安装调试 APK..."
    adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
}

# 查看日志
klogs() {
    echo "📋 查看 PoLang 日志..."
    adb logcat -s "PoLang:*"
}

# 打开 Android Studio
kstudio() {
    echo "🚀 打开 Android Studio..."
    open -a "Android Studio" "$PICME_PATH"
}

# 打开项目目录
kcd() {
    cd "$PICME_PATH" || exit
    pwd
}

# 显示帮助
khelp() {
    cat << 'EOF'
Kimi CLI for PoLang - 快捷命令

命令列表:
  kbuild     - 构建调试 APK
  ktest      - 运行单元测试
  kclean     - 清理构建
  kinstall   - 安装调试 APK 到设备
  klogs      - 查看 PoLang 日志
  kstudio    - 打开 Android Studio
  kcd        - 切换到项目目录
  khelp      - 显示此帮助

环境变量:
  PICME_PATH - 项目路径 (默认: ~/AndroidStudioProjects/PoLang)

使用示例:
  source scripts/kimi-cli.sh  # 加载函数
  kbuild                      # 构建项目
  kinstall                    # 安装到设备
  klogs                       # 查看日志

EOF
}

# 如果带参数执行
if [ $# -gt 0 ]; then
    case "$1" in
        build) kbuild ;;
        test) ktest ;;
        clean) kclean ;;
        install) kinstall ;;
        logs) klogs ;;
        studio) kstudio ;;
        cd) kcd ;;
        help|--help|-h) khelp ;;
        *) echo "未知命令: $1"; khelp ;;
    esac
else
    # 交互模式（合并原 start-kimi-cli.sh 的引导输出）
    echo "🤖 Kimi CLI for PoLang"
    echo "====================="
    echo ""
    echo "📁 项目路径: $PICME_PATH"
    echo "📄 AI 配置索引: $PICME_PATH/AI_TOOLS.md"
    echo ""
    echo "已加载快捷命令: kbuild, ktest, kclean, kinstall, klogs, kstudio, kcd"
    echo "输入 khelp 查看详细帮助"
    echo ""
    echo "当前目录: $(pwd)"
fi
